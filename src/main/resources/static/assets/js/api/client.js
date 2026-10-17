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
    const headers = { "Content-Type": "application/json", ...options.headers };
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

    // Blog Feed (UC-09). params: { q, topicIds: [..], type, sort, page, size }
    blogs: (params = {}) => request(`/blogs?${toQuery(params)}`),
    blogOverview: () => request("/blogs/overview"),

    // Post Detail (UC-10). Calls that change comments return the refreshed post.
    blog: (id) => request(`/blogs/${id}`),
    likeBlog: (id, on) => request(`/blogs/${id}/like`, { method: on ? "POST" : "DELETE" }),
    saveBlog: (id, on) => request(`/blogs/${id}/save`, { method: on ? "POST" : "DELETE" }),
    deleteBlog: (id) => request(`/blogs/${id}`, { method: "DELETE" }),
    reportBlog: (id, body) => post(`/blogs/${id}/report`, body),
    commentBlog: (id, body) => post(`/blogs/${id}/comments`, body),
    chooseBestComment: (id, commentId) =>
        request(`/blogs/${id}/best-comment`, { method: "PUT", body: JSON.stringify({ commentId }) }),
    likeComment: (id, on) => request(`/comments/${id}/like`, { method: on ? "POST" : "DELETE" }),
    reportComment: (id, body) => post(`/comments/${id}/report`, body),
    deleteComment: (id) => request(`/comments/${id}`, { method: "DELETE" })
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
