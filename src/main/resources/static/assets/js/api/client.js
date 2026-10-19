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

/** Spring Security needs the CSRF token in a header for every POST/PUT/PATCH/DELETE. */
async function csrfHeader(force = false) {
    if (!csrf || force) {
        const response = await fetch(`${API_BASE}/auth/csrf`, { credentials: "same-origin" });
        const payload = await response.json();
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
    const response = await fetch(`${API_BASE}${path}`, { ...options, method, headers, credentials: "same-origin" });

    // The CSRF token changes after login; fetch a fresh one and retry once
    if (response.status === 403 && method !== "GET" && !retried) {
        await csrfHeader(true);
        return request(path, options, true);
    }
    if (response.status === 204) {
        return null;
    }
    let payload = null;
    try {
        payload = await response.json();
    } catch {
        payload = null;
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
        request(`/questions/${id}/best-answer`, { method: "PUT", body: JSON.stringify({ answerId }) })
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
