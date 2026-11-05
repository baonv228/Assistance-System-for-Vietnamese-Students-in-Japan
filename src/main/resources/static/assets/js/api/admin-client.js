// =========================================================
// API Client dành riêng cho module Quản trị viên (Admin)
// =========================================================

const API_BASE = "/api/admin";

let csrfTokenCache = null;

async function getCsrfHeader(force = false) {
    if (!csrfTokenCache || force) {
        // Ưu tiên đọc cookie XSRF-TOKEN được sinh bởi Spring Security
        const match = document.cookie.match(/(^|;)\s*XSRF-TOKEN=([^;]+)/);
        if (match && !force) {
            return { "X-XSRF-TOKEN": decodeURIComponent(match[2]) };
        }
        try {
            const res = await fetch("/api/auth/csrf", { credentials: "same-origin" });
            const payload = await res.json();
            csrfTokenCache = { name: payload.data.headerName, value: payload.data.token };
        } catch {
            csrfTokenCache = { name: "X-XSRF-TOKEN", value: "" };
        }
    }
    return { [csrfTokenCache.name]: csrfTokenCache.value };
}

async function adminRequest(endpoint, options = {}, retried = false) {
    const method = (options.method || "GET").toUpperCase();
    const headers = {
        "Content-Type": "application/json",
        ...options.headers
    };

    if (method !== "GET") {
        Object.assign(headers, await getCsrfHeader(retried));
    }

    const response = await fetch(`${API_BASE}${endpoint}`, {
        ...options,
        method,
        headers,
        credentials: "same-origin"
    });

    if (response.status === 403 && method !== "GET" && !retried) {
        return adminRequest(endpoint, options, true);
    }

    // Phiên đăng nhập đã hết (hoặc tài khoản vừa bị khóa): về trang đăng nhập
    if (response.status === 401) {
        window.location.replace("/login");
        const error = new Error("Phiên đăng nhập đã hết. Vui lòng đăng nhập lại.");
        error.status = 401;
        throw error;
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
        const errorMsg = payload?.message || "Đã xảy ra lỗi khi thực hiện thao tác.";
        const error = new Error(errorMsg);
        error.status = response.status;
        // Lỗi theo từng ô nhập (tên ô → thông báo), nếu máy chủ trả về
        error.fields = payload?.data && typeof payload.data === "object" ? payload.data : {};
        throw error;
    }

    return payload?.data;
}

export const adminClient = {
    // Dashboard Stats
    getStats: () => adminRequest("/dashboard/stats"),

    // Overview (khoảng ngày: today | last7days | last30days | custom kèm from, to)
    getOverview: ({ period = "last7days", from = "", to = "" } = {}) => {
        const params = new URLSearchParams({ period });
        if (from) params.set("from", from);
        if (to) params.set("to", to);
        return adminRequest(`/dashboard/overview?${params.toString()}`);
    },

    // Users & Roles
    getUsers: ({ keyword = "", role = "", status = "", tagId = "", page = 1, size = 15 } = {}) => {
        const params = new URLSearchParams();
        if (keyword) params.set("keyword", keyword);
        if (role) params.set("role", role);
        if (status) params.set("status", status);
        if (tagId) params.set("tagId", tagId);
        params.set("page", page);
        params.set("size", size);
        return adminRequest(`/users?${params.toString()}`);
    },
    getAccountOptions: () => adminRequest("/accounts/options"),
    getAccountDetail: (id) => adminRequest(`/accounts/${id}`),
    checkStatusChange: (id, target) => adminRequest(`/users/${id}/status-check?target=${encodeURIComponent(target)}`),
    // expectedRole / expectedStatus: giá trị trang đang hiển thị, để máy chủ phát hiện dữ liệu đã cũ (409)
    updateUserRole: (id, role, expectedRole = "") => adminRequest(`/users/${id}/role`, {
        method: "PUT",
        body: JSON.stringify({ role, expectedRole })
    }),
    updateUserStatus: (id, status, reason = "", expectedStatus = "") => adminRequest(`/users/${id}/status`, {
        method: "PUT",
        body: JSON.stringify({ status, reason, expectedStatus })
    }),

    // Safety configuration (hai thẻ tham số, lưu một lần)
    getSafetyConfig: () => adminRequest("/safety-config"),
    saveSafetyConfig: (values) => adminRequest("/safety-config", {
        method: "PUT",
        body: JSON.stringify(values)
    }),

    // Settings
    getSettings: () => adminRequest("/settings"),
    updateSetting: (key, settingValue) => adminRequest(`/settings/${encodeURIComponent(key)}`, {
        method: "PUT",
        body: JSON.stringify({ settingValue })
    }),

    // Banned Keywords
    getKeywords: (search = "") => {
        const query = search ? `?search=${encodeURIComponent(search)}` : "";
        return adminRequest(`/keywords${query}`);
    },
    addKeyword: (keyword) => adminRequest("/keywords", {
        method: "POST",
        body: JSON.stringify({ keyword })
    }),
    updateKeyword: (id, keyword) => adminRequest(`/keywords/${id}`, {
        method: "PUT",
        body: JSON.stringify({ keyword })
    }),
    deleteKeyword: (id) => adminRequest(`/keywords/${id}`, {
        method: "DELETE"
    }),

    // Activity Logs
    getLogs: ({ page = 1, size = 20, action = "" } = {}) => {
        const params = new URLSearchParams();
        params.set("page", page);
        params.set("size", size);
        if (action) params.set("action", action);
        return adminRequest(`/logs?${params.toString()}`);
    }
};
