// Pages of the Academic Manager (Biên tập viên nội dung tiếng Nhật), one script for all of them; <body data-view> picks the page:
// overview (Tổng quan), phrases (Thư viện mẫu câu, UC 12–14), learn-topics (Chủ đề học, UC 9–11), phrase-reports
// (Báo cáo mẫu câu, UC 13 AF1/AF2). API data goes into HTML only through esc().
//
// Every page is drawn into `main` = the right-hand column of components/staff-desk.js (top bar + side menu "Biên tập
// nội dung tiếng Nhật"), started at the bottom of this file. Styles: assets/css/academic.css.
//   /academic                  overview()       pages/academic/index.html
//     tiles "Báo cáo mẫu câu" / "Mục trong thư viện" → their pages; "Danh mục chưa có mẫu câu" (link = add one there);
//     "Chủ đề học còn ít câu"; "Thao tác gần đây của bạn" (activity log)
//   /academic/phrases          phrases()        pages/academic/phrases.html
//     filters (.mod-filters: category, topic, kind, search) → loadLibrary() fills the table #lib-rows and #pager;
//     "Sửa" / "Xóa" in a row → onLibraryClick(); the form under the table #phrase-form → renderForm() (add, or edit
//     after "Sửa"; ?edit=&case= from a report), onFormInput / onFormClick / savePhrase()
//   /academic/topics           learnTopics()    pages/academic/topics.html
//     the table #topic-rows → loadTopics(), "Sửa" / "Xóa" → onTopicRowClick(); the form #topic-form (name + icon
//     buttons) → renderTopicForm(), saveTopic()
//   /academic/phrase-reports   phraseReports()  pages/academic/phrase-reports.html
//     one card per open report (#cases) → loadPhraseReports(): reasons and reporters, "Sửa mẫu câu này" (opens the
//     library form with ?edit=&case=), "Không sai, bỏ qua" [data-dismiss]
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic } from "../lib/ui.js";

const KIND = { PHRASE: "Mẫu câu", VOCAB: "Từ vựng" };
/** Vietnamese names of the topic icons (tooltips of the "Biểu tượng" buttons); the list itself is AcademicService.ICONS. */
const ICON_LABELS = {
    label: "Nhãn", payments: "Thanh toán", help: "Hỏi", restaurant_menu: "Gọi món", train: "Tàu", medical_services: "Y tế",
    description: "Giấy tờ", waving_hand: "Chào hỏi", home: "Nhà", bed: "Nhà trọ", work: "Việc làm", school: "Trường",
    shopping_cart: "Mua sắm", local_post_office: "Bưu điện", smartphone: "Điện thoại", directions_bus: "Xe buýt",
    local_laundry_service: "Giặt", emergency: "Khẩn cấp", celebration: "Lễ hội", account_balance: "Cơ quan",
    translate: "Ngôn ngữ", forum: "Trò chuyện"
};
const SEARCH_DELAY = 300;

const view = document.body.dataset.view;
/** The content column of the staff desk; every page draws into it. */
let main = null;
/** The signed-in Academic Manager (name on Tổng quan). */
let user = null;

// ---------------------------------------------------------------- shared pieces

/** "‹ Trang 2/5 · 87 mục ›" under a table; the buttons carry data-page. */
function pager(page, size, total, label) {
    const pages = Math.max(1, Math.ceil(total / size));
    return total > size ? `
        <button class="btn sm" type="button" data-page="${page - 1}" ${page === 0 ? "disabled" : ""} aria-label="Trang trước">‹</button>
        <span class="note">Trang ${page + 1}/${pages} · ${total} ${label}</span>
        <button class="btn sm" type="button" data-page="${page + 1}" ${page + 1 >= pages ? "disabled" : ""} aria-label="Trang sau">›</button>`
        : (total ? `<span class="note">${total} ${label}</span>` : "");
}

/** A "Hủy" / "Xóa" dialog before a delete; resolves true when "Xóa" is pressed. */
function confirmBox(title, html) {
    return new Promise((resolve) => {
        const dlg = document.createElement("dialog");
        dlg.className = "dlg";
        dlg.innerHTML = `<form method="dialog"><h3>${esc(title)}</h3>${html}
            <div class="row end"><button class="btn" type="button" value="no">Hủy</button>
            <button class="btn dan solid" type="button" value="yes">Xóa</button></div></form>`;
        document.body.append(dlg);
        // Settled at the click itself: the "close" event is queued and may come late; it still covers Escape
        let done = false;
        const finish = (yes) => {
            if (done) return;
            done = true;
            if (dlg.open) dlg.close();
            dlg.remove();
            resolve(yes);
        };
        dlg.addEventListener("click", (event) => {
            const b = event.target.closest("button[value]");
            if (b || event.target === dlg) finish(b?.value === "yes");
        });
        dlg.addEventListener("close", () => finish(false));
        dlg.showModal();
    });
}

/** Keeps the filters (and the row being edited) in the address without a new history entry. */
function setUrl(params) {
    const query = new URLSearchParams(Object.entries(params).filter(([, v]) => v !== "" && v != null && v !== 0)).toString();
    history.replaceState(null, "", location.pathname + (query ? `?${query}` : ""));
}

/** Japanese · romaji · meaning on one line (report cards). */
const phraseLine = (p) => `<span class="ja jp-strong" lang="ja">${esc(p.japanese)}</span>
    ${p.romaji ? `<span class="note">${esc(p.romaji)}</span>` : ""} <span>· ${esc(p.meaningVi)}</span>`;

/** "Lý do: Sai nghĩa (2), …" and the list of reporters with their notes (report cards). */
const reasonsLine = (c) => `<p class="note">Lý do: ${c.reasons.map((r) => `${esc(r.label)} (${r.count})`).join(", ")}</p>
    <ul class="acad-reporters">${c.reporters.map((r) => `<li><b>${esc(r.displayName)}</b> · ${esc(r.reason)}
        <span class="note">${esc(daysAgo(r.at))}</span>${r.note ? `<div class="note">“${esc(r.note)}”</div>` : ""}</li>`).join("")}</ul>`;

/** A failed call: to /login when the session ended, else a red toast. */
function fail(error) {
    if (error.status === 401) {
        location.replace("/login");
        return;
    }
    toast(error.message, "error");
}

// ---------------------------------------------------------------- Tổng quan (/academic)

/** Greeting, the two tiles, "Danh mục chưa có mẫu câu" + "Chủ đề học còn ít câu", the last 5 actions. */
async function overview() {
    const data = await apiClient.acadOverview();
    const c = data.counts;
    const tile = (href, icon, n, label, hot) =>
        `<a class="tile acad-tile ${hot && n ? "hot" : ""}" href="${href}">${ic(icon)}<b>${n}</b>${esc(label)}</a>`;
    main.innerHTML = `
        <div><h2>Chào ${esc(user.displayName)}</h2>
            <p class="note">Việc của bạn: nội dung tiếng Nhật chính xác và đủ cho từng địa điểm, từng chủ đề.</p></div>
        <div class="tiles">
            ${tile("/academic/phrase-reports", "flag", c.phraseReports, "Báo cáo mẫu câu", true)}
            ${tile("/academic/phrases", "translate", c.phrases + c.vocab, `Mục trong thư viện (${c.phrases} mẫu câu · ${c.vocab} từ vựng)`)}
        </div>
        <div class="two">
            <div class="card"><h3>${ic("location_off")} Danh mục chưa có mẫu câu</h3>
                <div class="row">${data.emptyCategories.map((cat) => `<a class="badge acad-chip" href="/academic/phrases?new=1&category=${encodeURIComponent(cat.id)}">${ic(cat.icon)} ${esc(cat.name)}</a>`).join("")
                    || `<span class="note">Danh mục nào cũng đã có.</span>`}</div></div>
            <div class="card"><h3>${ic("category")} Chủ đề học còn ít câu (dưới 3)</h3>
                <div class="row">${data.thinTopics.map((t) => `<a class="badge acad-chip" href="/academic/phrases?topic=${encodeURIComponent(t.id)}">${esc(t.name)} · ${t.count}</a>`).join("")
                    || `<span class="note">Chủ đề nào cũng đủ.</span>`}</div></div>
        </div>
        <div class="card"><h3>${ic("history")} Thao tác gần đây của bạn</h3>
            ${data.recent.length ? `<ul class="mod-history">${data.recent.map((l) => `<li><span>${esc(l.description)}</span>
                <span class="note" title="${esc(fullDate(l.at))}">${esc(daysAgo(l.at))}</span></li>`).join("")}</ul>`
                : `<p class="note">Chưa có thao tác nào.</p>`}</div>`;
}

// ---------------------------------------------------------------- Thư viện mẫu câu (/academic/phrases, UC 12–14)

/** State of the library page: filters (also in the address), the page shown, the form and its problems. */
const lib = {
    filters: { category: "", topic: "", kind: "", q: "", page: 0 },
    data: null,
    options: null,
    edit: null,          // the row being edited, or null for "Thêm mẫu câu"
    caseId: null,        // "Sửa mẫu câu này" from a report
    form: null,          // what is typed in the form
    errors: {},          // field → message from the last save (400 `fields`)
    duplicate: null,     // "Danh mục này đã có mục…": { message, row } until "Vẫn lưu" / "Không lưu"
    timer: null          // search box delay
};

const emptyForm = (categoryId = "") => ({ categoryId, kind: "PHRASE", japanese: "", romaji: "", meaningVi: "", topicIds: [] });

/**
 * Lays out the library page (title, filters, table #lib-rows, #pager, form #phrase-form), wires the listeners, opens
 * the row of ?edit= (from a report: &case=) or an empty form (?new=1 focuses it), then loads the first page.
 */
async function phrases() {
    const p = new URLSearchParams(location.search);
    Object.assign(lib.filters, { category: p.get("category") || "", topic: p.get("topic") || "",
        kind: p.get("kind") || "", q: p.get("q") || "" });
    lib.options = await apiClient.acadOptions();
    lib.form = emptyForm(lib.filters.category || lib.options.categories[0]?.id || "");
    main.innerHTML = `
        <div><h2>Thư viện mẫu câu</h2>
            <p class="note">Mỗi mục (mẫu câu hoặc từ vựng) thuộc một danh mục địa điểm (hiện ở thông tin địa điểm trên bản đồ)
                và một hoặc nhiều chủ đề học (hiện ở tab Nhật ngữ).</p></div>
        <div class="row mod-filters">
            <label class="sr-only" for="f-category">Danh mục</label>
            <select class="input" id="f-category"><option value="">Mọi danh mục</option>
                ${lib.options.categories.map((c) => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join("")}</select>
            <label class="sr-only" for="f-topic">Chủ đề học</label>
            <select class="input" id="f-topic"><option value="">Mọi chủ đề học</option>
                ${lib.options.topics.map((t) => `<option value="${esc(t.id)}">${esc(t.name)}</option>`).join("")}</select>
            <label class="sr-only" for="f-kind">Loại</label>
            <select class="input" id="f-kind"><option value="">Mọi loại</option><option value="PHRASE">Mẫu câu</option><option value="VOCAB">Từ vựng</option></select>
            <div class="searchbox">${ic("search")}<label class="sr-only" for="f-q">Tìm trong thư viện</label>
                <input id="f-q" type="search" placeholder="Tìm tiếng Nhật, romaji, nghĩa" maxlength="100" autocomplete="off"></div>
        </div>
        <div class="acad-table-wrap"><table class="acad-table">
            <thead><tr><th>Loại</th><th>Tiếng Nhật</th><th>Nghĩa</th><th>Danh mục</th><th>Chủ đề học</th><th>Báo cáo</th><th><span class="sr-only">Thao tác</span></th></tr></thead>
            <tbody id="lib-rows"><tr><td colspan="7" class="note">Đang tải…</td></tr></tbody></table></div>
        <div class="row mod-pager" id="pager"></div>
        <section class="card acad-form" id="phrase-form" aria-live="polite"></section>`;
    ["category", "topic", "kind"].forEach((key) => {
        const select = main.querySelector(`#f-${key}`);
        select.value = lib.filters[key];
        select.addEventListener("change", () => { lib.filters[key] = select.value; lib.filters.page = 0; loadLibrary(); });
    });
    const q = main.querySelector("#f-q");
    q.value = lib.filters.q;
    q.addEventListener("input", () => {
        clearTimeout(lib.timer);
        lib.timer = setTimeout(() => { lib.filters.q = q.value.trim(); lib.filters.page = 0; loadLibrary(); }, SEARCH_DELAY);
    });
    main.querySelector("#pager").addEventListener("click", (event) => {
        const b = event.target.closest("[data-page]");
        if (b) { lib.filters.page = Number(b.dataset.page); loadLibrary(); }
    });
    main.querySelector("#lib-rows").addEventListener("click", onLibraryClick);
    const form = main.querySelector("#phrase-form");
    form.addEventListener("input", onFormInput);
    form.addEventListener("click", onFormClick);
    form.addEventListener("submit", (event) => { event.preventDefault(); savePhrase(false); });

    const editId = Number(p.get("edit"));
    if (editId) {
        lib.caseId = Number(p.get("case")) || null;
        try {
            startEdit(await apiClient.acadPhrase(editId));
        } catch (error) {
            toast(error.message, "error");
            renderForm();
        }
    } else {
        renderForm();
        if (p.get("new")) main.querySelector("#pf-japanese")?.focus();
    }
    await loadLibrary();
}

/** Reads one page with the filters and redraws the table rows and the pager. */
async function loadLibrary() {
    const f = lib.filters;
    setUrl({ category: f.category, topic: f.topic, kind: f.kind, q: f.q, page: f.page, edit: lib.edit?.id, case: lib.caseId });
    try {
        lib.data = await apiClient.acadPhrases(f);
    } catch (error) {
        fail(error);
        return;
    }
    const d = lib.data;
    const filtered = f.category || f.topic || f.kind || f.q;
    main.querySelector("#lib-rows").innerHTML = d.items.length ? d.items.map((r) => `
        <tr data-id="${r.id}" ${lib.edit?.id === r.id ? 'class="editing"' : ""}>
            <td><span class="badge ${r.kind === "VOCAB" ? "lan" : ""}">${KIND[r.kind]}</span></td>
            <td><span class="ja jp-strong" lang="ja">${esc(r.japanese)}</span>${r.romaji ? `<div class="note">${esc(r.romaji)}</div>` : ""}</td>
            <td>${esc(r.meaningVi)}</td>
            <td>${ic(r.category.icon)} ${esc(r.category.name)}</td>
            <td>${r.topics.map((t) => `<span class="badge br">${esc(t.name)}</span>`).join(" ") || `<span class="note">Chưa gắn</span>`}</td>
            <td>${r.openReports ? `<span class="badge warn" title="Báo cáo đang mở">${ic("flag")}${r.openReports}</span>` : `<span class="note">0</span>`}</td>
            <td><div class="row acad-actions"><button class="btn sm" type="button" data-act="edit">Sửa</button>
                <button class="btn sm dan" type="button" data-act="delete">Xóa</button></div></td>
        </tr>`).join("")
        : `<tr><td colspan="7" class="note">${filtered ? "Không có mục nào khớp." : "Thư viện còn trống. Thêm mục đầu tiên ở khung bên dưới."}</td></tr>`;
    main.querySelector("#pager").innerHTML = pager(d.page, d.size, d.total, "mục");
}

/** "Sửa" (fills the form below) or "Xóa" (asks first, soft delete) in a table row. */
async function onLibraryClick(event) {
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const row = lib.data.items.find((r) => r.id === Number(button.closest("tr").dataset.id));
    if (button.dataset.act === "edit") {
        lib.caseId = null;
        startEdit(row);
        main.querySelector("#phrase-form").scrollIntoView({ behavior: "smooth", block: "start" });
        return;
    }
    const ok = await confirmBox(`Xóa ${KIND[row.kind].toLowerCase()} “${row.japanese}”?`,
        `<p class="note">Mục sẽ không còn hiện ở bản đồ, tab Nhật ngữ, danh sách Chưa thuộc, Đã thuộc và kết quả tìm kiếm. Các báo cáo đang mở được đóng.</p>`);
    if (!ok) return;
    try {
        await apiClient.acadDeletePhrase(row.id);
        toast("Đã xóa mẫu câu.");
        if (lib.edit?.id === row.id) cancelEdit();
        loadLibrary();
    } catch (error) {
        fail(error);
        if (error.status === 404) loadLibrary();
    }
}

/** The form shows this row; its table row is highlighted (.editing). */
function startEdit(row) {
    lib.edit = row;
    lib.errors = {};
    lib.duplicate = null;
    lib.form = { categoryId: row.category.id, kind: row.kind, japanese: row.japanese, romaji: row.romaji,
        meaningVi: row.meaningVi, topicIds: row.topics.map((t) => t.id) };
    renderForm();
    main.querySelectorAll("#lib-rows tr").forEach((tr) => tr.classList.toggle("editing", Number(tr.dataset.id) === row.id));
}

/** Back to an empty "Thêm" form (the category stays). */
function cancelEdit() {
    const categoryId = lib.form.categoryId;
    lib.edit = null;
    lib.caseId = null;
    lib.errors = {};
    lib.duplicate = null;
    lib.form = emptyForm(categoryId);
    const f = lib.filters;
    setUrl({ category: f.category, topic: f.topic, kind: f.kind, q: f.q, page: f.page });
    renderForm();
    main.querySelectorAll("#lib-rows tr.editing").forEach((tr) => tr.classList.remove("editing"));
}

/**
 * The form under the table: "Thêm mẫu câu hoặc từ vựng" or "Sửa …"; the report notice, category, kind, Japanese,
 * romaji, meaning, the topic buttons, the duplicate warning ("Vẫn lưu" / "Không lưu"), "Lưu" / "Hủy". Each field shows
 * its own error under it.
 */
function renderForm() {
    const f = lib.form;
    const e = lib.errors;
    const err = (key) => e[key] ? `<p class="field-error" role="alert">${esc(e[key])}</p>` : "";
    const invalid = (key) => e[key] ? 'aria-invalid="true"' : "";
    main.querySelector("#phrase-form").innerHTML = `
        <form id="pf" novalidate>
            <h3>${lib.edit ? `Sửa ${KIND[lib.edit.kind].toLowerCase()}` : "Thêm mẫu câu hoặc từ vựng"}</h3>
            ${lib.caseId ? `<div class="info">${ic("flag")} Đang sửa theo báo cáo. Lưu xong, các báo cáo của mục này được đánh dấu đã xử lý.</div>` : ""}
            <div class="acad-grid">
                <div class="field"><label for="pf-category">Danh mục địa điểm</label>
                    <select class="input" id="pf-category" name="categoryId" ${invalid("categoryId")}>
                        ${lib.options.categories.map((c) => `<option value="${esc(c.id)}" ${c.id === f.categoryId ? "selected" : ""}>${esc(c.name)}</option>`).join("")}
                    </select>${err("categoryId")}</div>
                <div class="field"><label for="pf-kind">Loại</label>
                    <select class="input" id="pf-kind" name="kind" ${invalid("kind")}>
                        <option value="PHRASE" ${f.kind === "PHRASE" ? "selected" : ""}>Mẫu câu</option>
                        <option value="VOCAB" ${f.kind === "VOCAB" ? "selected" : ""}>Từ vựng</option>
                    </select>${err("kind")}</div>
                <div class="field"><label for="pf-japanese">Tiếng Nhật</label>
                    <input class="input ja" id="pf-japanese" name="japanese" maxlength="200" lang="ja" value="${esc(f.japanese)}"
                        placeholder="${f.kind === "VOCAB" ? "領収書" : "領収書をください。"}" ${invalid("japanese")}>${err("japanese")}</div>
                <div class="field"><label for="pf-romaji">Romaji</label>
                    <input class="input" id="pf-romaji" name="romaji" maxlength="200" value="${esc(f.romaji)}"
                        placeholder="${f.kind === "VOCAB" ? "ryōshūsho" : "Ryōshūsho o kudasai."}" ${invalid("romaji")}>${err("romaji")}</div>
                <div class="field acad-wide"><label for="pf-meaning">Nghĩa tiếng Việt</label>
                    <input class="input" id="pf-meaning" name="meaningVi" maxlength="300" value="${esc(f.meaningVi)}"
                        placeholder="${f.kind === "VOCAB" ? "Hóa đơn." : "Cho tôi xin hóa đơn."}" ${invalid("meaningVi")}>${err("meaningVi")}</div>
            </div>
            <fieldset class="field acad-topics"><legend>Chủ đề học (chọn một hoặc nhiều)</legend>
                <div class="row">${lib.options.topics.map((t) => `<button class="btn sm ${f.topicIds.includes(t.id) ? "on" : ""}" type="button"
                    data-topic="${esc(t.id)}" aria-pressed="${f.topicIds.includes(t.id)}">${esc(t.name)}</button>`).join("")
                    || `<span class="note">Chưa có chủ đề học nào. <a class="link" href="/academic/topics">Thêm chủ đề học</a></span>`}</div>
                ${err("topicIds")}</fieldset>
            ${lib.duplicate ? `<div class="warnbox" role="alert">${ic("warning")} ${esc(lib.duplicate.message)}
                <div class="note">Mục đã có: <span class="ja" lang="ja">${esc(lib.duplicate.row.japanese)}</span> · ${esc(lib.duplicate.row.meaningVi)}</div>
                <div class="row"><button class="btn sm pri" type="button" data-act="confirm-duplicate">Vẫn lưu</button>
                    <button class="btn sm" type="button" data-act="drop-duplicate">Không lưu</button></div></div>` : ""}
            <div class="row"><button class="btn pri" type="submit" id="pf-save">${lib.edit ? "Lưu thay đổi" : "Lưu"}</button>
                ${lib.edit ? `<button class="btn" type="button" data-act="cancel">Hủy</button>` : ""}</div>
        </form>`;
}

/** Typing keeps lib.form current; a kind change redraws the form (the placeholders follow the kind). */
function onFormInput(event) {
    const name = event.target.name;
    if (!name) return;
    lib.form[name] = event.target.value;
    if (name === "kind") renderFormKeepingFocus(event.target.id);
}

function renderFormKeepingFocus(id) {
    renderForm();
    main.querySelector(`#${id}`)?.focus();
}

/** A topic button (on / off), "Hủy", "Vẫn lưu", "Không lưu". */
function onFormClick(event) {
    const topic = event.target.closest("[data-topic]");
    if (topic) {
        const id = topic.dataset.topic;
        const list = lib.form.topicIds;
        lib.form.topicIds = list.includes(id) ? list.filter((t) => t !== id) : [...list, id];
        topic.classList.toggle("on");
        topic.setAttribute("aria-pressed", String(lib.form.topicIds.includes(id)));
        return;
    }
    const act = event.target.closest("[data-act]")?.dataset.act;
    if (act === "cancel") cancelEdit();
    if (act === "confirm-duplicate") savePhrase(true);
    if (act === "drop-duplicate") { lib.duplicate = null; renderForm(); }
}

/**
 * "Lưu" / "Lưu thay đổi" (POST or PUT /api/academic/phrases). saved:false = a duplicate to confirm; 400 with fields =
 * errors under the fields; after an edit from a report the page goes back to "Báo cáo mẫu câu".
 */
async function savePhrase(confirmDuplicate) {
    const save = main.querySelector("#pf-save");
    save.disabled = true;
    const body = { ...lib.form, confirmDuplicate, reportCaseId: lib.caseId };
    try {
        const result = lib.edit ? await apiClient.acadUpdatePhrase(lib.edit.id, body) : await apiClient.acadCreatePhrase(body);
        if (!result.saved) {
            lib.duplicate = { message: result.message, row: result.duplicate };
            renderForm();
            return;
        }
        toast(result.message);
        if (lib.edit) {
            const wasCase = lib.caseId;
            cancelEdit();
            if (wasCase) location.assign("/academic/phrase-reports");
        } else {
            lib.errors = {};
            lib.duplicate = null;
            lib.form = emptyForm(lib.form.categoryId);      // UC 12 step 6: the category stays for the next entry
            renderForm();
            main.querySelector("#pf-japanese").focus();
        }
        loadLibrary();
    } catch (error) {
        if (error.status === 400 && Object.keys(error.fields || {}).length) {
            lib.errors = error.fields;
            renderForm();
            main.querySelector('[aria-invalid="true"]')?.focus();
        } else if (error.status === 404) {
            toast(error.message, "error");
            cancelEdit();
            loadLibrary();
        } else {
            fail(error);
        }
    } finally {
        const again = main.querySelector("#pf-save");
        if (again) again.disabled = false;
    }
}

// ---------------------------------------------------------------- Chủ đề học (/academic/topics, UC 9–11)

/** The topic rows, the one being edited, the name error and the icons offered. */
const topicsState = { rows: [], edit: null, error: "", icons: [] };

/** Lays out the page (title, table #topic-rows, form #topic-form) and loads the topics. */
async function learnTopics() {
    topicsState.icons = (await apiClient.acadOptions()).icons;
    main.innerHTML = `
        <div><h2>Chủ đề học</h2>
            <p class="note">Chủ đề học là cách thứ hai để tìm mẫu câu, bên cạnh danh mục địa điểm. Ví dụ "Thanh toán" gom câu từ cửa hàng
                tiện lợi, siêu thị, quán ăn. Đây không phải "chủ đề bài viết" của blog.</p></div>
        <div class="acad-table-wrap"><table class="acad-table">
            <thead><tr><th>Chủ đề học</th><th>Số mẫu câu</th><th>Có ở danh mục</th><th><span class="sr-only">Thao tác</span></th></tr></thead>
            <tbody id="topic-rows"></tbody></table></div>
        <section class="card acad-form" id="topic-form"></section>`;
    main.querySelector("#topic-rows").addEventListener("click", onTopicRowClick);
    const form = main.querySelector("#topic-form");
    form.addEventListener("submit", (event) => { event.preventDefault(); saveTopic(); });
    form.addEventListener("click", (event) => {
        const icon = event.target.closest("[data-icon]");
        if (icon) {
            form.querySelectorAll("[data-icon]").forEach((b) => b.setAttribute("aria-pressed", String(b === icon)));
            return;
        }
        if (event.target.closest("[data-act=cancel]")) { topicsState.edit = null; topicsState.error = ""; renderTopicForm(); }
    });
    renderTopicForm();
    await loadTopics();
}

/** The table: icon + name, number of entries (link to the library filtered by it), categories, "Sửa" / "Xóa". */
async function loadTopics() {
    try {
        topicsState.rows = await apiClient.acadTopics();
    } catch (error) {
        fail(error);
        return;
    }
    main.querySelector("#topic-rows").innerHTML = topicsState.rows.map((t) => `
        <tr data-id="${esc(t.id)}" ${topicsState.edit?.id === t.id ? 'class="editing"' : ""}>
            <td>${ic(t.icon)} ${esc(t.name)}</td>
            <td><a class="link" href="/academic/phrases?topic=${encodeURIComponent(t.id)}">${t.count}</a></td>
            <td>${t.categories.map(esc).join(", ") || `<span class="note">Chưa có</span>`}</td>
            <td><div class="row acad-actions"><button class="btn sm" type="button" data-act="edit">Sửa</button>
                <button class="btn sm dan" type="button" data-act="delete">Xóa</button></div></td>
        </tr>`).join("") || `<tr><td colspan="4" class="note">Chưa có chủ đề học nào.</td></tr>`;
}

/** "Thêm chủ đề học" / "Sửa chủ đề học": name, the icon buttons (.acad-icons), "Thêm" or "Lưu thay đổi" / "Hủy". */
function renderTopicForm() {
    const t = topicsState.edit;
    const icon = t?.icon || "label";
    main.querySelector("#topic-form").innerHTML = `
        <form novalidate>
            <h3>${t ? "Sửa chủ đề học" : "Thêm chủ đề học"}</h3>
            <div class="field"><label for="tf-name">Tên chủ đề học</label>
                <input class="input" id="tf-name" maxlength="60" value="${esc(t?.name || "")}" placeholder="Ví dụ: Ở nhà trọ"
                    ${topicsState.error ? 'aria-invalid="true"' : ""}>
                ${topicsState.error ? `<p class="field-error" role="alert">${esc(topicsState.error)}</p>` : ""}</div>
            <fieldset class="field"><legend>Biểu tượng</legend>
                <div class="acad-icons">${topicsState.icons.map((name) => `<button class="acad-icon" type="button" data-icon="${esc(name)}"
                    aria-pressed="${name === icon}" title="${esc(ICON_LABELS[name] || name)}">${ic(name)}<span class="sr-only">${esc(ICON_LABELS[name] || name)}</span></button>`).join("")}</div></fieldset>
            <div class="row"><button class="btn pri" type="submit" id="tf-save">${t ? "Lưu thay đổi" : "Thêm"}</button>
                ${t ? `<button class="btn" type="button" data-act="cancel">Hủy</button>` : ""}</div>
            <p class="note">Xóa một chủ đề học chỉ gỡ nhãn khỏi các mẫu câu, không xóa mẫu câu.</p>
        </form>`;
}

/** Sends the topic form; a 400 ("Chủ đề học này đã có.") shows under the name and keeps what was typed. */
async function saveTopic() {
    const form = main.querySelector("#topic-form");
    const body = { name: form.querySelector("#tf-name").value,
        icon: form.querySelector('[data-icon][aria-pressed="true"]')?.dataset.icon || "label" };
    const save = form.querySelector("#tf-save");
    save.disabled = true;
    try {
        const t = topicsState.edit;
        if (t) {
            await apiClient.acadUpdateTopic(t.id, body);
            toast(t.name === body.name.trim() && t.icon === body.icon ? "Không có thay đổi nào." : "Đã lưu thay đổi.");
        } else {
            await apiClient.acadCreateTopic(body);
            toast("Đã thêm chủ đề học.");
        }
        topicsState.edit = null;
        topicsState.error = "";
        renderTopicForm();
        await loadTopics();
    } catch (error) {
        save.disabled = false;
        if (error.status === 400) {
            topicsState.error = error.message;
            const name = form.querySelector("#tf-name").value;
            const pickedIcon = body.icon;
            renderTopicForm();
            form.querySelector("#tf-name").value = name;
            form.querySelectorAll("[data-icon]").forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.icon === pickedIcon)));
            form.querySelector("#tf-name").focus();
        } else {
            fail(error);
            if (error.status === 404) { topicsState.edit = null; renderTopicForm(); loadTopics(); }
        }
    }
}

/** "Sửa" (fills the form) or "Xóa" (asks first; only the labels go, the phrases stay) in a topic row. */
async function onTopicRowClick(event) {
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const topic = topicsState.rows.find((t) => t.id === button.closest("tr").dataset.id);
    if (button.dataset.act === "edit") {
        topicsState.edit = topic;
        topicsState.error = "";
        renderTopicForm();
        main.querySelectorAll("#topic-rows tr").forEach((tr) => tr.classList.toggle("editing", tr.dataset.id === topic.id));
        main.querySelector("#tf-name").focus();
        return;
    }
    const ok = await confirmBox(`Xóa chủ đề học “${topic.name}”?`,
        `<p>${topic.count} mẫu câu đang gắn chủ đề này.</p><p class="note">Xóa một chủ đề học chỉ gỡ nhãn khỏi các mẫu câu, không xóa mẫu câu.</p>`);
    if (!ok) return;
    try {
        await apiClient.acadDeleteTopic(topic.id);
        toast("Đã xóa chủ đề học. Mẫu câu vẫn giữ nguyên.");
    } catch (error) {
        fail(error);
    }
    if (topicsState.edit?.id === topic.id) { topicsState.edit = null; renderTopicForm(); }
    loadTopics();
}

// ---------------------------------------------------------------- Báo cáo mẫu câu (/academic/phrase-reports, UC 13 AF1, AF2)

/** Lays out the page (title, #cases); "Không sai, bỏ qua" closes a case and reloads the list. */
async function phraseReports() {
    main.innerHTML = `
        <div><h2>Báo cáo mẫu câu</h2>
            <p class="note">Người dùng báo lỗi trong nội dung chính thức: sai tiếng Nhật, sai nghĩa… Mẫu câu không tự ẩn; cách xử lý là
                sửa lại, hoặc bỏ qua nếu mẫu câu không sai.</p></div>
        <div class="acad-cases" id="cases" aria-live="polite"><p class="note">Đang tải…</p></div>`;
    main.querySelector("#cases").addEventListener("click", async (event) => {
        const b = event.target.closest("[data-dismiss]");
        if (!b) return;
        b.disabled = true;
        try {
            await apiClient.acadDismissReport(Number(b.dataset.dismiss));
            toast("Đã đóng báo cáo. Mẫu câu giữ nguyên.");
        } catch (error) {
            fail(error);
        }
        loadPhraseReports();
    });
    await loadPhraseReports();
}

/** One card per open report: kind · category, count, "Khẩn", the phrase, reasons and reporters, the two buttons. */
async function loadPhraseReports() {
    let cases;
    try {
        cases = await apiClient.acadReports();
    } catch (error) {
        fail(error);
        return;
    }
    main.querySelector("#cases").innerHTML = cases.length ? cases.map((c) => `
        <article class="card acad-case">
            <div class="row"><span class="badge">${KIND[c.phrase.kind]} · ${esc(c.phrase.category.name)}</span>
                <span class="badge warn">${c.reportCount} báo cáo</span>
                ${c.priority === "URGENT" ? `<span class="badge crit">Khẩn</span>` : ""}
                <span class="grow"></span><span class="note">Báo cáo đầu ${esc(daysAgo(c.createdAt))}</span></div>
            <p>${phraseLine(c.phrase)}</p>
            ${reasonsLine(c)}
            <div class="row"><a class="btn pri sm" href="/academic/phrases?edit=${c.phrase.id}&case=${c.caseId}">${ic("edit")}Sửa mẫu câu này</a>
                <button class="btn sm" type="button" data-dismiss="${c.caseId}">Không sai, bỏ qua</button></div>
        </article>`).join("") : `<div class="card">Không còn báo cáo nào.</div>`;
}

// ---------------------------------------------------------------- start

// data-view of <body> → the function that draws that page, and the tab title. mountStaffDesk checks the role and draws
// the top bar and side menu (it answers null when the page is sent elsewhere, e.g. to /login).
const PAGES = { overview, phrases, "learn-topics": learnTopics, "phrase-reports": phraseReports };
const TITLES = { overview: "Biên tập nội dung tiếng Nhật", phrases: "Thư viện mẫu câu", "learn-topics": "Chủ đề học",
    "phrase-reports": "Báo cáo mẫu câu" };

const shell = await mountStaffDesk("ACADEMIC_MANAGER", view);
if (shell) {
    ({ main, user } = shell);
    document.title = `${TITLES[view]} – Senpai ơi`;
    try {
        await PAGES[view]();
    } catch (error) {
        main.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
    }
}
