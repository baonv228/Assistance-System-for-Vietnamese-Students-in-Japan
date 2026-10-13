const API_BASE = "/api";

async function request(path, options = {}) {
    const response = await fetch(`${API_BASE}${path}`, {
        headers: {
            "Content-Type": "application/json",
            ...options.headers
        },
        ...options
    });
    const payload = await response.json();
    if (!response.ok || payload.success === false) {
        throw new Error(payload.message || "Request failed");
    }
    return payload.data;
}

export const apiClient = {
    getGuides: () => request("/guides"),
    getScholarships: () => request("/scholarships"),
    login: (body) => request("/auth/login", {
        method: "POST",
        body: JSON.stringify(body)
    })
};
