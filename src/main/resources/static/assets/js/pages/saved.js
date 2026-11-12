// "Đã lưu" (UC Browse Favorites): saved places, posts, questions, guides and "Nhật ngữ" entries of the signed-in Member,
// newest saved first.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { esc, fullDate, ic, loginUrl, timeAgo } from "../lib/ui.js";

const soon = (feature, params = {}) => `/coming-soon?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    place: (id) => `/map?place=${id}`,
    POST: (id) => `/blog/${id}`,
    QA: (id) => `/qa/${id}`,
    GUIDE: (id) => `/guides/${id}`,
    PHRASE: (japanese) => `/phrases?${new URLSearchParams({ q: japanese })}`,
    savedPhrases: "/phrases?view=saved",           // the "Đã lưu" list of Nhật ngữ (flash cards over it)
    login: loginUrl                                // back to "Đã lưu" after signing in
};
const SECTIONS = [
    { key: "places", title: "Địa điểm", icon: "location_on", empty: "Chưa lưu địa điểm nào.", list: "place-grid", card: placeCard },
    { key: "posts", title: "Bài viết", icon: "article", empty: "Chưa lưu bài nào.", list: "post-list", card: blogCard },
    { key: "questions", title: "Câu hỏi", icon: "contact_support", empty: "Chưa lưu câu hỏi nào.", list: "post-list", card: blogCard },
    { key: "guides", title: "Cẩm nang", icon: "menu_book", empty: "Chưa lưu cẩm nang nào.", list: "post-list", card: guideCard },
    { key: "phrases", title: "Nhật ngữ", icon: "translate", empty: "Chưa lưu mẫu câu hay từ vựng nào.", list: "post-list", card: phraseCard,
        more: `<a class="link" href="${ROUTES.savedPhrases}">Luyện thẻ ghi nhớ${ic("chevron_right")}</a>` }
];
// "Bỏ lưu" on a card, by its data-type: the save API of that kind, the list the item leaves and the message.
const UNSAVE = {
    POST: { key: "posts", call: (id) => apiClient.saveBlog(id, false), message: "Đã bỏ lưu bài viết." },
    QA: { key: "questions", call: (id) => apiClient.saveQuestion(id, false), message: "Đã bỏ lưu câu hỏi." },
    GUIDE: { key: "guides", call: (id) => apiClient.saveGuide(id, false), message: "Đã bỏ lưu cẩm nang." },
    PHRASE: { key: "phrases", call: (id) => apiClient.savePhrase(id, false), message: "Đã bỏ lưu." }
};
const KIND = { PHRASE: "Mẫu câu", VOCAB: "Từ vựng" };

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
            <h3 id="h-${s.key}">${ic(s.icon)}${s.title} <span class="note" data-count="${s.key}"></span>
                ${s.more ? `<span class="saved-more" data-more="${s.key}">${s.more}</span>` : ""}</h3>
            <div class="${s.list}" data-list="${s.key}">
                ${data[s.key].map(s.card).join("")}
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

/** A saved guide: stage (and topic when it differs), title, summary, when it was saved. */
function guideCard(g) {
    const topic = g.topicName && g.topicName !== g.stageName ? `<span class="badge">${esc(g.topicName)}</span>` : "";
    return `
        <article class="card post" data-id="${g.id}" data-type="GUIDE">
            <div class="row meta">${g.stageName ? `<span class="badge br">${esc(g.stageName)}</span>` : ""}${topic}</div>
            <h3><a class="stretch" href="${ROUTES.GUIDE(g.id)}">${esc(g.title)}</a></h3>
            ${g.summary ? `<p class="note excerpt">${esc(g.summary)}</p>` : ""}
            <div class="row foot">
                ${g.savedAt ? `<span class="note">Đã lưu: <time datetime="${esc(g.savedAt)}" title="${esc(fullDate(g.savedAt))}">${timeAgo(g.savedAt)}</time></span>` : ""}
                <span class="grow"></span>
                <button class="btn sm" type="button" data-unsave>${ic("bookmark_remove")}Bỏ lưu</button>
            </div>
        </article>`;
}

/** A saved phrase or word: kind and place category, Japanese, romaji, meaning; opens it in "Nhật ngữ". */
function phraseCard(p) {
    return `
        <article class="card post saved-phrase" data-id="${p.id}" data-type="PHRASE">
            <div class="row meta"><span class="badge br">${esc(KIND[p.kind] || p.kind)}</span>
                ${p.categoryName ? `<span class="badge">${ic(p.icon || "place")}${esc(p.categoryName)}</span>` : ""}</div>
            <h3 lang="ja"><a class="stretch" href="${ROUTES.PHRASE(p.japanese)}">${esc(p.japanese)}</a></h3>
            ${p.romaji ? `<p class="note romaji">${esc(p.romaji)}</p>` : ""}
            <p class="excerpt">${esc(p.meaningVi)}</p>
            <div class="row foot">
                ${p.savedAt ? `<span class="note">Đã lưu: <time datetime="${esc(p.savedAt)}" title="${esc(fullDate(p.savedAt))}">${timeAgo(p.savedAt)}</time></span>` : ""}
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
    const unsave = UNSAVE[card.dataset.type];
    button.disabled = true;
    try {
        await unsave.call(id);
        data[unsave.key] = data[unsave.key].filter((item) => item.id !== id);
        card.remove();
        updateCounts(unsave.message);
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
        const more = content.querySelector(`[data-more="${s.key}"]`);
        if (more) {
            more.hidden = n === 0;
        }
    });
    const counted = total ? `${total} mục đã lưu.` : "Bạn chưa lưu mục nào.";
    summary.textContent = message ? `${message} ${counted}` : counted;
}
