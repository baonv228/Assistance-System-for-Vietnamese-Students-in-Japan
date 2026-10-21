// Hỏi-Đáp list: search, status and topic filters, sorting, paging and the side statistics. A separate section
// from the Blog (questions are stored with the blog posts but listed, asked and opened on their own pages).
// Filters live in the URL so refresh and the Back button keep them.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, loginUrl, placeChip, tagChips, timeAgo } from "../lib/ui.js";

const soon = (feature, params = {}) => `/coming-soon?${new URLSearchParams({ f: feature, ...params })}`;
const ROUTES = {
    detail: (id) => `/qa/${id}`,
    ask: "/qa/ask",
    profile: (id) => soon("public-profile", { id }),
    login: loginUrl
};

const MSG = {
    EMPTY: "Chưa có câu hỏi nào.",
    NO_MATCH: "Không có câu hỏi nào khớp với lựa chọn của bạn.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại."
};
const STATUSES = ["ALL", "UNANSWERED", "UNSOLVED", "SOLVED"];
const SORTS = ["NEWEST", "LIKES", "ANSWERS"];
const PAGE_SIZE = 10;
const SEARCH_DELAY = 400;

const $ = (selector) => document.querySelector(selector);
const els = {
    q: $("#q"), qCount: $("#q-count"), search: $("#search"), filters: $("#filters"), topicDd: $("#topic-dd"),
    topicList: $("#topic-list"), topicLabel: $("#topic-label"), chips: $("#chips"), summary: $("#summary"),
    list: $("#list"), pager: $("#pager"), side: $("#side"), ask: $("#ask")
};

let topics = [];
let lastRequest = 0;        // ignores answers to requests that were overtaken by a newer one
let state = readUrl();

// ---------------------------------------------------------------- URL state

function readUrl() {
    const p = new URLSearchParams(location.search);
    const oneOf = (value, allowed, fallback) => (allowed.includes(value) ? value : fallback);
    return {
        q: (p.get("q") || "").slice(0, 100),
        topicIds: (p.get("topics") || "").split(",").map(Number).filter((n) => Number.isInteger(n) && n > 0),
        status: oneOf(p.get("status"), STATUSES, "ALL"),
        sort: oneOf(p.get("sort"), SORTS, "NEWEST"),
        page: Math.max(1, Number.parseInt(p.get("page"), 10) || 1)   // 1-based in the URL, 0-based in the API
    };
}

function writeUrl(push) {
    const p = new URLSearchParams();
    if (state.q.trim()) p.set("q", state.q.trim());
    if (state.topicIds.length) p.set("topics", state.topicIds.join(","));
    if (state.status !== "ALL") p.set("status", state.status);
    if (state.sort !== "NEWEST") p.set("sort", state.sort);
    if (state.page > 1) p.set("page", state.page);
    const query = p.toString();
    history[push ? "pushState" : "replaceState"](null, "", `${location.pathname}${query ? `?${query}` : ""}`);
}

const isFiltered = () => Boolean(state.q.trim()) || state.topicIds.length > 0 || state.status !== "ALL";

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
}

function renderChips() {
    const chosen = topics.filter((t) => state.topicIds.includes(t.id));
    els.chips.hidden = !isFiltered();
    els.chips.innerHTML = chosen.map((t) => `
        <button class="badge br chip" type="button" data-remove-topic="${t.id}" aria-label="Bỏ chủ đề ${esc(t.name)}">
            ${esc(t.name)}${ic("close")}</button>`).join("")
        + `<button class="link" type="button" data-clear>Xóa bộ lọc</button>`;
}

function bindControls() {
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
        update({ topicIds: [...els.topicList.querySelectorAll("input:checked")].map((box) => Number(box.value)) });
    });
    document.addEventListener("click", (event) => {
        if (els.topicDd.open && !els.topicDd.contains(event.target)) els.topicDd.open = false;
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && els.topicDd.open) {
            els.topicDd.open = false;
            els.topicDd.querySelector("summary").focus();
        }
    });

    // Buttons rendered inside the page
    document.addEventListener("click", (event) => {
        const target = event.target.closest("[data-remove-topic],[data-clear],[data-topic],[data-status],[data-page],[data-retry]");
        if (!target) return;
        if (target.dataset.removeTopic) {
            update({ topicIds: state.topicIds.filter((id) => id !== Number(target.dataset.removeTopic)) });
        } else if ("clear" in target.dataset) {
            update({ q: "", topicIds: [], status: "ALL" });
        } else if (target.dataset.topic) {
            update({ topicIds: [Number(target.dataset.topic)] });
            els.filters.scrollIntoView({ behavior: "smooth", block: "start" });
        } else if (target.dataset.status) {
            update({ status: target.dataset.status, q: "", topicIds: [] });
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
    if (!els.list.querySelector(".question")) {
        els.list.innerHTML = skeleton(3);
    } else {
        els.list.classList.add("dim");
    }
    try {
        const page = await apiClient.questions({
            q: state.q.trim(), topicIds: state.topicIds, status: state.status, sort: state.sort,
            page: state.page - 1, size: PAGE_SIZE
        });
        if (id !== lastRequest) return;
        renderList(page);
    } catch (error) {
        if (id !== lastRequest) return;
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
    if (page.page + 1 !== state.page) {
        state.page = page.page + 1;
        writeUrl(false);
    }
    if (!page.items.length) {
        els.summary.textContent = "";
        els.pager.hidden = true;
        const ask = `<a class="btn pri sm" href="${els.ask.getAttribute("href")}">${ic("contact_support")}Đặt câu hỏi</a>`;
        els.list.innerHTML = page.filtered
            ? stateBox("empty", state.q.trim() ? `${MSG.NO_MATCH} Chưa thấy lời giải? Hãy hỏi các senpai.` : MSG.NO_MATCH,
                `<div class="row">${state.q.trim() ? ask : ""}<button class="btn sm" type="button" data-clear>Xóa bộ lọc</button></div>`)
            : stateBox("empty", MSG.EMPTY, ask);
        return;
    }
    els.summary.textContent = `${page.totalItems.toLocaleString("vi-VN")} câu hỏi${page.filtered ? " phù hợp" : ""}`;
    els.list.innerHTML = page.items.map(questionCard).join("");
    renderPager(page);
}

function questionCard(q) {
    const answers = q.answerCount;
    const countClass = q.solved ? "solved" : answers ? "answered" : "";
    const countLabel = q.solved ? `Đã giải quyết, ${answers} câu trả lời` : `${answers} câu trả lời`;
    const badges = [
        q.pinned ? `<span class="badge">${ic("keep")}Đã ghim</span>` : "",
        q.solved ? `<span class="badge ok">${ic("check_circle")}Đã giải quyết</span>`
            : answers === 0 ? `<span class="badge lan">${ic("hourglass_empty")}Chưa ai trả lời</span>` : "",
        q.status === "PROCESSING" ? `<span class="badge warn" title="Chỉ bạn thấy câu hỏi này cho đến khi quét xong">${ic("hourglass_top")}Đang quét ảnh/video</span>` : ""
    ].join("");
    const topicsHtml = q.topics.map((t) =>
        `<button class="badge topic" type="button" data-topic="${t.id}" title="Lọc theo chủ đề này">${esc(t.name)}</button>`).join("");
    const placesHtml = q.places.map((p) =>
        placeChip(p)).join("");
    return `
        <article class="card post question${q.status === "PROCESSING" ? " processing" : ""}">
            <div class="qa-count ${countClass}" title="${esc(countLabel)}">
                ${q.solved ? ic("check") : ""}<b>${answers}</b><span>trả lời</span>
                <span class="sr-only">${esc(countLabel)}</span>
            </div>
            <div class="qa-body">
                <div class="row meta">${badges}<span class="grow"></span>
                    <time class="note" datetime="${esc(q.createdAt)}" title="${esc(fullDate(q.createdAt))}">${timeAgo(q.createdAt)}</time></div>
                <h3><a class="stretch" href="${ROUTES.detail(q.id)}">${esc(q.title)}</a></h3>
                ${q.excerpt ? `<p class="excerpt">${esc(q.excerpt)}</p>` : ""}
                ${topicsHtml || placesHtml ? `<div class="row tags">${topicsHtml}${placesHtml}</div>` : ""}
                <div class="row foot">
                    <a class="author" href="${ROUTES.profile(q.author.id)}">${avatar(q.author)}<b>${esc(q.author.displayName)}</b></a>
                    ${tagChips(q.author.tags)}
                    <span class="grow"></span>
                    <span class="stat" title="Lượt thích">${ic("favorite")}<span class="sr-only">Lượt thích:</span>${q.likeCount}</span>
                </div>
            </div>
        </article>`;
}

function renderPager(page) {
    if (page.totalPages <= 1) {
        els.pager.hidden = true;
        els.pager.innerHTML = "";
        return;
    }
    const current = page.page + 1;
    const last = page.totalPages;
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

// ---------------------------------------------------------------- overview: topics and statistics

function renderOverview(overview) {
    topics = overview.topics;
    els.topicList.innerHTML = `<legend class="sr-only">Chọn chủ đề</legend>` + topics.map((t) => `
        <label class="check"><input type="checkbox" value="${t.id}"><span>${esc(t.name)}</span></label>`).join("");
    const active = new Set(topics.map((t) => t.id));
    if (state.topicIds.some((id) => !active.has(id))) {
        state.topicIds = state.topicIds.filter((id) => active.has(id));
        writeUrl(false);
    }

    const s = overview.stats;
    const number = (n) => n.toLocaleString("vi-VN");
    els.side.innerHTML = `
        <div class="card">
            <h3>${ic("forum")} Cộng đồng hỏi đáp</h3>
            <div class="qa-stats">
                <div class="qa-stat"><b>${number(s.total)}</b><span>câu hỏi</span></div>
                <button class="qa-stat" type="button" data-status="UNANSWERED"><b>${number(s.unanswered)}</b><span>chưa ai trả lời</span></button>
                <button class="qa-stat ok" type="button" data-status="SOLVED"><b>${number(s.solved)}</b><span>đã giải quyết</span></button>
            </div>
            ${s.unanswered ? `<p class="note">Bạn biết câu trả lời? Giúp người hỏi trong mục “Chưa ai trả lời”.</p>` : ""}
        </div>
        <div class="card">
            <h3>${ic("sell")} Chủ đề hay được hỏi</h3>
            ${overview.popularTopics.length ? `<ul class="side-list">${overview.popularTopics.map((t) => `
                <li><button class="side-item" type="button" data-topic="${t.id}"><span>${esc(t.name)}</span>
                    <span class="note">${t.postCount} câu hỏi</span></button></li>`).join("")}</ul>`
                : `<p class="note">Chưa có dữ liệu.</p>`}
        </div>
        <div class="card">
            <h3>${ic("workspace_premium")} Senpai giải đáp nhiều</h3>
            <p class="note">Trong ${overview.topAnswerersDays} ngày qua</p>
            ${overview.topAnswerers.length ? `<ul class="side-list">${overview.topAnswerers.map((a) => `
                <li class="writer"><a class="author" href="${ROUTES.profile(a.author.id)}">${avatar(a.author)}<b>${esc(a.author.displayName)}</b></a>
                    ${tagChips(a.author.tags)}<span class="grow"></span>
                    <span class="note">${a.answerCount} trả lời${a.bestCount ? ` · ${a.bestCount} hay nhất` : ""}</span></li>`).join("")}</ul>`
                : `<p class="note">Chưa có ai trả lời trong thời gian này.</p>`}
        </div>
        <div class="card qa-tips">
            <h3>${ic("tips_and_updates")} Hỏi sao cho dễ được trả lời</h3>
            <ul>
                <li>Tìm trước xem đã có ai hỏi điều tương tự chưa.</li>
                <li>Ghi rõ bạn đang ở đâu, đã thử những gì.</li>
                <li>Gắn địa điểm liên quan nếu có (văn phòng quận, ngân hàng…).</li>
                <li>Khi có câu trả lời đúng, chọn “Câu trả lời hay nhất” để người sau biết.</li>
            </ul>
        </div>`;
}

// ---------------------------------------------------------------- helpers

const stateBox = (kind, message, action = "") =>
    `<div class="state ${kind}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(kind === "error" ? "cloud_off" : "contact_support", "big")}<p>${esc(message)}</p>${action}</div>`;

const skeleton = (n) => Array.from({ length: n }, () =>
    `<div class="card post skel" aria-hidden="true"><span></span><span></span><span></span></div>`).join("")
    + `<span class="sr-only">Đang tải…</span>`;

// ---------------------------------------------------------------- start

async function start() {
    els.list.innerHTML = skeleton(3);
    try {
        renderOverview(await apiClient.questionOverview());
    } catch {
        els.side.innerHTML = "";
        els.summary.textContent = "";
        els.list.innerHTML = stateBox("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry="all">${ic("refresh")}Thử lại</button>`);
        return;
    }
    syncControls();
    await loadList();
}

const user = await mountSiteHeader("qa");
els.ask.href = user ? ROUTES.ask : ROUTES.login(ROUTES.ask);     // Guests sign in before asking, then ask
bindControls();
syncControls();
await start();
