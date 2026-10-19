// Blog Feed (UC-09 View Blog List, SRS 6.1): featured posts, search, topic filter, sorting, paging and the
// community statistics. Posts only: questions have their own section, Hỏi-Đáp (pages/qa.html).
// Filters live in the URL so refresh and the Back button keep them.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, tagChips, timeAgo } from "../lib/ui.js";

// Where links go. Map and Public Profile are still placeholders; change them when they exist.
const soon = (feature, params = {}) => `/coming-soon?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    detail: (id) => `/blog/${id}`,
    write: "/blog/new",
    qa: "/qa",
    place: (id) => soon("map", { place: id }),
    profile: (id) => soon("public-profile", { id }),
    login: "/login"
};

const MSG = {
    MSG32: "Chưa có bài viết nào.",
    MSG33: "Không có bài viết nào khớp với lựa chọn của bạn.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại."
};
const PAGE_SIZE = 10;

// Old links to the Q&A filter of the Blog now open Hỏi-Đáp
if (new URLSearchParams(location.search).get("type") === "QA") {
    location.replace(ROUTES.qa);
}
const SEARCH_DELAY = 400;

const $ = (selector) => document.querySelector(selector);
const els = {
    q: $("#q"), qCount: $("#q-count"), search: $("#search"), filters: $("#filters"), topicDd: $("#topic-dd"),
    topicList: $("#topic-list"), topicLabel: $("#topic-label"), chips: $("#chips"), featured: $("#featured"),
    summary: $("#summary"), list: $("#list"), pager: $("#pager"), side: $("#side")
};

let topics = [];            // active topics from the overview, for the filter and the chips
let lastRequest = 0;        // ignores answers to requests that were overtaken by a newer one
let state = readUrl();

// ---------------------------------------------------------------- URL state

function readUrl() {
    const p = new URLSearchParams(location.search);
    const oneOf = (value, allowed, fallback) => (allowed.includes(value) ? value : fallback);
    return {
        q: (p.get("q") || "").slice(0, 100),
        topicIds: (p.get("topics") || "").split(",").map(Number).filter((n) => Number.isInteger(n) && n > 0),
        sort: oneOf(p.get("sort"), ["NEWEST", "LIKES", "COMMENTS"], "NEWEST"),
        page: Math.max(1, Number.parseInt(p.get("page"), 10) || 1)   // 1-based in the URL, 0-based in the API
    };
}

function writeUrl(push) {
    const p = new URLSearchParams();
    if (state.q.trim()) p.set("q", state.q.trim());
    if (state.topicIds.length) p.set("topics", state.topicIds.join(","));
    if (state.sort !== "NEWEST") p.set("sort", state.sort);
    if (state.page > 1) p.set("page", state.page);
    const query = p.toString();
    const url = `${location.pathname}${query ? `?${query}` : ""}`;
    history[push ? "pushState" : "replaceState"](null, "", url);
}

const isFiltered = () => Boolean(state.q.trim()) || state.topicIds.length > 0;

/** Changes the filters, goes back to page 1 unless the page itself changes, and reloads the list. */
function update(changes, { push = true } = {}) {
    state = { ...state, page: 1, ...changes };
    writeUrl(push);
    syncControls();
    loadList();
}

// ---------------------------------------------------------------- controls

function syncControls() {
    if (document.activeElement !== els.q) {
        els.q.value = state.q;
    }
    els.qCount.textContent = state.q.length >= 80 ? `${state.q.length}/100` : "";
    els.filters.querySelectorAll(".seg").forEach((group) => {
        const current = state[group.dataset.group];
        group.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.value === current)));
    });
    els.topicList.querySelectorAll("input").forEach((box) => {
        box.checked = state.topicIds.includes(Number(box.value));
    });
    const n = state.topicIds.length;
    els.topicLabel.textContent = n ? `Chủ đề (${n})` : "Chủ đề";
    els.topicDd.querySelector("summary").classList.toggle("on", n > 0);
    renderChips();
    els.featured.hidden = isFiltered() || !els.featured.innerHTML;
}

function renderChips() {
    const chosen = topics.filter((t) => state.topicIds.includes(t.id));
    els.chips.hidden = !isFiltered();
    els.chips.innerHTML = chosen.map((t) => `
        <button class="badge br chip" type="button" data-remove-topic="${t.id}" aria-label="Bỏ chủ đề ${esc(t.name)}">
            ${esc(t.name)}${ic("close")}</button>`).join("")
        + `<button class="link" type="button" data-clear>Xóa bộ lọc</button>`;
}

function bindControls(user) {
    // Guests are sent to sign in before writing
    $("#write").href = user ? ROUTES.write : ROUTES.login;

    let timer;
    els.q.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(() => update({ q: els.q.value }, { push: false }), SEARCH_DELAY);
        els.qCount.textContent = els.q.value.length >= 80 ? `${els.q.value.length}/100` : "";
    });
    els.search.addEventListener("submit", (event) => {
        event.preventDefault();
        clearTimeout(timer);
        update({ q: els.q.value });
    });

    els.filters.querySelectorAll(".seg").forEach((group) => {
        group.addEventListener("click", (event) => {
            const button = event.target.closest("button[data-value]");
            if (button && state[group.dataset.group] !== button.dataset.value) {
                update({ [group.dataset.group]: button.dataset.value });
            }
        });
    });

    els.topicList.addEventListener("change", () => {
        const ids = [...els.topicList.querySelectorAll("input:checked")].map((box) => Number(box.value));
        update({ topicIds: ids });
    });
    // Close the topic dropdown on outside click or Escape
    document.addEventListener("click", (event) => {
        if (els.topicDd.open && !els.topicDd.contains(event.target)) els.topicDd.open = false;
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && els.topicDd.open) {
            els.topicDd.open = false;
            els.topicDd.querySelector("summary").focus();
        }
    });

    // Buttons rendered inside the page: remove a topic chip, clear filters, pick a topic, change page, retry
    document.addEventListener("click", (event) => {
        const target = event.target.closest("[data-remove-topic],[data-clear],[data-topic],[data-page],[data-retry]");
        if (!target) return;
        if (target.dataset.removeTopic) {
            update({ topicIds: state.topicIds.filter((id) => id !== Number(target.dataset.removeTopic)) });
        } else if ("clear" in target.dataset) {
            update({ q: "", topicIds: [] });
        } else if (target.dataset.topic) {
            update({ topicIds: [Number(target.dataset.topic)] });
            els.filters.scrollIntoView({ behavior: "smooth", block: "start" });
        } else if (target.dataset.page) {
            update({ page: Number(target.dataset.page) });
            els.summary.scrollIntoView({ behavior: "smooth", block: "start" });
        } else if (target.dataset.retry === "list") {
            loadList();
        } else if (target.dataset.retry === "all") {
            start();
        }
    });

    window.addEventListener("popstate", () => {
        state = readUrl();
        syncControls();
        loadList();
    });
}

// ---------------------------------------------------------------- list

async function loadList() {
    const id = ++lastRequest;
    els.list.setAttribute("aria-busy", "true");
    if (!els.list.querySelector(".post")) {
        els.list.innerHTML = skeleton(3);
    } else {
        els.list.classList.add("dim");
    }
    try {
        const page = await apiClient.blogs({
            q: state.q.trim(), topicIds: state.topicIds, sort: state.sort,
            page: state.page - 1, size: PAGE_SIZE
        });
        if (id !== lastRequest) return;
        renderList(page);
    } catch (error) {
        if (id !== lastRequest) return;
        // A 400 (e.g. keyword too long) has a message the user can act on; anything else is MSG17
        const message = error.status === 400 ? error.message : MSG.MSG17;
        els.summary.textContent = "";
        els.pager.hidden = true;
        els.list.innerHTML = stateBox("error", message, `<button class="btn sm" type="button" data-retry="list">${ic("refresh")}Thử lại</button>`);
    } finally {
        if (id === lastRequest) {
            els.list.removeAttribute("aria-busy");
            els.list.classList.remove("dim");
        }
    }
}

function renderList(page) {
    // The server moves an out-of-range page back to the last page; keep the URL in step
    if (page.page + 1 !== state.page) {
        state.page = page.page + 1;
        writeUrl(false);
    }
    if (!page.items.length) {
        els.summary.textContent = "";
        els.pager.hidden = true;
        els.list.innerHTML = page.filtered
            ? stateBox("empty", MSG.MSG33, `<button class="btn sm" type="button" data-clear>Xóa bộ lọc</button>`)
            : stateBox("empty", MSG.MSG32, `<a class="btn pri sm" href="${$("#write").getAttribute("href")}">${ic("edit_square")}Viết bài đầu tiên</a>`);
        return;
    }
    els.summary.textContent = `${page.totalItems.toLocaleString("vi-VN")} bài${page.filtered ? " phù hợp" : ""}`;
    els.list.innerHTML = page.items.map((post) => postCard(post)).join("");
    renderPager(page);
}

function postCard(post, { featured = false, rank = 0 } = {}) {
    const badges = [
        rank ? `<span class="badge lan">${ic("star")}#${rank}</span>` : "",
        post.pinned && !featured ? `<span class="badge">${ic("keep")}Đã ghim</span>` : "",
        post.status === "PROCESSING" ? `<span class="badge warn" title="Chỉ bạn thấy bài này cho đến khi quét xong">${ic("hourglass_top")}Đang quét ảnh/video</span>` : ""
    ].join("");
    const topicsHtml = post.topics.map((t) =>
        `<button class="badge topic" type="button" data-topic="${t.id}" title="Lọc theo chủ đề này">${esc(t.name)}</button>`).join("");
    const placesHtml = post.places.map((p) =>
        `<a class="place" href="${ROUTES.place(p.id)}">${ic("location_on")}${esc(p.name)}</a>`).join("");
    return `
        <article class="card post${featured ? " featured-post" : ""}${post.status === "PROCESSING" ? " processing" : ""}">
            <div class="row meta">${badges}<span class="grow"></span>
                <time class="note" datetime="${esc(post.createdAt)}" title="${esc(fullDate(post.createdAt))}">${timeAgo(post.createdAt)}</time></div>
            <h3><a class="stretch" href="${ROUTES.detail(post.id)}">${esc(post.title)}</a></h3>
            ${post.excerpt ? `<p class="excerpt">${esc(post.excerpt)}</p>` : ""}
            ${topicsHtml || placesHtml ? `<div class="row tags">${topicsHtml}${placesHtml}</div>` : ""}
            <div class="row foot">
                ${authorHtml(post.author)}
                <span class="grow"></span>
                <span class="stat" title="Lượt thích">${ic("favorite")}<span class="sr-only">Lượt thích:</span>${post.likeCount}</span>
                <span class="stat" title="Bình luận">${ic("chat_bubble")}<span class="sr-only">Bình luận:</span>${post.commentCount}</span>
            </div>
        </article>`;
}

function authorHtml(author) {
    return `<a class="author" href="${ROUTES.profile(author.id)}">${avatar(author)}<b>${esc(author.displayName)}</b></a>
        ${tagChips(author.tags)}`;
}

function renderPager(page) {
    if (page.totalPages <= 1) {
        els.pager.hidden = true;
        els.pager.innerHTML = "";
        return;
    }
    const current = page.page + 1;
    const last = page.totalPages;
    // 1 … 4 5 [6] 7 8 … 20
    const numbers = [...new Set([1, current - 2, current - 1, current, current + 1, current + 2, last])]
        .filter((n) => n >= 1 && n <= last).sort((a, b) => a - b);
    const parts = [];
    numbers.forEach((n, i) => {
        if (i > 0 && n - numbers[i - 1] > 1) parts.push(`<span class="gap" aria-hidden="true">…</span>`);
        parts.push(n === current
            ? `<span class="btn sm on" aria-current="page">${n}</span>`
            : `<button class="btn sm" type="button" data-page="${n}" aria-label="Trang ${n}">${n}</button>`);
    });
    els.pager.innerHTML = `
        <button class="btn sm" type="button" data-page="${current - 1}" ${current === 1 ? "disabled" : ""} aria-label="Trang trước">${ic("chevron_left")}</button>
        ${parts.join("")}
        <button class="btn sm" type="button" data-page="${current + 1}" ${current === last ? "disabled" : ""} aria-label="Trang sau">${ic("chevron_right")}</button>`;
    els.pager.hidden = false;
}

// ---------------------------------------------------------------- overview: featured, topics, statistics

function renderOverview(overview) {
    topics = overview.topics;
    els.topicList.innerHTML = `<legend class="sr-only">Chọn chủ đề</legend>` + topics.map((t) => `
        <label class="check"><input type="checkbox" value="${t.id}"><span>${esc(t.name)}</span></label>`).join("");
    // Topics in the URL that are no longer active are dropped
    const active = new Set(topics.map((t) => t.id));
    if (state.topicIds.some((id) => !active.has(id))) {
        state.topicIds = state.topicIds.filter((id) => active.has(id));
        writeUrl(false);
    }

    const f = overview.featured;
    els.featured.innerHTML = f.posts.length ? `
        <div class="row"><h3 id="featured-title">${ic("local_florist")} Bài nổi bật ${f.period === "WEEK" ? "tuần này" : "tháng này"}</h3>
            <span class="note">Tự động chọn theo lượt thích và bình luận</span></div>
        <div class="featured-grid">${f.posts.map((p, i) => postCard(p, { featured: true, rank: i + 1 })).join("")}</div>` : "";

    els.side.innerHTML = `
        <div class="card">
            <h3>${ic("sell")} Chủ đề phổ biến</h3>
            ${overview.popularTopics.length ? `<ul class="side-list">${overview.popularTopics.map((t) => `
                <li><button class="side-item" type="button" data-topic="${t.id}"><span>${esc(t.name)}</span>
                    <span class="note">${t.postCount} bài</span></button></li>`).join("")}</ul>`
                : `<p class="note">Chưa có dữ liệu.</p>`}
        </div>
        <div class="card">
            <h3>${ic("workspace_premium")} Senpai viết nhiều</h3>
            <p class="note">Trong ${overview.topWritersDays} ngày qua</p>
            ${overview.topWriters.length ? `<ul class="side-list">${overview.topWriters.map((w) => `
                <li class="writer"><a class="author" href="${ROUTES.profile(w.author.id)}">${avatar(w.author)}<b>${esc(w.author.displayName)}</b></a>
                    ${tagChips(w.author.tags)}<span class="grow"></span><span class="note">${w.postCount} bài</span></li>`).join("")}</ul>`
                : `<p class="note">Chưa có dữ liệu.</p>`}
        </div>
        <div class="card qa-promo">
            <h3>${ic("contact_support")} Có điều chưa rõ?</h3>
            <p class="note">Đặt câu hỏi ở Hỏi-Đáp, các senpai đã trải qua sẽ trả lời bạn.</p>
            <a class="btn sm" href="${ROUTES.qa}">${ic("arrow_forward")}Sang Hỏi-Đáp</a>
        </div>`;
}

// ---------------------------------------------------------------- helpers

const stateBox = (kind, message, action = "") =>
    `<div class="state ${kind}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(kind === "error" ? "cloud_off" : "forum", "big")}<p>${esc(message)}</p>${action}</div>`;

const skeleton = (n) => Array.from({ length: n }, () =>
    `<div class="card post skel" aria-hidden="true"><span></span><span></span><span></span></div>`).join("")
    + `<span class="sr-only">Đang tải…</span>`;

// ---------------------------------------------------------------- start

async function start() {
    els.list.innerHTML = skeleton(3);
    try {
        renderOverview(await apiClient.blogOverview());
    } catch {
        // The list is the main content: show MSG17 for the whole page and let the user retry everything
        els.featured.hidden = true;
        els.side.innerHTML = "";
        els.summary.textContent = "";
        els.list.innerHTML = stateBox("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry="all">${ic("refresh")}Thử lại</button>`);
        return;
    }
    syncControls();
    await loadList();
}

const user = await mountSiteHeader("blog");
bindControls(user);
syncControls();
await start();
