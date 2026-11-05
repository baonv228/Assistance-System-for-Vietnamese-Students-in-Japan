// Pieces of a post of the personal feed ("Dành cho bạn"), shared by the member Home and the page /for-you. The server
// chooses and ranks the posts (HomeService); these only draw them. Styles: .whys/.why/.spot-meta in site.css.
import { esc, ic, timeAgo } from "./ui.js";

/** Why the server chose the post, in its ranking order: the place near the user, the stage topic, the interest. */
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
