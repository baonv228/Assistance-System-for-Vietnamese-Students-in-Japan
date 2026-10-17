// Post Detail (UC-10 View Blog Details): the post, its author, media and comment tree, with the actions the
// viewer may take. Guests can read; every action that writes data sends them to sign in first.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, tagChips, timeAgo } from "../lib/ui.js";

const soon = (feature, params = {}) => `/pages/coming-soon.html?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    list: "/pages/blog.html",
    topic: (id) => `/pages/blog.html?topics=${id}`,
    place: (id) => soon("map", { place: id }),
    profile: (id) => soon("public-profile", { id }),
    edit: (id) => soon("blog-edit", { id }),
    login: "/pages/login.html"
};

const MSG = {
    MSG19: "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại.",
    MSG14: "Đã có lỗi xảy ra. Vui lòng thử lại sau.",
    MSG40: "Hãy chọn lý do báo cáo.",
    MSG16_POST: "Bạn có chắc muốn xóa bài viết này? Bình luận của bài cũng bị xóa, và điểm bài đã nhận sẽ bị thu lại.",
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
const blogId = Number.parseInt(new URLSearchParams(location.search).get("id"), 10);

let user = null;            // signed-in user or null
let post = null;            // last BlogDetail from the API
let replyTo = null;         // comment id whose reply box is open
let reportTarget = null;    // { kind: "blog" | "comment", id }

// ---------------------------------------------------------------- loading

async function load() {
    if (!Number.isInteger(blogId) || blogId <= 0) {
        renderState("error", MSG.MSG19, `<a class="btn sm" href="${ROUTES.list}">Về Blog</a>`);
        return;
    }
    try {
        render(await apiClient.blog(blogId));
    } catch (error) {
        if (error.status === 404) {
            renderState("empty", MSG.MSG19, `<a class="btn sm" href="${ROUTES.list}">Về Blog</a>`);
        } else {
            renderState("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button>`);
        }
    }
}

function renderState(kind, message, action) {
    document.title = "Bài viết – Senpai ơi";
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
            <div class="row actions-bar">${postActions(data)}</div>
        </article>
        <section class="card comments" aria-labelledby="comments-title">
            <h2 id="comments-title">${question ? "Câu trả lời" : "Bình luận"} <span class="note">(${data.commentCount})</span></h2>
            ${commentForm(null)}
            ${data.comments.length
                ? `<ol class="comment-list">${data.comments.map((c) => commentHtml(c, 0)).join("")}</ol>`
                : `<p class="note empty-comments">${question ? "Chưa có câu trả lời. Bạn biết câu trả lời thì chia sẻ nhé." : "Chưa có bình luận nào."}</p>`}
        </section>`;
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
        return parentId ? "" : `<div class="login-box"><span>Đăng nhập để ${question ? "trả lời" : "bình luận"} và thích bài viết.</span>
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
            const state = await busy(button, () => guarded(() => apiClient.likeBlog(blogId, on)));
            if (state) {
                post.viewer.liked = state.active;
                post.likeCount = state.count;
                button.outerHTML = actionButton({ act: "like-post", pressed: state.active, icon: "favorite", label: `Thích · ${state.count}` });
            }
            break;
        }
        case "save-post": {
            const on = button.getAttribute("aria-pressed") !== "true";
            const state = await busy(button, () => guarded(() => apiClient.saveBlog(blogId, on)));
            if (state) {
                post.viewer.saved = state.saved;
                button.outerHTML = actionButton({ act: "save-post", pressed: state.saved,
                    icon: state.saved ? "bookmark_added" : "bookmark", label: state.saved ? "Đã lưu" : "Lưu" });
                toast(state.saved ? "Đã lưu bài viết. Xem lại ở Tôi → Đã lưu." : "Đã bỏ lưu bài viết.");
            }
            break;
        }
        case "focus-comment":
            content.querySelector("#comment-new, .login-box a")?.focus();
            content.querySelector(".comments").scrollIntoView({ behavior: "smooth", block: "start" });
            break;
        case "delete-post":
            if (await confirmDialog(MSG.MSG16_POST)) {
                const ok = await guarded(() => apiClient.deleteBlog(blogId).then(() => true));
                if (ok) {
                    toast("Đã xóa bài viết.");
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
            const data = await busy(button, () => guarded(() => apiClient.chooseBestComment(blogId, commentId)));
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
        error.textContent = "Hãy viết nội dung bình luận.";
        error.hidden = false;
        form.content.focus();
        return;
    }
    const submit = form.querySelector('[type="submit"]');
    const parentId = form.dataset.parent ? Number(form.dataset.parent) : null;
    try {
        const data = await busy(submit, () => apiClient.commentBlog(blogId, { content: text, parentId }));
        const before = new Set(allIds(post.comments));
        replyTo = null;
        render(data);
        toast("Đã đăng bình luận.");
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
    document.querySelector("#report-title").textContent = target.kind === "blog" ? "Báo cáo bài viết" : "Báo cáo bình luận";
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
            ? apiClient.reportBlog(reportTarget.id, body)
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

user = await mountSiteHeader("blog");
await load();
