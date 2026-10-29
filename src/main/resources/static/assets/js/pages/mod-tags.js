// UC-M01 / UC-M02 "Duyệt tag định danh" (Moderator): waiting requests, oldest first, 10 a page, filtered by tag or by
// the user's name; the chosen request with its proof photo (loaded from the Moderator API, never a public address, and
// gone once the request is handled), "Duyệt và cấp tag" or "Từ chối" with a reason.
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic, tagChips } from "../lib/ui.js";

const SEARCH_DELAY = 300;

let main = null;
let page = { items: [], page: 0, size: 10, total: 0, tags: [] };
let filters = { tag: "", q: "", page: 0 };
let selected = null;
let detail = null;
let busy = false;
let timer = null;

function renderPage() {
    main.innerHTML = `
        <div><h2>Duyệt tag định danh</h2>
            <p class="note">Đối chiếu ảnh minh chứng với tag và loại giấy tờ. Ảnh chỉ xem được ở đây và bị xóa ngay sau khi duyệt hoặc từ chối, hoặc sau 14 ngày.</p></div>
        <div class="row mod-filters">
            <label class="sr-only" for="f-tag">Tag</label>
            <select class="input" id="f-tag"><option value="">Mọi tag</option></select>
            <div class="searchbox">${ic("search")}<label class="sr-only" for="f-q">Tên người dùng</label>
                <input id="f-q" type="search" placeholder="Tên người dùng" maxlength="60" autocomplete="off"></div>
        </div>
        <div class="mod-master">
            <div><div class="mod-list" id="list" aria-live="polite"></div><div class="row mod-pager" id="pager"></div></div>
            <section class="mod-detail" id="detail" aria-live="polite"></section>
        </div>`;
    main.querySelector("#f-tag").addEventListener("change", (event) => setFilter("tag", event.target.value));
    main.querySelector("#f-q").addEventListener("input", (event) => {
        clearTimeout(timer);
        timer = setTimeout(() => setFilter("q", event.target.value.trim()), SEARCH_DELAY);
    });
    main.querySelector("#list").addEventListener("click", (event) => {
        const row = event.target.closest("[data-id]");
        if (row) open(Number(row.dataset.id));
    });
    main.querySelector("#pager").addEventListener("click", (event) => {
        const b = event.target.closest("[data-page]");
        if (!b) return;
        filters.page = Number(b.dataset.page);
        load();
    });
    main.querySelector("#detail").addEventListener("click", onDetailClick);
}

function setFilter(key, value) {
    filters[key] = value;
    filters.page = 0;            // UC-M01 step 4: back to page 1
    load();
}

const daysBadge = (n) => `<span class="badge ${n < 3 ? "crit" : ""}">${n === 0 ? "Hết hạn hôm nay" : `Còn ${n} ngày`}</span>`;

function renderList() {
    const tagSelect = main.querySelector("#f-tag");
    if (tagSelect.options.length === 1) {
        tagSelect.insertAdjacentHTML("beforeend", page.tags.map((t) => `<option value="${t.id}">${esc(t.name)}</option>`).join(""));
    }
    main.querySelector("#list").innerHTML = page.items.length ? page.items.map((r) => `
        <button class="card mod-item" type="button" data-id="${r.id}" ${r.id === selected ? 'aria-current="true"' : ""}>
            <span class="row"><b>${esc(r.userName)}</b>${tagChips([{ name: r.tagName, color: r.tagColor }])}</span>
            <span class="note">${esc(r.proofType)}</span>
            <span class="row note">Gửi ${esc(daysAgo(r.submittedAt))} ${daysBadge(r.daysLeft)}</span>
        </button>`).join("")
        : `<div class="card">${filters.tag || filters.q ? "Không có yêu cầu nào khớp bộ lọc." : "Không có yêu cầu tag nào đang chờ."}</div>`;
    const pages = Math.max(1, Math.ceil(page.total / page.size));
    main.querySelector("#pager").innerHTML = page.total > page.size ? `
        <button class="btn sm" type="button" data-page="${page.page - 1}" ${page.page === 0 ? "disabled" : ""} aria-label="Trang trước">‹</button>
        <span class="note">Trang ${page.page + 1}/${pages} · ${page.total} yêu cầu</span>
        <button class="btn sm" type="button" data-page="${page.page + 1}" ${page.page + 1 >= pages ? "disabled" : ""} aria-label="Trang sau">›</button>`
        : "";
}

function renderDetail() {
    const box = main.querySelector("#detail");
    if (!detail) {
        box.innerHTML = page.items.length ? `<div class="card note">Chọn một yêu cầu để xem ảnh minh chứng.</div>` : "";
        return;
    }
    const d = detail;
    box.innerHTML = `<div class="card mod-card">
        <div class="row"><h3>${esc(d.userName)}</h3>${tagChips([{ name: d.tagName, color: d.tagColor }])}</div>
        <dl class="mod-facts">
            <dt>Minh chứng</dt><dd>${esc(d.proofType)}</dd>
            <dt>Ngày gửi</dt><dd>${esc(fullDate(d.submittedAt))}</dd>
            <dt>Ảnh tự xóa</dt><dd>${esc(fullDate(d.expiresAt))} ${daysBadge(d.daysLeft)}</dd>
        </dl>
        ${d.selfieOnly ? `<div class="info">${ic("face")} Tag chỉ cần ảnh chân dung: xác nhận người trong ảnh là người quen của nhóm.</div>` : ""}
        <div class="proof-box"><img class="proof" id="proof" src="${esc(d.photoUrl)}" alt="Ảnh minh chứng của ${esc(d.userName)}"
            draggable="false" referrerpolicy="no-referrer"></div>
        <div class="alert error" id="act-error" role="alert" tabindex="-1" hidden></div>
        <div class="mod-actions">
            <div class="mod-act"><h4>Duyệt</h4>
                <button class="btn pri" type="button" data-act="approve">${ic("verified")}Duyệt và cấp tag</button></div>
            <div class="mod-act"><h4>Từ chối</h4>
                <div class="field"><label for="r-reason">Lý do từ chối</label>
                    <select class="input" id="r-reason"><option value="">Chọn lý do</option>
                        ${d.rejectReasons.map((r) => `<option>${esc(r)}</option>`).join("")}</select></div>
                <button class="btn dan" type="button" data-act="reject">${ic("block")}Từ chối</button>
                <p class="note">Người dùng được báo lý do và có thể gửi lại.</p></div>
        </div>
    </div>`;
    // The photo is for checking only: no right-click menu ("Lưu ảnh")
    box.querySelector("#proof").addEventListener("contextmenu", (event) => event.preventDefault());
}

async function open(id) {
    selected = id;
    renderList();
    try {
        detail = await apiClient.modTagRequest(id);
    } catch (error) {
        detail = null;
        toast(error.message, "error");
        await load();
        return;
    }
    renderDetail();
}

async function onDetailClick(event) {
    const button = event.target.closest("[data-act]");
    if (!button || busy || !detail) return;
    let call;
    if (button.dataset.act === "approve") {
        call = () => apiClient.modApproveTag(detail.id);
    } else {
        const reason = main.querySelector("#r-reason").value;
        if (!reason) return showError("Hãy chọn lý do từ chối.");
        call = () => apiClient.modRejectTag(detail.id, reason);
    }
    busy = true;
    button.disabled = true;
    try {
        const done = await call();
        toast(done.message);
        selected = null;
        detail = null;
        await load();
    } catch (error) {
        showError(error.message);
        if (error.status === 409) {
            selected = null;
            detail = null;
            await load();          // AF6: handled by another Moderator, reload the list
        }
    } finally {
        busy = false;
        button.disabled = false;
    }
}

function showError(message) {
    const box = main.querySelector("#act-error");
    if (!box) return toast(message, "error");
    box.textContent = message;
    box.hidden = false;
    box.focus();
}

async function load() {
    try {
        page = await apiClient.modTagRequests({ tag: filters.tag, q: filters.q, page: filters.page });
    } catch (error) {
        main.querySelector("#list").innerHTML = `<div class="alert error" role="alert">${esc(error.message)}</div>`;
        return;
    }
    if (selected && !page.items.some((r) => r.id === selected)) {
        selected = null;
        detail = null;
    }
    renderList();
    renderDetail();
}

const shell = await mountStaffDesk("MODERATOR", "tags");
if (shell) {
    main = shell.main;
    renderPage();
    await load();
}
