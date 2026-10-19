// Small DOM helpers shared by the app pages.

const ESCAPES = { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" };

/** Escapes text before putting it into innerHTML. Always use it for data from the API. */
export const esc = (value) => String(value ?? "").replace(/[&<>"']/g, (c) => ESCAPES[c]);

/** Material Symbols icon. */
export const ic = (name, extra = "") => `<span class="ic ${extra}" aria-hidden="true">${name}</span>`;

export const stars = (n) => "★".repeat(n) + "☆".repeat(Math.max(0, 5 - n));

export const LOGO_SVG = `<svg viewBox="0 0 40 40" aria-hidden="true"><g fill="#C2456E"><circle cx="20" cy="9" r="7"/><circle cx="30.5" cy="16.6" r="7"/><circle cx="26.5" cy="29" r="7"/><circle cx="13.5" cy="29" r="7"/><circle cx="9.5" cy="16.6" r="7"/></g><circle cx="20" cy="20" r="4.5" fill="#E8A33C"/></svg>`;

export const STAGES = { PRE: "Sắp sang Nhật", ARR: "Mới đến", LEAVE: "Sắp về nước", HOME: "Đã về nước" };

/** Tag chips coloured with the tag's own colour. */
export const tagChips = (tags = []) =>
    tags.map((t) => `<span class="badge tag" style="color:${esc(t.color)}">${esc(t.name)}</span>`).join(" ");

/** "Còn 3 ngày", "Đang diễn ra"… */
export function eventWhen(event) {
    if (event.daysUntil === 0) {
        return "Đang diễn ra";
    }
    return `Còn ${event.daysUntil} ngày`;
}

export function formatDay(isoDate) {
    const [, m, d] = isoDate.split("-");
    return { day: Number(d), month: `Th${Number(m)}` };
}

/** "Vừa xong", "5 phút trước", "3 ngày trước", then dd/mm/yyyy after 30 days. */
export function timeAgo(iso) {
    const then = new Date(iso);
    const seconds = Math.max(0, (Date.now() - then.getTime()) / 1000);
    if (seconds < 60) return "Vừa xong";
    if (seconds < 3600) return `${Math.floor(seconds / 60)} phút trước`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)} giờ trước`;
    if (seconds < 30 * 86400) return `${Math.floor(seconds / 86400)} ngày trước`;
    return then.toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" });
}

export const fullDate = (iso) => new Date(iso).toLocaleString("vi-VN", { dateStyle: "medium", timeStyle: "short" });

/** Round avatar: the user's picture, or the first letter of their given name (the last word). */
export function avatar(user) {
    if (user.avatarUrl) {
        return `<img class="avatar" src="${esc(user.avatarUrl)}" alt="" loading="lazy" referrerpolicy="no-referrer">`;
    }
    const initial = (user.displayName || "?").trim().split(/\s+/).pop().charAt(0).toUpperCase();
    return `<span class="avatar" aria-hidden="true">${esc(initial)}</span>`;
}
