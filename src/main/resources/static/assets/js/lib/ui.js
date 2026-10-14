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
