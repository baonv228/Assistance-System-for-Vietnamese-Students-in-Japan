// How one notification looks in the bell's panel and on the "Thông báo" page (components/notif-panel.js), drawn like
// Facebook's: a round picture with a small badge for what happened, the text with the names in bold, the time ("20
// giờ"), a dot while unread, and the list split into "Hôm nay" / "Trước đó". No DOM here (Node tests:
// src/test/js/notif-view.test.mjs).
import { esc, ic, LOGO_SVG } from "./ui.js";

/**
 * Badge of each type: [icon, tone]. Tones: br (brand pink), ok (green), crit (red), lan (lantern), ink (indigo),
 * warn (amber). Types not listed get the bell.
 */
const BADGES = {
    NEW_COMMENT: ["chat_bubble", "br"],
    BEST_ANSWER: ["workspace_premium", "lan"],
    CONTENT_PUBLISHED: ["check", "ok"],
    CONTENT_REJECTED: ["block", "crit"],
    CONTENT_REMOVED: ["delete", "crit"],
    CONTENT_RESTORED: ["restore", "ok"],
    REPORT_RESOLVED: ["flag", "ok"],
    REPORT_MUTED: ["flag", "crit"],
    PIN_APPROVED: ["location_on", "ok"],
    PIN_REJECTED: ["wrong_location", "crit"],
    PIN_MERGED: ["call_merge", "ink"],
    PLACE_MERGED: ["call_merge", "ink"],
    PLACE_UPDATED: ["edit_location_alt", "ink"],
    PLACE_CLOSED: ["door_front", "warn"],
    PLACE_REMOVED: ["location_off", "crit"],
    PLACE_REPORT_HANDLED: ["flag", "ok"],
    TAG_GRANTED: ["verified", "ok"],
    TAG_REJECTED: ["block", "crit"],
    TAG_EXPIRED: ["schedule", "warn"],
    QUEST_STREAK: ["local_fire_department", "lan"]
};
const DEFAULT_BADGE = ["notifications", "br"];

/** Colours of the letter pictures (a member's name), picked by the name so one person keeps one colour. */
const TONES = 6;
/** "Lan đã bình luận…": a name longer than this is not taken as a name. */
const ACTOR_MAX = 60;

/** [icon, tone] of the small badge. An answer to a question gets the Q&A bubble. */
export function badgeOf(n) {
    if (n?.type === "NEW_COMMENT" && String(n.link || "").startsWith("/qa/")) return ["question_answer", "br"];
    return BADGES[n?.type] || DEFAULT_BADGE;
}

/**
 * The member a NEW_COMMENT is about: the words before " đã " ("Lan Anh đã bình luận bài “…” của bạn."). Null for
 * every other type (they come from Senpai ơi itself).
 */
export function actorOf(n) {
    if (n?.type !== "NEW_COMMENT") return null;
    const text = String(n.content || "");
    const at = text.indexOf(" đã ");
    if (at <= 0 || at > ACTOR_MAX) return null;
    const name = text.slice(0, at).trim();
    return name && !name.includes("“") ? name : null;
}

/** First letter of the given name (the last word), as `avatar()` of ui.js. */
export function initialOf(name) {
    const word = String(name || "").trim().split(/\s+/).pop() || "?";
    return (Array.from(word)[0] || "?").toLocaleUpperCase("vi");
}

/** 0 … TONES-1, the same for the same name. */
export function toneOf(name) {
    let hash = 0;
    for (const ch of String(name || "")) hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
    return hash % TONES;
}

/**
 * The text as HTML: escaped, the member's name (NEW_COMMENT) and every “quoted” title or place name in bold, as
 * Facebook shows names and group names.
 */
export function messageHtml(n) {
    const text = String(n?.content ?? "");
    const actor = actorOf(n);
    const head = actor ? `<b>${esc(actor)}</b>` : "";
    const rest = actor ? text.slice(actor.length) : text;
    return head + rest.split(/(“[^“”]{1,300}”)/).map((part, i) =>
        i % 2 ? `“<b>${esc(part.slice(1, -1))}</b>”` : esc(part)).join("");
}

/** The round picture: the member's first letter for a comment, else the flower of Senpai ơi; the badge on it. */
export function pictureHtml(n) {
    const [icon, tone] = badgeOf(n);
    const actor = actorOf(n);
    const face = actor
        ? `<span class="np-face np-tone-${toneOf(actor)}">${esc(initialOf(actor))}</span>`
        : `<span class="np-face np-logo">${LOGO_SVG}</span>`;
    return `<span class="np-pic" aria-hidden="true">${face}<span class="np-badge np-${tone}">${ic(icon)}</span></span>`;
}

/** Facebook's short times: "Vừa xong", "5 phút", "20 giờ", "2 ngày", "3 tuần", "1 năm". */
export function shortAgo(iso, now = Date.now()) {
    const then = new Date(iso).getTime();
    if (!Number.isFinite(then)) return "";
    const minutes = Math.floor(Math.max(0, now - then) / 60000);
    if (minutes < 1) return "Vừa xong";
    if (minutes < 60) return `${minutes} phút`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours} giờ`;
    const days = Math.floor(hours / 24);
    if (days < 7) return `${days} ngày`;
    if (days < 365) return `${Math.floor(days / 7)} tuần`;
    return `${Math.floor(days / 365)} năm`;
}

/** "Hôm nay" (this device's date) then "Trước đó", each newest first as given; empty groups left out. */
export function groupByDay(items, now = new Date()) {
    const today = [];
    const earlier = [];
    const sameDay = (d) => d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()
        && d.getDate() === now.getDate();
    for (const n of items) {
        const when = new Date(n.createdAt);
        (!Number.isNaN(when.getTime()) && sameDay(when) ? today : earlier).push(n);
    }
    return [["today", "Hôm nay", today], ["earlier", "Trước đó", earlier]]
        .filter(([, , list]) => list.length)
        .map(([key, title, list]) => ({ key, title, items: list }));
}

/** The newest id among the notifications (0 when none). */
export const newestId = (items) => items.reduce((max, n) => Math.max(max, Number(n.id) || 0), 0);

/**
 * The page's list merged with a fresh first page: new ones on top, the ones already shown take the fresh copy (read
 * mark, link), newest first; nothing shown twice.
 */
export function mergeFresh(shown, fresh) {
    const byId = new Map(shown.map((n) => [n.id, n]));
    fresh.forEach((n) => byId.set(n.id, n));
    const time = (n) => Date.parse(n.createdAt) || 0;
    return [...byId.values()].sort((a, b) => time(b) - time(a) || Number(b.id) - Number(a.id));
}
