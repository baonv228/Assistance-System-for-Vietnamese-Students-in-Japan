// UC-M03 / UC-M04 "Báo cáo nội dung" (Moderator). Left: two tabs, "Cần xử lý khẩn" (over the threshold, already hidden)
// and "Cần xử lý" (still shown), most reports first, 10 a page, filtered by kind of content or reason. Right: the case
// with the full content, the reasons and the reporters; "Gỡ nội dung" or "Báo cáo sai".
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic } from "../lib/ui.js";

const KINDS = [["POST", "Bài viết"], ["QA", "Câu hỏi"], ["ANSWER", "Câu trả lời"], ["COMMENT", "Bình luận"],
    ["REVIEW", "Review"], ["MESSAGE", "Tin nhắn"]];

let main = null;
let page = { items: [], page: 0, size: 10, total: 0, urgentCount: 0, normalCount: 0, reasons: [] };
let filters = { tab: "URGENT", kind: "", reason: "", page: 0 };
let selected = null;
let detail = null;
let busy = false;

function renderPage() {
    main.innerHTML = `
        <div><h2>Báo cáo nội dung</h2>
            <p class="note">Đối chiếu nội dung với lý do báo cáo và Quy tắc cộng đồng. Báo đóng cửa nằm ở mục “Báo cáo đóng cửa”.</p></div>
        <div class="row mod-tabs" id="tabs" role="tablist" aria-label="Mức độ"></div>
        <div class="row mod-filters">
            <label class="sr-only" for="f-kind">Loại nội dung</label>
            <select class="input" id="f-kind"><option value="">Mọi loại nội dung</option>
                ${KINDS.map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>
            <label class="sr-only" for="f-reason">Lý do</label>
            <select class="input" id="f-reason"><option value="">Mọi lý do</option></select>
        </div>
        <div class="mod-master">
            <div><div class="mod-list" id="list" aria-live="polite"></div><div class="row mod-pager" id="pager"></div></div>
            <section class="mod-detail" id="detail" aria-live="polite"></section>
        </div>`;
    main.querySelector("#tabs").addEventListener("click", (event) => {
        const b = event.target.closest("[data-tab]");
        if (b && b.dataset.tab !== filters.tab) setFilter("tab", b.dataset.tab);
    });
    main.querySelector("#f-kind").addEventListener("change", (event) => setFilter("kind", event.target.value));
    main.querySelector("#f-reason").addEventListener("change", (event) => setFilter("reason", event.target.value));
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
    filters.page = 0;
    selected = null;
    detail = null;
    load();
}

function renderList() {
    main.querySelector("#tabs").innerHTML = [["URGENT", "Cần xử lý khẩn", page.urgentCount], ["NORMAL", "Cần xử lý", page.normalCount],
        ["REMOVED", "Đã gỡ", page.removedCount]]
        .map(([key, label, n]) => `<button class="btn sm ${filters.tab === key ? "pri" : ""}" type="button" role="tab"
            data-tab="${key}" aria-selected="${filters.tab === key}">${label} (${n})</button>`).join("");
    const reasonSelect = main.querySelector("#f-reason");
    if (reasonSelect.options.length === 1) {
        reasonSelect.insertAdjacentHTML("beforeend", page.reasons.map((r) => `<option value="${r.id}">${esc(r.label)}</option>`).join(""));
    }
    main.querySelector("#list").innerHTML = page.items.length ? page.items.map((c) => `
        <button class="card mod-item" type="button" data-id="${c.id}" ${c.id === selected ? 'aria-current="true"' : ""}>
            <span class="row"><b>${esc(c.label)}</b>${c.hidden ? `<span class="badge crit">Đang ẩn</span>` : ""}
                ${c.purgeAt ? `<span class="badge">${esc(purgeText(c.purgeAt))}</span>` : ""}</span>
            <span class="note">${esc(c.excerpt || "(không có phần chữ)")}</span>
            <span class="row note">${esc(c.authorName)} · ${c.reportCount} báo cáo · ${esc(c.topReason || "")} · ${esc(daysAgo(c.firstReportedAt))}</span>
        </button>`).join("")
        : `<div class="card">${filters.tab === "REMOVED" ? "Không có nội dung nào đã gỡ." : "Không có nội dung nào cần xử lý."}</div>`;
    const pages = Math.max(1, Math.ceil(page.total / page.size));
    main.querySelector("#pager").innerHTML = page.total > page.size ? `
        <button class="btn sm" type="button" data-page="${page.page - 1}" ${page.page === 0 ? "disabled" : ""} aria-label="Trang trước">‹</button>
        <span class="note">Trang ${page.page + 1}/${pages} · ${page.total} hồ sơ</span>
        <button class="btn sm" type="button" data-page="${page.page + 1}" ${page.page + 1 >= pages ? "disabled" : ""} aria-label="Trang sau">›</button>`
        : "";
}

/** "Còn 12 ngày" until a removed item is deleted for good. */
function purgeText(iso) {
    const days = Math.max(0, Math.ceil((new Date(iso) - Date.now()) / 86400000));
    return days === 0 ? "Xóa hẳn trong hôm nay" : `Còn ${days} ngày`;
}

function actionsHtml(d, top) {
    if (d.removed) {
        return `<div class="mod-actions">
            <div class="mod-act"><h4>Gỡ nhầm?</h4>
                <p class="note">Lý do đã gỡ: ${esc(d.removedBecause || "")}. Gỡ lúc ${esc(fullDate(d.removedAt))}; xóa hẳn lúc ${esc(fullDate(d.purgeAt))}.</p>
                <button class="btn pri" type="button" data-act="restore">${ic("restore")}Khôi phục</button>
                <p class="note">Nội dung hiện lại, tác giả được hoàn 20 điểm và được báo. Người báo cáo không bị tính báo cáo sai.</p></div>
        </div>`;
    }
    return `<div class="mod-actions">
            <div class="mod-act"><h4>Vi phạm</h4>
                <div class="field"><label for="r-reason">Lý do gỡ</label>
                    <select class="input" id="r-reason">${d.removeReasons.map((r) =>
                        `<option value="${r.id}" ${r.label === top ? "selected" : ""}>${esc(r.label)}</option>`).join("")}</select></div>
                <div class="field"><label for="r-note">Ghi chú cho tác giả (không bắt buộc)</label>
                    <textarea class="input short" id="r-note" maxlength="200" rows="2"></textarea></div>
                <button class="btn dan" type="button" data-act="remove">${ic("delete")}Gỡ nội dung</button>
                <p class="note">Tác giả bị trừ 20 điểm và được báo lý do. Khôi phục được trong 30 ngày, sau đó nội dung bị xóa hẳn.${d.kind === "MESSAGE" ? " Riêng tin nhắn đã gỡ thì không khôi phục được." : ""}</p></div>
            <div class="mod-act"><h4>Không vi phạm</h4>
                <button class="btn" type="button" data-act="dismiss">${ic(d.hidden ? "undo" : "check")}${d.hidden ? "Báo cáo sai, khôi phục" : "Báo cáo sai, bỏ qua"}</button>
                <p class="note">Không trừ điểm tác giả. Mỗi người báo cáo bị tính thêm 1 lần báo cáo sai.</p></div>
        </div>`;
}

function mediaHtml(d) {
    if (!d.media.length) return "";
    return `<div class="mod-media">${d.media.map((m) => m.type === "VIDEO"
        ? `<video src="${esc(m.url)}" controls preload="metadata"></video>`
        : `<img src="${esc(m.url)}" alt="Ảnh trong nội dung bị báo cáo" loading="lazy">`).join("")}</div>`;
}

function renderDetail() {
    const box = main.querySelector("#detail");
    if (!detail) {
        box.innerHTML = page.items.length ? `<div class="card note">Chọn một hồ sơ để xem chi tiết.</div>` : "";
        return;
    }
    const d = detail;
    const top = d.reasons[0]?.label;
    const encryptedNote = d.encrypted ? `<div class="info">${ic("lock")} Tin nhắn mã hóa đầu cuối: đây là phần người báo cáo đưa ra, hệ thống
        đã kiểm tra đúng là tin người viết đã gửi.${d.files ? ` Tin có ${d.files} tệp đính kèm, không xem được ở đây.` : ""}</div>` : "";
    box.innerHTML = `<div class="card mod-card">
        <div class="row"><h3>${esc(d.label)}</h3>
            <span class="badge ${d.hidden ? "crit" : ""}">${d.hidden ? "Đang ẩn" : "Đang hiển thị"}</span></div>
        <dl class="mod-facts">
            <dt>Tác giả</dt><dd><a class="link" href="/users/${d.authorId}">${esc(d.authorName)}</a>
                ${d.authorStatus === "ACTIVE" ? "" : `<span class="badge crit">${d.authorStatus === "LOCKED" ? "Đã khóa" : "Đã xóa"}</span>`}</dd>
            <dt>Đăng lúc</dt><dd>${esc(fullDate(d.contentCreatedAt))}</dd>
            <dt>Báo cáo đầu</dt><dd>${esc(fullDate(d.firstReportedAt))}</dd>
        </dl>
        ${d.link ? `<a class="link" href="${esc(d.link)}" target="_blank" rel="noopener">${ic("open_in_new")}Xem tại trang gốc</a>` : ""}
        ${encryptedNote}
        <div class="mod-review">
            ${d.title && d.kind !== "REVIEW" ? `<b>${esc(d.title)}</b>` : ""}
            <p class="mod-text">${d.text ? esc(d.text) : `<span class="note">(không có phần chữ)</span>`}</p>
            ${mediaHtml(d)}
        </div>
        <div><h4>Lý do</h4><p>${d.reasons.map((r) => `<span class="badge">${esc(r.label)} · ${r.count}</span>`).join(" ")}</p></div>
        <div><h4>Người báo cáo (${d.reporters.length})</h4>
            <ul class="mod-history">${d.reporters.map((r) => `<li><b>${esc(r.name)}</b> · ${esc(r.reason)}
                ${r.falseReports ? `<span class="badge">${r.falseReports} lần báo cáo sai</span>` : ""}
                <span class="note">${esc(daysAgo(r.reportedAt))}</span>
                ${r.note ? `<br><span class="note">${esc(r.note)}</span>` : ""}</li>`).join("")}</ul></div>
        <div class="alert error" id="act-error" role="alert" tabindex="-1" hidden></div>
        ${actionsHtml(d, top)}
    </div>`;
}

async function open(id) {
    selected = id;
    renderList();
    try {
        detail = await apiClient.modReport(id);
    } catch (error) {
        detail = null;
        selected = null;
        toast(error.message, "error");
        await load();
        return;
    }
    renderDetail();
}

async function onDetailClick(event) {
    const button = event.target.closest("[data-act]");
    if (!button || busy || !detail) return;
    const act = button.dataset.act;
    if (act === "remove" && !confirm("Gỡ nội dung này? Tác giả sẽ bị trừ 20 điểm.")) return;
    busy = true;
    button.disabled = true;
    try {
        let done;
        if (act === "remove") {
            done = await apiClient.modRemoveReported(detail.id, { reasonId: Number(main.querySelector("#r-reason").value),
                note: main.querySelector("#r-note").value.trim() || null });
        } else if (act === "restore") {
            done = await apiClient.modRestoreReported(detail.id);
        } else {
            done = await apiClient.modDismissReport(detail.id);
        }
        toast(done.message);
        selected = null;
        detail = null;
        await load();
    } catch (error) {
        showError(error.message);
        if (error.status === 409) {        // AF6: handled by another Moderator
            selected = null;
            detail = null;
            await load();
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
        page = await apiClient.modReports({ tab: filters.tab, kind: filters.kind, reason: filters.reason, page: filters.page });
    } catch (error) {
        main.querySelector("#list").innerHTML = `<div class="alert error" role="alert">${esc(error.message)}</div>`;
        return;
    }
    if (selected && !page.items.some((c) => c.id === selected)) {
        selected = null;
        detail = null;
    }
    renderList();
    renderDetail();
}

const shell = await mountStaffDesk("MODERATOR", "reports");
if (shell) {
    main = shell.main;
    renderPage();
    await load();
}
