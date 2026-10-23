const API_BASE = "/api";

/** Error thrown for a failed API call. `status` is the HTTP status, `fields` the per-field messages. */
export class ApiError extends Error {
    constructor(message, status, fields = {}) {
        super(message);
        this.status = status;
        this.fields = fields;
    }
}

let csrf = null;

const OFFLINE = "Không kết nối được máy chủ. Hãy kiểm tra mạng rồi thử lại.";

/** fetch, with a lost connection (offline, server down) turned into an ApiError the pages show like any other. */
async function send(url, options) {
    try {
        return await fetch(url, options);
    } catch {
        throw new ApiError(OFFLINE, 0);
    }
}

/** Spring Security needs the CSRF token in a header for every POST/PUT/PATCH/DELETE. */
async function csrfHeader(force = false) {
    if (!csrf || force) {
        const response = await send(`${API_BASE}/auth/csrf`, { credentials: "same-origin" });
        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload?.data?.headerName) {
            throw new ApiError("Có lỗi xảy ra, vui lòng thử lại.", response.status);
        }
        csrf = payload.data;
    }
    return { [csrf.headerName]: csrf.token };
}

async function request(path, options = {}, retried = false) {
    const method = (options.method || "GET").toUpperCase();
    // FormData (file uploads): the browser sets multipart/form-data with its boundary itself
    const headers = options.body instanceof FormData
        ? { ...options.headers }
        : { "Content-Type": "application/json", ...options.headers };
    if (method !== "GET") {
        Object.assign(headers, await csrfHeader());
    }
    const response = await send(`${API_BASE}${path}`, { ...options, method, headers, credentials: "same-origin" });
    if (response.status === 204) {
        return null;
    }
    let payload = null;
    try {
        payload = await response.json();
    } catch {
        payload = null;
    }

    // The CSRF token changes after login (also in another tab): Spring Security then refuses the call before the
    // application runs it, with Spring's own error body ({ status, error, path }, no "success"). Fetch a fresh token
    // and send it once more. A 403 of the application itself (MSG18, reporting locked…) is an ApiResponse with
    // "success": it is final and never sent twice.
    if (response.status === 403 && method !== "GET" && !retried && payload?.success === undefined) {
        await csrfHeader(true);
        return request(path, options, true);
    }
    if (!response.ok || payload?.success === false) {
        const message = payload?.message
            || (response.status === 401 ? "Bạn chưa đăng nhập." : "Có lỗi xảy ra, vui lòng thử lại.");
        throw new ApiError(message, response.status, payload?.data || {});
    }
    return payload?.data;
}

const post = (path, body) => request(path, { method: "POST", body: JSON.stringify(body ?? {}) });

export const apiClient = {
    getGuides: () => request("/guides"),
    getScholarships: () => request("/scholarships"),

    // Auth
    providers: () => request("/auth/providers"),
    me: () => request("/auth/me"),
    login: (body) => post("/auth/login", body),
    logout: () => post("/auth/logout"),
    sendRegisterCode: (email) => post("/auth/register/send-code", { email }),
    verifyRegisterCode: (email, code) => post("/auth/register/verify-code", { email, code }),
    completeRegistration: (body) => post("/auth/register/complete", body),
    pendingSocial: () => request("/auth/social/pending"),
    completeSocial: (body) => post("/auth/social/complete", body),

    // Legal documents: "tos", "privacy", "community"
    legal: (type) => request(`/legal/${type}`),

    // Homepage and "Tôi"
    guestHome: () => request("/home"),
    memberHome: () => request("/me/home"),
    profile: () => request("/me/profile"),
    updateProfile: (body) => request("/me/profile", { method: "PUT", body: JSON.stringify(body) }),
    deleteAccount: () => request("/me", { method: "DELETE" }),
    // "Đã lưu": { places, posts, questions, counts }. Unsave with saveBlog / saveQuestion(id, false).
    saved: () => request("/me/saved"),
    areas: () => request("/areas"),

    // Blog Feed (UC-09), posts only. params: { q, topicIds: [..], sort, page, size }
    blogs: (params = {}) => request(`/blogs?${toQuery(params)}`),
    blogOverview: () => request("/blogs/overview"),

    // Post Detail (UC-10). Calls that change comments return the refreshed post.
    blog: (id) => request(`/blogs/${id}`),
    likeBlog: (id, on) => request(`/blogs/${id}/like`, { method: on ? "POST" : "DELETE" }),
    saveBlog: (id, on) => request(`/blogs/${id}/save`, { method: on ? "POST" : "DELETE" }),
    deleteBlog: (id) => request(`/blogs/${id}`, { method: "DELETE" }),
    reportBlog: (id, body) => post(`/blogs/${id}/report`, body),
    commentBlog: (id, body) => post(`/blogs/${id}/comments`, body),
    likeComment: (id, on) => request(`/comments/${id}/like`, { method: on ? "POST" : "DELETE" }),
    reportComment: (id, body) => post(`/comments/${id}/report`, body),
    deleteComment: (id) => request(`/comments/${id}`, { method: "DELETE" }),
    updateComment: (id, content) => request(`/comments/${id}`, { method: "PUT", body: JSON.stringify({ content }) }),

    // Write Post (UC-11). createBlog takes FormData: part "post" (JSON {title, content, topicIds, placeIds,
    // externalPlaceIds}) + "images" (≤ 5) + "video" (≤ 1).
    blogCompose: () => request("/blogs/compose"),
    // "Địa điểm liên quan" (Viết bài, Đặt câu hỏi): { items: [{ id | null, externalId, name, nameJa, category,
    // address, source }], external: ON|OFF|SHORT|UNAVAILABLE|BUSY, externalReason }. Signed-in users only.
    searchPlaces: (q) => request(`/places/search?${toQuery({ q })}`),
    createBlog: (formData) => request("/blogs", { method: "POST", body: formData }),
    // Edit ("Sửa bài"): the post as it is now + compose options; updateBlog takes FormData like createBlog, the JSON
    // part also lists keepMediaIds (attached photos/video to keep, in order)
    blogEditData: (id) => request(`/blogs/${id}/edit`),
    updateBlog: (id, formData) => request(`/blogs/${id}`, { method: "PUT", body: formData }),
    scamWarnings: (placeId) => request(`/blogs/scam-warnings?${toQuery({ placeId })}`),

    // Map (UC-U14, UC-U15): { categories, places, center, signedIn }; search { categories, places, external }
    mapOverview: () => request("/map"),
    mapEvents: (days) => request(`/map/events?${toQuery({ days })}`),
    mapSearch: (q) => request(`/map/search?${toQuery({ q })}`),
    // UC-U19 "Cắm ghim": createPin takes FormData (part "pin" JSON + "images") ; UC-U20 "Báo đã đóng cửa"
    pinCompose: () => request("/map/pins/compose"),
    pinDuplicates: (lat, lng, names) => request(`/map/pins/duplicates?${toQuery({ lat, lng, names: names.join("|") })}`),
    createPin: (formData) => request("/map/pins", { method: "POST", body: formData }),
    reportClosed: (id) => post(`/map/places/${id}/closed-report`),
    mapNearby: (lat, lng) => request(`/map/nearby?${toQuery({ lat, lng })}`),
    mapPlace: (id) => request(`/map/places/${id}`),
    mapPlaceReviews: (id, page = 0) => request(`/map/places/${id}/reviews?${toQuery({ page })}`),
    mapPlacePhrases: (id) => request(`/map/places/${id}/phrases`),
    saveMapPlace: (id, on) => request(`/map/places/${id}/save`, { method: on ? "POST" : "DELETE" }),
    // Write / Edit / Delete Review (UC-U16..18). createReview / updateReview take FormData: part "review" (JSON
    // {placeId | externalPlaceId, rating, content} / {rating, content, keepMediaIds}) + "images" (≤ 5) + "video" (≤ 1)
    reviewCompose: (placeId) => request(`/reviews/compose?${toQuery({ placeId })}`),
    createReview: (formData) => request("/reviews", { method: "POST", body: formData }),
    reviewEditData: (id) => request(`/reviews/${id}/edit`),
    updateReview: (id, formData) => request(`/reviews/${id}`, { method: "PUT", body: formData }),
    deleteReview: (id) => request(`/reviews/${id}`, { method: "DELETE" }),
    likeReview: (id, on) => request(`/reviews/${id}/like`, { method: on ? "POST" : "DELETE" }),
    reportReview: (id, body) => post(`/reviews/${id}/report`, body),

    // Hỏi-Đáp. params: { q, topicIds: [..], status: ALL|UNANSWERED|UNSOLVED|SOLVED, sort: NEWEST|LIKES|ANSWERS, page, size }
    questions: (params = {}) => request(`/questions?${toQuery(params)}`),
    questionOverview: () => request("/questions/overview"),
    questionCompose: () => request("/questions/compose"),
    // FormData: part "question" (JSON) + "images" (≤ 5) + "video" (≤ 1)
    askQuestion: (formData) => request("/questions", { method: "POST", body: formData }),
    questionEditData: (id) => request(`/questions/${id}/edit`),
    updateQuestion: (id, formData) => request(`/questions/${id}`, { method: "PUT", body: formData }),
    question: (id) => request(`/questions/${id}`),
    likeQuestion: (id, on) => request(`/questions/${id}/like`, { method: on ? "POST" : "DELETE" }),
    saveQuestion: (id, on) => request(`/questions/${id}/save`, { method: on ? "POST" : "DELETE" }),
    deleteQuestion: (id) => request(`/questions/${id}`, { method: "DELETE" }),
    reportQuestion: (id, body) => post(`/questions/${id}/report`, body),
    answerQuestion: (id, body) => post(`/questions/${id}/answers`, body),
    chooseBestAnswer: (id, answerId) =>
        request(`/questions/${id}/best-answer`, { method: "PUT", body: JSON.stringify({ answerId }) }),

    // Lễ hội và sự kiện. events: [EventCard] soonest first; event: { event, tip, album, albumCount, reviews, viewer,
    // reportReasons }
    events: (q) => request(`/events?${toQuery({ q })}`),
    eventPins: (days) => request(`/events/pins?${toQuery({ days })}`),
    event: (id) => request(`/events/${id}`),
    // "Viết review": { event, limits, quota, canPostLinks, review (own review of this year, to edit) }
    eventReviewForm: (id) => request(`/events/${id}/review-form`),
    // FormData: part "review" (JSON {rating, content}) + "images" (≤ 5) + "video" (≤ 1)
    createEventReview: (id, formData) => request(`/events/${id}/reviews`, { method: "POST", body: formData }),
    // FormData: part "review" (JSON {rating, content, keepMediaIds}) + new files
    updateEventReview: (reviewId, formData) => request(`/event-reviews/${reviewId}`, { method: "PUT", body: formData }),
    deleteEventReview: (reviewId) => request(`/event-reviews/${reviewId}`, { method: "DELETE" }),
    likeEventReview: (reviewId, on) => request(`/event-reviews/${reviewId}/like`, { method: on ? "POST" : "DELETE" }),
    reportEventReview: (reviewId, body) => post(`/event-reviews/${reviewId}/report`, body),

    // Moderator: UC-M07. create/update take FormData: part "event" (JSON) + optional "cover" photo
    modEvents: () => request("/moderator/events"),
    modCreateEvent: (formData) => request("/moderator/events", { method: "POST", body: formData }),
    modUpdateEvent: (id, formData) => request(`/moderator/events/${id}`, { method: "PUT", body: formData }),
    modDeleteEvent: (id) => request(`/moderator/events/${id}`, { method: "DELETE" }),
    modEventPlaces: (q) => request(`/moderator/events/places?${toQuery({ q })}`)
};

/** { q: "rác", topicIds: [1, 2] } → "q=r%C3%A1c&topicIds=1%2C2" (empty values are left out). */
function toQuery(params) {
    const query = new URLSearchParams();
    Object.entries(params).forEach(([key, value]) => {
        if (Array.isArray(value)) {
            if (value.length) {
                query.set(key, value.join(","));
            }
        } else if (value !== undefined && value !== null && value !== "") {
            query.set(key, value);
        }
    });
    return query.toString();
}

/** Returns the signed-in user, or null for a guest. */
export async function currentUser() {
    try {
        return await apiClient.me();
    } catch (error) {
        if (error.status === 401) {
            return null;
        }
        throw error;
    }
}
