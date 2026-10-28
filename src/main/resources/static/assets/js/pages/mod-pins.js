// UC-M05 / UC-M06 "Ghim mới" (Moderator): the pins waiting for approval, oldest first, with how many approved places
// are within 200 m ("Có thể trùng" when a name looks alike), and the chosen pin's details: its names, first review,
// a small map with the places around it, and "Duyệt" (the Vietnamese name and the category can be corrected first),
// "Từ chối" (with a reason) or "Gộp" into a place nearby.
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, miniMap, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic, stars } from "../lib/ui.js";

let main = null;
let queue = { items: [], categories: [] };
let filters = { category: "", duplicates: false };
let selected = null;         // id of the pin shown on the right
let detail = null;           // its PinDetail
let busy = false;

// ---------------------------------------------------------------- page

function renderPage() {
    main.innerHTML = `
        <div>
            <h2>Ghim mới</h2>
            <p class="note">Ghim do người dùng tự cắm cần Moderator duyệt mới hiện trên bản đồ. Địa điểm lấy từ OpenStreetMap không qua bước này. Ghim cũ nhất ở trên cùng.</p>
        </div>
        <div class="row mod-filters">
            <label class="sr-only" for="f-cat">Danh mục</label>
            <select class="input" id="f-cat"><option value="">Tất cả danh mục</option></select>
            <label class="check"><input type="checkbox" id="f-dup"> Chỉ ghim “Có thể trùng”</label>
        </div>
        <div class="mod-master">
            <div class="mod-list" id="list" aria-live="polite"></div>
            <section class="mod-detail" id="detail" aria-live="polite"></section>
        </div>`;
    main.querySelector("#f-cat").addEventListener("change", (event) => {
        filters.category = event.target.value;
        load();
    });
    main.querySelector("#f-dup").addEventListener("change", (event) => {
        filters.duplicates = event.target.checked;
        load();
    });
    main.querySelector("#list").addEventListener("click", (event) => {
        const row = event.target.closest("[data-id]");
        if (row) open(Number(row.dataset.id));
    });
    main.querySelector("#detail").addEventListener("click", onDetailClick);
}

function renderCategories() {
    const select = main.querySelector("#f-cat");
    if (select.options.length > 1) return;
    select.insertAdjacentHTML("beforeend", queue.categories
        .map((c) => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join(""));
    select.value = filters.category;
}

function renderList() {
    const box = main.querySelector("#list");
    if (!queue.items.length) {
        box.innerHTML = `<div class="card">${filters.category || filters.duplicates
            ? "Không có ghim nào khớp bộ lọc." : "Không có ghim nào chờ duyệt."}</div>`;
        return;
    }
    box.innerHTML = queue.items.map((p) => `
        <button class="card mod-item" type="button" data-id="${p.id}" ${p.id === selected ? 'aria-current="true"' : ""}>
            <span class="row"><b>${esc(p.nameVi)}</b>${p.possibleDuplicate ? `<span class="badge warn">${ic("content_copy")}Có thể trùng</span>` : ""}</span>
            ${p.nameJa || p.nameEn ? `<span class="note">${esc([p.nameJa, p.nameEn].filter(Boolean).join(" · "))}</span>` : ""}
            <span class="row note"><span class="badge">${ic(p.category.icon)}${esc(p.category.name)}</span>
                ${esc(p.authorName)} · ${esc(daysAgo(p.createdAt))} · ${p.nearby} địa điểm trong 200 m</span>
        </button>`).join("");
}

// ---------------------------------------------------------------- details

function renderDetail() {
    const box = main.querySelector("#detail");
    if (!detail) {
        box.innerHTML = queue.items.length ? `<div class="card note">Chọn một ghim để xem chi tiết.</div>` : "";
        return;
    }
    const d = detail;
    const r = d.review;
    box.innerHTML = `
        <div class="card mod-card">
            <div class="row"><h3>${esc(d.nameVi)}</h3><span class="badge">${ic(d.category.icon)}${esc(d.category.name)}</span></div>
            <dl class="mod-facts">
                ${d.nameJa ? `<dt>Tên tiếng Nhật</dt><dd lang="ja">${esc(d.nameJa)}</dd>` : ""}
                ${d.nameEn ? `<dt>Tên tiếng Anh</dt><dd>${esc(d.nameEn)}</dd>` : ""}
                <dt>Địa chỉ</dt><dd>${esc(d.address || "Chưa có")}</dd>
                <dt>Người gửi</dt><dd>${esc(d.authorName)} · ${esc(fullDate(d.createdAt))}</dd>
            </dl>
            <div class="pick-map mod-map" id="pin-map" role="region" aria-label="Vị trí ghim và các địa điểm trong bán kính 200 m"></div>

            <h4>Review đầu tiên</h4>
            ${r ? `<div class="mod-review">
                    <div class="row"><span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span>
                        ${r.status !== "PUBLISHED" ? `<span class="badge ${r.status === "REJECTED" ? "crit" : "lan"}">${r.status === "REJECTED" ? "Ảnh vi phạm, đã bị từ chối" : "Đang quét ảnh"}</span>` : ""}</div>
                    <p>${esc(r.content)}</p>
                    ${r.media.length ? `<div class="mod-media">${r.media.map((m) => m.type === "VIDEO"
                        ? `<video src="${esc(m.url)}" controls preload="metadata"></video>`
                        : `<a href="${esc(m.url)}" target="_blank" rel="noopener"><img src="${esc(m.url)}" alt="Ảnh trong review" loading="lazy"></a>`).join("")}</div>` : ""}
                </div>` : `<p class="note">Không có review đầu tiên.</p>`}

            <h4>Địa điểm trong bán kính 200 m</h4>
            ${d.nearby.length ? `<ul class="mod-near">${d.nearby.map((n) => `<li>
                    <a class="link" href="/map?place=${n.id}" target="_blank" rel="noopener"><b>${esc(n.name)}</b></a>
                    <span class="note">${esc(n.categoryName)} · ${n.distance} m · ${n.reviewCount} review</span>
                    ${n.similar ? `<span class="badge warn">Tên giống</span>` : ""}</li>`).join("")}</ul>`
                : `<p class="note">Không có địa điểm nào đang hoạt động trong bán kính 200 m.</p>`}

            <div class="alert error" id="act-error" role="alert" tabindex="-1" hidden></div>
            <div class="mod-actions">
                <div class="mod-act">
                    <h4>Duyệt</h4>
                    <div class="field"><label for="a-name">Tên tiếng Việt</label>
                        <input class="input" id="a-name" maxlength="160" value="${esc(d.nameVi)}" autocomplete="off"></div>
                    <div class="field"><label for="a-cat">Danh mục</label>
                        <select class="input" id="a-cat">${d.categories.map((c) => `<option value="${esc(c.id)}" ${c.id === d.category.id ? "selected" : ""}>${esc(c.name)}</option>`).join("")}</select></div>
                    <button class="btn pri" type="button" data-act="approve" ${d.scanning ? "disabled" : ""}>${ic("check")}Duyệt</button>
                    ${d.scanning ? `<p class="note">${ic("hourglass_top")} Ảnh đang được quét. Duyệt được khi có kết quả.</p>` : ""}
                </div>
                <div class="mod-act">
                    <h4>Từ chối</h4>
                    <div class="field"><label for="a-reason">Lý do từ chối</label>
                        <select class="input" id="a-reason"><option value="">Chọn lý do</option>
                            ${d.rejectReasons.map((x) => `<option value="${x.id}">${esc(x.label)}</option>`).join("")}</select></div>
                    <button class="btn dan" type="button" data-act="reject">${ic("block")}Từ chối</button>
                    <p class="note">Review đầu tiên bị xóa, người gửi được báo lý do.</p>
                </div>
                ${d.nearby.length ? `<div class="mod-act">
                    <h4>Gộp vào địa điểm có sẵn</h4>
                    <div class="field"><label for="a-target">Địa điểm</label>
                        <select class="input" id="a-target"><option value="">Chọn địa điểm</option>
                            ${d.nearby.map((n) => `<option value="${n.id}">${esc(n.name)} (${n.distance} m)</option>`).join("")}</select></div>
                    <button class="btn" type="button" data-act="merge">${ic("merge")}Gộp</button>
                    <p class="note">Review đầu tiên chuyển sang địa điểm đó nếu người gửi chưa có review ở đó.</p>
                </div>` : ""}
            </div>
        </div>`;
    miniMap(box.querySelector("#pin-map"), { lat: d.lat, lng: d.lng, icon: d.category.icon },
        d.nearby.map((n) => ({ lat: n.lat, lng: n.lng, name: n.name, label: `${n.distance} m`, kind: n.similar ? "warn" : "near" })));
}

async function open(id) {
    selected = id;
    renderList();
    try {
        detail = await apiClient.modPin(id);
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
    const id = detail.id;
    const value = (sel) => main.querySelector(sel)?.value || "";
    let call;
    if (button.dataset.act === "approve") {
        call = () => apiClient.modApprovePin(id, { nameVi: value("#a-name").trim(), categoryId: value("#a-cat") });
    } else if (button.dataset.act === "reject") {
        if (!value("#a-reason")) return showError("Hãy chọn lý do từ chối.");
        call = () => apiClient.modRejectPin(id, Number(value("#a-reason")));
    } else if (button.dataset.act === "merge") {
        if (!value("#a-target")) return showError("Hãy chọn địa điểm để gộp vào.");
        call = () => apiClient.modMergePin(id, Number(value("#a-target")));
    } else {
        return;
    }
    busy = true;
    button.disabled = true;
    try {
        const done = await call();
        toast(done.message);
        selected = null;
        detail = null;
        await load(true);
    } catch (error) {
        showError(error.message);
        if (error.status === 409 || error.status === 404) await load();
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

// ---------------------------------------------------------------- data

/** @param next open the oldest pin left (after an action) */
async function load(next = false) {
    try {
        queue = await apiClient.modPins({ category: filters.category, duplicates: filters.duplicates || "" });
    } catch (error) {
        main.querySelector("#list").innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được danh sách ghim.")}</div>`;
        return;
    }
    renderCategories();
    if (selected && !queue.items.some((p) => p.id === selected)) {
        selected = null;
        detail = null;
    }
    renderList();
    if (next && queue.items.length) {
        await open(queue.items[0].id);
    } else {
        renderDetail();
    }
}

// ---------------------------------------------------------------- start

const shell = await mountStaffDesk("MODERATOR", "pins");
if (shell) {
    main = shell.main;
    renderPage();
    const wanted = Number(new URLSearchParams(location.search).get("id"));
    await load(!wanted);
    if (wanted) await open(wanted);
}
