// Hỏi-Đáp, two tabs:
//  - "Cộng đồng hỏi": questions of the community with search, status and topic filters, sorting, paging and the side
//    statistics; a search also shows the FAQ entries that look like it above the questions (GET /api/faqs/related).
//  - "Câu hỏi thường gặp": FAQ entries written by Moderators (GET /api/faqs, /api/faqs/topics, /api/faqs/{id}), by
//    topic, opened in place; a search that holds no entry word for word shows the related ones instead.
// What is shown lives in the URL (lib/qa-state.js), so refresh and the Back button keep it. Questions are stored with
// the blog posts but listed, asked and opened on their own pages.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { faqItem } from "../lib/faq-ui.js";
import { communityHref, faqHref, qaQuery, Q_MAX, readQaState } from "../lib/qa-state.js";
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
    FAQ_EMPTY: "Chưa có câu hỏi thường gặp nào.",
    FAQ_NO_MATCH: "Không có câu hỏi thường gặp nào khớp với lựa chọn của bạn.",
    FAQ_GONE: "Câu hỏi thường gặp này không còn hoặc đã được ẩn.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại."
};
/** Texts that change with the tab. */
const TAB_TEXT = {
    community: {
        title: "Hỏi-Đáp – Senpai ơi",
        lead: "Hỏi điều bạn chưa rõ, các senpai đã trải qua sẽ trả lời. Tìm trước, biết đâu đã có lời giải.",
        placeholder: "Tìm câu hỏi theo nội dung hoặc địa điểm",
        label: "Tìm câu hỏi"
    },
    faq: {
        title: "Câu hỏi thường gặp – Hỏi-Đáp – Senpai ơi",
        lead: "Những thắc mắc phổ biến được Moderator tổng hợp và kiểm tra, nhiều câu lấy từ câu trả lời hay nhất của cộng đồng.",
        placeholder: "Tìm câu hỏi thường gặp, ví dụ: mua SIM, đổ rác",
        label: "Tìm câu hỏi thường gặp"
    }
};
const PAGE_SIZE = 10;
const SEARCH_DELAY = 400;
const FAQ_HITS = 3;          // FAQ entries shown above the community questions of a search
const FAQ_RELATED = 5;       // FAQ entries offered when a search holds no entry word for word
const FAQ_HITS_MIN = 2;      // characters a search needs before FAQ entries are looked for

const $ = (selector) => document.querySelector(selector);
const els = {
    q: $("#q"), qLabel: $("#q-label"), qCount: $("#q-count"), search: $("#search"), filters: $("#filters"),
    topicDd: $("#topic-dd"), topicList: $("#topic-list"), topicLabel: $("#topic-label"), chips: $("#chips"),
    summary: $("#summary"), list: $("#list"), pager: $("#pager"), side: $("#side"), ask: $("#ask"),
    tabs: $("#tabs"), lead: $("#lead"), faqTopics: $("#faq-topics"), faqHits: $("#faq-hits")
};

let topics = [];
let overview = null;         // GET /api/questions/overview: topics and side statistics of the community tab
let sideTab = null;          // which tab the side column was drawn for
let lastRequest = 0;         // ignores answers to requests that were overtaken by a newer one
let state = readQaState(location.search);

// ---------------------------------------------------------------- URL state

function writeUrl(push) {
    const query = qaQuery(state);
    history[push ? "pushState" : "replaceState"](null, "", `${location.pathname}${query ? `?${query}` : ""}`);
}

const isFaq = () => state.tab === "faq";
const isFiltered = () => Boolean(state.q.trim()) || state.topicIds.length > 0 || state.status !== "ALL";

/** Any change goes back to the first page and the FAQ list (an entry shown alone closes), unless it says otherwise. */
function update(changes, { push = true } = {}) {
    state = { ...state, page: 1, faqId: null, ...changes };
    writeUrl(push);
    syncControls();
    load();
}

const load = () => (isFaq() ? loadFaq() : loadList());

// ---------------------------------------------------------------- controls

function syncControls() {
    const faq = isFaq();
    const text = TAB_TEXT[state.tab];
    document.title = text.title;
    els.lead.textContent = text.lead;
    els.q.placeholder = text.placeholder;
    els.qLabel.textContent = text.label;
    els.tabs.querySelectorAll("a[data-tab]").forEach((a) => {
        if (a.dataset.tab === state.tab) a.setAttribute("aria-current", "page");
        else a.removeAttribute("aria-current");
        // A new browser tab (middle click, Ctrl+click) opens the other tab with the same search
        a.href = a.dataset.tab === "faq" ? faqHref({ q: state.q.trim() }) : communityHref(state.q.trim());
    });
    els.filters.hidden = faq;
    els.faqTopics.hidden = !faq;
    if (faq) hideFaqHits();
    if (sideTab !== state.tab) renderSide();

    if (document.activeElement !== els.q) {
        els.q.value = state.q;
    }
    els.qCount.textContent = state.q.length >= 80 ? `${state.q.length}/${Q_MAX}` : "";
    els.filters.querySelectorAll(".seg").forEach((group) => {
        const current = state[group.dataset.group];
        group.querySelectorAll("button").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.value === current)));
    });
    els.topicList.querySelectorAll("input").forEach((box) => {
        box.checked = state.topicIds.includes(Number(box.value));
    });
    els.faqTopics.querySelectorAll("[data-faq-topic]").forEach((b) => {
        b.setAttribute("aria-pressed", String(!state.faqId && b.dataset.faqTopic === state.faqTopic));
    });
    const n = state.topicIds.length;
    els.topicLabel.textContent = n ? `Chủ đề (${n})` : "Chủ đề";
    els.topicDd.querySelector("summary").classList.toggle("on", n > 0);
    renderChips();
}

function renderChips() {
    const chosen = topics.filter((t) => state.topicIds.includes(t.id));
    els.chips.hidden = isFaq() || !isFiltered();
    els.chips.innerHTML = chosen.map((t) => `
        <button class="badge br chip" type="button" data-remove-topic="${t.id}" aria-label="Bỏ chủ đề ${esc(t.name)}">
            ${esc(t.name)}${ic("close")}</button>`).join("")
        + `<button class="link" type="button" data-clear>Xóa bộ lọc</button>`;
}

/** A plain click on a link of this page switches in place; Ctrl/⌘/Shift/middle click keeps the browser's own way. */
const plainClick = (event) => event.button === 0 && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;

function bindControls() {
    let timer;
    els.q.addEventListener("input", () => {
        clearTimeout(timer);
        timer = setTimeout(() => update({ q: els.q.value }, { push: false }), SEARCH_DELAY);
        els.qCount.textContent = els.q.value.length >= 80 ? `${els.q.value.length}/${Q_MAX}` : "";
    });
    els.search.addEventListener("submit", (event) => {
        event.preventDefault();
        clearTimeout(timer);
        update({ q: els.q.value });
    });

    els.tabs.addEventListener("click", (event) => {
        const link = event.target.closest("a[data-tab]");
        if (!link || !plainClick(event)) return;
        event.preventDefault();
        clearTimeout(timer);
        if (link.dataset.tab !== state.tab || state.faqId) {
            update({ tab: link.dataset.tab, q: els.q.value });
        }
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
        const target = event.target.closest("[data-remove-topic],[data-clear],[data-topic],[data-status],[data-page],"
            + "[data-retry],[data-faq-topic],[data-faq-all],[data-tab-go]");
        if (!target) return;
        if (target.dataset.removeTopic) {
            update({ topicIds: state.topicIds.filter((id) => id !== Number(target.dataset.removeTopic)) });
        } else if ("clear" in target.dataset) {
            update({ q: "", topicIds: [], status: "ALL", faqTopic: "" });
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
            load();
        } else if (target.dataset.retry === "all") {
            start();
        } else if ("faqTopic" in target.dataset) {
            if (target.dataset.faqTopic !== state.faqTopic || state.faqId) update({ faqTopic: target.dataset.faqTopic });
        } else if ("faqAll" in target.dataset) {
            update({ faqTopic: "", q: "" });
        } else if (target.dataset.tabGo && plainClick(event)) {
            event.preventDefault();
            update({ tab: target.dataset.tabGo });
            window.scrollTo({ top: 0, behavior: "smooth" });
        }
    });

    window.addEventListener("popstate", () => {
        state = readQaState(location.search);
        syncControls();
        load();
    });
}

// ---------------------------------------------------------------- community questions

async function loadList() {
    const id = ++lastRequest;
    els.list.setAttribute("aria-busy", "true");
    if (!els.list.querySelector(".question")) {
        els.list.innerHTML = skeleton(3);
    } else {
        els.list.classList.add("dim");
    }
    const q = state.q.trim();
    // FAQ entries like the search, on the first page only; never in the way of the questions themselves
    const hits = q.length >= FAQ_HITS_MIN && state.page === 1
        ? apiClient.faqRelated(q, FAQ_HITS).catch(() => [])
        : Promise.resolve([]);
    try {
        if (!overview) await ensureOverview();
        const page = await apiClient.questions({
            q, topicIds: state.topicIds, status: state.status, sort: state.sort,
            page: state.page - 1, size: PAGE_SIZE
        });
        const faqEntries = await hits;
        if (id !== lastRequest) return;
        renderFaqHits(faqEntries, q);
        renderList(page);
    } catch (error) {
        if (id !== lastRequest) return;
        const message = error.status === 400 ? error.message : MSG.MSG17;
        hideFaqHits();
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
        els.list.innerHTML = page.filtered
            ? stateBox("empty", state.q.trim() ? `${MSG.NO_MATCH} Chưa thấy lời giải? Hãy hỏi các senpai.` : MSG.NO_MATCH,
                `<div class="row">${state.q.trim() ? askButton() : ""}<button class="btn sm" type="button" data-clear>Xóa bộ lọc</button></div>`)
            : stateBox("empty", MSG.EMPTY, askButton());
        return;
    }
    els.summary.textContent = `${page.totalItems.toLocaleString("vi-VN")} câu hỏi${page.filtered ? " phù hợp" : ""}`;
    els.list.innerHTML = page.items.map(questionCard).join("");
    renderPager(page);
}

/** "Câu hỏi thường gặp liên quan" above the questions found by a search. */
function renderFaqHits(entries, q) {
    if (!entries.length || isFaq()) {
        hideFaqHits();
        return;
    }
    els.faqHits.innerHTML = `
        <h3 id="faq-hits-title">${ic("quiz")}Câu hỏi thường gặp liên quan</h3>
        <p class="note">Có thể câu trả lời bạn cần đã có ở đây.</p>`;
    const list = document.createElement("div");
    list.className = "qa-faq-list";
    list.append(...entries.map((entry) => faqItem(entry)));
    els.faqHits.append(list);
    els.faqHits.insertAdjacentHTML("beforeend", `<div class="row">
        <a class="link" href="${esc(faqHref({ q }))}" data-tab-go="faq">${ic("quiz")}Tìm “${esc(q)}” trong Câu hỏi thường gặp</a></div>`);
    els.faqHits.hidden = false;
}

function hideFaqHits() {
    els.faqHits.hidden = true;
    els.faqHits.replaceChildren();
}

function questionCard(q) {
    const answers = q.answerCount;
    const countClass = q.solved ? "solved" : answers ? "answered" : "";
    const countLabel = q.solved ? `Đã giải quyết, ${answers} câu trả lời` : `${answers} câu trả lời`;
    const badges = [
        q.pinned ? `<span class="badge">${ic("keep")}Đã ghim</span>` : "",
        q.solved ? `<span class="badge ok">${ic("check_circle")}Đã giải quyết</span>`
            : q.unanswered ? `<span class="badge lan">${ic("hourglass_empty")}Chưa ai trả lời</span>` : "",
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

// ---------------------------------------------------------------- "Câu hỏi thường gặp"

async function loadFaq() {
    const id = ++lastRequest;
    els.list.setAttribute("aria-busy", "true");
    if (!els.list.querySelector(".faq-q")) {
        els.list.innerHTML = skeleton(3);
    } else {
        els.list.classList.add("dim");
    }
    const q = state.q.trim();
    try {
        const [topicsAnswer, listAnswer] = await Promise.allSettled([
            apiClient.faqTopics(q),
            state.faqId
                ? apiClient.faq(state.faqId)
                : apiClient.faqs({ q, topic: state.faqTopic, page: state.page - 1, size: PAGE_SIZE })
        ]);
        if (id !== lastRequest) return;
        if (topicsAnswer.status === "fulfilled") {
            const faqTopics = topicsAnswer.value;
            // A topic in the address that does not exist (any more): show every entry instead
            if (state.faqTopic && !faqTopics.some((t) => t.code === state.faqTopic)) {
                state.faqTopic = "";
                writeUrl(false);
                loadFaq();
                return;
            }
            renderFaqTopics(faqTopics);
        }
        if (listAnswer.status === "rejected") throw listAnswer.reason;
        if (state.faqId) {
            renderFaqEntry(listAnswer.value);
            return;
        }
        const result = listAnswer.value;
        const related = !result.total && q ? await apiClient.faqRelated(q, FAQ_RELATED).catch(() => []) : [];
        if (id !== lastRequest) return;
        renderFaqList(result, related);
    } catch (error) {
        if (id !== lastRequest) return;
        els.pager.hidden = true;
        if (state.faqId && error.status === 404) {
            els.summary.textContent = "";
            els.list.innerHTML = stateBox("empty", MSG.FAQ_GONE, faqAllButton());
            return;
        }
        els.summary.textContent = "";
        const message = error.status === 400 ? error.message : MSG.MSG17;
        els.list.innerHTML = stateBox("error", message, `<button class="btn sm" type="button" data-retry="list">${ic("refresh")}Thử lại</button>`);
    } finally {
        if (id === lastRequest) {
            els.list.removeAttribute("aria-busy");
            els.list.classList.remove("dim");
        }
    }
}

/** "Tất cả" and the topics holding entries (for the search typed), each with its count. */
function renderFaqTopics(faqTopics) {
    const all = faqTopics.reduce((sum, t) => sum + t.count, 0);
    const shown = faqTopics.filter((t) => t.count > 0 || t.code === state.faqTopic);
    els.faqTopics.innerHTML = [{ code: "", title: "Tất cả", count: all }, ...shown].map((t) => `
        <button type="button" data-faq-topic="${esc(t.code)}" aria-pressed="${!state.faqId && t.code === state.faqTopic}">
            ${esc(t.title)}<span class="n">${t.count}<span class="sr-only"> câu</span></span></button>`).join("");
}

function renderFaqList(result, related) {
    if (result.totalPages && result.page + 1 !== state.page) {
        state.page = result.page + 1;
        writeUrl(false);
    }
    const q = state.q.trim();
    const filtered = Boolean(q || state.faqTopic);
    if (!result.items.length) {
        els.pager.hidden = true;
        if (related.length) {
            els.summary.textContent = `Không có câu nào chứa đúng “${q}”${state.faqTopic ? " trong chủ đề này" : ""}. `
                + "Những câu có thể liên quan:";
            els.list.replaceChildren(...related.map((entry) => faqItem(entry)));
            return;
        }
        els.summary.textContent = "";
        els.list.innerHTML = filtered
            ? stateBox("empty", `${MSG.FAQ_NO_MATCH} Chưa thấy lời giải? Hãy hỏi các senpai.`,
                `<div class="row">${askButton()}<button class="btn sm" type="button" data-clear>Xóa bộ lọc</button></div>`)
            : stateBox("empty", MSG.FAQ_EMPTY, askButton());
        return;
    }
    els.summary.textContent = `${result.total.toLocaleString("vi-VN")} câu hỏi thường gặp${filtered ? " phù hợp" : ""}`;
    els.list.replaceChildren(...result.items.map((entry) => faqItem(entry)));
    renderPager(result);
}

/** One entry alone (?tab=faq&id=): opened, with a way back to the whole list. */
function renderFaqEntry(entry) {
    document.title = `${entry.question} – Câu hỏi thường gặp – Senpai ơi`;
    els.summary.textContent = `Chủ đề: ${entry.topicName}`;
    els.pager.hidden = true;
    const back = document.createElement("div");
    back.className = "row";
    back.innerHTML = faqAllButton();
    els.list.replaceChildren(faqItem(entry, { open: true, link: "none" }), back);
}

const faqAllButton = () =>
    `<button class="btn sm" type="button" data-faq-all>${ic("arrow_back")}Xem tất cả câu hỏi thường gặp</button>`;

// ---------------------------------------------------------------- side column

async function ensureOverview() {
    if (overview) return true;
    try {
        renderOverview(await apiClient.questionOverview());
        return true;
    } catch {
        return false;
    }
}

function renderOverview(data) {
    overview = data;
    topics = data.topics;
    els.topicList.innerHTML = `<legend class="sr-only">Chọn chủ đề</legend>` + topics.map((t) => `
        <label class="check"><input type="checkbox" value="${t.id}"><span>${esc(t.name)}</span></label>`).join("");
    const active = new Set(topics.map((t) => t.id));
    if (state.topicIds.some((id) => !active.has(id))) {
        state.topicIds = state.topicIds.filter((id) => active.has(id));
        if (!isFaq()) writeUrl(false);
    }
    renderSide();
}

function renderSide() {
    if (isFaq()) {
        els.side.innerHTML = faqSide();
        sideTab = "faq";
    } else if (overview) {
        els.side.innerHTML = communitySide(overview);
        sideTab = "community";
    } else {
        els.side.innerHTML = "";
        sideTab = null;
    }
}

function communitySide(data) {
    const s = data.stats;
    const number = (n) => n.toLocaleString("vi-VN");
    return `
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
            ${data.popularTopics.length ? `<ul class="side-list">${data.popularTopics.map((t) => `
                <li><button class="side-item" type="button" data-topic="${t.id}"><span>${esc(t.name)}</span>
                    <span class="note">${t.postCount} câu hỏi</span></button></li>`).join("")}</ul>`
                : `<p class="note">Chưa có dữ liệu.</p>`}
        </div>
        <div class="card">
            <h3>${ic("workspace_premium")} Senpai giải đáp nhiều</h3>
            <p class="note">Trong ${data.topAnswerersDays} ngày qua</p>
            ${data.topAnswerers.length ? `<ul class="side-list">${data.topAnswerers.map((a) => `
                <li class="writer"><a class="author" href="${ROUTES.profile(a.author.id)}">${avatar(a.author)}<b>${esc(a.author.displayName)}</b></a>
                    ${tagChips(a.author.tags)}<span class="grow"></span>
                    <span class="note">${a.answerCount} trả lời${a.bestCount ? ` · ${a.bestCount} hay nhất` : ""}</span></li>`).join("")}</ul>`
                : `<p class="note">Chưa có ai trả lời trong thời gian này.</p>`}
        </div>
        <div class="card qa-tips">
            <h3>${ic("tips_and_updates")} Hỏi sao cho dễ được trả lời</h3>
            <ul>
                <li>Tìm trước xem đã có ai hỏi điều tương tự chưa, và xem mục “Câu hỏi thường gặp”.</li>
                <li>Ghi rõ bạn đang ở đâu, đã thử những gì.</li>
                <li>Gắn địa điểm liên quan nếu có (văn phòng quận, ngân hàng…).</li>
                <li>Khi có câu trả lời đúng, chọn “Câu trả lời hay nhất” để người sau biết.</li>
            </ul>
        </div>`;
}

const faqSide = () => `
    <div class="card">
        <h3>${ic("quiz")} Về câu hỏi thường gặp</h3>
        <p class="note">Moderator tổng hợp những điều được hỏi nhiều, nhiều câu lấy từ câu trả lời hay nhất của cộng đồng, và kiểm tra lại trước khi đăng.</p>
    </div>
    <div class="card">
        <h3>${ic("contact_support")} Chưa thấy câu trả lời?</h3>
        <p class="note">Hỏi cộng đồng, các senpai đã trải qua sẽ trả lời bạn.</p>
        <div class="row">${askButton()}
            <a class="btn sm" href="${esc(communityHref(state.q.trim()))}" data-tab-go="community">${ic("forum")}Xem câu hỏi cộng đồng</a></div>
    </div>`;

// ---------------------------------------------------------------- helpers

const askButton = () =>
    `<a class="btn pri sm" href="${esc(els.ask.getAttribute("href"))}">${ic("contact_support")}Đặt câu hỏi</a>`;

const stateBox = (kind, message, action = "") =>
    `<div class="state ${kind}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(kind === "error" ? "cloud_off" : "contact_support", "big")}<p>${esc(message)}</p>${action}</div>`;

const skeleton = (n) => Array.from({ length: n }, () =>
    `<div class="card post skel" aria-hidden="true"><span></span><span></span><span></span></div>`).join("")
    + `<span class="sr-only">Đang tải…</span>`;

// ---------------------------------------------------------------- start

async function start() {
    els.list.innerHTML = skeleton(3);
    // The community tab needs its topics and statistics first; the FAQ tab does not wait for them
    if (!isFaq() && !(await ensureOverview())) {
        els.side.innerHTML = "";
        els.summary.textContent = "";
        els.list.innerHTML = stateBox("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry="all">${ic("refresh")}Thử lại</button>`);
        return;
    }
    syncControls();
    await load();
    if (isFaq()) ensureOverview();
}

const user = await mountSiteHeader("qa");
els.ask.href = user ? ROUTES.ask : ROUTES.login(ROUTES.ask);     // Guests sign in before asking, then ask
bindControls();
syncControls();
await start();
