// Illustrated covers for events without a cover photo (drawings from the prototype, no copyright issue).
// The drawing is picked from the event's name (fireworks, cherry blossoms, market…), else from its id.

import { esc } from "./ui.js";

const ART = {
    lantern: "Đèn lồng", market: "Chợ phiên", fabric: "Phố vải", taiko: "Trống taiko", sakura: "Hoa anh đào",
    firework: "Pháo hoa"
};

/** Which drawing suits the event: by words in its name, else one of them by id. */
const KEYWORDS = [
    ["firework", /pháo hoa|hanabi|花火/i],
    ["sakura", /anh đào|sakura|hanami|桜/i],
    ["market", /chợ|market|ichi|市/i],
    ["fabric", /vải|fabric|tơ lụa/i],
    ["taiko", /trống|taiko|đền|jinja|matsuri|神社|祭/i],
    ["lantern", /đèn lồng|obon|bon odori|lantern|lồng đèn/i]
];

export function artKey(event) {
    const hit = KEYWORDS.find(([, re]) => re.test(event.title || ""));
    if (hit) return hit[0];
    const keys = Object.keys(ART);
    return keys[Math.abs(Number(event.id) || 0) % keys.length];
}

const flower = (cx, cy, r, fill, core) => `<g transform="translate(${cx} ${cy})">${[0, 72, 144, 216, 288]
    .map((a) => `<ellipse cx="0" cy="${-r * 0.52}" rx="${r * 0.34}" ry="${r * 0.52}" fill="${fill}" transform="rotate(${a})"/>`)
    .join("")}<circle r="${r * 0.2}" fill="${core}"/></g>`;

const svg = (key, bg, body) => `<svg class="festart" viewBox="0 0 320 120" preserveAspectRatio="xMidYMid slice"
    role="img" aria-label="Ảnh minh họa: ${esc(ART[key])}"><rect width="320" height="120" fill="${bg}"/>${body}</svg>`;

const DRAW = {
    lantern: () => svg("lantern", "#2F3E6E", `<path d="M0 18 Q160 48 320 18" stroke="#E6E9F4" stroke-width="1.5" fill="none"/>${
        [[60, 30, "#C2456E"], [130, 38, "#E8A33C"], [200, 38, "#C2456E"], [270, 30, "#E8A33C"]].map(([x, y, c]) =>
            `<line x1="${x}" y1="${y - 10}" x2="${x}" y2="${y}" stroke="#E6E9F4"/><ellipse cx="${x}" cy="${y + 26}" rx="20" ry="26" fill="${c}"/>`
            + `<rect x="${x - 10}" y="${y - 2}" width="20" height="5" fill="#3A2400"/><rect x="${x - 10}" y="${y + 49}" width="20" height="5" fill="#3A2400"/>`
            + [-6, 6].map((d) => `<path d="M${x + d} ${y + 3} Q${x + d * 2.2} ${y + 26} ${x + d} ${y + 49}" stroke="#3A2400" stroke-opacity=".35" fill="none"/>`).join("")
        ).join("")}`),
    firework: () => svg("firework", "#1E2340", [[80, 50, "#F08DAE"], [200, 40, "#F2B65A"], [270, 75, "#AEB9E6"]].map(([x, y, c]) =>
        Array.from({ length: 12 }, (_, i) => {
            const a = i * Math.PI / 6;
            return `<line x1="${x + Math.cos(a) * 8}" y1="${y + Math.sin(a) * 8}" x2="${x + Math.cos(a) * 30}" y2="${y + Math.sin(a) * 30}" stroke="${c}" stroke-width="2.5" stroke-linecap="round"/>`;
        }).join("")).join("") + '<rect y="104" width="320" height="16" fill="#151A30"/>'),
    sakura: () => svg("sakura", "#FCE9EF", `<path d="M0 100 Q90 80 150 60 T320 20" stroke="#6B4A3A" stroke-width="6" fill="none"/>`
        + `<path d="M120 70 Q140 95 175 100" stroke="#6B4A3A" stroke-width="3" fill="none"/>`
        + [[40, 88, 12], [90, 72, 10], [150, 58, 14], [200, 44, 11], [250, 34, 13], [300, 22, 10], [175, 98, 10], [60, 40, 8], [230, 80, 9]]
            .map(([x, y, r]) => flower(x, y, r, "#F08DAE", "#C2456E")).join("")),
    market: () => svg("market", "#FDF0D8", [30, 130, 230].map((x) =>
        `<rect x="${x}" y="30" width="80" height="18" fill="#C2456E"/>${[0, 1, 2, 3].map((i) => `<rect x="${x + i * 20}" y="30" width="10" height="18" fill="#FFFFFF"/>`).join("")}`
        + `<rect x="${x + 6}" y="48" width="4" height="50" fill="#6B4A3A"/><rect x="${x + 70}" y="48" width="4" height="50" fill="#6B4A3A"/>`
        + `<rect x="${x}" y="80" width="80" height="18" fill="#E8A33C"/>`).join("") + '<rect y="98" width="320" height="22" fill="#E7D8BE"/>'),
    fabric: () => svg("fabric", "#E6E9F4", [[30, "#C2456E"], [95, "#2F3E6E"], [160, "#E8A33C"], [225, "#5E8C61"]].map(([x, c]) =>
        `<rect x="${x}" y="22" width="56" height="76" rx="6" fill="${c}"/><ellipse cx="${x + 28}" cy="22" rx="28" ry="7" fill="${c}" opacity=".7"/>`
        + `<path d="M${x + 8} 40 H${x + 48} M${x + 8} 60 H${x + 48} M${x + 8} 80 H${x + 48}" stroke="#FFFFFF" stroke-opacity=".5" stroke-width="2"/>`).join("")
        + '<rect y="98" width="320" height="22" fill="#C9CFE3"/>'),
    taiko: () => svg("taiko", "#3A2626", `<ellipse cx="160" cy="70" rx="70" ry="40" fill="#8A4B2A"/><ellipse cx="160" cy="70" rx="56" ry="32" fill="#E9D7B8"/>`
        + `<rect x="90" y="66" width="140" height="8" fill="#C2456E" opacity=".9"/>`
        + `<line x1="70" y1="20" x2="120" y2="52" stroke="#E8A33C" stroke-width="6" stroke-linecap="round"/>`
        + `<line x1="250" y1="20" x2="200" y2="52" stroke="#E8A33C" stroke-width="6" stroke-linecap="round"/>`)
};

/** The event's cover: its photo when the Moderator uploaded one, else a drawing. */
export function coverHtml(event, alt = "") {
    if (event.coverUrl) {
        return `<img class="festart photo" src="${esc(event.coverUrl)}" alt="${esc(alt || `Ảnh bìa: ${event.title}`)}" loading="lazy">`;
    }
    return DRAW[artKey(event)]();
}
