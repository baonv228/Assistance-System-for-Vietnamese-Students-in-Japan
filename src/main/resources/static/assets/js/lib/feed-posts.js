// Pieces of a post of the personal feed ("Dành cho bạn"), shared by the member Home and the page /for-you. The server
// chooses and ranks the posts (HomeService); these only draw them. Styles: .whys/.why/.spot-meta in site.css.
import { esc, ic, timeAgo } from "./ui.js";

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

/** Topic, author, age, likes and comments under the title. */
export const postMeta = (p) => `<span class="row spot-meta">${p.topicName ? `<span class="badge">${esc(p.topicName)}</span>` : ""}
    <span class="note">${esc(p.authorName)} · ${esc(timeAgo(p.createdAt))} ·
        <span aria-label="${p.likeCount} lượt thích">${ic("favorite")}${p.likeCount}</span>
        <span aria-label="${p.commentCount} bình luận">${ic("chat_bubble")}${p.commentCount}</span></span></span>`;

/** The post's own page. */
export const postUrl = (p) => `/blog/${encodeURIComponent(p.id)}`;

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
