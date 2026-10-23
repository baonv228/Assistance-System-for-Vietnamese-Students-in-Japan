// Compose a post or a question. Shared by two pages, which set <body data-kind="…">:
//   pages/blog-write.html  data-kind="POST"  Blog → "Viết bài"       (UC-11, SRS 6.2) → POST /api/blogs
//   pages/qa-ask.html      data-kind="QA"    Hỏi-Đáp → "Đặt câu hỏi" (SRS 6.3)        → POST /api/questions
// The browser checks the fields first; the server checks everything again in the agreed order
// (fields → links → new-account limit → banned keywords) and its message is shown here.
// Photos are shrunk to 1600 px and re-encoded as JPG in the browser (this also converts HEIC where the browser
// can open it and drops EXIF/GPS); the server re-encodes them again anyway.
// The same pages edit an existing post or question at /blog/{id}/edit and /qa/{id}/edit ("Sửa bài" / "Sửa câu hỏi"):
// the form is filled from GET …/{id}/edit and sent with PUT; attached photos/video can be removed (keepMediaIds).
import { apiClient, ApiError } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { isImageFile, isVideoFile, MB, shrinkToJpeg, unreadableImage, videoLength } from "../lib/media-files.js";
import { esc, ic, loginUrl } from "../lib/ui.js";

const QUESTION = document.body.dataset.kind === "QA";
const EDIT_ID = Number((location.pathname.match(/^\/(?:blog|qa)\/(\d+)\/edit\/?$/) || [])[1]) || null;
const EDITING = EDIT_ID !== null;

// Old links to blog-write.html?type=QA open the Hỏi-Đáp page
if (!QUESTION && !EDITING && (new URLSearchParams(location.search).get("type") || "").toUpperCase() === "QA") {
    location.replace("/qa/ask");
}

const ROUTES = QUESTION ? {
    list: "/qa",
    listName: "Hỏi-Đáp",
    detail: (id) => `/qa/${id}?${EDITING ? "edited" : "created"}=1`,
    back: EDITING ? `/qa/${EDIT_ID}` : "/qa",
    login: loginUrl,                     // back to this form after signing in
    newPin: "/coming-soon?f=map"         // Map → "Cắm ghim" (New Pin), not built yet
} : {
    list: "/blog",
    listName: "Blog",
    detail: (id) => `/blog/${id}?${EDITING ? "edited" : "created"}=1`,
    back: EDITING ? `/blog/${EDIT_ID}` : "/blog",
    login: loginUrl,
    newPin: "/coming-soon?f=map"
};
/** Each section has its own endpoints; the rules behind them are the same. */
const API = QUESTION
    ? {
        compose: apiClient.questionCompose, create: apiClient.askQuestion, part: "question",
        editData: apiClient.questionEditData, update: apiClient.updateQuestion
    }
    : {
        compose: apiClient.blogCompose, create: apiClient.createBlog, part: "post",
        editData: apiClient.blogEditData, update: apiClient.updateBlog
    };

const TEXT = QUESTION ? {
    page: "Đặt câu hỏi",
    sub: "Hỏi cộng đồng, các senpai đã trải qua sẽ trả lời bạn.",
    card: "Thông tin câu hỏi",
    title: "Câu hỏi",
    titlePlaceholder: "Ví dụ: Gia hạn visa du học cần mang những giấy tờ gì?",
    content: "Mô tả chi tiết",
    contentPlaceholder: "Thêm bối cảnh để mọi người trả lời đúng: bạn đang ở đâu, đã thử những gì",
    submit: "Đăng câu hỏi",
    titleMissing: "Hãy nhập câu hỏi.",
    contentMissing: "Hãy nhập mô tả chi tiết."
} : {
    page: "Viết bài",
    sub: "Chia sẻ kinh nghiệm thật để người đến sau đỡ bỡ ngỡ.",
    card: "Thông tin bài viết",
    title: "Tiêu đề",
    titlePlaceholder: "Ví dụ: Năm điều mình ước biết trước tuần đầu ở Tokyo",
    content: "Nội dung",
    contentPlaceholder: "Kể lại trải nghiệm của bạn: chuyện gì đã xảy ra, bạn làm thế nào, cần lưu ý gì",
    submit: "Đăng bài",
    titleMissing: "Hãy nhập tiêu đề.",
    contentMissing: "Hãy nhập nội dung bài viết."
};
if (EDITING) {
    Object.assign(TEXT, QUESTION
        ? { page: "Sửa câu hỏi", sub: "Sửa lại câu hỏi để mọi người hiểu đúng điều bạn cần.", submit: "Lưu thay đổi" }
        : { page: "Sửa bài", sub: "Cập nhật bài viết của bạn.", submit: "Lưu thay đổi" });
}

const MSG = {
    MSG37: "Hãy nhập tiêu đề và nội dung cho bài viết.",
    MSG17: "Không tải được dữ liệu. Vui lòng thử lại.",
    MSG18: "Bạn không có quyền thực hiện thao tác này.",
    MSG19: "Không tìm thấy nội dung bạn yêu cầu. Bài viết có thể đã bị gỡ hoặc đã bị xóa.",
    MSG14: "Đã có lỗi xảy ra. Vui lòng thử lại sau.",
    IMAGE_RULE: "Tối đa 5 ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá 20 MB.",
    TOPICS: "Hãy chọn ít nhất 1 chủ đề. Không có chủ đề phù hợp thì chọn “Khác”."
};
const SEARCH_DELAY = 250;

/** Which control to focus for each field the server may complain about. */
const FIELD_INPUT = {
    title: "#title", topicIds: "#topic-q", placeIds: "#place-q", content: "#content", images: "#add-image",
    video: "#add-video"
};

const $ = (selector) => document.querySelector(selector);
const els = {
    gate: $("#gate"), form: $("#form"), formError: $("#form-error"), quota: $("#quota"),
    title: $("#title"), titleCount: $("#title-count"),
    topicQ: $("#topic-q"), topicList: $("#topic-list"), topicsCount: $("#topics-count"),
    placeQ: $("#place-q"), placeList: $("#place-list"), placeChips: $("#place-chips"), placesCount: $("#places-count"),
    scamHint: $("#scam-hint"),
    content: $("#content"), contentCount: $("#content-count"), linkNote: $("#link-note"),
    mediaPicks: $("#media-picks"), imageInput: $("#image-input"), videoInput: $("#video-input"),
    addImage: $("#add-image"), addVideo: $("#add-video"), imagesCount: $("#images-count"),
    mediaRules: $("#media-rules"), videoNote: $("#video-note"),
    submit: $("#submit"), submitLabel: $("#submit-label"), leaveDlg: $("#leave-dlg")
};

let options = null;          // ComposeOptions from GET /api/blogs/compose
const state = {
    topicIds: [],
    places: [],              // [{ key, id, externalId, name, category, source }]; id null = new OpenStreetMap place
    images: [],              // [{ blob, url, name }] already shrunk to JPG
    video: null,             // { file, url, seconds }
    existing: []             // editing: attached media still kept [{ id, type, url, scanStatus }]
};
let hadVideo = false;        // editing: the post had a video before (replacing it is not a new video for the limit)
let initial = null;          // editing: the form as loaded, to tell whether something changed
let submitting = false;
let leaving = false;         // set once the post is saved, so the leave guard steps aside
let pendingHref = null;
let processingImages = 0;

const existingImages = () => state.existing.filter((m) => m.type === "IMAGE");
const existingVideo = () => state.existing.find((m) => m.type === "VIDEO") || null;
const imageCount = () => existingImages().length + state.images.length;

// ---------------------------------------------------------------- start

function applyText() {
    if (EDITING) {
        document.querySelectorAll("a[data-leave]").forEach((a) => { a.href = ROUTES.back; });
    }
    document.title = `${TEXT.page} – Senpai ơi`;
    $("#page-title").textContent = TEXT.page;
    $("#page-sub").textContent = TEXT.sub;
    $("#info-title").textContent = TEXT.card;
    $("#title-label").textContent = TEXT.title;
    $("#content-label").textContent = TEXT.content;
    els.title.placeholder = TEXT.titlePlaceholder;
    els.content.placeholder = TEXT.contentPlaceholder;
    els.submitLabel.textContent = TEXT.submit;
}

async function start() {
    applyText();
    const user = await mountSiteHeader(QUESTION ? "qa" : "blog");
    if (!user) {
        renderGate("login", `Bạn cần đăng nhập để ${QUESTION ? "đặt câu hỏi" : "viết bài"}.`,
            `<a class="btn pri" href="${esc(ROUTES.login())}">${ic("login")}Đăng nhập</a>`);
        return;
    }
    await loadOptions();
}

/** Topics, limits and today's usage; "Thử lại" calls this again (the header is mounted only once). */
async function loadOptions() {
    let post = null;
    try {
        if (EDITING) {
            post = await API.editData(EDIT_ID);
            options = post.options;
        } else {
            options = await API.compose();
        }
    } catch (error) {
        if (error.status === 401) {
            renderGate("login", `Bạn cần đăng nhập để ${QUESTION ? "đặt câu hỏi" : "viết bài"}.`,
                `<a class="btn pri" href="${esc(ROUTES.login())}">${ic("login")}Đăng nhập</a>`);
        } else if (error.status === 403) {
            renderGate("block", MSG.MSG18, `<a class="btn" href="${ROUTES.list}">Về ${ROUTES.listName}</a>`);
        } else if (error.status === 404) {
            renderGate("block", MSG.MSG19, `<a class="btn" href="${ROUTES.list}">Về ${ROUTES.listName}</a>`);
        } else {
            renderGate("error", MSG.MSG17, `<button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button>`);
        }
        return;
    }
    els.gate.hidden = true;
    els.form.hidden = false;
    if (post) fillForm(post);
    setupForm();
    if (post) {
        renderEditStatus(post.status);
        initial = snapshot();
    }
}

/** Editing: puts the post as it is now into the form. */
function fillForm(post) {
    els.title.value = post.title || "";
    els.content.value = post.content || "";
    state.topicIds = [...post.topicIds];
    state.places = post.places.map((p) => ({
        key: placeKey(p), id: p.id, externalId: null, name: p.name, category: p.category, source: null
    }));
    state.existing = post.media.map((m) => ({ id: m.id, type: m.type, url: m.url, scanStatus: m.scanStatus }));
    hadVideo = Boolean(existingVideo());
}

/** Editing a post that is not public yet: say why and what saving does. */
function renderEditStatus(status) {
    const note = {
        PROCESSING: `Ảnh và video đang được quét; trong lúc này chỉ bạn thấy ${QUESTION ? "câu hỏi" : "bài"}.`,
        REJECTED: `${QUESTION ? "Câu hỏi" : "Bài"} chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng `
            + "(đã được gỡ). Sửa xong bấm “Lưu thay đổi” để đăng lại."
    }[status];
    if (!note) return;
    els.quota.className = `alert quota ${status === "REJECTED" ? "full" : ""}`;
    els.quota.innerHTML = `${ic(status === "REJECTED" ? "block" : "hourglass_top")}<span>${esc(note)}</span>`;
    els.quota.hidden = false;
}

function renderGate(kind, message, action) {
    const icon = { login: "lock", block: "block", error: "cloud_off" }[kind];
    els.gate.innerHTML = `<div class="state ${kind === "error" ? "error" : ""}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(icon, "big")}<p>${esc(message)}</p>${action}</div>`;
}

els.gate.addEventListener("click", (event) => {
    if (event.target.closest("[data-retry]")) {
        els.gate.innerHTML = `<p class="note">Đang tải…</p>`;
        loadOptions();
    }
});

function setupForm() {
    const { limits, quota, canPostLinks } = options;
    els.title.maxLength = limits.titleMax;
    els.content.maxLength = limits.contentMax;
    const contentRequired = !QUESTION || limits.questionContentRequired;
    $("#content-req").hidden = !contentRequired;
    els.content.required = contentRequired;
    if (!contentRequired) {
        $("#content-label").insertAdjacentHTML("afterend", ` <span class="note">(không bắt buộc)</span>`);
    }
    els.linkNote.innerHTML = canPostLinks
        ? `${ic("link")} Bạn có tag định danh nên được chèn link. Không dùng link rút gọn (bit.ly, tinyurl…).`
        : `${ic("link_off")} Chỉ tài khoản đã có tag định danh mới được chèn link, ghi số điện thoại hoặc tài khoản Zalo, LINE, Telegram.`;
    els.mediaRules.textContent = `Ảnh JPG, PNG hoặc HEIC, tối đa ${limits.imagesMax} ảnh, mỗi ảnh không quá ${limits.imageMaxMb} MB. `
        + `Video MP4 hoặc MOV, tối đa ${limits.videoMaxSeconds} giây và ${limits.videoMaxMb} MB.`;
    renderQuota(quota);
    renderTopics();
    renderPlaceChips();
    renderMedia();
    updateCounters();
}

/** "Tài khoản mới, hôm nay: …" (BR-07), only during the first days of the account. */
function renderQuota(quota) {
    // An edit is not a new post: no daily-limit note (a new video is still checked when sent)
    if (!quota.newAccount || EDITING) {
        els.quota.hidden = true;
        return;
    }
    const full = quota.postsUsed >= quota.postsLimit;
    els.quota.className = `alert quota ${full ? "full" : ""}`;
    els.quota.innerHTML = `${ic(full ? "block" : "info")}
        <span>Tài khoản mới, hôm nay: <b>${quota.postsUsed}/${quota.postsLimit}</b> bài viết và review ·
        <b>${quota.pinsUsed}/${quota.pinsLimit}</b> ghim · <b>${quota.videosUsed}/${quota.videosLimit}</b> video.
        ${full ? " Bạn đã dùng hết lượt đăng hôm nay, hãy quay lại vào ngày mai." : ""}</span>`;
    els.quota.hidden = false;
}

// ---------------------------------------------------------------- title and content

function updateCounters() {
    els.titleCount.textContent = `${els.title.value.length}/${options.limits.titleMax}`;
    els.contentCount.textContent = `${els.content.value.length}/${options.limits.contentMax}`;
}

els.title.addEventListener("input", () => {
    updateCounters();
    clearFieldError("title");
});
els.content.addEventListener("input", () => {
    updateCounters();
    clearFieldError("content");
});

// Enter in a one-line box must not send the form by accident
els.form.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && event.target.tagName === "INPUT") {
        event.preventDefault();
    }
});

// ---------------------------------------------------------------- topics (1 to 3)

const fold = (text) => String(text ?? "").normalize("NFD").replace(/\p{M}+/gu, "")
    .replace(/đ/g, "d").replace(/Đ/g, "D").toLowerCase().trim();

function renderTopics() {
    const max = options.limits.topicsMax;
    const q = fold(els.topicQ.value);
    const shown = options.topics.filter((t) => !q || fold(t.name).includes(q) || state.topicIds.includes(t.id));
    const full = state.topicIds.length >= max;
    els.topicList.innerHTML = shown.length ? shown.map((t) => {
        const on = state.topicIds.includes(t.id);
        const blocked = full && !on;
        return `<button class="topic-pick${t.other ? " other" : ""}" type="button" data-topic="${t.id}"
            aria-pressed="${on}" ${blocked ? `aria-disabled="true" title="Đã chọn đủ ${max} chủ đề"` : ""}>
            ${on ? ic("check") : ""}${esc(t.name)}</button>`;
    }).join("") : `<p class="note">Không có chủ đề “${esc(els.topicQ.value.trim())}”. Bạn có thể chọn “Khác”.</p>`;
    els.topicsCount.textContent = `(${state.topicIds.length}/${max})`;
    els.scamHint.hidden = !options.topics.some((t) => t.scamWarning && state.topicIds.includes(t.id));
}

els.topicQ.addEventListener("input", renderTopics);
els.topicList.addEventListener("click", (event) => {
    const button = event.target.closest("[data-topic]");
    if (!button || button.getAttribute("aria-disabled") === "true") {
        return;
    }
    const id = Number(button.dataset.topic);
    state.topicIds = state.topicIds.includes(id)
        ? state.topicIds.filter((x) => x !== id)
        : [...state.topicIds, id];
    clearFieldError("topicIds");
    renderTopics();
    els.topicList.querySelector(`[data-topic="${id}"]`)?.focus();
});

// ---------------------------------------------------------------- related places (up to 5)
// One search box for ASVSJ places (places already in ASVSJ, team places, approved community pins) and OpenStreetMap
// places in Japan and Vietnam (GET /api/places/search). An OpenStreetMap place ASVSJ does not have yet has no id: it
// is sent as externalPlaceIds and the server adds it to ASVSJ when the post is saved.

const SOURCE = {
    OSM: ["OpenStreetMap", "Địa điểm từ OpenStreetMap"],
    GOOGLE: ["Google Maps", "Địa điểm lưu từ Google Maps"],
    TEAM: ["Nhóm ASVSJ", "Nhóm ASVSJ thêm"],
    MODERATOR: ["Nhóm ASVSJ", "Nhóm ASVSJ thêm"],
    USER: ["Ghim cộng đồng", "Ghim cộng đồng (đã được Moderator duyệt)"]
};
const MAP_NOTE = {
    OFF: "Chưa bật tìm trên OpenStreetMap: đang hiện địa điểm có trên Senpai ơi.",
    SHORT: "Gõ thêm chữ để tìm cả trên OpenStreetMap.",
    UNAVAILABLE: "OpenStreetMap tạm thời không phản hồi: đang hiện địa điểm có trên Senpai ơi.",
    BUSY: "Bạn tìm hơi nhanh. Đợi một chút rồi gõ tiếp để tìm cả trên OpenStreetMap."
};
const SEARCH_TIP = "Mẹo: địa điểm ở Nhật hãy gõ tên tiếng Anh/romaji (Ueno, Lawson) hoặc tiếng Nhật (上野); "
    + "gõ “ga Ueno” để tìm nhà ga.";
const placeKey = (p) => (p.id ? `p${p.id}` : `m${p.externalId}`);

let placeResults = [];       // ASVSJ results first, then new OpenStreetMap places, in the order shown
let placeMap = "ON";         // what happened with OpenStreetMap for the last search (see PlaceSearch.external)
let placeMapReason = null;   // why it did not answer (LIMITED, TIMEOUT, SSL…), shown so the team knows what to fix
let activePlace = -1;
let placeTimer = null;
let placeSearch = 0;
const placeCache = new Map(); // text → answer, so typing back and forth does not ask again

els.placeQ.addEventListener("input", () => {
    clearTimeout(placeTimer);
    // The list on screen belongs to the previous text: Enter must not pick from it, it is shown as out of date, and an
    // answer still on its way for that text is ignored. Without this, an answer arriving during the pause before the
    // new search (longer in a background tab) was drawn as if it were current and selected its first place again.
    placeSearch++;
    activePlace = -1;
    syncActivePlace();
    if (!els.placeList.hidden) els.placeList.setAttribute("aria-busy", "true");
    placeTimer = setTimeout(searchPlaces, SEARCH_DELAY);
});

async function searchPlaces() {
    const q = els.placeQ.value.trim();
    const ticket = ++placeSearch;
    if (!q) {
        closePlaceList();
        return;
    }
    const cacheKey = q.toLowerCase();
    try {
        let found = placeCache.get(cacheKey);
        if (!found) {
            found = await apiClient.searchPlaces(q);
            // Keep only complete answers; a busy or failed map service may answer next time
            if (["ON", "OFF", "SHORT"].includes(found.external)) placeCache.set(cacheKey, found);
        }
        if (ticket !== placeSearch) return;            // an older answer arriving late
        const chosen = new Set(state.places.map(placeKey));
        const chosenMap = new Set(state.places.map((p) => p.externalId).filter(Boolean));
        const fresh = found.items.filter((p) => !chosen.has(placeKey(p))
            && !(p.externalId && chosenMap.has(p.externalId)));
        placeResults = [...fresh.filter((p) => p.id), ...fresh.filter((p) => !p.id)];
        placeMap = found.external;
        placeMapReason = found.externalReason || null;
        activePlace = placeResults.length ? 0 : -1;
        renderPlaceList(q, false);
    } catch (error) {
        if (ticket === placeSearch) {
            placeResults = [];
            renderPlaceList(q, true, error instanceof ApiError && error.status === 401);
        }
    }
}

function placeOption(p, i) {
    const fromMap = !p.id;
    const line = fromMap
        ? [p.category, p.address]
        : [p.category, p.nameJa && p.nameJa !== p.name ? p.nameJa : null, p.address];
    const source = SOURCE[p.source] || SOURCE.TEAM;
    return `<li role="option" id="place-opt-${i}" data-index="${i}" aria-selected="${i === activePlace}">
            ${ic(fromMap ? "travel_explore" : "location_on")}<span><b>${esc(p.name)}</b>
            <small>${esc(line.filter(Boolean).join(" · "))}</small></span>
            ${fromMap ? "" : `<em class="src src-${esc(String(p.source).toLowerCase())}" title="${esc(source[1])}">${esc(source[0])}</em>`}
        </li>`;
}

function renderPlaceList(q, failed, signedOut = false) {
    const asvsj = placeResults.filter((p) => p.id);
    const fromMap = placeResults.filter((p) => !p.id);
    let html = "";
    if (failed) {
        html = `<li class="combo-empty">${signedOut
            ? "Phiên đăng nhập đã hết. Hãy đăng nhập lại để tìm địa điểm."
            : "Không tìm được địa điểm lúc này. Thử lại sau."}</li>`;
    } else if (!placeResults.length) {
        html = `<li class="combo-empty">${placeMap === "ON"
            ? `Không tìm thấy “${esc(q)}” trên Senpai ơi và OpenStreetMap.<br><small>${esc(SEARCH_TIP)}</small>`
            : `Không tìm thấy “${esc(q)}” trong dữ liệu Senpai ơi.`}</li>`;
    } else {
        if (asvsj.length) {
            html += `<li class="combo-group" role="presentation">Trên Senpai ơi</li>`;
            html += asvsj.map((p, i) => placeOption(p, i)).join("");
        }
        if (fromMap.length) {
            html += `<li class="combo-group" role="presentation">Từ OpenStreetMap <span>· chưa có trên Senpai ơi, sẽ được thêm khi bạn đăng</span></li>`;
            html += fromMap.map((p, i) => placeOption(p, asvsj.length + i)).join("");
        }
    }
    if (!failed) {
        let note = MAP_NOTE[placeMap];
        if (placeMap === "UNAVAILABLE" && placeMapReason === "LIMITED") {
            note = "OpenStreetMap đang bận, hãy thử lại sau ít phút: đang hiện địa điểm có trên Senpai ơi.";
        } else if (note && placeMap === "UNAVAILABLE" && placeMapReason) {
            note = note.replace(":", ` (mã lỗi ${placeMapReason}):`);
        }
        html += `<li class="combo-foot" role="presentation">
            ${note ? `<p class="combo-note">${ic("info")}${esc(note)}</p>` : ""}
            <p>Chưa có trên bản đồ? <a href="${ROUTES.newPin}" target="_blank" rel="noopener">Cắm ghim mới trên Bản đồ</a></p>
            ${fromMap.length ? `<p class="combo-attrib">Dữ liệu bản đồ © <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a></p>` : ""}
        </li>`;
    }
    const opening = els.placeList.hidden;
    els.placeList.innerHTML = html;
    els.placeList.removeAttribute("aria-busy");
    els.placeList.hidden = false;
    els.placeQ.setAttribute("aria-expanded", "true");
    syncActivePlace();
    if (opening) revealPlaceList();
}

/** On a short screen the list may open below the fold or under the phone tab bar: scroll it up, keeping the box. */
function revealPlaceList() {
    const list = els.placeList.getBoundingClientRect();
    const bar = document.querySelector(".tabbar");
    const bottom = bar && getComputedStyle(bar).display !== "none" ? bar.getBoundingClientRect().top : innerHeight;
    const header = document.querySelector(".site-top")?.getBoundingClientRect().bottom || 0;
    const room = els.placeQ.getBoundingClientRect().top - header - 8;
    const need = list.bottom - (bottom - 8);
    if (need > 0 && room > 0) window.scrollBy({ top: Math.min(need, room) });
}

function syncActivePlace() {
    els.placeList.querySelectorAll("[role=option]").forEach((li) => {
        const on = Number(li.dataset.index) === activePlace;
        li.setAttribute("aria-selected", String(on));
        if (on) li.scrollIntoView({ block: "nearest" });
    });
    if (placeResults[activePlace]) {
        els.placeQ.setAttribute("aria-activedescendant", `place-opt-${activePlace}`);
    } else {
        els.placeQ.removeAttribute("aria-activedescendant");
    }
}

function closePlaceList() {
    clearTimeout(placeTimer);
    placeSearch++;                                      // answers still on their way are ignored
    els.placeList.hidden = true;
    els.placeList.innerHTML = "";
    els.placeList.removeAttribute("aria-busy");
    els.placeQ.setAttribute("aria-expanded", "false");
    els.placeQ.removeAttribute("aria-activedescendant");
    placeResults = [];
    activePlace = -1;
}

function choosePlace(place) {
    if (!place || state.places.length >= options.limits.placesMax) return;
    state.places.push({
        key: placeKey(place), id: place.id || null, externalId: place.externalId || null,
        name: place.name, category: place.category, source: place.source
    });
    els.placeQ.value = "";
    closePlaceList();
    clearFieldError("placeIds");
    renderPlaceChips();
    if (!els.placeQ.disabled) els.placeQ.focus();
}

els.placeQ.addEventListener("keydown", (event) => {
    if (els.placeList.hidden) {
        if (event.key === "ArrowDown" && els.placeQ.value.trim()) searchPlaces();
        return;
    }
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        if (!placeResults.length) return;
        const step = event.key === "ArrowDown" ? 1 : -1;
        activePlace = (activePlace + step + placeResults.length) % placeResults.length;
        syncActivePlace();
    } else if (event.key === "Enter") {
        event.preventDefault();
        choosePlace(placeResults[activePlace]);
    } else if (event.key === "Escape") {
        event.preventDefault();
        closePlaceList();
    }
});
// mousedown keeps the focus in the box, so blur does not close the list before the click lands (the link to the
// Map is the exception: it must get its click)
els.placeList.addEventListener("mousedown", (event) => {
    if (!event.target.closest("a")) event.preventDefault();
});
els.placeList.addEventListener("click", (event) => {
    const li = event.target.closest("[role=option]");
    if (li) choosePlace(placeResults[Number(li.dataset.index)]);
});
// The list closes when the focus leaves the whole box (input, list, or the Map link inside the list)
const placeCombo = els.placeQ.closest(".combo");
placeCombo.addEventListener("focusout", () => setTimeout(() => {
    if (!placeCombo.contains(document.activeElement)) closePlaceList();
}, 150));
els.placeList.addEventListener("keydown", (event) => {
    if (event.key === "Escape") {
        closePlaceList();
        els.placeQ.focus();
    }
});

function renderPlaceChips() {
    const max = options.limits.placesMax;
    els.placeChips.innerHTML = state.places.map((p) => {
        const fromMap = !p.id;
        const title = fromMap ? "Địa điểm OpenStreetMap, sẽ được thêm vào Senpai ơi khi bạn đăng"
            : p.source ? (SOURCE[p.source] || SOURCE.TEAM)[1] : "Địa điểm trên Senpai ơi";
        return `<span class="badge place-chip${fromMap ? " from-map" : ""}" title="${esc(title)}">${ic(fromMap ? "travel_explore" : "location_on")}${esc(p.name)}
            <button type="button" data-remove-place="${esc(p.key)}" aria-label="Bỏ địa điểm ${esc(p.name)}">${ic("close")}</button>
        </span>`;
    }).join("");
    els.placeChips.hidden = !state.places.length;
    els.placesCount.textContent = `(${state.places.length}/${max})`;
    const full = state.places.length >= max;
    els.placeQ.disabled = full;
    els.placeQ.placeholder = full ? `Đã chọn đủ ${max} địa điểm` : "Tìm trên Senpai ơi và OpenStreetMap (không bắt buộc)";
}

els.placeChips.addEventListener("click", (event) => {
    const button = event.target.closest("[data-remove-place]");
    if (!button) return;
    const key = button.dataset.removePlace;
    state.places = state.places.filter((p) => p.key !== key);
    renderPlaceChips();
    els.placeQ.focus();
});

// ---------------------------------------------------------------- photos and video

els.addImage.addEventListener("click", () => els.imageInput.click());
els.addVideo.addEventListener("click", () => els.videoInput.click());
els.imageInput.addEventListener("change", () => addImages([...els.imageInput.files]));
els.videoInput.addEventListener("change", () => addVideo(els.videoInput.files[0]));

// Replacing the video of a post that already had one is not a new video for the new-account limit
const videoQuotaUsed = () => options.quota.newAccount && options.quota.videosUsed >= options.quota.videosLimit
    && !hadVideo;

async function addImages(files) {
    els.imageInput.value = "";
    clearFieldError("images");
    const { imagesMax, imageMaxMb } = options.limits;
    const room = imagesMax - imageCount() - processingImages;
    if (files.length > room) {
        fieldError("images", MSG.IMAGE_RULE);
        files = files.slice(0, Math.max(0, room));
    }
    processingImages += files.length;
    renderMedia();
    for (const file of files) {
        const typeOk = isImageFile(file);
        try {
            if (!typeOk || file.size > imageMaxMb * MB) {
                fieldError("images", MSG.IMAGE_RULE);
                continue;
            }
            const blob = await shrinkToJpeg(file);
            if (imageCount() < imagesMax) {
                state.images.push({ blob, url: URL.createObjectURL(blob), name: file.name });
            }
        } catch {
            fieldError("images", unreadableImage(file));
        } finally {
            processingImages--;
            renderMedia();
        }
    }
}

async function addVideo(file) {
    els.videoInput.value = "";
    clearFieldError("video");
    if (!file) return;
    const { videoMaxMb, videoMaxSeconds } = options.limits;
    const typeOk = isVideoFile(file);
    if (!typeOk || file.size > videoMaxMb * MB) {
        fieldError("video", `Video phải là MP4 hoặc MOV, dài tối đa ${videoMaxSeconds} giây và không quá ${videoMaxMb} MB.`);
        return;
    }
    const url = URL.createObjectURL(file);
    const seconds = await videoLength(url);
    if (seconds !== null && seconds > videoMaxSeconds + 0.5) {
        URL.revokeObjectURL(url);
        fieldError("video", `Video dài ${Math.round(seconds)} giây. Video tối đa ${videoMaxSeconds} giây.`);
        return;
    }
    if (state.video) URL.revokeObjectURL(state.video.url);
    state.video = { file, url, seconds };
    renderMedia();
}

function renderMedia() {
    const { imagesMax } = options.limits;
    const scanning = (m) => (m.scanStatus === "PROCESSING"
        ? `<span class="badge pick-tag">${ic("hourglass_top")}Đang quét</span>` : "");
    const kept = existingImages();
    const items = kept.map((m, i) => `
        <li class="pick">
            <img src="${esc(m.url)}" alt="Ảnh ${i + 1} đã đăng">${scanning(m)}
            <button class="pick-remove" type="button" data-remove-existing="${m.id}" aria-label="Bỏ ảnh ${i + 1}">${ic("close")}</button>
        </li>`);
    items.push(...state.images.map((img, i) => `
        <li class="pick">
            <img src="${img.url}" alt="Ảnh ${kept.length + i + 1}: ${esc(img.name)}">
            <button class="pick-remove" type="button" data-remove-image="${i}" aria-label="Bỏ ảnh ${kept.length + i + 1}">${ic("close")}</button>
        </li>`));
    for (let i = 0; i < processingImages; i++) {
        items.push(`<li class="pick busy" aria-label="Đang xử lý ảnh">${ic("hourglass_top")}</li>`);
    }
    const oldVideo = existingVideo();
    if (oldVideo) {
        items.push(`<li class="pick video">
            <video src="${esc(oldVideo.url)}" muted playsinline preload="metadata" aria-label="Video đã đăng"></video>
            ${scanning(oldVideo) || `<span class="badge pick-tag">${ic("videocam")}Video</span>`}
            <button class="pick-remove" type="button" data-remove-existing="${oldVideo.id}" aria-label="Bỏ video">${ic("close")}</button>
        </li>`);
    }
    if (state.video) {
        const length = state.video.seconds === null ? "" : `${Math.round(state.video.seconds)} giây`;
        items.push(`<li class="pick video">
            <video src="${state.video.url}" muted playsinline preload="metadata" aria-label="Video đã chọn"></video>
            <span class="badge pick-tag">${ic("videocam")}${length || "Video"}</span>
            <button class="pick-remove" type="button" data-remove-video aria-label="Bỏ video">${ic("close")}</button>
        </li>`);
    }
    els.mediaPicks.innerHTML = items.join("");
    els.mediaPicks.hidden = !items.length;
    els.imagesCount.textContent = `${imageCount()}/${imagesMax}`;
    els.addImage.disabled = imageCount() + processingImages >= imagesMax;
    const videoBlocked = videoQuotaUsed();
    els.addVideo.disabled = Boolean(state.video || oldVideo) || videoBlocked;
    els.videoNote.hidden = !videoBlocked && !(state.video && state.video.seconds === null);
    els.videoNote.textContent = videoBlocked
        ? `Tài khoản mới chỉ đăng ${options.quota.videosLimit} video mỗi ngày. Hôm nay bạn đã dùng lượt này.`
        : "Trình duyệt không xem trước được video này; độ dài sẽ được kiểm tra khi đăng.";
}

els.mediaPicks.addEventListener("click", (event) => {
    const old = event.target.closest("[data-remove-existing]");
    if (old) {
        const id = Number(old.dataset.removeExisting);
        const removed = state.existing.find((m) => m.id === id);
        state.existing = state.existing.filter((m) => m.id !== id);
        clearFieldError(removed?.type === "VIDEO" ? "video" : "images");
        renderMedia();
        (removed?.type === "VIDEO" ? els.addVideo : els.addImage).focus();
        return;
    }
    const image = event.target.closest("[data-remove-image]");
    if (image) {
        const [removed] = state.images.splice(Number(image.dataset.removeImage), 1);
        if (removed) URL.revokeObjectURL(removed.url);
        clearFieldError("images");
        renderMedia();
        els.addImage.focus();
        return;
    }
    if (event.target.closest("[data-remove-video]")) {
        URL.revokeObjectURL(state.video.url);
        state.video = null;
        clearFieldError("video");
        renderMedia();
        els.addVideo.focus();
    }
});

// ---------------------------------------------------------------- errors

function fieldError(field, message) {
    const box = $(`#${field}-error`);
    if (box) {
        box.textContent = message;
        box.hidden = false;
    }
    $(FIELD_INPUT[field])?.setAttribute("aria-invalid", "true");
}

function clearFieldError(field) {
    const box = $(`#${field}-error`);
    if (box) {
        box.hidden = true;
        box.textContent = "";
    }
    $(FIELD_INPUT[field])?.removeAttribute("aria-invalid");
}

function clearErrors() {
    Object.keys(FIELD_INPUT).forEach(clearFieldError);
    els.formError.hidden = true;
    els.formError.textContent = "";
}

function showFormError(message) {
    els.formError.innerHTML = `${ic("error")} <span>${esc(message)}</span>`;
    els.formError.hidden = false;
    els.formError.scrollIntoView({ block: "center", behavior: "smooth" });
    els.formError.focus({ preventScroll: true });
}

/** Field messages under each box, the summary above the form, and the focus on the first wrong field. */
function showErrors(fields, summary) {
    Object.entries(fields).forEach(([field, message]) => fieldError(field, message));
    if (summary) {
        els.formError.innerHTML = `${ic("error")} <span>${esc(summary)}</span>`;
        els.formError.hidden = false;
    }
    const first = Object.keys(FIELD_INPUT).find((field) => field in fields);
    const target = first ? $(FIELD_INPUT[first]) : els.formError;
    target?.scrollIntoView({ block: "center", behavior: "smooth" });
    target?.focus({ preventScroll: true });
}

/** Same rules as the server's step 1 (BlogWriteRules), so most mistakes are caught before uploading. */
function validate() {
    const L = options.limits;
    const errors = {};
    const title = els.title.value.replace(/\s+/g, " ").trim();
    const content = els.content.value.trim();
    if (!title) {
        errors.title = TEXT.titleMissing;
    } else if (title.length > L.titleMax) {
        errors.title = `${TEXT.title} tối đa ${L.titleMax} ký tự.`;
    }
    if (!state.topicIds.length) {
        errors.topicIds = MSG.TOPICS;
    } else if (state.topicIds.length > L.topicsMax) {
        errors.topicIds = `Chọn tối đa ${L.topicsMax} chủ đề.`;
    }
    if (state.places.length > L.placesMax) {
        errors.placeIds = `Chọn tối đa ${L.placesMax} địa điểm.`;
    }
    const contentRequired = !QUESTION || L.questionContentRequired;
    if (!content && contentRequired) {
        errors.content = TEXT.contentMissing;
    } else if (content.length > L.contentMax) {
        errors.content = `${TEXT.content} tối đa 5.000 ký tự.`;
    }
    return errors;
}

function summaryOf(errors) {
    if (!QUESTION && (errors.title === TEXT.titleMissing || errors.content === TEXT.contentMissing)) {
        return MSG.MSG37;
    }
    return Object.values(errors)[0];
}

// ---------------------------------------------------------------- send

els.form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting) return;
    if (processingImages > 0) {
        showFormError("Ảnh vẫn đang được xử lý. Đợi vài giây rồi bấm Đăng lại.");
        return;
    }
    clearErrors();
    const errors = validate();
    if (Object.keys(errors).length) {
        showErrors(errors, summaryOf(errors));
        return;
    }

    const body = {
        title: els.title.value.replace(/\s+/g, " ").trim(),
        content: els.content.value.trim(),
        topicIds: state.topicIds,
        placeIds: state.places.filter((p) => p.id).map((p) => p.id),
        externalPlaceIds: state.places.filter((p) => !p.id).map((p) => p.externalId)
    };
    if (EDITING) {
        // Photos first, then the video, as the post shows them
        body.keepMediaIds = [...existingImages(), ...(existingVideo() ? [existingVideo()] : [])].map((m) => m.id);
    }
    const data = new FormData();
    data.append(API.part, new Blob([JSON.stringify(body)], { type: "application/json" }));
    state.images.forEach((img, i) => data.append("images", img.blob, `anh-${i + 1}.jpg`));
    if (state.video) data.append("video", state.video.file, state.video.file.name);

    setBusy(true);
    try {
        const created = EDITING ? await API.update(EDIT_ID, data) : await API.create(data);
        leaving = true;
        location.href = ROUTES.detail(created.id);
    } catch (error) {
        setBusy(false);
        if (!(error instanceof ApiError)) {
            showFormError(MSG.MSG14);
        } else if (error.status === 401) {
            showFormError("Phiên đăng nhập đã hết. Hãy đăng nhập lại ở thẻ khác; nội dung bạn viết vẫn còn trên trang này.");
        } else if (Object.keys(error.fields || {}).length) {
            showErrors(error.fields, error.message);
        } else {
            showFormError(error.message || MSG.MSG14);   // links, daily limit (MSG44), banned keyword (MSG36)
        }
    }
});

function setBusy(on) {
    submitting = on;
    els.submit.disabled = on;
    els.submitLabel.textContent = on
        ? (state.images.length || state.video ? "Đang tải lên và kiểm tra…" : "Đang kiểm tra nội dung…")
        : TEXT.submit;
}

// ---------------------------------------------------------------- leaving with unsaved changes (MSG15)

/** Editing: what the author can change, to compare with the post as it was loaded. */
function snapshot() {
    return JSON.stringify([els.title.value.trim(), els.content.value.trim(), state.topicIds, state.places.map(placeKey),
        state.existing.map((m) => m.id), state.images.length, Boolean(state.video)]);
}

function isDirty() {
    if (leaving || !options) return false;
    if (EDITING) return initial !== null && snapshot() !== initial;
    return Boolean(els.title.value.trim() || els.content.value.trim() || state.topicIds.length
        || state.places.length || state.images.length || state.video);
}

document.addEventListener("click", (event) => {
    const link = event.target.closest("a[href]");
    if (!link || !isDirty() || link.target === "_blank" || event.button !== 0
        || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
        return;
    }
    event.preventDefault();
    pendingHref = link.href;
    els.leaveDlg.showModal();
}, true);

els.leaveDlg.addEventListener("click", (event) => {
    if (event.target.closest("[data-close]")) {
        els.leaveDlg.close();
    }
});
$("#leave-ok").addEventListener("click", () => {
    leaving = true;
    els.leaveDlg.close();
    location.href = pendingHref || ROUTES.list;
});

// Closing the tab, reloading or typing another address: the browser shows its own warning
window.addEventListener("beforeunload", (event) => {
    if (isDirty()) {
        event.preventDefault();
        event.returnValue = "";
    }
});

start();
