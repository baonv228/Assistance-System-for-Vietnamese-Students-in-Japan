// "Đã lưu" (UC Browse Favorites): saved places, posts and questions of the signed-in Member, newest saved first.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { esc, fullDate, ic, loginUrl, timeAgo } from "../lib/ui.js";

const soon = (feature, params = {}) => `/coming-soon?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    place: (id) => `/map?place=${id}`,
    POST: (id) => `/blog/${id}`,
    QA: (id) => `/qa/${id}`,
    login: loginUrl                                // back to "Đã lưu" after signing in
};
const SECTIONS = [
    { key: "places", title: "Địa điểm", icon: "location_on", empty: "Chưa lưu địa điểm nào." },
    { key: "posts", title: "Bài viết", icon: "article", empty: "Chưa lưu bài nào." },
    { key: "questions", title: "Câu hỏi", icon: "contact_support", empty: "Chưa lưu câu hỏi nào." }
];

const summary = document.querySelector("#summary");
const content = document.querySelector("#content");
let data = null;

await mountSiteHeader("me");
try {
    data = await apiClient.saved();
    render();
} catch (error) {
    if (error.status === 401) {
        location.replace(ROUTES.login());
    } else {
        summary.textContent = "";
        content.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
    }
}

function render() {
    content.innerHTML = SECTIONS.map((s) => `
        <section class="saved-section" id="sec-${s.key}" aria-labelledby="h-${s.key}">
            <h3 id="h-${s.key}">${ic(s.icon)}${s.title} <span class="note" data-count="${s.key}"></span></h3>
            <div class="${s.key === "places" ? "place-grid" : "post-list"}" data-list="${s.key}">
                ${data[s.key].map(s.key === "places" ? placeCard : blogCard).join("")}
            </div>
            <p class="note" data-empty="${s.key}">${s.empty}</p>
        </section>`).join("");
    content.addEventListener("click", onClick);
    updateCounts();
}

function placeCard(p) {
    return `
        <a class="card place-card" href="${ROUTES.place(p.id)}">
            <span class="place-ic">${ic(p.icon || "place")}</span>
            <span class="place-txt"><b>${esc(p.name)}</b>
                <span class="note">${esc(p.categoryName)}${p.address ? ` · ${esc(p.address)}` : ""}</span></span>
            ${p.closed ? `<span class="badge warn">Đã đóng cửa</span>` : ""}
        </a>`;
}

function blogCard(b) {
    const solved = b.type === "QA" && b.solved ? `<span class="badge ok">${ic("check")}Đã giải quyết</span>` : "";
    const topic = b.topics[0] ? `<span class="badge topic">${esc(b.topics[0].name)}</span>` : "";
    return `
        <article class="card post" data-id="${b.id}" data-type="${b.type}">
            <div class="row meta">${topic}${solved}<span class="grow"></span>
                <time class="note" datetime="${esc(b.createdAt)}" title="${esc(fullDate(b.createdAt))}">${timeAgo(b.createdAt)}</time></div>
            <h3><a class="stretch" href="${ROUTES[b.type](b.id)}">${esc(b.title)}</a></h3>
            <div class="row foot">
                <span class="note">${esc(b.author.displayName)}</span>
                <span class="stat" title="Lượt thích">${ic("favorite")}<span class="sr-only">Lượt thích:</span>${b.likeCount}</span>
                <span class="stat" title="${b.type === "QA" ? "Trả lời" : "Bình luận"}">${ic("chat_bubble")}<span class="sr-only">${b.type === "QA" ? "Trả lời" : "Bình luận"}:</span>${b.commentCount}</span>
                <span class="grow"></span>
                <button class="btn sm" type="button" data-unsave>${ic("bookmark_remove")}Bỏ lưu</button>
            </div>
        </article>`;
}

async function onClick(event) {
    const button = event.target.closest("[data-unsave]");
    if (!button) {
        return;
    }
    const card = button.closest("[data-id]");
    const id = Number(card.dataset.id);
    const type = card.dataset.type;
    button.disabled = true;
    try {
        await (type === "QA" ? apiClient.saveQuestion(id, false) : apiClient.saveBlog(id, false));
        const key = type === "QA" ? "questions" : "posts";
        data[key] = data[key].filter((b) => b.id !== id);
        card.remove();
        updateCounts(type === "QA" ? "Đã bỏ lưu câu hỏi." : "Đã bỏ lưu bài viết.");
    } catch (error) {
        button.disabled = false;
        summary.textContent = error.message;
    }
}

function updateCounts(message) {
    const total = SECTIONS.reduce((sum, s) => sum + data[s.key].length, 0);
    SECTIONS.forEach((s) => {
        const n = data[s.key].length;
        content.querySelector(`[data-count="${s.key}"]`).textContent = n ? `(${n})` : "";
        content.querySelector(`[data-empty="${s.key}"]`).hidden = n > 0;
        content.querySelector(`[data-list="${s.key}"]`).hidden = n === 0;
    });
    const counted = total ? `${total} mục đã lưu.` : "Bạn chưa lưu mục nào.";
    summary.textContent = message ? `${message} ${counted}` : counted;
}
