// Public profile (UC-U07): name, tags, rank, numbers and the member's published reviews, posts and questions, with
// "Nhắn tin" (guests sign in first and land on the conversation) or "Chỉnh sửa hồ sơ" on one's own page.
import { apiClient, currentUser } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, loginUrl, stars, tagChips, timeAgo } from "../lib/ui.js";

const TABS = [
    { key: "reviews", label: "Review", empty: "Chưa có review nào.", load: (id, page) => apiClient.userReviews(id, page) },
    { key: "posts", label: "Bài viết", empty: "Chưa có bài viết nào.", load: (id, page) => apiClient.userPosts(id, page) },
    { key: "questions", label: "Câu hỏi", empty: "Chưa có câu hỏi nào.", load: (id, page) => apiClient.userQuestions(id, page) }
];

const main = document.querySelector("#user");
const userId = Number(location.pathname.split("/").pop());
let current = TABS.find((t) => t.key === new URLSearchParams(location.search).get("tab")) || TABS[0];
let loadTicket = 0;

await mountSiteHeader(null);
try {
    const [profile, me] = await Promise.all([apiClient.publicProfile(userId), currentUser().catch(() => null)]);
    document.title = `${profile.displayName} – Senpai ơi`;
    render(profile, me);
} catch (error) {
    main.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
}

function actionButton(p, me) {
    if (p.self) {
        return `<a class="btn" href="/profile">${ic("edit")}Chỉnh sửa hồ sơ</a>`;
    }
    if (!p.canMessage) {
        return "";
    }
    const chat = `/messages?to=${p.id}`;
    return `<a class="btn pri" href="${esc(me ? chat : loginUrl(chat))}">${ic("chat")}Nhắn tin</a>`;
}

function render(p, me) {
    const start = new Date(p.joinedAt);
    const joined = `${String(start.getMonth() + 1).padStart(2, "0")}/${start.getFullYear()}`;
    const stat = (n, label) => `<div><dt>${label}</dt><dd>${n}</dd></div>`;
    main.innerHTML = `
        <section class="card user-head">
            <div class="user-top">
                ${avatar(p)}
                <div class="user-who">
                    <h2>${esc(p.displayName)}</h2>
                    <div class="user-badges">${tagChips(p.tags)}
                        ${p.rankName ? `<span class="badge rank" title="Cấp bậc">${ic("workspace_premium")}${esc(p.rankName)}</span>` : ""}
                        ${p.roleLabel ? `<span class="badge br">${esc(p.roleLabel)}</span>` : ""}</div>
                    <span class="note">Tham gia ${esc(joined)}</span>
                </div>
                ${actionButton(p, me)}
            </div>
            <dl class="user-stats">
                ${stat(p.reviewCount, "Review")}${stat(p.postCount, "Bài viết")}${stat(p.questionCount, "Câu hỏi")}
                ${stat(p.bestAnswerCount, "Câu trả lời hay nhất")}${stat(p.likesReceived, "Lượt thích")}
            </dl>
        </section>
        <div class="user-tabs" role="tablist" aria-label="Nội dung đã đăng">
            ${TABS.map((t) => `<button type="button" role="tab" data-tab="${t.key}" aria-selected="${t === current}">${t.label}</button>`).join("")}
        </div>
        <div class="user-list" id="list" role="tabpanel" aria-live="polite"></div>`;
    main.querySelector(".user-tabs").addEventListener("click", (event) => {
        const button = event.target.closest("[data-tab]");
        if (!button || button.dataset.tab === current.key) return;
        current = TABS.find((t) => t.key === button.dataset.tab);
        main.querySelectorAll("[data-tab]").forEach((b) => b.setAttribute("aria-selected", String(b === button)));
        const url = new URL(location.href);
        if (current === TABS[0]) url.searchParams.delete("tab");
        else url.searchParams.set("tab", current.key);
        history.replaceState(null, "", url);
        loadList(0);
    });
    loadList(0);
}

/** Page 0 replaces the list, later pages are added under it; an answer for a tab left meanwhile is dropped. */
async function loadList(page) {
    const ticket = ++loadTicket;
    const tab = current;
    const list = main.querySelector("#list");
    const more = list.querySelector(".user-more");
    if (more) more.disabled = true;
    if (page === 0) list.innerHTML = `<p class="note">Đang tải…</p>`;
    let data;
    try {
        data = await tab.load(userId, page);
    } catch (error) {
        if (ticket !== loadTicket) return;
        if (page === 0) list.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
        else if (more) more.disabled = false;
        return;
    }
    if (ticket !== loadTicket) return;
    if (page === 0) list.innerHTML = data.items.length ? "" : `<p class="note">${tab.empty}</p>`;
    more?.remove();
    list.insertAdjacentHTML("beforeend", data.items.map(tab.key === "reviews" ? reviewCard : blogCard(tab.key)).join(""));
    if (data.hasMore) {
        list.insertAdjacentHTML("beforeend", `<button class="btn user-more" type="button">Xem thêm</button>`);
        list.querySelector(".user-more").addEventListener("click", () => loadList(page + 1));
    }
}

const when = (iso) => `<time class="note" datetime="${esc(iso)}" title="${esc(fullDate(iso))}">${timeAgo(iso)}</time>`;

function reviewCard(r) {
    const href = r.eventId ? `/events/${r.eventId}` : `/map?place=${r.placeId}`;
    return `<article class="card user-review">
        <div class="row">${ic(r.eventId ? "celebration" : r.icon || "location_on")}<h3><a class="link" href="${esc(href)}">${esc(r.title)}</a></h3>
            <span class="grow"></span>${when(r.createdAt)}</div>
        <div class="row"><span class="stars" role="img" aria-label="${r.rating} trên 5 sao">${stars(r.rating)}</span>
            ${r.likeCount ? `<span class="stat" title="Lượt thích">${ic("thumb_up")}${r.likeCount}</span>` : ""}</div>
        <p>${esc(r.excerpt)}</p>
    </article>`;
}

function blogCard(kind) {
    const question = kind === "questions";
    return (b) => `<article class="card post">
        <div class="row meta">${b.topicName ? `<span class="badge topic">${esc(b.topicName)}</span>` : ""}
            ${question && b.solved ? `<span class="badge ok">${ic("check")}Đã giải quyết</span>` : ""}
            <span class="grow"></span>${when(b.createdAt)}</div>
        <h3><a class="stretch" href="${question ? "/qa" : "/blog"}/${b.id}">${esc(b.title)}</a></h3>
        ${b.excerpt ? `<p class="note">${esc(b.excerpt)}</p>` : ""}
        <div class="row foot">
            <span class="stat" title="Lượt thích">${ic("favorite")}${b.likeCount}</span>
            <span class="stat" title="${question ? "Trả lời" : "Bình luận"}">${ic("chat_bubble")}${b.commentCount}</span>
        </div>
    </article>`;
}
