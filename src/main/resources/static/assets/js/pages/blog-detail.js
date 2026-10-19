// Post Detail (UC-10 View Blog Details): the post, its author, media and comment tree, with the actions the
// viewer may take. Guests can read; every action that writes data sends them to sign in first.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, tagChips, timeAgo } from "../lib/ui.js";

// Shared by two pages, which set <body data-kind="…">: pages/blog-detail.html (POST, Blog) and
// pages/qa-detail.html (QA, Hỏi-Đáp). Each kind has its own endpoints; comment actions are shared.
const QUESTION = document.body.dataset.kind === "QA";
const soon = (feature, params = {}) => `/coming-soon?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    list: QUESTION ? "/qa" : "/blog",
    listName: QUESTION ? "Hỏi-Đáp" : "Blog",
    topic: (id) => `${QUESTION ? "/qa" : "/blog"}?topics=${id}`,
    place: (id) => soon("map", { place: id }),
    profile: (id) => soon("public-profile", { id }),
    edit: (id) => `${QUESTION ? "/qa" : "/blog"}/${id}/edit`,
    login: "/login"
};
const API = QUESTION ? {
    get: apiClient.question, like: apiClient.likeQuestion, save: apiClient.saveQuestion,
    remove: apiClient.deleteQuestion, report: apiClient.reportQuestion, comment: apiClient.answerQuestion
} : {
    get: apiClient.blog, like: apiClient.likeBlog, save: apiClient.saveBlog,
    remove: apiClient.deleteBlog, report: apiClient.reportBlog, comment: apiClient.commentBlog
};
const WORD = QUESTION ? "câu hỏi" : "bài viết";

const MSG = {
    MSG19: "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại.",
    MSG14: "Đã có lỗi xảy ra. Vui lòng thử lại sau.",
    MSG40: "Hãy chọn lý do báo cáo.",
    MSG16_POST: QUESTION
        ? "Bạn có chắc muốn xóa câu hỏi này? Các câu trả lời cũng bị xóa, và điểm câu hỏi đã nhận sẽ bị thu lại."
        : "Bạn có chắc muốn xóa bài viết này? Bình luận của bài cũng bị xóa, và điểm bài đã nhận sẽ bị thu lại.",
    MSG16_COMMENT: "Bạn có chắc muốn xóa bình luận này?"
};
const STATUS_NOTE = {
    PROCESSING: ["hourglass_top", "warn", "Ảnh hoặc video của bài đang được quét. Trong lúc này chỉ bạn thấy bài."],
    HIDDEN: ["visibility_off", "crit", "Bài đang tạm ẩn vì có nhiều báo cáo, chờ Moderator xem xét."],
    REJECTED: ["block", "crit", "Bài chưa được đăng vì vi phạm Quy tắc cộng đồng. Bạn có thể sửa rồi gửi lại."]
};
const COMMENT_MAX = 2000;
const MAX_INDENT = 3;

const content = document.querySelector("#content");
const toastBox = document.querySelector("#toast");
// Address /blog/{id} or /qa/{id} (old ?id= links are moved there by the server)
const blogId = Number.parseInt(location.pathname.split("/").pop(), 10);

let user = null;            // signed-in user or null
let post = null;            // last BlogDetail from the API
let replyTo = null;         // comment id whose reply box is open
let reportTarget = null;    // { kind: "blog" | "comment", id }

// ---------------------------------------------------------------- loading

async function load() {
    if (!Number.isInteger(blogId) || blogId <= 0) {
        renderState("error", MSG.MSG19, `<a class="btn sm" href="${ROUTES.list}">Về ${ROUTES.listName}</a>`);
        return;
    }
    try {
        render(await API.get(blogId));
    } catch (error) {
        if (error.status === 404 && await openOtherKind()) {
            return;
        }
        if (error.status === 404) {
            renderState("empty", MSG.MSG19, `<a class="btn sm" href="${ROUTES.list}">Về ${ROUTES.listName}</a>`);
        } else {
            renderState("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button>`);
        }
    }
}

/**
 * Questions used to open on the Blog detail page, so old bookmarks and shared links point there. When the id belongs to
 * the other kind, move to that page (keeping the query string) instead of showing "not found".
 */
async function openOtherKind() {
    try {
        await (QUESTION ? apiClient.blog(blogId) : apiClient.question(blogId));
    } catch {
        return false;
    }
    location.replace(`${QUESTION ? "/blog" : "/qa"}/${blogId}${location.search}${location.hash}`);
    return true;
}

function renderState(kind, message, action) {
    document.title = `${QUESTION ? "Câu hỏi" : "Bài viết"} – Senpai ơi`;
    content.innerHTML = `<div class="state ${kind}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(kind === "error" ? "cloud_off" : "search_off", "big")}<p>${esc(message)}</p>${action}</div>`;
}

// ---------------------------------------------------------------- post

function render(data) {
    post = data;
    document.title = `${data.title} – Senpai ơi`;
    const question = data.type === "QA";
    const v = data.viewer;
    const status = STATUS_NOTE[data.status];
    content.innerHTML = `
        <article class="card post-full">
            ${status ? `<div class="alert ${status[1] === "crit" ? "error" : "warnbox"}">${ic(status[0])} ${esc(status[2])}</div>` : ""}
            <div class="row meta">
                <span class="badge ${question ? "lan" : "br"}">${ic(question ? "help" : "article")}${question ? "Hỏi đáp" : "Bài viết"}</span>
                ${question && data.bestCommentId ? `<span class="badge ok">${ic("check_circle")}Đã giải quyết</span>` : ""}
                ${question && !data.bestCommentId && data.status === "PUBLISHED" ? `<span class="badge">${ic("contact_support")}Câu hỏi chờ trả lời</span>` : ""}
                ${data.pinned ? `<span class="badge">${ic("keep")}Đã ghim</span>` : ""}
            </div>
            <h1>${esc(data.title)}</h1>
            <div class="row byline">
                ${authorHtml(data.author)}
                <span class="note">·</span>
                <time class="note" datetime="${esc(data.createdAt)}" title="${esc(fullDate(data.createdAt))}">${timeAgo(data.createdAt)}</time>
                ${data.edited ? `<span class="note">· đã sửa</span>` : ""}
            </div>
            ${data.topics.length || data.places.length ? `<div class="row tags">
                ${data.topics.map((t) => `<a class="badge topic" href="${ROUTES.topic(t.id)}">${esc(t.name)}</a>`).join("")}
                ${data.places.map((p) => `<a class="place" href="${ROUTES.place(p.id)}">${ic("location_on")}${esc(p.name)}</a>`).join("")}
            </div>` : ""}
            ${mediaHtml(data.media)}
            <div class="post-body">${richText(data.content)}</div>
            ${placeMapHtml(data.places)}
            <div class="row actions-bar">${postActions(data)}</div>
        </article>
        <section class="card comments" aria-labelledby="comments-title">
            <h2 id="comments-title">${question ? "Câu trả lời" : "Bình luận"} <span class="note">(${data.commentCount})</span></h2>
            ${commentForm(null)}
            ${data.comments.length
                ? `<ol class="comment-list">${data.comments.map((c) => commentHtml(c, 0)).join("")}</ol>`
                : `<p class="note empty-comments">${question ? "Chưa có câu trả lời. Bạn biết câu trả lời thì chia sẻ nhé." : "Chưa có bình luận nào."}</p>`}
        </section>`;
    mountPlaceMap(data.places);
}

function authorHtml(author) {
    return `<a class="author" href="${ROUTES.profile(author.id)}">${avatar(author)}<b>${esc(author.displayName)}</b></a>
        ${tagChips(author.tags)}
        ${author.rankName ? `<span class="badge rank" title="Cấp bậc">${ic("workspace_premium")}${esc(author.rankName)}</span>` : ""}`;
}

function mediaHtml(media) {
    if (!media.length) return "";
    return `<div class="media-grid count-${Math.min(media.length, 4)}">${media.map((m) => m.type === "VIDEO"
        ? `<video controls preload="metadata" src="${esc(m.url)}"></video>`
        : `<a href="${esc(m.url)}" target="_blank" rel="noopener"><img src="${esc(m.url)}" alt="Ảnh trong bài" loading="lazy"
             ${m.width ? `width="${m.width}" height="${m.height}"` : ""}></a>`).join("")}</div>`;
}

// ---------------------------------------------------------------- places map
// The linked places on a small OpenStreetMap map (Leaflet + OSM tiles, free, no key). Leaflet is loaded only for a
// post that has places with a position. The map lives in its own element, moved into each new render, so liking or
// commenting does not rebuild it.

const LEAFLET = {
    css: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
    cssHash: "sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=",
    js: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
    jsHash: "sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo="
};
const OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
const OSM_CREDIT = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>';
let leafletLoading = null;
const placeMap = { el: null, map: null, key: "", ticket: 0 };

const mappable = (places) => places.filter((p) => p.lat != null && p.lng != null);

function placeMapHtml(places) {
    if (!mappable(places).length) return "";
    return `<section class="place-map-box" aria-labelledby="place-map-title">
            <h2 id="place-map-title">${ic("map")}Địa điểm liên quan trên bản đồ</h2>
            <div id="place-map-slot"></div>
        </section>`;
}

function loadLeaflet() {
    if (!leafletLoading) {
        leafletLoading = new Promise((resolve, reject) => {
            const css = Object.assign(document.createElement("link"),
                { rel: "stylesheet", href: LEAFLET.css, integrity: LEAFLET.cssHash, crossOrigin: "" });
            const js = Object.assign(document.createElement("script"),
                { src: LEAFLET.js, integrity: LEAFLET.jsHash, crossOrigin: "", async: true });
            js.onload = () => (window.L ? resolve(window.L) : reject(new Error("Leaflet")));
            js.onerror = () => {
                leafletLoading = null;                  // try again on the next render
                js.remove();
                reject(new Error("Leaflet"));
            };
            document.head.append(css, js);
        });
    }
    return leafletLoading;
}

async function mountPlaceMap(places) {
    const slot = document.getElementById("place-map-slot");
    const spots = mappable(places);
    if (!slot || !spots.length) return;
    if (!placeMap.el) {
        placeMap.el = document.createElement("div");
        placeMap.el.className = "place-map";
        placeMap.el.setAttribute("role", "region");
        placeMap.el.setAttribute("aria-label", "Bản đồ các địa điểm liên quan");
    }
    slot.replaceWith(placeMap.el);
    const key = spots.map((p) => `${p.id}@${p.lat},${p.lng}`).join("|");
    if (placeMap.map && placeMap.key === key) {
        placeMap.map.invalidateSize();
        return;
    }
    const ticket = ++placeMap.ticket;
    let L;
    try {
        L = await loadLeaflet();
    } catch {
        if (ticket === placeMap.ticket) {
            placeMap.el.innerHTML = `<p class="map-off">${ic("map")}Không tải được bản đồ lúc này. Các địa điểm vẫn ở phần thẻ phía trên.</p>`;
        }
        return;
    }
    if (ticket !== placeMap.ticket) return;             // a newer render is drawing the map
    if (placeMap.map) placeMap.map.remove();
    placeMap.el.innerHTML = "";
    const map = L.map(placeMap.el, { scrollWheelZoom: false });
    L.tileLayer(OSM_TILES, { maxZoom: 19, attribution: OSM_CREDIT }).addTo(map);
    const pin = L.divIcon({ className: "map-pin", html: ic("location_on"), iconSize: [34, 34], iconAnchor: [17, 32],
        popupAnchor: [0, -28] });
    const markers = spots.map((p) => {
        const line = [p.category, p.address].filter(Boolean).join(" · ");
        return L.marker([p.lat, p.lng], { icon: pin, title: p.name, alt: p.name })
            .bindPopup(`<b>${esc(p.name)}</b>${line ? `<br><small>${esc(line)}</small>` : ""}
                <br><a href="${ROUTES.place(p.id)}">Xem trên Bản đồ Senpai ơi</a>`)
            .addTo(map);
    });
    if (spots.length === 1) {
        map.setView([spots[0].lat, spots[0].lng], 16);
    } else {
        map.fitBounds(L.featureGroup(markers).getBounds(), { padding: [32, 32], maxZoom: 16 });
    }
    placeMap.map = map;
    placeMap.key = key;
}

/** Escaped text with line breaks kept and web addresses turned into links. */
function richText(text) {
    const linked = esc(text).replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])/g,
        (url) => `<a href="${url}" target="_blank" rel="nofollow noopener ugc">${url}</a>`);
    return linked.split(/\n{2,}/).map((para) => `<p>${para.replace(/\n/g, "<br>")}</p>`).join("");
}

function postActions(data) {
    const v = data.viewer;
    const published = data.status === "PUBLISHED";
    const parts = [];
    // Like: not on one's own post (the count is still shown)
    if (v.author) {
        parts.push(`<span class="stat">${ic("favorite")}${data.likeCount} lượt thích</span>`);
    } else {
        parts.push(actionButton({ act: "like-post", pressed: v.liked, icon: "favorite", label: `Thích · ${data.likeCount}`,
            disabled: v.signedIn && !v.canInteract }));
    }
    parts.push(actionButton({ act: "save-post", pressed: v.saved, icon: v.saved ? "bookmark_added" : "bookmark",
        label: v.saved ? "Đã lưu" : "Lưu", disabled: v.signedIn && !v.canInteract }));
    if (published) {
        parts.push(`<button class="btn sm" type="button" data-act="focus-comment">${ic("chat_bubble")}Bình luận</button>`);
    }
    parts.push(`<span class="grow"></span>`);
    if (v.author) {
        parts.push(`<a class="btn sm" href="${ROUTES.edit(data.id)}">${ic("edit")}Sửa</a>`);
        parts.push(`<button class="btn sm dan" type="button" data-act="delete-post">${ic("delete")}Xóa</button>`);
    } else if (published) {
        parts.push(actionButton({ act: "report-post", icon: "flag", label: "Báo cáo", disabled: v.signedIn && !v.canInteract }));
    }
    return parts.join("");
}

/** A guest gets a link to sign in instead of the button. */
function actionButton({ act, pressed, icon, label, disabled = false, small = true }) {
    if (!user) {
        return `<a class="btn ${small ? "sm" : ""}" href="${ROUTES.login}">${ic(icon)}${esc(label)}</a>`;
    }
    const aria = pressed === undefined ? "" : `aria-pressed="${pressed}"`;
    return `<button class="btn ${small ? "sm" : ""}${pressed ? " on" : ""}" type="button" data-act="${act}" ${aria}
        ${disabled ? `disabled title="Tài khoản của bạn không thực hiện được thao tác này."` : ""}>${ic(icon)}${esc(label)}</button>`;
}

// ---------------------------------------------------------------- comments

function commentForm(parentId) {
    const question = post.type === "QA";
    if (post.status !== "PUBLISHED") {
        return parentId ? "" : `<p class="note">Bài chưa được đăng nên chưa nhận bình luận.</p>`;
    }
    if (!user) {
        return parentId ? "" : `<div class="login-box"><span>Đăng nhập để ${question ? "trả lời" : "bình luận"} và thích ${WORD}.</span>
            <a class="btn pri sm" href="${ROUTES.login}">${ic("login")}Đăng nhập</a></div>`;
    }
    if (!post.viewer.canInteract) {
        return parentId ? "" : `<p class="note">Tài khoản của bạn chỉ xem được, không bình luận được.</p>`;
    }
    const id = parentId ? `reply-${parentId}` : "comment-new";
    const placeholder = parentId ? "Viết câu trả lời cho bình luận này"
        : question ? "Viết câu trả lời của bạn" : "Chia sẻ thêm kinh nghiệm hoặc góp ý";
    return `<form class="comment-form" data-parent="${parentId ?? ""}">
        <label class="sr-only" for="${id}">${placeholder}</label>
        <textarea id="${id}" name="content" rows="${parentId ? 2 : 3}" maxlength="${COMMENT_MAX}" placeholder="${placeholder}" required></textarea>
        <p class="field-error" role="alert" hidden></p>
        <div class="row">
            <span class="note counter" aria-live="polite">0/${COMMENT_MAX}</span><span class="grow"></span>
            ${parentId ? `<button class="btn sm" type="button" data-act="cancel-reply">Hủy</button>` : ""}
            <button class="btn pri sm" type="submit">${ic("send")}Gửi</button>
        </div>
    </form>`;
}

function commentHtml(c, depth) {
    const indent = Math.min(depth, MAX_INDENT);
    const replies = c.replies.length ? `<ol class="comment-list">${c.replies.map((r) => commentHtml(r, depth + 1)).join("")}</ol>` : "";
    if (c.removed) {
        return `<li class="comment removed depth-${indent}" id="c-${c.id}">
            <p class="note">${ic("delete")} Bình luận đã bị xóa.</p>${replies}</li>`;
    }
    const v = post.viewer;
    const canAct = user && v.canInteract;
    const actions = [];
    if (c.mine) {
        if (c.likeCount) actions.push(`<span class="stat sm">${ic("favorite")}${c.likeCount}</span>`);
    } else {
        actions.push(actionButton({ act: "like-comment", pressed: c.liked, icon: "favorite",
            label: c.likeCount ? `Thích · ${c.likeCount}` : "Thích", disabled: user && !canAct }));
    }
    if (post.status === "PUBLISHED") {
        actions.push(user
            ? `<button class="btn sm ghost" type="button" data-act="reply" ${canAct ? "" : "disabled"}>${ic("reply")}Trả lời</button>`
            : `<a class="btn sm ghost" href="${ROUTES.login}">${ic("reply")}Trả lời</a>`);
    }
    if (c.mine) {
        actions.push(`<button class="btn sm ghost dan" type="button" data-act="delete-comment">${ic("delete")}Xóa</button>`);
    } else {
        actions.push(actionButton({ act: "report-comment", icon: "flag", label: "Báo cáo", disabled: user && !canAct }));
    }
    // The asker picks the best answer among direct answers written by other people
    if (post.type === "QA" && v.author && v.canInteract && depth === 0 && !c.mine && !c.best) {
        actions.push(`<button class="btn sm ghost okbtn" type="button" data-act="best">${ic("verified")}Chọn là câu trả lời hay nhất</button>`);
    }
    return `<li class="comment depth-${indent}${c.best ? " best" : ""}" id="c-${c.id}" data-id="${c.id}">
        ${c.best ? `<span class="badge ok best-badge">${ic("verified")}Câu trả lời hay nhất</span>` : ""}
        <div class="row byline">${authorHtml(c.author)}<span class="note">·</span>
            <time class="note" datetime="${esc(c.createdAt)}" title="${esc(fullDate(c.createdAt))}">${timeAgo(c.createdAt)}</time>
            ${c.edited ? `<span class="note">· đã sửa</span>` : ""}</div>
        <div class="comment-body">${richText(c.content)}</div>
        <div class="row comment-actions">${actions.join("")}</div>
        <div class="reply-slot">${replyTo === c.id ? commentForm(c.id) : ""}</div>
        ${replies}
    </li>`;
}

// ---------------------------------------------------------------- actions

async function guarded(action, { onNotFound = true } = {}) {
    try {
        return await action();
    } catch (error) {
        if (error.status === 401) {
            location.href = ROUTES.login;
        } else if (error.status === 404 && onNotFound) {
            toast(error.message || MSG.MSG19, "error");
            await load();
        } else {
            toast(error.message || MSG.MSG14, "error");
        }
        return undefined;
    }
}

content.addEventListener("click", async (event) => {
    if (event.target.closest("[data-retry]")) {
        content.innerHTML = `<p class="note">Đang tải…</p>`;
        await load();
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button || button.disabled) return;
    const commentId = Number(button.closest("[data-id]")?.dataset.id);
    switch (button.dataset.act) {
        case "like-post": {
            const on = button.getAttribute("aria-pressed") !== "true";
            const state = await busy(button, () => guarded(() => API.like(blogId, on)));
            if (state) {
                post.viewer.liked = state.active;
                post.likeCount = state.count;
                button.outerHTML = actionButton({ act: "like-post", pressed: state.active, icon: "favorite", label: `Thích · ${state.count}` });
            }
            break;
        }
        case "save-post": {
            const on = button.getAttribute("aria-pressed") !== "true";
            const state = await busy(button, () => guarded(() => API.save(blogId, on)));
            if (state) {
                post.viewer.saved = state.saved;
                button.outerHTML = actionButton({ act: "save-post", pressed: state.saved,
                    icon: state.saved ? "bookmark_added" : "bookmark", label: state.saved ? "Đã lưu" : "Lưu" });
                toast(state.saved ? `Đã lưu ${WORD}. Xem lại ở Tôi → Đã lưu.` : `Đã bỏ lưu ${WORD}.`);
            }
            break;
        }
        case "focus-comment":
            content.querySelector("#comment-new, .login-box a")?.focus();
            content.querySelector(".comments").scrollIntoView({ behavior: "smooth", block: "start" });
            break;
        case "delete-post":
            if (await confirmDialog(MSG.MSG16_POST)) {
                const ok = await guarded(() => API.remove(blogId).then(() => true));
                if (ok) {
                    toast(`Đã xóa ${WORD}.`);
                    setTimeout(() => location.replace(ROUTES.list), 900);
                }
            }
            break;
        case "report-post":
            openReport({ kind: "blog", id: blogId });
            break;
        case "like-comment": {
            const on = button.getAttribute("aria-pressed") !== "true";
            const state = await busy(button, () => guarded(() => apiClient.likeComment(commentId, on)));
            if (state) {
                button.outerHTML = actionButton({ act: "like-comment", pressed: state.active, icon: "favorite",
                    label: state.count ? `Thích · ${state.count}` : "Thích" });
            }
            break;
        }
        case "reply":
            replyTo = commentId;
            render(post);
            content.querySelector(`#reply-${commentId}`)?.focus();
            break;
        case "cancel-reply":
            replyTo = null;
            render(post);
            break;
        case "report-comment":
            openReport({ kind: "comment", id: commentId });
            break;
        case "delete-comment":
            if (await confirmDialog(MSG.MSG16_COMMENT)) {
                const data = await guarded(() => apiClient.deleteComment(commentId));
                if (data) {
                    render(data);
                    toast("Đã xóa bình luận.");
                }
            }
            break;
        case "best": {
            const data = await busy(button, () => guarded(() => apiClient.chooseBestAnswer(blogId, commentId)));
            if (data) {
                render(data);
                toast("Đã chọn câu trả lời hay nhất. Người trả lời được +5 điểm.");
                document.querySelector(`#c-${commentId}`)?.scrollIntoView({ behavior: "smooth", block: "center" });
            }
            break;
        }
        default:
            break;
    }
});

// Comment and reply forms
content.addEventListener("input", (event) => {
    if (event.target.matches(".comment-form textarea")) {
        const form = event.target.closest("form");
        form.querySelector(".counter").textContent = `${event.target.value.length}/${COMMENT_MAX}`;
        form.querySelector(".field-error").hidden = true;
    }
});

content.addEventListener("submit", async (event) => {
    const form = event.target.closest(".comment-form");
    if (!form) return;
    event.preventDefault();
    const text = form.content.value.trim();
    const error = form.querySelector(".field-error");
    if (!text) {
        error.textContent = QUESTION && !form.dataset.parent ? "Hãy viết câu trả lời." : "Hãy viết nội dung bình luận.";
        error.hidden = false;
        form.content.focus();
        return;
    }
    const submit = form.querySelector('[type="submit"]');
    const parentId = form.dataset.parent ? Number(form.dataset.parent) : null;
    try {
        const data = await busy(submit, () => API.comment(blogId, { content: text, parentId }));
        const before = new Set(allIds(post.comments));
        replyTo = null;
        render(data);
        toast(QUESTION && !parentId ? "Đã đăng câu trả lời." : "Đã đăng bình luận.");
        const added = allIds(data.comments).find((id) => !before.has(id));
        const node = added && document.querySelector(`#c-${added}`);
        if (node) {
            node.classList.add("flash");
            node.scrollIntoView({ behavior: "smooth", block: "center" });
        }
    } catch (err) {
        if (err.status === 401) {
            location.href = ROUTES.login;
            return;
        }
        // Keep the text so the user can fix it (keyword, link, length)
        error.textContent = err.message || MSG.MSG14;
        error.hidden = false;
        if (err.status === 404) await load();
    }
});

const allIds = (nodes) => nodes.flatMap((n) => [n.id, ...allIds(n.replies)]);

async function busy(button, action) {
    button.disabled = true;
    try {
        return await action();
    } finally {
        if (button.isConnected) button.disabled = false;
    }
}

// ---------------------------------------------------------------- report dialog

const reportDlg = document.querySelector("#report-dlg");
const reportForm = document.querySelector("#report-form");
const reportError = document.querySelector("#report-error");

function openReport(target) {
    reportTarget = target;
    document.querySelector("#report-title").textContent = target.kind === "blog" ? `Báo cáo ${WORD}` : "Báo cáo bình luận";
    document.querySelector("#report-reasons").innerHTML = `<legend class="sr-only">Lý do</legend>`
        + post.reportReasons.map((r) => `<label class="check"><input type="radio" name="reason" value="${r.id}"><span>${esc(r.label)}</span></label>`).join("");
    document.querySelector("#report-note").value = "";
    reportError.hidden = true;
    reportDlg.showModal();
}

reportForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const reason = reportForm.querySelector('input[name="reason"]:checked');
    if (!reason) {
        reportError.textContent = MSG.MSG40;
        reportError.hidden = false;
        return;
    }
    const body = { reasonId: Number(reason.value), note: document.querySelector("#report-note").value.trim() || null };
    const submit = document.querySelector("#report-submit");
    try {
        const result = await busy(submit, () => reportTarget.kind === "blog"
            ? API.report(reportTarget.id, body)
            : apiClient.reportComment(reportTarget.id, body));
        reportDlg.close();
        toast("Cảm ơn bạn. Báo cáo đã được gửi.");                       // MSG38
        if (result && reportTarget.kind === "comment") render(result);
    } catch (err) {
        if (err.status === 401) {
            location.href = ROUTES.login;
            return;
        }
        reportError.textContent = err.message || MSG.MSG14;           // e.g. MSG39 already reported
        reportError.hidden = false;
    }
});

// ---------------------------------------------------------------- confirm dialog and toast

const confirmDlg = document.querySelector("#confirm-dlg");

function confirmDialog(text) {
    document.querySelector("#confirm-text").textContent = text;
    confirmDlg.showModal();
    return new Promise((resolve) => {
        const ok = document.querySelector("#confirm-ok");
        const done = (value) => {
            ok.removeEventListener("click", onOk);
            confirmDlg.removeEventListener("close", onClose);
            if (confirmDlg.open) confirmDlg.close();
            resolve(value);
        };
        const onOk = () => done(true);
        const onClose = () => done(false);
        ok.addEventListener("click", onOk);
        confirmDlg.addEventListener("close", onClose);
    });
}

document.querySelectorAll("dialog [data-close]").forEach((b) => b.addEventListener("click", () => b.closest("dialog").close()));
document.querySelectorAll("dialog").forEach((d) => d.addEventListener("click", (event) => {
    if (event.target === d) d.close();          // click on the backdrop
}));

let toastTimer;
function toast(message, kind = "ok") {
    toastBox.textContent = message;
    toastBox.className = `toast ${kind}`;
    toastBox.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastBox.hidden = true; }, 3500);
}

// ---------------------------------------------------------------- start

user = await mountSiteHeader(QUESTION ? "qa" : "blog");
await load();
announceCreated();

/**
 * Arriving from Write Post (?created=1): say whether the post is public or still being scanned (MSG34).
 * Arriving from the edit form (?edited=1): "Đã lưu thay đổi.", plus the scan note when new media is being scanned.
 */
function announceCreated() {
    const params = new URLSearchParams(location.search);
    const edited = params.has("edited");
    if (!(params.has("created") || edited) || !post) return;
    params.delete("created");
    params.delete("edited");
    history.replaceState(null, "", `${location.pathname}${params.size ? `?${params}` : ""}`);
    if (edited) {
        toast(post.status === "PROCESSING"
            ? `Đã lưu thay đổi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy ${QUESTION ? "câu hỏi" : "bài"}.`
            : "Đã lưu thay đổi.");
    } else if (post.status === "PROCESSING") {
        toast("Đã gửi. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy bài.");
    } else if (post.status === "PUBLISHED") {
        toast(QUESTION ? "Câu hỏi của bạn đã được đăng." : "Bài viết của bạn đã được đăng.");
    }
}
