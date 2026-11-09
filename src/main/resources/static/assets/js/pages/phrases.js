// "Nhật ngữ" (the prototype's "Học" tab), page /phrases = pages/phrases.html + this script + assets/css/phrases.css.
// Entries (Mẫu câu = kind PHRASE, Từ vựng = kind VOCAB) are written by the Academic Manager (pages/academic/*).
//
// The view lives in the address, so Back works and links from other pages (Home) open the right list:
//   start page   none = Chủ đề, ?by=category = Địa điểm, ?q= (search, both kinds)
//   a set        ?topic=pay&kind=VOCAB, ?category=konbini&kind=PHRASE (without kind: the kind it has, Mẫu câu first),
//                ?view=learning|learned (the flash card lists, both kinds; &kind= filters them)
//   &status=learning|learned|new filters a set by the Member's marks ("Chưa thuộc", "Đã thuộc", "Chưa luyện").
// "Yêu thích" / "Đã lưu" was removed (user decision); old ?view=favorites links open the start page.
//
// Where each part of the screen is drawn (everything goes into <div id="content"> of phrases.html, except the dialogs):
//   START PAGE  renderStart()
//     "Nhật ngữ" title + intro .................. .phrase-head
//     search box "Tìm câu tiếng Nhật…" ........... #q          → onSearch() → search() fills #results
//     search results (under Từ vựng / Mẫu câu) .... #results    (cards: phraseCard())
//     everything below, hidden while searching .... #browse
//       "Luyện tập" button ....................... [data-act=practice] → openPractice() (dialog #flash-dlg)
//       "Đăng nhập để lưu kết quả…" (guests) ...... .info
//       "Chủ đề" / "Địa điểm" switch ............. .part-tabs [data-by]
//       "Từ vựng", then "Mẫu câu" sections ........ kindSection() → topic/category cards groupCard() + progress()
//   A SET (one topic, one category, or "Chưa thuộc" / "Đã thuộc")  loadSet() then renderSetList()
//     "← Chủ đề" / "← Địa điểm" back link ........ #set-back      backLink()
//     icon + name + progress bar ................ .set-head, #set-progress   updateSetHead()
//     "Từ vựng (n) / Mẫu câu (m)" switch ......... #set-kinds [data-kind]
//     "Tất cả / Chưa thuộc / Đã thuộc / Chưa luyện" #set-status [data-status]   (Members only)
//     "Luyện thẻ ghi nhớ (n thẻ)" button ......... #set-tools [data-act=flash]   updateSetHead()
//     the cards ................................. #set-list      phraseCard()
//   ONE CARD  phraseCard(): kind badge, category and topic badges (links), "Chưa thuộc"/"Đã thuộc" badge statusBadge(),
//     Japanese / romaji / meaning, buttons "Đưa cho nhân viên xem" (phrases only), "Nghe", "Bỏ đánh dấu", "Báo lỗi".
//     Clicks on all of it: the one listener on #content ("actions" below).
//   DIALOGS (in phrases.html)
//     #report-dlg  "Báo lỗi mẫu câu" ............. openReport(), its submit listener
//     #staff-dlg   "Đưa cho nhân viên xem" ........ showStaff()
//     #flash-dlg   two modes in #flash-body: the "Luyện tập" choices renderPractice(), then the cards renderFlash()
//     #toast       short messages ................ toast()
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { esc, ic, loginUrl } from "../lib/ui.js";

/** The two kinds, in the order the page shows them. */
const KIND = { VOCAB: "Từ vựng", PHRASE: "Mẫu câu" };
/** The Member's own lists: name, icon, which mark puts an entry in it, what the empty list says. */
const MINE = {
    learning: { name: "Chưa thuộc", icon: "replay", has: (p) => p.learning,
        empty: `Không còn thẻ nào chưa thuộc. Lật thẻ ở một chủ đề và chọn "Chưa thuộc" để lưu lại đây.` },
    learned: { name: "Đã thuộc", icon: "task_alt", has: (p) => p.learned,
        empty: `Chưa có thẻ nào đã thuộc. Lật thẻ và chọn "Đã thuộc" khi bạn nhớ câu đó.` }
};
/** The "Chưa thuộc / Đã thuộc / Chưa luyện" filter (Members only): the flash card answer kept for each entry. */
const STATUS = {
    learning: { name: "Chưa thuộc", has: (p) => p.learning },
    learned: { name: "Đã thuộc", has: (p) => p.learned },
    new: { name: "Chưa luyện", has: (p) => !p.learning && !p.learned }
};
/** The two main sections of the start page. */
const SECTIONS = {
    topic: { name: "Chủ đề", icon: "category", groups: (x) => x.topics },
    category: { name: "Địa điểm", icon: "location_on", groups: (x) => x.categories }
};
const SEARCH_DELAY = 250;

// The fixed elements of pages/phrases.html
const content = document.querySelector("#content");        // the whole page body, redrawn by show()
const toastBox = document.querySelector("#toast");
const reportDlg = document.querySelector("#report-dlg");    // "Báo lỗi"
const staffDlg = document.querySelector("#staff-dlg");      // "Đưa cho nhân viên xem"
const flashDlg = document.querySelector("#flash-dlg");      // "Luyện tập" choices and the flash cards

/** The signed-in account from the header (null for guests). */
let user = null;
/** GET /api/phrases/overview: the two parts with their topics and categories, `member`, the report reasons. */
let overview = null;
/** The phrases on screen (a set or search results). */
let items = [];
/** Every entry, loaded for "Luyện tập". */
let library = null;
let searchTimer = null;
let searchSeq = 0;
let toastTimer = null;
/** Ids of the flash card round in progress, where it is, and the answers so far (see startFlash). */
let flash = null;
/** The kind shown in the set on screen: the part of a topic or category, a filter of the Member's lists ("" = both). */
let setKind = "";
/** The "Chưa thuộc / Đã thuộc / Chưa luyện" filter of the set on screen ("" = all). */
let setStatus = "";
/** Phrase id while the report dialog is open. */
let reporting = null;

/** A signed-in, active Member (role USER): only they keep flash card answers and may report. */
const member = () => Boolean(overview?.member);
/** The overview's part of one kind ("Mẫu câu" or "Từ vựng"). */
const part = (kind) => overview.parts.find((x) => x.kind === kind);
/** The parts in the page's order: Từ vựng, then Mẫu câu. */
const kinds = () => Object.keys(KIND).map(part).filter(Boolean);

/** The view asked for by the address (see the top of this file); unknown values fall back to the start page. */
function view() {
    const p = new URLSearchParams(location.search);
    const mine = p.get("view");
    return {
        topic: p.get("topic") || null,
        category: p.get("category") || null,
        mine: MINE[mine] ? mine : null,
        by: p.get("by") === "category" ? "category" : "topic",
        kind: KIND[p.get("kind")] ? p.get("kind") : "",
        status: STATUS[p.get("status")] ? p.get("status") : "",
        q: (p.get("q") || "").trim()
    };
}

const hasSet = (v) => Boolean(v.topic || v.category || v.mine);

/** The entries of the set step by step: in the Member's list, of the kind shown, passing the status filter. */
function layers() {
    const mine = MINE[view().mine];
    const inList = mine ? items.filter(mine.has) : items;
    const ofKind = setKind ? inList.filter((p) => p.kind === setKind) : inList;
    const list = setStatus ? ofKind.filter(STATUS[setStatus].has) : ofKind;
    return { inList, ofKind, list };
}

/** The cards on screen in a set, in order: what "Luyện thẻ ghi nhớ" goes through. */
const shown = () => layers().list;

/** Opens another view: writes the address (a new history entry unless `replace`) and draws it. */
function go(params, replace = false) {
    const query = new URLSearchParams(Object.entries(params).filter(([, value]) => value)).toString();
    const url = location.pathname + (query ? `?${query}` : "");
    history[replace ? "replaceState" : "pushState"](null, "", url);
    return show();
}

/** Changes one parameter of the address without a new history entry. */
function setParam(name, value) {
    const p = new URLSearchParams(location.search);
    if (value) p.set(name, value); else p.delete(name);
    const query = p.toString();
    history.replaceState(null, "", location.pathname + (query ? `?${query}` : ""));
}

// ---------------------------------------------------------------- pages

/** Draws the view of the address: a set (loadSet) or the start page (renderStart, plus the search of ?q=). */
async function show() {
    const v = view();
    if (v.mine && !user) {
        location.replace(loginUrl());
        return;
    }
    if (hasSet(v)) {
        content.innerHTML = `<p class="note">Đang tải…</p>`;
        await loadSet(v);
    } else {
        renderStart(v);
        if (v.q) await search(v.q);
    }
    window.scrollTo(0, 0);
}

/**
 * START PAGE: title, search box (#q), search results (#results) and, hidden while a search is typed, #browse: the
 * "Luyện tập" button, the guest hint, the "Chủ đề" / "Địa điểm" switch and one section per kind (kindSection).
 */
function renderStart(v) {
    items = [];
    const section = SECTIONS[v.by];
    content.innerHTML = `
        <div class="phrase-head">
            <h2>Nhật ngữ</h2>
            <p class="note">Học trước những câu và từ bạn sẽ cần khi sống ở Nhật. Mỗi mẫu câu, từ vựng thuộc một loại địa
                điểm (cửa hàng tiện lợi, ga tàu…) và một hoặc nhiều chủ đề, do Biên tập viên nội dung tiếng Nhật biên soạn.</p>
        </div>
        <label class="blog-search">${ic("search")}<span class="sr-only">Tìm mẫu câu, từ vựng</span>
            <input type="search" id="q" value="${esc(v.q)}" placeholder="Tìm câu tiếng Nhật, nghĩa, chủ đề" autocomplete="off" enterkeyhint="search">
        </label>
        <div id="results" class="phrase-list" aria-live="polite"></div>
        <div id="browse" ${v.q ? "hidden" : ""}>
            <div class="row start-actions">
                <button class="btn pri" type="button" data-act="practice">${ic("style")}Luyện tập</button>
            </div>
            ${user ? "" : `<div class="info">${ic("info")} <a class="link" href="${esc(loginUrl())}">Đăng nhập</a> để lưu kết quả lật thẻ (Chưa thuộc / Đã thuộc).</div>`}
            <div class="part-tabs" role="group" aria-label="Xem theo">${Object.entries(SECTIONS).map(([key, s]) => `
                <button type="button" class="part-tab" data-by="${key}" aria-pressed="${key === v.by}">
                    ${ic(s.icon)}<span class="grow"><b>${s.name}</b><span class="note">${groupCount(s)} ${s.name.toLowerCase()}</span></span>
                </button>`).join("")}
            </div>
            ${kinds().map((x) => kindSection(x, section, v.by)).join("")}
        </div>`;
    const input = content.querySelector("#q");
    input.addEventListener("input", () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => onSearch(input.value), SEARCH_DELAY);
    });
    input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            clearTimeout(searchTimer);
            onSearch(input.value);
        }
    });
}

/** How many topics (or categories) hold at least one entry, of either kind. */
function groupCount(section) {
    return new Set(overview.parts.flatMap((x) => section.groups(x).map((g) => g.id))).size;
}

/** "Từ vựng" or "Mẫu câu" inside the main section on screen: its topics or categories. */
function kindSection(x, section, by) {
    const groups = section.groups(x);
    const learned = member() ? ` · ${x.learned}/${x.count} đã thuộc` : "";
    return `<section class="kind-sec" aria-label="${esc(x.name)}">
        <h3 class="part-h">${ic(x.icon)}${esc(x.name)}<span class="note">${x.count} mục${learned}</span></h3>
        <div class="topic-grid">${groups.length
            ? groups.map((g) => groupCard(g, by, x.kind)).join("")
            : `<p class="note">Chưa có ${x.name.toLowerCase()} nào.</p>`}</div>
    </section>`;
}

/** A topic or category card of the start page; opens that set of one kind (?topic=…&kind=… / ?category=…&kind=…). */
function groupCard(g, by, kind) {
    const href = `?${by === "category" ? "category" : "topic"}=${encodeURIComponent(g.id)}&kind=${kind}`;
    return `<a class="card topic-card" href="${href}" data-nav>${ic(g.icon, "big")}
        <b>${esc(g.name)}</b><span class="note">${g.count} mục</span>${progress(g.learned, g.count)}</a>`;
}

/** The "đã thuộc" progress bar (Members only; empty for guests and staff). */
function progress(learned, count) {
    if (!member()) return "";
    const width = count ? Math.round((learned / count) * 100) : 0;
    return `<div class="row progress"><div class="bar2 grow"><span style="width:${width}%"></span></div>
        <span class="note">${learned}/${count}</span></div>`;
}

/** Typing in the search box: keeps ?q= in the address, hides #browse while there is a text. */
async function onSearch(text) {
    const q = text.trim();
    setParam("q", q);
    content.querySelector("#browse").hidden = Boolean(q);
    if (!q) {
        searchSeq++;
        items = [];
        content.querySelector("#results").innerHTML = "";
        return;
    }
    await search(q);
}

/** Search over both kinds; the results are shown under "Từ vựng" and "Mẫu câu". */
async function search(q) {
    const seq = ++searchSeq;
    const box = content.querySelector("#results");
    box.setAttribute("aria-busy", "true");
    try {
        const found = await apiClient.phrases({ q });
        if (seq !== searchSeq) return;           // an answer for an older text
        items = found;
        const groups = kinds().map((x) => [x, found.filter((p) => p.kind === x.kind)]).filter(([, list]) => list.length);
        box.innerHTML = `<p class="note">${found.length} kết quả</p>`
            + (found.length ? groups.map(([x, list]) => `<h3 class="part-h">${ic(x.icon)}${esc(x.name)} (${list.length})</h3>`
                + list.map((p) => phraseCard(p, true)).join("")).join("")
                : `<p class="note">Không tìm thấy mẫu câu hay từ vựng nào.</p>`);
    } catch (error) {
        if (seq === searchSeq) box.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
    } finally {
        if (seq === searchSeq) box.removeAttribute("aria-busy");
    }
}

/**
 * A SET: reads its entries, picks the kind and the status filter shown, and lays out the empty boxes that
 * renderSetList() fills (#set-back, .set-head with #set-progress, #set-kinds, #set-status, #set-tools, #set-list).
 */
async function loadSet(v) {
    const head = setHead(v);
    if (!head) {
        content.innerHTML = `${backLink(v)}<div class="alert error">Không tìm thấy chủ đề này.</div>`;
        return;
    }
    try {
        items = await apiClient.phrases(v.mine ? { mine: v.mine } : { topic: v.topic, category: v.category });
    } catch (error) {
        if (error.status === 401) {
            location.replace(loginUrl());
            return;
        }
        content.innerHTML = `${backLink(v)}<div class="alert error">${esc(error.message)}</div>`;
        return;
    }
    // A topic or category shows one kind at a time; without ?kind= (links from Home) the kind it has, Mẫu câu first
    setKind = v.mine ? v.kind
        : v.kind || (items.length && !items.some((p) => p.kind === "PHRASE") ? "VOCAB" : "PHRASE");
    setStatus = member() && v.mine !== "learning" && v.mine !== "learned" ? v.status : "";
    content.innerHTML = `
        <div id="set-back"></div>
        <div class="row set-head">${ic(head.icon, "big")}<div class="grow"><h2>${esc(head.name)}</h2><div id="set-progress"></div></div></div>
        <div class="set-filters"><div id="set-kinds"></div><div id="set-status"></div></div>
        <div id="set-tools"></div>
        <div class="phrase-list" id="set-list"></div>`;
    renderSetList();
}

/** The cards of the set that pass the filters, the filters themselves and the head. */
function renderSetList() {
    const v = view();
    const { inList, ofKind, list } = layers();
    const unit = setKind ? KIND[setKind].toLowerCase() : "mục";
    const empty = setStatus && ofKind.length ? `Không có ${unit} nào ${STATUS[setStatus].name.toLowerCase()} ở đây.`
        : v.mine ? (setKind && inList.length ? `Không có ${unit} nào ở đây.` : MINE[v.mine].empty)
            : `${v.category ? "Địa điểm" : "Chủ đề"} này chưa có ${unit} nào.`;
    content.querySelector("#set-back").innerHTML = backLink(v);
    content.querySelector("#set-list").innerHTML = list.length
        ? list.map((p) => phraseCard(p, !v.category)).join("") : `<p class="note">${empty}</p>`;

    // Từ vựng / Mẫu câu: one kind at a time in a topic or category, "Tất cả" too in the Member's lists
    const counts = Object.keys(KIND).map((k) => [k, inList.filter((p) => p.kind === k).length]);
    content.querySelector("#set-kinds").innerHTML = counts.every(([, n]) => n > 0) ? `
        <div class="seg" role="group" aria-label="Loại">
            ${v.mine ? `<button type="button" data-kind="" aria-pressed="${!setKind}">Tất cả (${inList.length})</button>` : ""}
            ${counts.map(([k, n]) => `<button type="button" data-kind="${k}" aria-pressed="${setKind === k}">${KIND[k]} (${n})</button>`).join("")}
        </div>` : "";

    // Chưa thuộc / Đã thuộc / Chưa luyện (not in the lists that are already one of them)
    const statusOn = member() && v.mine !== "learning" && v.mine !== "learned" && ofKind.length > 0;
    content.querySelector("#set-status").innerHTML = statusOn ? `
        <div class="seg" role="group" aria-label="Lọc theo mức thuộc">
            <button type="button" data-status="" aria-pressed="${!setStatus}">Tất cả (${ofKind.length})</button>
            ${Object.entries(STATUS).map(([key, s]) => `<button type="button" data-status="${key}" aria-pressed="${setStatus === key}">${s.name} (${ofKind.filter(s.has).length})</button>`).join("")}
        </div>` : "";
    updateSetHead();
}

/** Name and icon of the set: the Member's list, else the topic or category from the overview (undefined = unknown id). */
function setHead(v) {
    if (v.mine) return { name: MINE[v.mine].name, icon: MINE[v.mine].icon };
    const all = overview.parts.flatMap((x) => (v.topic ? x.topics : x.categories));
    return all.find((g) => g.id === (v.topic || v.category));
}

/** Back to the start page: "Chủ đề" or "Địa điểm" ("Nhật ngữ" from the Member's lists). */
function backLink(v) {
    const by = v.category ? "category" : "topic";
    const label = v.mine ? "Nhật ngữ" : SECTIONS[by].name;
    const href = location.pathname + (v.category ? "?by=category" : "");
    return `<div><a class="link" href="${href}" data-nav>${ic("arrow_back")}${label}</a></div>`;
}

/** The progress bar and the flash card button follow the marks without redrawing the cards. */
function updateSetHead() {
    const bar = content.querySelector("#set-progress");
    if (!bar) return;
    const v = view();
    const { ofKind, list } = layers();
    bar.innerHTML = v.mine ? `<span class="note">${ofKind.length} mục</span>`
        : progress(ofKind.filter((p) => p.learned).length, ofKind.length);
    const label = v.mine === "learning" || setStatus === "learning" ? "Luyện lại thẻ chưa thuộc" : "Luyện thẻ ghi nhớ";
    content.querySelector("#set-tools").innerHTML = list.length
        ? `<button class="btn pri" type="button" data-act="flash">${ic("style")}${label} (${list.length} thẻ)</button>`
        : "";
}

// ---------------------------------------------------------------- a phrase

/**
 * ONE CARD (search results and sets): badges (kind, category unless the set is that category, topics, mark), the
 * Japanese, romaji and meaning, then the buttons. "Báo lỗi" shows for guests (it sends them to sign in) and Members,
 * not for staff. The buttons are handled by the click listener on #content (data-act).
 */
function phraseCard(p, withCategory) {
    const report = user && !member() ? "" : `<button class="btn sm" type="button" data-act="report-phrase">${ic("flag")}Báo lỗi</button>`;
    return `
        <article class="card vocab" data-id="${p.id}">
            <div class="row">
                <span class="badge">${KIND[p.kind] || esc(p.kind)}</span>
                ${withCategory ? `<a class="badge" href="?category=${encodeURIComponent(p.category.id)}" data-nav>${ic(p.category.icon)} ${esc(p.category.name)}</a>` : ""}
                ${p.topics.map((t) => `<a class="badge br" href="?topic=${encodeURIComponent(t.id)}" data-nav>${esc(t.name)}</a>`).join("")}
                <span class="grow"></span>
                ${statusBadge(p)}
            </div>
            <div class="ja vocab-ja" lang="ja">${esc(p.japanese)}</div>
            <div class="note">${esc(p.romaji)}</div>
            <div>${esc(p.meaningVi)}</div>
            <div class="row">
                ${p.kind === "VOCAB" ? "" : `<button class="btn sm pri" type="button" data-act="staff">${ic("front_hand")}Đưa cho nhân viên xem</button>`}
                <button class="btn sm" type="button" data-act="speak">${ic("volume_up")}Nghe</button>
                ${member() && (p.learning || p.learned) ? `<button class="btn sm" type="button" data-act="clear">${ic("undo")}Bỏ đánh dấu</button>` : ""}
                ${report}
            </div>
        </article>`;
}

/** "Đã thuộc" / "Chưa thuộc" badge of the Member's last flash card answer (none when not answered). */
function statusBadge(p) {
    if (p.learned) return `<span class="badge ok">${ic("check_circle")}Đã thuộc</span>`;
    if (p.learning) return `<span class="badge warn">${ic("replay")}Chưa thuộc</span>`;
    return "";
}

function redrawCard(p) {
    const card = content.querySelector(`.vocab[data-id="${p.id}"]`);
    if (card) card.outerHTML = phraseCard(p, !view().category);
}

/** An entry on screen, else one of the library loaded for "Luyện tập". */
const findItem = (id) => items.find((p) => p.id === id) || library?.find((p) => p.id === id);

/** Every change of a mark shows at once: the cards (a list of the Member's own drops what left it) and the head. */
function refresh(phrase) {
    if (content.querySelector("#set-list")) renderSetList();
    else if (phrase) redrawCard(phrase);
}

// ---------------------------------------------------------------- actions

// Every click inside #content, in this order: links marked data-nav (drawn here without reloading the page), the
// "Chủ đề" / "Địa điểm" switch, the kind switch of a set, its status filter, then the buttons with data-act.
content.addEventListener("click", async (event) => {
    const nav = event.target.closest("a[data-nav]");
    if (nav && !event.ctrlKey && !event.metaKey && !event.shiftKey && event.button === 0) {
        event.preventDefault();
        const url = new URL(nav.href);
        history.pushState(null, "", url.pathname + url.search);
        await show();
        return;
    }
    // "Chủ đề" / "Địa điểm": the main section of the start page
    const by = event.target.closest("[data-by]");
    if (by) {
        go({ by: by.dataset.by === "category" ? "category" : "" }, true);
        content.querySelector(`[data-by="${by.dataset.by}"]`)?.focus();
        return;
    }
    const kind = event.target.closest("[data-kind]");
    if (kind) {
        setKind = kind.dataset.kind;
        setParam("kind", setKind);
        renderSetList();
        content.querySelector(`[data-kind="${setKind}"]`)?.focus();
        return;
    }
    const status = event.target.closest("[data-status]");
    if (status) {
        setStatus = status.dataset.status;
        setParam("status", setStatus);
        renderSetList();
        content.querySelector(`[data-status="${setStatus}"]`)?.focus();
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const card = button.closest(".vocab");
    const phrase = card ? findItem(Number(card.dataset.id)) : null;
    switch (button.dataset.act) {
        case "staff": showStaff(phrase); break;
        case "speak": speak(phrase.japanese); break;
        case "clear": await clearMark(phrase, button); break;
        case "report-phrase": openReport(phrase.id); break;
        case "flash": startFlash(shown()); break;
        case "practice": await openPractice(button); break;
        default: break;
    }
});

/** "Bỏ đánh dấu" on a card: DELETE /api/phrases/{id}/flash. */
async function clearMark(phrase, button) {
    button.disabled = true;
    try {
        applyMarks(phrase, await apiClient.phraseClearFlash(phrase.id));
        refresh(phrase);
        toast("Đã bỏ đánh dấu.");
    } catch (error) {
        button.disabled = false;
        failed(error);
    }
}

/** Keeps the overview's "đã thuộc" counts (part, topic and category progress) in step with a mark change. */
function applyMarks(phrase, result) {
    const own = part(phrase.kind);
    if (result.learned !== phrase.learned && own) {
        const delta = result.learned ? 1 : -1;
        own.learned += delta;
        phrase.topics.forEach((t) => {
            const topic = own.topics.find((x) => x.id === t.id);
            if (topic) topic.learned += delta;
        });
        const category = own.categories.find((x) => x.id === phrase.category.id);
        if (category) category.learned += delta;
    }
    phrase.learning = result.learning;
    phrase.learned = result.learned;
}

/** An action that failed: to sign in on a 401, else the message as a red toast. */
function failed(error) {
    if (error.status === 401) {
        location.assign(loginUrl());
        return;
    }
    toast(error.message, "error");
}

// ---------------------------------------------------------------- report (dialog #report-dlg)

/** "Báo lỗi": fills the reasons (overview.reportReasons) and opens the dialog; guests go to sign in. */
function openReport(id) {
    if (!user) {
        location.assign(loginUrl());
        return;
    }
    reporting = id;
    reportDlg.querySelector("#report-reasons").innerHTML = `<legend class="sr-only">Lý do</legend>`
        + overview.reportReasons.map((r) => `<label class="check"><input type="radio" name="reason" value="${r.id}"><span>${esc(r.label)}</span></label>`).join("");
    reportDlg.querySelector("#report-note").value = "";
    reportDlg.querySelector("#report-error").hidden = true;
    reportDlg.showModal();
}

// "Gửi báo cáo": POST /api/phrases/{id}/report; errors stay in the dialog (#report-error)
reportDlg.querySelector("#report-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const error = reportDlg.querySelector("#report-error");
    const reason = reportDlg.querySelector("input[name=reason]:checked");
    if (!reason) {
        error.textContent = "Hãy chọn lý do báo cáo.";
        error.hidden = false;
        return;
    }
    const body = { reasonId: Number(reason.value), note: reportDlg.querySelector("#report-note").value };
    const submit = reportDlg.querySelector("#report-submit");
    submit.disabled = true;
    try {
        await apiClient.reportPhrase(reporting, body);
        reportDlg.close();
        toast("Cảm ơn bạn. Báo cáo đã được gửi.");
    } catch (e) {
        if (e.status === 401) {
            location.assign(loginUrl());
            return;
        }
        error.textContent = e.message;
        error.hidden = false;
    } finally {
        submit.disabled = false;
    }
});

// ---------------------------------------------------------------- show to staff (dialog #staff-dlg), listen

/** "Đưa cho nhân viên xem": the phrase in large type with its meaning; "Nghe phát âm" reads it. */
function showStaff(phrase) {
    staffDlg.querySelector("#staff-ja").textContent = phrase.japanese;
    staffDlg.querySelector("#staff-vi").textContent = phrase.meaningVi;
    staffDlg.dataset.text = phrase.japanese;
    staffDlg.showModal();
}

staffDlg.querySelector("[data-speak-staff]").addEventListener("click", () => speak(staffDlg.dataset.text));

/** Reads the Japanese aloud with the browser's own voice (free, no API). */
function speak(text) {
    try {
        if (!("speechSynthesis" in window)) throw new Error("no speech");
        const utterance = new SpeechSynthesisUtterance(text);
        utterance.lang = "ja-JP";
        utterance.rate = 0.85;
        speechSynthesis.cancel();
        speechSynthesis.speak(utterance);
    } catch {
        toast("Thiết bị này không hỗ trợ đọc tiếng Nhật.", "error");
    }
}

// ---------------------------------------------------------------- "Luyện tập" (dialog #flash-dlg, first mode)

/**
 * The choices of "Luyện tập": where the cards come from (`by` "" = everything, "topic" or "category", with `group` the
 * topic or category id), kind ("" = both), mark ("" = all, Members only), shuffled or in library order. Kept while the
 * page is open, so "Luyện tập" opens again with the last choices.
 */
const practice = { by: "", group: "", kind: "", status: "", shuffle: true };

/**
 * "Luyện tập" on the start page: flash cards over the library, the cards chosen by topic or place category, by kind
 * and (for Members) by mark. The library is read again each time, so the marks are the current ones; an entry also on
 * screen (a search result) shares its object, so a mark changed on the card shows on both.
 */
async function openPractice(button) {
    button.disabled = true;
    try {
        const all = await apiClient.phrases({});
        library = all.map((p) => items.find((x) => x.id === p.id) || p);
    } catch (error) {
        failed(error);
        return;
    } finally {
        button.disabled = false;
    }
    if (!member()) practice.status = "";
    renderPractice();
    if (!flashDlg.open) flashDlg.showModal();
}

/** Whether an entry belongs to a topic (`by` "topic") or a place category ("category"). */
const inGroup = (p, by, id) => (by === "topic" ? p.topics.some((t) => t.id === id) : p.category.id === id);

/** The topics or the place categories that hold entries, in the start page's order, with how many each holds. */
function practiceGroups(by) {
    const seen = new Map();
    overview.parts.flatMap((x) => (by === "topic" ? x.topics : x.categories))
        .forEach((g) => { if (!seen.has(g.id)) seen.set(g.id, g); });
    return [...seen.values()].map((g) => ({ ...g, count: library.filter((p) => inGroup(p, by, g.id)).length }))
        .filter((g) => g.count > 0);
}

/** The entries of the chosen topic or place (all of them when none is chosen). */
const practiceScope = () => (practice.by ? library.filter((p) => inGroup(p, practice.by, practice.group)) : library);

/** The cards "Bắt đầu" will practise: the chosen topic or place, kind and mark. */
function practicePool() {
    return practiceScope().filter((p) => (!practice.kind || p.kind === practice.kind)
        && (!practice.status || STATUS[practice.status].has(p)));
}

/**
 * The "Luyện tập" choices in #flash-body, top to bottom: "Luyện theo" (Tất cả / Chủ đề / Địa điểm + a list
 * #practice-group), "Loại", "Thẻ" (Members only), "Xáo trộn thứ tự thẻ", then "Hủy" / "Bắt đầu (n thẻ)". The buttons
 * carry data-flash="p-by|p-kind|p-status|p-start" and are handled by the click listener of #flash-dlg below.
 */
function renderPractice() {
    // A topic or place chosen before that no longer holds entries: the first one instead
    const groups = practice.by ? practiceGroups(practice.by) : [];
    if (practice.by && !groups.some((g) => g.id === practice.group)) practice.group = groups[0]?.id || "";
    const scope = practiceScope();
    const ofKind = scope.filter((p) => !practice.kind || p.kind === practice.kind);
    const pool = practicePool();
    const option = (name, value, label, on) => `<button type="button" data-flash="${name}" data-value="${value}" aria-pressed="${on}">${label}</button>`;
    const section = SECTIONS[practice.by];
    flashDlg.querySelector("#flash-body").innerHTML = `
        <div class="row"><h3 id="flash-title" class="grow">Luyện tập</h3>
            <button class="icon-btn" type="button" data-close aria-label="Đóng">${ic("close")}</button></div>
        <p class="note">Chọn thẻ muốn luyện. Lật thẻ rồi chọn "Chưa thuộc" hoặc "Đã thuộc".</p>
        <div class="practice-opt"><b>Luyện theo</b>
            <div class="seg" role="group" aria-label="Luyện theo">
                ${option("p-by", "", "Tất cả", !practice.by)}
                ${Object.entries(SECTIONS).map(([key, s]) => option("p-by", key, `${ic(s.icon)}${s.name}`, practice.by === key)).join("")}
            </div>
            ${practice.by ? `<label class="sr-only" for="practice-group">Chọn ${section.name.toLowerCase()}</label>
                <select id="practice-group" class="practice-group">${groups.map((g) => `
                    <option value="${esc(g.id)}" ${g.id === practice.group ? "selected" : ""}>${esc(g.name)} (${g.count})</option>`).join("")}
                </select>` : ""}
        </div>
        <div class="practice-opt"><b>Loại</b>
            <div class="seg" role="group" aria-label="Loại">
                ${option("p-kind", "", `Cả hai (${scope.length})`, !practice.kind)}
                ${Object.entries(KIND).map(([k, name]) => option("p-kind", k, `${name} (${scope.filter((p) => p.kind === k).length})`, practice.kind === k)).join("")}
            </div></div>
        ${member() ? `<div class="practice-opt"><b>Thẻ</b>
            <div class="seg" role="group" aria-label="Lọc theo mức thuộc">
                ${option("p-status", "", `Tất cả (${ofKind.length})`, !practice.status)}
                ${Object.entries(STATUS).map(([key, s]) => option("p-status", key, `${s.name} (${ofKind.filter(s.has).length})`, practice.status === key)).join("")}
            </div></div>` : ""}
        <label class="check"><input type="checkbox" id="practice-shuffle" ${practice.shuffle ? "checked" : ""}><span>Xáo trộn thứ tự thẻ</span></label>
        <div class="row center">
            <button class="btn" type="button" data-close>Hủy</button>
            <button class="btn pri" type="button" data-flash="p-start" ${pool.length ? "" : "disabled"}>${ic("play_arrow")}Bắt đầu (${pool.length} thẻ)</button>
        </div>`;
}

/** A shuffled copy (Fisher–Yates). */
function shuffled(list) {
    const copy = [...list];
    for (let i = copy.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [copy[i], copy[j]] = [copy[j], copy[i]];
    }
    return copy;
}

// The topic or place chosen in "Luyện tập"
flashDlg.addEventListener("change", (event) => {
    if (event.target.id !== "practice-group") return;
    practice.group = event.target.value;
    renderPractice();
    flashDlg.querySelector("#practice-group")?.focus();
});

// The start page shows the new numbers ("đã thuộc", progress) once the cards are closed
flashDlg.addEventListener("close", () => {
    const v = view();
    if (!hasSet(v) && !v.q && content.querySelector("#browse")) renderStart(v);
});

// ---------------------------------------------------------------- flash cards (dialog #flash-dlg, second mode)

/**
 * "Luyện thẻ ghi nhớ": flip a card, then "Chưa thuộc" or "Đã thuộc". A Member's answer is kept (the last one counts)
 * and puts the card in that list; a guest or staff account flips without keeping anything. Started from a set
 * ("Luyện thẻ ghi nhớ"), from "Bắt đầu" in "Luyện tập", or from "Luyện lại N thẻ chưa thuộc" at the end of a round.
 */
function startFlash(list) {
    flash = { ids: list.map((p) => p.id), i: 0, flip: false, learned: [], learning: [] };
    renderFlash();
    if (!flashDlg.open) flashDlg.showModal();
}

/**
 * One card in #flash-body: title "Thẻ ghi nhớ · i/n", progress bar, the card (front: Japanese; back: + romaji and
 * meaning), "Nghe", and once flipped "Chưa thuộc" / "Đã thuộc". After the last card: the result ("Xong n thẻ") with
 * "Luyện lại…", "Xem danh sách Chưa thuộc" and "Xong".
 */
function renderFlash() {
    const body = flashDlg.querySelector("#flash-body");
    const phrase = findItem(flash.ids[flash.i]);
    if (!phrase) {
        const kept = member();
        body.innerHTML = `
            <div class="flash-done">${ic("celebration", "big")}<h3 id="flash-title">Xong ${flash.ids.length} thẻ</h3>
                <p>Đã thuộc: ${flash.learned.length} · Chưa thuộc: ${flash.learning.length}</p>
                <p class="note">${kept ? `Đã lưu vào danh sách "Chưa thuộc" và "Đã thuộc" của bạn.`
                    : user ? "Kết quả không được lưu với tài khoản nhân viên." : `<a class="link" href="${esc(loginUrl())}">Đăng nhập</a> để lưu kết quả lần sau.`}</p></div>
            <div class="row center">
                ${flash.learning.length ? `<button class="btn pri" type="button" data-flash="again-learning">${ic("replay")}Luyện lại ${flash.learning.length} thẻ chưa thuộc</button>` : ""}
                ${kept && flash.learning.length && view().mine !== "learning" ? `<a class="btn" href="?view=learning" data-flash="list">Xem danh sách Chưa thuộc</a>` : ""}
                <button class="btn" type="button" data-close>Xong</button></div>`;
        return;
    }
    const done = Math.round((flash.i / flash.ids.length) * 100);
    body.innerHTML = `
        <div class="row"><h3 id="flash-title" class="grow">Thẻ ghi nhớ · ${flash.i + 1}/${flash.ids.length}</h3>
            <button class="icon-btn" type="button" data-close aria-label="Đóng">${ic("close")}</button></div>
        <div class="bar2"><span style="width:${done}%"></span></div>
        <button class="flash-card ${flash.flip ? "flip" : ""}" type="button" data-flash="flip" aria-live="polite">
            <span class="ja" lang="ja">${esc(phrase.japanese)}</span>
            ${flash.flip ? `<span class="note">${esc(phrase.romaji)}</span><span>${esc(phrase.meaningVi)}</span>` : `<span class="note">Bấm để lật thẻ</span>`}
        </button>
        <div class="row center">${statusBadge(phrase)}<button class="btn sm" type="button" data-flash="speak">${ic("volume_up")}Nghe</button></div>
        ${flash.flip ? `<div class="row center">
            <button class="btn" type="button" data-flash="no">${ic("replay")}Chưa thuộc</button>
            <button class="btn pri" type="button" data-flash="yes">${ic("task_alt")}Đã thuộc</button></div>` : ""}`;
}

// Every button of #flash-dlg (data-flash): first the "Luyện tập" choices, then the flash card buttons. The answers
// "no" / "yes" are sent as POST /api/phrases/{id}/flash for Members.
flashDlg.addEventListener("click", async (event) => {
    const button = event.target.closest("[data-flash]");
    if (!button) return;
    switch (button.dataset.flash) {       // the "Luyện tập" choices, before any card
        case "p-by":
        case "p-kind":
        case "p-status":
            practice[button.dataset.flash.slice(2)] = button.dataset.value;
            renderPractice();
            flashDlg.querySelector(`[data-flash="${button.dataset.flash}"][data-value="${button.dataset.value}"]`)?.focus();
            return;
        case "p-start": {
            practice.shuffle = flashDlg.querySelector("#practice-shuffle").checked;
            const list = practicePool();
            startFlash(practice.shuffle ? shuffled(list) : list);
            return;
        }
        default: break;
    }
    const phrase = findItem(flash.ids[flash.i]);
    switch (button.dataset.flash) {
        case "flip":
            flash.flip = !flash.flip;
            renderFlash();
            flashDlg.querySelector("[data-flash=flip]").focus();
            break;
        case "speak": speak(phrase.japanese); break;
        case "again-learning": startFlash(flash.learning.map(findItem).filter(Boolean)); break;
        case "list":
            event.preventDefault();
            flashDlg.close();
            go({ view: "learning" });
            break;
        case "no":
        case "yes": {
            const learned = button.dataset.flash === "yes";
            if (member()) {
                button.disabled = true;
                try {
                    applyMarks(phrase, await apiClient.phraseFlash(phrase.id, learned));
                    refresh(phrase);
                } catch (error) {
                    button.disabled = false;
                    failed(error);
                    return;
                }
            }
            (learned ? flash.learned : flash.learning).push(phrase.id);
            flash.i++;
            flash.flip = false;
            renderFlash();
            break;
        }
        default: break;
    }
});

// ---------------------------------------------------------------- dialogs, toast

// Any dialog closes on a [data-close] button ("Hủy", "Đóng", ✕) or a click on its backdrop
document.querySelectorAll("dialog").forEach((dlg) => {
    dlg.addEventListener("click", (event) => {
        if (event.target.closest("[data-close]") || event.target === dlg) dlg.close();
    });
});

function toast(message, kind = "ok") {
    toastBox.textContent = message;
    toastBox.className = `toast ${kind}`;
    toastBox.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastBox.hidden = true; }, 3500);
}

// ---------------------------------------------------------------- start

// The site header (tab "Nhật ngữ" highlighted), the overview, then the view of the address; Back/Forward redraw it
user = await mountSiteHeader("learn");
try {
    overview = await apiClient.phraseOverview();
    await show();
} catch (error) {
    content.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
}
window.addEventListener("popstate", () => show());
