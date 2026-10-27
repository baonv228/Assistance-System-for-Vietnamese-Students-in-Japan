// UC-M07 / UC-M08 "Địa điểm" (Moderator). Left: the places list (search, category, source, status, prefecture; 10 a page).
// Right, one of:
//   view   — the place's details, small map, actions (Sửa thông tin, Gỡ khỏi bản đồ / Khôi phục, nhãn đóng cửa) and
//            its history ("Danh mục: Quán ăn → Siêu thị", who, when);
//   edit   — the form filled in (drag the pin to move the place); OpenStreetMap places keep their OSM id;
//   remove — "Gỡ “…” khỏi bản đồ?" with the reason and a note;
//   add    — "Thêm địa điểm": step 1 search OpenStreetMap ("Mở" a place Senpai ơi has, "Chọn" one it has not, or
//            "Cắm ghim tay"), step 2 the form. A similar name within 200 m asks "Mở địa điểm đó" / "Vẫn lưu".
// /moderator/places?id=12 opens a place, ?new=1 opens "Thêm địa điểm".
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { miniMap, SOURCE_LABELS, STATUS_LABELS, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic, osmUrl } from "../lib/ui.js";

const DEFAULT_VIEW = { lat: 35.7279, lng: 139.7707 };      // Nippori, where the team's sample places are
const SEARCH_DELAY = 350;
const STATUS_CLASS = { ACTIVE: "ok", PENDING: "lan", REMOVED: "crit", REJECTED: "crit", MERGED: "" };
const SOURCE_FILTERS = [["OSM", "OpenStreetMap"], ["MODERATOR", "Nhóm Senpai ơi thêm"], ["USER", "Ghim cộng đồng"],
    ["GOOGLE", "Google Maps"]];

let main = null;
let lists = { categories: [], prefectures: [] };
let page = { items: [], page: 0, size: 10, total: 0 };
let filters = { q: "", category: "", source: "", status: "ACTIVE", prefecture: "", page: 0 };
let selected = null;        // id of the place on the right
let info = null;            // its PlaceInfo
let mode = "view";          // view | edit | remove | add
let draft = null;           // the form being filled (edit, add)
let duplicates = [];        // "Có thể trùng" after a save
let add = { step: "search", query: "", results: null, ticket: 0 };
let searchTimer = null;
let listTimer = null;
let busy = false;

// ---------------------------------------------------------------- page

function renderPage() {
    main.innerHTML = `
        <div class="row mod-head">
            <div class="grow"><h2>Địa điểm</h2>
                <p class="note">Mọi địa điểm của Senpai ơi. Địa điểm không bao giờ bị xóa hẳn: gỡ khỏi bản đồ thì vẫn khôi phục được, review và lượt lưu được giữ.</p></div>
            <button class="btn pri" type="button" id="add-btn">${ic("add_location_alt")}Thêm địa điểm</button>
        </div>
        <div class="row mod-filters">
            <div class="searchbox">${ic("search")}<label class="sr-only" for="f-q">Tìm theo tên hoặc địa chỉ</label>
                <input id="f-q" type="search" placeholder="Tìm theo tên hoặc địa chỉ" maxlength="100" autocomplete="off"></div>
            <label class="sr-only" for="f-cat">Danh mục</label><select class="input" id="f-cat"><option value="">Tất cả danh mục</option></select>
            <label class="sr-only" for="f-src">Nguồn</label><select class="input" id="f-src"><option value="">Mọi nguồn</option>
                ${SOURCE_FILTERS.map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>
            <label class="sr-only" for="f-st">Trạng thái</label><select class="input" id="f-st"><option value="">Mọi trạng thái</option>
                ${Object.entries(STATUS_LABELS).map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>
            <label class="sr-only" for="f-pref">Tỉnh</label><select class="input" id="f-pref"><option value="">Mọi tỉnh</option></select>
        </div>
        <div class="mod-master">
            <div><div class="mod-list" id="list" aria-live="polite"></div><div class="row mod-pager" id="pager"></div></div>
            <section class="mod-detail" id="detail" aria-live="polite"></section>
        </div>`;
    main.querySelector("#f-st").value = filters.status;
    main.querySelector("#f-q").addEventListener("input", (event) => {
        clearTimeout(listTimer);
        listTimer = setTimeout(() => setFilter("q", event.target.value.trim()), SEARCH_DELAY);
    });
    [["#f-cat", "category"], ["#f-src", "source"], ["#f-st", "status"], ["#f-pref", "prefecture"]].forEach(([sel, key]) =>
        main.querySelector(sel).addEventListener("change", (event) => setFilter(key, event.target.value)));
    main.querySelector("#add-btn").addEventListener("click", startAdd);
    main.querySelector("#list").addEventListener("click", (event) => {
        const row = event.target.closest("[data-id]");
        if (row) openPlace(Number(row.dataset.id));
    });
    main.querySelector("#pager").addEventListener("click", (event) => {
        const b = event.target.closest("[data-page]");
        if (!b) return;
        filters.page = Number(b.dataset.page);
        loadList();
    });
    const detail = main.querySelector("#detail");
    detail.addEventListener("click", onDetailClick);
    detail.addEventListener("input", onDetailInput);
    detail.addEventListener("change", onDetailInput);
    detail.addEventListener("submit", (event) => {
        event.preventDefault();
        if (event.target.id === "place-form") savePlace(false);
        if (event.target.id === "remove-form") removePlace();
    });
}

function setFilter(key, value) {
    filters[key] = value;
    filters.page = 0;
    loadList();
}

function renderLists() {
    const cat = main.querySelector("#f-cat");
    if (cat.options.length === 1) {
        cat.insertAdjacentHTML("beforeend", lists.categories.map((c) => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join(""));
    }
    const pref = main.querySelector("#f-pref");
    if (pref.options.length === 1) {
        pref.insertAdjacentHTML("beforeend", lists.prefectures.map((p) => `<option value="${esc(p.code)}">${esc(p.name)}</option>`).join(""));
    }
}

function renderList() {
    const box = main.querySelector("#list");
    box.innerHTML = page.items.length ? page.items.map((p) => `
        <button class="card mod-item" type="button" data-id="${p.id}" ${p.id === selected && mode !== "add" ? 'aria-current="true"' : ""}>
            <span class="row"><b>${esc(p.nameVi)}</b>${statusBadge(p.status)}${p.closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}</span>
            ${p.nameJa ? `<span class="note" lang="ja">${esc(p.nameJa)}</span>` : ""}
            <span class="row note"><span class="badge">${ic(p.category.icon)}${esc(p.category.name)}</span>
                ${esc(SOURCE_LABELS[p.source] || p.source)} · ${p.reviewCount} review${p.areaName ? ` · ${esc(p.areaName)}` : ""}</span>
        </button>`).join("") : `<div class="card">Không có địa điểm nào khớp bộ lọc.</div>`;
    const pages = Math.max(1, Math.ceil(page.total / page.size));
    main.querySelector("#pager").innerHTML = page.total > page.size ? `
        <button class="btn sm" type="button" data-page="${page.page - 1}" ${page.page === 0 ? "disabled" : ""}>${ic("chevron_left")}Trước</button>
        <span class="note">Trang ${page.page + 1}/${pages} · ${page.total} địa điểm</span>
        <button class="btn sm" type="button" data-page="${page.page + 1}" ${page.page + 1 >= pages ? "disabled" : ""}>Sau${ic("chevron_right")}</button>`
        : `<span class="note">${page.total} địa điểm</span>`;
}

const statusBadge = (status) => `<span class="badge ${STATUS_CLASS[status] ?? ""}">${esc(STATUS_LABELS[status] || status)}</span>`;

// ---------------------------------------------------------------- right side

function renderDetail() {
    const box = main.querySelector("#detail");
    if (mode === "add") {
        box.innerHTML = add.step === "search" ? addSearchHtml() : formHtml();
        if (add.step === "search") box.querySelector("#osm-q")?.focus();
        else drawFormMap();
        return;
    }
    if (!info) {
        box.innerHTML = `<div class="card note">Chọn một địa điểm để xem chi tiết, hoặc bấm “Thêm địa điểm”.</div>`;
        return;
    }
    box.innerHTML = mode === "edit" ? formHtml() : mode === "remove" ? removeHtml() : viewHtml();
    if (mode === "edit") {
        drawFormMap();
    } else if (mode === "view" && info.lat != null) {
        miniMap(box.querySelector("#place-map"), { lat: info.lat, lng: info.lng, icon: info.category.icon });
    }
}

function viewHtml() {
    const p = info;
    const active = p.status === "ACTIVE";
    return `<div class="card mod-card">
        <div class="row"><h3>${esc(p.nameVi)}</h3>${statusBadge(p.status)}${p.closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}</div>
        ${p.status === "REMOVED" ? `<div class="alert error">${ic("visibility_off")} Đã gỡ khỏi bản đồ: ${esc(p.removedReason || "")}. Review và lượt lưu vẫn được giữ, bấm “Khôi phục” để đưa trở lại.</div>` : ""}
        ${p.status === "REJECTED" ? `<div class="alert error">Ghim bị từ chối: ${esc(p.rejectReason || "")}</div>` : ""}
        ${p.status === "MERGED" ? `<div class="alert quota">${ic("merge")} Đã gộp vào <a class="link" href="?id=${p.mergedIntoId}" data-open="${p.mergedIntoId}">${esc(p.mergedIntoName || "#" + p.mergedIntoId)}</a>.</div>` : ""}
        ${p.status === "PENDING" ? `<div class="alert quota">Ghim đang chờ duyệt. <a class="link" href="/moderator/pins?id=${p.id}">Mở ở mục Duyệt ghim mới</a></div>` : ""}
        <dl class="mod-facts">
            ${p.nameJa ? `<dt>Tên tiếng Nhật</dt><dd lang="ja">${esc(p.nameJa)}</dd>` : ""}
            ${p.nameEn ? `<dt>Tên tiếng Anh</dt><dd>${esc(p.nameEn)}</dd>` : ""}
            <dt>Danh mục</dt><dd>${ic(p.category.icon)} ${esc(p.category.name)}</dd>
            <dt>Khu vực</dt><dd>${esc(p.areaName || "Chưa chọn")}</dd>
            <dt>Địa chỉ</dt><dd>${esc(p.address || "Chưa có")}</dd>
            <dt>Nguồn</dt><dd>${esc(SOURCE_LABELS[p.source] || p.source)}${p.osmId ? ` · <a class="link" href="${esc(osmUrl(p))}" target="_blank" rel="noopener">${esc(p.osmId)}</a>` : ""}</dd>
            <dt>Người thêm</dt><dd>${esc(p.authorName)} · ${esc(fullDate(p.createdAt))}</dd>
            <dt>Review</dt><dd>${p.reviewCount} · ${p.saveCount} lượt lưu${p.closedReports ? ` · <span class="badge warn">${p.closedReports} báo đóng cửa</span>` : ""}</dd>
        </dl>
        ${p.lat != null ? `<div class="pick-map mod-map" id="place-map" role="region" aria-label="Vị trí địa điểm"></div>` : `<p class="note">Địa điểm cũ từ Google Maps chưa có vị trí. Bấm “Sửa thông tin” để đặt vị trí.</p>`}
        <div class="row mod-buttons">
            ${active || p.status === "REMOVED" ? `<button class="btn" type="button" data-act="edit">${ic("edit")}Sửa thông tin</button>` : ""}
            ${active ? `<button class="btn" type="button" data-act="${p.closed ? "reopen" : "close"}">${ic("storefront")}${p.closed ? "Gỡ nhãn đóng cửa" : "Đánh dấu đã đóng cửa"}</button>` : ""}
            ${active ? `<button class="btn dan" type="button" data-act="remove">${ic("visibility_off")}Gỡ khỏi bản đồ</button>` : ""}
            ${p.status === "REMOVED" ? `<button class="btn pri" type="button" data-act="restore">${ic("undo")}Khôi phục</button>` : ""}
            ${active ? `<a class="btn" href="/moderator/merge?a=${p.id}">${ic("merge")}Gộp với địa điểm khác</a>` : ""}
            ${active ? `<a class="btn ghost" href="/map?place=${p.id}" target="_blank" rel="noopener">${ic("map")}Xem trên bản đồ</a>` : ""}
        </div>
        <h4>Lịch sử</h4>
        ${p.history.length ? `<ol class="mod-history">${p.history.map((h) => `<li><span>${esc(h.text)}</span>
            <span class="note">${esc(h.actorName)} · ${esc(fullDate(h.at))}</span></li>`).join("")}</ol>` : `<p class="note">Chưa có thay đổi nào.</p>`}
    </div>`;
}

function removeHtml() {
    return `<form class="card mod-card" id="remove-form" novalidate>
        <h3>Gỡ “${esc(info.nameVi)}” khỏi bản đồ?</h3>
        <p class="note">Địa điểm không còn hiện trên bản đồ, tìm kiếm và bài viết${info.reviewCount ? `; ${info.reviewCount} review sẽ bị ẩn` : ""}. Không xóa hẳn: Moderator khôi phục được bất cứ lúc nào.</p>
        <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
        <div class="field"><label for="r-reason">Lý do <span class="req" aria-hidden="true">*</span></label>
            <select class="input" id="r-reason"><option value="">Chọn lý do</option>
                ${info.removeReasons.map((r) => `<option value="${r.id}">${esc(r.label)}</option>`).join("")}</select></div>
        <div class="field"><label for="r-note">Ghi chú</label>
            <textarea class="input short" id="r-note" maxlength="200" rows="2"></textarea></div>
        <div class="row"><button class="btn dan solid" type="submit">${ic("visibility_off")}Gỡ khỏi bản đồ</button>
            <button class="btn" type="button" data-act="cancel">Hủy</button></div>
    </form>`;
}

// ---------------------------------------------------------------- "Thêm địa điểm"

function addSearchHtml() {
    const r = add.results;
    let results = "";
    if (r) {
        const items = r.items || [];
        results = items.length ? `<ul class="mod-osm">${items.map((o) => `<li>
                <span><b>${esc(o.name)}</b>${o.nameJa && o.nameJa !== o.name ? ` <span class="note" lang="ja">${esc(o.nameJa)}</span>` : ""}
                <br><span class="note">${esc([o.category, o.address].filter(Boolean).join(" · "))}</span></span>
                ${o.id != null
                    ? `<span class="badge ok">Đã có trên Senpai ơi</span><button class="btn sm" type="button" data-open="${o.id}">Mở</button>`
                    : `<button class="btn sm pri" type="button" data-pick="${esc(o.externalId)}">Chọn</button>`}</li>`).join("")}</ul>`
            : `<p class="note">Không tìm thấy trên OpenStreetMap. Bạn có thể cắm ghim tay.</p>`;
        if (r.external === "UNAVAILABLE" || r.external === "BUSY") {
            results = `<div class="alert error">Chưa tìm được trên OpenStreetMap, hãy thử lại sau. Bạn vẫn có thể cắm ghim tay.</div>` + results;
        } else if (r.external === "SHORT") {
            results = `<p class="note">Gõ ít nhất 2 ký tự để tìm trên OpenStreetMap.</p>`;
        }
    }
    return `<div class="card mod-card">
        <h3>Thêm địa điểm</h3>
        <p class="note"><b>Bước 1. Tìm trên OpenStreetMap.</b> Địa điểm lấy từ OpenStreetMap có sẵn tên, địa chỉ và vị trí. Chỉ cắm ghim tay khi OpenStreetMap không có.</p>
        <div class="searchbox">${ic("travel_explore")}<label class="sr-only" for="osm-q">Tên hoặc địa chỉ</label>
            <input id="osm-q" type="search" placeholder="Tên hoặc địa chỉ, ví dụ: ga Ueno, Lawson Nippori" maxlength="100" autocomplete="off" value="${esc(add.query)}"></div>
        <div id="osm-results" aria-live="polite" aria-busy="false">${results}</div>
        <div class="row"><button class="btn" type="button" data-act="manual">${ic("add_location")}Cắm ghim tay</button>
            <button class="btn ghost" type="button" data-act="cancel">Hủy</button></div>
    </div>`;
}

async function searchOsm(query) {
    add.query = query;
    const ticket = ++add.ticket;
    if (query.length < 2) {
        add.results = null;
        renderOsmResults();
        return;
    }
    main.querySelector("#osm-results")?.setAttribute("aria-busy", "true");
    try {
        const results = await apiClient.modSearchOsm(query);
        if (ticket !== add.ticket) return;          // an older answer after the text changed
        add.results = results;
    } catch (error) {
        if (ticket !== add.ticket) return;
        add.results = { items: [], external: "UNAVAILABLE" };
    }
    renderOsmResults();
}

/** Redraws only the results, so typing in the box is not interrupted. */
function renderOsmResults() {
    const box = main.querySelector("#osm-results");
    if (!box || mode !== "add" || add.step !== "search") return;
    const html = addSearchHtml();
    const tmp = document.createElement("div");
    tmp.innerHTML = html;
    box.replaceWith(tmp.querySelector("#osm-results"));
}

async function pickOsm(externalId) {
    try {
        const pick = await apiClient.modPickOsm(externalId);
        if (pick.existingId) {
            toast("Địa điểm này đã có trên Senpai ơi.");
            await openPlace(pick.existingId);
            return;
        }
        draft = { ...pick.prefill, confirmDuplicate: false };
        duplicates = [];
        add.step = "form";
        renderDetail();
    } catch (error) {
        toast(error.message, "error");
    }
}

function startAdd() {
    mode = "add";
    add = { step: "search", query: "", results: null, ticket: add.ticket + 1 };
    draft = null;
    duplicates = [];
    history.replaceState(null, "", "?new=1");
    renderList();
    renderDetail();
}

// ---------------------------------------------------------------- the form (add, edit)

function emptyDraft() {
    return { osmId: null, nameVi: "", nameJa: "", nameEn: "", address: "", categoryId: "", lat: null,
        lng: null, confirmDuplicate: false };
}

function formHtml() {
    const d = draft;
    const editing = mode === "edit";
    const title = editing ? `Sửa thông tin “${esc(info.nameVi)}”` : d.osmId ? "Thêm vào Senpai ơi" : "Thêm địa điểm (cắm ghim tay)";
    const option = (v, l, cur) => `<option value="${esc(v)}" ${String(v) === String(cur ?? "") ? "selected" : ""}>${esc(l)}</option>`;
    return `<form class="card mod-card" id="place-form" novalidate>
        <h3>${title}</h3>
        ${d.osmId ? `<p class="note">${ic("public")} Dữ liệu gốc từ OpenStreetMap (mã ${esc(d.osmId)}). Mã OSM không sửa được; thay đổi ở đây chỉ lưu trên Senpai ơi, không ghi lên OpenStreetMap.</p>` : ""}
        <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
        ${duplicates.length ? `<div class="alert quota full" role="alert"><div><b>Có thể trùng với địa điểm đã có:</b>
            <ul class="mod-near">${duplicates.map((n) => `<li><b>${esc(n.name)}</b><span class="note">${esc(n.categoryName)} · ${n.distance} m · ${n.reviewCount} review</span>
                <button class="btn sm" type="button" data-open="${n.id}">Mở địa điểm đó</button></li>`).join("")}</ul>
            <button class="btn sm pri" type="button" data-act="save-anyway">Vẫn lưu</button></div></div>` : ""}
        <div class="split">
            <div class="col">
                <div class="field"><label for="p-vi">Tên tiếng Việt <span class="req" aria-hidden="true">*</span></label>
                    <input class="input" id="p-vi" data-bind="nameVi" maxlength="160" value="${esc(d.nameVi || "")}" autocomplete="off"></div>
                <div class="field"><label for="p-ja">Tên tiếng Nhật</label>
                    <input class="input" id="p-ja" data-bind="nameJa" maxlength="160" lang="ja" value="${esc(d.nameJa || "")}" autocomplete="off"></div>
                <div class="field"><label for="p-en">Tên tiếng Anh</label>
                    <input class="input" id="p-en" data-bind="nameEn" maxlength="160" value="${esc(d.nameEn || "")}" autocomplete="off"></div>
                <div class="field"><label for="p-addr">Địa chỉ</label>
                    <input class="input" id="p-addr" data-bind="address" maxlength="255" value="${esc(d.address || "")}" autocomplete="off"></div>
                <div class="field"><label for="p-cat">Danh mục <span class="req" aria-hidden="true">*</span></label>
                    <select class="input" id="p-cat" data-bind="categoryId"><option value="">Chọn danh mục</option>
                        ${lists.categories.map((c) => option(c.id, c.name, d.categoryId)).join("")}</select></div>
                <p class="note">${ic("location_city")} Khu vực (quận, thành phố) tự lấy theo vị trí trên bản đồ khi lưu.</p>
            </div>
            <div class="col">
                <span class="label-like"><b>Vị trí trên bản đồ</b> <span class="req" aria-hidden="true">*</span></span>
                <div class="pick-map" id="form-map" role="region" aria-label="Bản đồ đặt vị trí"></div>
                <p class="note" id="pos-note">${positionNote()}</p>
            </div>
        </div>
        <div class="row"><button class="btn pri" type="submit">${ic("save")}${editing ? "Lưu thay đổi" : d.osmId ? "Thêm vào Senpai ơi" : "Thêm địa điểm"}</button>
            <button class="btn" type="button" data-act="cancel">Hủy</button></div>
    </form>`;
}

function positionNote() {
    return draft.lat == null ? "Bấm lên bản đồ để đặt vị trí." : `Kéo ghim hoặc bấm chỗ khác để đổi vị trí (${draft.lat.toFixed(5)}, ${draft.lng.toFixed(5)}).`;
}

function drawFormMap() {
    const box = main.querySelector("#form-map");
    if (!box) return;
    const at = draft.lat == null ? { ...DEFAULT_VIEW, unset: true } : { lat: draft.lat, lng: draft.lng };
    const icon = lists.categories.find((c) => c.id === draft.categoryId)?.icon || "add_location_alt";
    miniMap(box, { ...at, icon }, [], (lat, lng) => {
        draft.lat = lat;
        draft.lng = lng;
        const note = main.querySelector("#pos-note");
        if (note) note.textContent = positionNote();
    });
}

function onDetailInput(event) {
    const key = event.target.dataset?.bind;
    if (key && draft) draft[key] = event.target.value;
    if (event.target.id === "osm-q" && event.type === "input") {
        clearTimeout(searchTimer);
        const query = event.target.value.trim();
        searchTimer = setTimeout(() => searchOsm(query), SEARCH_DELAY);
    }
}

async function savePlace(confirm) {
    if (busy) return;
    const body = { osmId: draft.osmId || null, nameVi: draft.nameVi, nameJa: draft.nameJa, nameEn: draft.nameEn,
        address: draft.address, categoryId: draft.categoryId || null,
        lat: draft.lat, lng: draft.lng, confirmDuplicate: confirm };
    busy = true;
    try {
        const saved = mode === "edit" ? await apiClient.modUpdatePlace(info.id, body) : await apiClient.modCreatePlace(body);
        if (!saved.placeId) {
            duplicates = saved.duplicates;
            renderDetail();
            return;
        }
        toast(saved.message);
        duplicates = [];
        await loadList();
        await openPlace(saved.placeId);
    } catch (error) {
        showError(error.message, error.fields);
    } finally {
        busy = false;
    }
}

function showError(message, fields = {}) {
    const box = main.querySelector("#form-error");
    if (!box) return toast(message, "error");
    const all = [...new Set(Object.values(fields || {}).filter((v) => typeof v === "string"))];
    box.textContent = all.length > 1 ? all.join(" ") : message;
    box.hidden = false;
    const ids = { nameVi: "#p-vi", categoryId: "#p-cat", position: "#form-map", reasonId: "#r-reason", note: "#r-note" };
    main.querySelectorAll("[aria-invalid]").forEach((el) => el.removeAttribute("aria-invalid"));
    Object.keys(fields || {}).forEach((f) => main.querySelector(ids[f])?.setAttribute("aria-invalid", "true"));
    box.focus();
}

// ---------------------------------------------------------------- actions

async function onDetailClick(event) {
    const open = event.target.closest("[data-open]");
    if (open) {
        event.preventDefault();
        await openPlace(Number(open.dataset.open));
        return;
    }
    const pick = event.target.closest("[data-pick]");
    if (pick) {
        await pickOsm(pick.dataset.pick);
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button || busy) return;
    switch (button.dataset.act) {
        case "edit":
            mode = "edit";
            duplicates = [];
            draft = { osmId: info.osmId, nameVi: info.nameVi, nameJa: info.nameJa, nameEn: info.nameEn,
                address: info.address, categoryId: info.category.id, lat: info.lat,
                lng: info.lng, confirmDuplicate: false };
            renderDetail();
            break;
        case "remove":
            mode = "remove";
            renderDetail();
            break;
        case "cancel":
            if (mode === "add" && !selected) {
                mode = "view";
                history.replaceState(null, "", location.pathname);
                renderList();
                renderDetail();
            } else if (mode === "add") {
                await openPlace(selected);
            } else {
                mode = "view";
                renderDetail();
            }
            break;
        case "manual":
            draft = emptyDraft();
            duplicates = [];
            add.step = "form";
            renderDetail();
            break;
        case "save-anyway":
            await savePlace(true);
            break;
        case "restore":
            await act(() => apiClient.modRestorePlace(info.id));
            break;
        case "close":
            await act(() => apiClient.modSetClosed(info.id, true));
            break;
        case "reopen":
            await act(() => apiClient.modSetClosed(info.id, false));
            break;
        default:
    }
}

async function removePlace() {
    const reasonId = main.querySelector("#r-reason").value;
    if (!reasonId) return showError("Hãy chọn lý do gỡ.", { reasonId: true });
    await act(() => apiClient.modRemovePlace(info.id, Number(reasonId), main.querySelector("#r-note").value.trim()));
}

async function act(call) {
    busy = true;
    try {
        const done = await call();
        toast(done.message);
        await loadList();
        await openPlace(info.id);
    } catch (error) {
        if (mode === "remove") showError(error.message, error.fields);
        else toast(error.message, "error");
    } finally {
        busy = false;
    }
}

// ---------------------------------------------------------------- data

async function openPlace(id) {
    selected = id;
    mode = "view";
    draft = null;
    duplicates = [];
    history.replaceState(null, "", `?id=${id}`);
    renderList();
    try {
        info = await apiClient.modPlace(id);
    } catch (error) {
        info = null;
        toast(error.message, "error");
    }
    renderDetail();
}

async function loadList() {
    try {
        page = await apiClient.modPlaces({ q: filters.q, category: filters.category, source: filters.source,
            status: filters.status, prefecture: filters.prefecture, page: filters.page });
    } catch (error) {
        main.querySelector("#list").innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được danh sách địa điểm.")}</div>`;
        return;
    }
    lists = { categories: page.categories, prefectures: page.prefectures };
    renderLists();
    renderList();
}

// ---------------------------------------------------------------- start

const shell = await mountStaffDesk("MODERATOR", "places");
if (shell) {
    main = shell.main;
    renderPage();
    await loadList();
    const params = new URLSearchParams(location.search);
    if (params.get("new")) {
        startAdd();
    } else if (Number(params.get("id"))) {
        await openPlace(Number(params.get("id")));
    } else {
        renderDetail();
    }
}
