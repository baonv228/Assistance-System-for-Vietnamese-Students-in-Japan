// Facebook-like cards of the Blog list (kind POST) and the Hỏi-Đáp list (kind QA): the author with avatar, tags and
// time on top, the title and excerpt with "Xem thêm", topics and places, the photo grid (lib/post-media.js), the
// counts, then "Thích", "Bình luận" / "Trả lời" and "Chia sẻ". "Thích" works in place for Members (not on their own
// post, which the server refuses); a guest goes to sign in and comes back to the list; staff accounts cannot like.
import { apiClient } from "../api/client.js";
import { touchOnly } from "./device.js";
import { bindMediaGrids, mediaGrid } from "./post-media.js";
import { avatar, esc, fullDate, ic, loginUrl, placeChip, tagChips, timeAgo } from "./ui.js";

const KINDS = {
    POST: {
        path: "/blog", like: (id, on) => apiClient.likeBlog(id, on), word: "bài viết",
        replies: (p) => p.commentCount, repliesWord: "bình luận", reply: "Bình luận", replyIcon: "chat_bubble",
        processing: "Chỉ bạn thấy bài này cho đến khi quét xong"
    },
    QA: {
        path: "/qa", like: (id, on) => apiClient.likeQuestion(id, on), word: "câu hỏi",
        replies: (p) => p.answerCount, repliesWord: "câu trả lời", reply: "Trả lời", replyIcon: "rate_review",
        processing: "Chỉ bạn thấy câu hỏi này cho đến khi quét xong"
    }
};

const NO_RIGHT = "Tài khoản của bạn không thực hiện được thao tác này.";

const number = (n) => n.toLocaleString("vi-VN");

/**
 * One card. `user` is the signed-in user from the header (null for a guest). Options, for pages other than the lists
 * ("Dành cho bạn" of the Home and /for-you): `top` = HTML drawn above the author (why the post was chosen),
 * `topicHref(topic)` = topics as links to that address instead of filter buttons of the list, `mark(text)` = the title
 * and excerpt as escaped HTML with the searched text marked (default: plain escaped text).
 * A post without the card's parts (a server not restarted since they were added answers "Dành cho bạn" with only
 * `authorName` and `topicName`) still draws: the author's name without a link, the topic without a link, no photos.
 */
export function feedCard(p, kind, user, { top = "", topicHref = null, mark = esc } = {}) {
    const k = KINDS[kind];
    const href = `${k.path}/${p.id}`;
    const a = p.author ?? { id: null, displayName: p.authorName ?? "", avatarUrl: null, tags: [] };
    const profile = a.id == null ? null : `/users/${encodeURIComponent(a.id)}`;
    const topics = p.topics ?? (p.topicName ? [{ id: null, name: p.topicName }] : []);
    const flags = [
        p.pinned ? `<span class="badge">${ic("keep")}Đã ghim</span>` : "",
        kind === "QA" && p.solved ? `<span class="badge ok">${ic("check_circle")}Đã giải quyết</span>` : "",
        kind === "QA" && !p.solved && p.unanswered ? `<span class="badge lan">${ic("hourglass_empty")}Chưa ai trả lời</span>` : "",
        p.status === "PROCESSING" ? `<span class="badge warn" title="${esc(k.processing)}">${ic("hourglass_top")}Đang quét ảnh/video</span>` : ""
    ].join("");
    // The excerpt is cut by the server and then ends with "…": the rest is on the post page
    const cut = p.excerpt?.endsWith("…");
    const tags = topics.map((t) => {
        if (t.id == null) return `<span class="badge topic">${esc(t.name)}</span>`;
        return topicHref
            ? `<a class="badge topic" href="${esc(topicHref(t))}" title="Xem các bài thuộc chủ đề này">${esc(t.name)}</a>`
            : `<button class="badge topic" type="button" data-topic="${esc(t.id)}" title="Lọc theo chủ đề này">${esc(t.name)}</button>`;
    }).join("") + (p.places ?? []).map(placeChip).join("");
    return `
        <article class="card fbp${p.status === "PROCESSING" ? " processing" : ""}" data-post="${esc(p.id)}" aria-labelledby="fbp-t-${esc(p.id)}">
            ${top ? `<div class="fbp-top">${top}</div>` : ""}
            <header class="fbp-head">
                ${profile ? `<a class="fbp-avatar" href="${profile}" tabindex="-1" aria-hidden="true">${avatar(a)}</a>`
                    : `<span class="fbp-avatar" aria-hidden="true">${avatar(a)}</span>`}
                <div class="fbp-who">
                    <div class="fbp-name">${profile ? `<a href="${profile}">${esc(a.displayName)}</a>` : `<b>${esc(a.displayName)}</b>`}${tagChips(a.tags)}</div>
                    <div class="fbp-sub">
                        <a href="${href}" title="${esc(fullDate(p.createdAt))}"><time datetime="${esc(p.createdAt)}">${timeAgo(p.createdAt)}</time></a>
                        <span aria-hidden="true">·</span>
                        <span title="${p.status === "PROCESSING" ? esc(k.processing) : "Công khai"}">${ic(p.status === "PROCESSING" ? "lock" : "public")}<span class="sr-only">${p.status === "PROCESSING" ? "Chỉ mình bạn" : "Công khai"}</span></span>
                    </div>
                </div>
                ${flags ? `<div class="fbp-flags">${flags}</div>` : ""}
            </header>
            <div class="fbp-text">
                <h3 class="fbp-title" id="fbp-t-${esc(p.id)}"><a href="${href}">${mark(p.title ?? "")}</a></h3>
                ${p.excerpt ? `<p class="fbp-excerpt">${mark(p.excerpt)}${cut ? ` <a class="fbp-more" href="${href}">Xem thêm</a>` : ""}</p>` : ""}
            </div>
            ${tags ? `<div class="row tags fbp-tags">${tags}</div>` : ""}
            ${mediaGrid(p.media ?? [])}
            ${countsHtml(p, kind)}
            ${actionsHtml(p, kind, user)}
        </article>`;
}

/** Grey placeholders of `n` cards while the list loads. */
export const feedSkeleton = (n) => Array.from({ length: n }, () => `
    <div class="card fbp-skel" aria-hidden="true">
        <div class="sk-head"><span class="sk-avatar"></span><span class="sk-lines"><span></span><span></span></span></div>
        <span class="sk-line"></span><span class="sk-line short"></span><span class="sk-media"></span>
    </div>`).join("") + `<span class="sr-only">Đang tải…</span>`;

/** "♥ 12 · 3 bình luận"; hidden while both are 0 (shown again after a like). */
function countsHtml(p, kind) {
    const k = KINDS[kind];
    const likes = Number(p.likeCount) || 0;
    const replies = Number(k.replies(p)) || 0;
    return `<div class="fbp-counts"${likes || replies ? "" : " hidden"}>
        <span class="fbp-likes">${likes ? `<span class="fbp-heart" aria-hidden="true">${ic("favorite")}</span>${number(likes)}<span class="sr-only"> lượt thích</span>` : ""}</span>
        ${replies ? `<a class="fbp-replies${kind === "QA" && p.solved ? " solved" : ""}" href="${k.path}/${p.id}#comments">${number(replies)} ${k.repliesWord}</a>` : ""}
    </div>`;
}

/** Nothing while the post waits for its scan (only its author sees it). */
function actionsHtml(p, kind, user) {
    if (p.status !== "PUBLISHED") return "";
    const k = KINDS[kind];
    const own = Boolean(user) && p.author?.id != null && user.id === p.author.id;
    return `<div class="fbp-actions${own ? " two" : ""}">
        ${own ? "" : likeButton(p, user)}
        <a class="fbp-act" href="${k.path}/${p.id}#reply">${ic(k.replyIcon)}<span>${k.reply}</span></a>
        <button class="fbp-act" type="button" data-fbp="share">${ic("share")}<span>Chia sẻ</span></button>
    </div>`;
}

function likeButton(p, user) {
    const inner = `${ic("favorite")}<span>Thích</span>`;
    if (!user) return `<a class="fbp-act" href="${esc(loginUrl())}" title="Đăng nhập để thích">${inner}</a>`;
    if (user.role !== "USER") return `<button class="fbp-act" type="button" disabled title="${NO_RIGHT}">${inner}</button>`;
    return `<button class="fbp-act${p.liked ? " on" : ""}" type="button" data-fbp="like" aria-pressed="${Boolean(p.liked)}">${inner}</button>`;
}

/**
 * Photo viewer, "Thích" and "Chia sẻ" of the cards inside `root`. `find(id)` returns the card's data (kept by the
 * page), which a like updates (`liked`, `likeCount`). `notify(text, icon, kind)` shows a short notice (the header's
 * `showSiteNotice`; kind "crit" for an error).
 */
export function bindFeedCards(root, { kind, find, notify }) {
    const k = KINDS[kind];
    bindMediaGrids(root, (tile) => {
        const p = postOf(tile, find);
        return p ? { media: p.media, title: p.title, href: `${k.path}/${p.id}` } : null;
    });
    root.addEventListener("click", (event) => {
        const button = event.target.closest("[data-fbp]");
        if (!button || !root.contains(button)) return;
        const p = postOf(button, find);
        if (!p) return;
        if (button.dataset.fbp === "like") like(button, p, kind, notify);
        else if (button.dataset.fbp === "share") share(p, k, notify);
    });
}

function postOf(element, find) {
    const card = element.closest("[data-post]");
    return card ? find(Number(card.dataset.post)) : null;
}

async function like(button, p, kind, notify) {
    if (button.disabled) return;
    const card = button.closest("[data-post]");
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    try {
        const state = await KINDS[kind].like(p.id, !p.liked);
        p.liked = state.active;
        p.likeCount = state.count;
        if (card.isConnected) {
            button.classList.toggle("on", p.liked);
            button.setAttribute("aria-pressed", String(p.liked));
            card.querySelector(".fbp-counts").outerHTML = countsHtml(p, kind);
        }
    } catch (error) {
        if (error.status === 401) {
            location.href = loginUrl();             // the session ended: sign in and come back to this list
            return;
        }
        notify(error.message || "Không thực hiện được. Vui lòng thử lại.", "error", "crit");
    } finally {
        button.disabled = false;
        button.removeAttribute("aria-busy");
    }
}

/** Phones: the share sheet. Elsewhere (or when it is not there): the link is copied. */
async function share(p, k, notify) {
    const url = new URL(`${k.path}/${p.id}`, location.origin).href;
    if (touchOnly() && navigator.share) {
        try {
            await navigator.share({ title: p.title, url });
            return;
        } catch (error) {
            if (error?.name === "AbortError") return;      // closed by the user
        }
    }
    try {
        await navigator.clipboard.writeText(url);
        notify(`Đã sao chép liên kết ${k.word}.`, "link");
    } catch {
        notify(`Không sao chép được. Liên kết ${k.word}: ${url}`, "link", "crit");
    }
}
