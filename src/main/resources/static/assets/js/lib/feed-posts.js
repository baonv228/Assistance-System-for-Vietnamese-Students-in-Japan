// Pieces of a post of the personal feed ("Dành cho bạn"), shared by the member Home and the page /for-you. The server
// chooses and ranks the posts (HomeService); these only draw them. Styles: .whys/.why in site.css, the card in blog.css.
import { feedCard } from "./feed-card.js";
import { esc, ic } from "./ui.js";

/**
 * Why the server chose the post, in its ranking order: the place near the user, a topic the user chose, the stage
 * topic, a topic learnt from what the user did (the last two share "INTEREST": one of them only).
 */
const REASON_STYLE = {
    AREA: ["location_on", ""], PREFECTURE: ["map", ""], STAGE: ["flag", "stage"], INTEREST: ["interests", "interest"]
};

export const reasonChips = (post) => (post.reasons?.length ? `<div class="whys">${post.reasons.map((r) => {
    const [icon, style] = REASON_STYLE[r.code] || ["auto_awesome", ""];
    return `<span class="why ${style}">${ic(icon)}<span>${esc(r.label)}${r.detail ? ` · <b>${esc(r.detail)}</b>` : ""}</span></span>`;
}).join("")}</div>` : "");

/**
 * Why the post is in "Dành cho bạn" as one quiet line (member Home, `.mh-why` in home.css): the first reason in full,
 * the others counted ("+1", written out on hover and for screen readers). /for-you shows them all (`reasonChips`).
 */
export function reasonLine(post) {
    const [first, ...rest] = post.reasons || [];
    if (!first) return "";
    const [icon] = REASON_STYLE[first.code] || ["auto_awesome"];
    const others = rest.map((r) => r.label + (r.detail ? `: ${r.detail}` : "")).join("; ");
    return `<p class="mh-why">${ic(icon)}<span>${esc(first.label)}${first.detail ? ` · <b>${esc(first.detail)}</b>` : ""}</span>${
        rest.length ? `<span class="mh-why-more" title="${esc(others)}" aria-hidden="true">+${rest.length}</span><span class="sr-only">; ${esc(others)}</span>` : ""}</p>`;
}

/**
 * A post of "Dành cho bạn" drawn as a card of the Blog list (lib/feed-card.js, user decision 25/11/2026), with why it
 * was chosen above the author. The feed holds only published posts of other members, so "Thích" is there for a Member.
 * Topics open the Blog filtered by them; `mark(text)` marks a searched text in the title and excerpt (/for-you).
 */
export const forYouCard = (p, user, { mark } = {}) => feedCard({ ...p, status: "PUBLISHED", pinned: false }, "POST",
    user, { top: reasonChips(p), topicHref: (t) => `/blog?topics=${encodeURIComponent(t.id)}`, mark });

/**
 * The interests in "Dựa trên" (Home and /for-you): the topics the member chose, then those learnt from what the member
 * did (`feed.interests` in that order, `feed.chosenInterests` the chosen ones), each drawn by the page's own
 * `chip(icon, text, what, extra)`.
 */
export const interestChips = (feed, chip) => {
    const chosen = new Set(feed.chosenInterests || []);
    return (feed.interests || []).map((t) => (chosen.has(t)
        ? chip("interests", t, "Chủ đề bạn chọn")
        : chip("history", t, "Chủ đề bạn hay đọc", "learnt"))).join("");
};

/** The line under "Dựa trên": where the interests come from, and how to choose some. */
export function interestNote(feed) {
    const all = feed.interests?.length || 0;
    const chosen = feed.chosenInterests?.length || 0;
    const learnt = "những bài bạn đã thích, lưu, bình luận hoặc viết trong 90 ngày qua";
    if (!all) {
        return "Bấm “Điều chỉnh” để chọn chủ đề bạn quan tâm. Senpai ơi cũng học từ những bài bạn thích, lưu hoặc bình luận để gợi ý sát hơn.";
    }
    if (!chosen) {
        return `Chủ đề quan tâm được rút ra từ ${learnt}. Bấm “Điều chỉnh” để tự chọn chủ đề.`;
    }
    return "Bài thuộc chủ đề bạn chọn được ưu tiên trong gợi ý."
        + (all > chosen ? ` Các chủ đề còn lại được rút ra từ ${learnt}.` : "");
}
