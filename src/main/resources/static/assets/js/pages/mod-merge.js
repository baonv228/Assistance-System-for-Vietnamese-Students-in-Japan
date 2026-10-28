// UC-M09 "Gộp trùng lặp" (Moderator). Three views:
//   queue   — pairs of approved places that look like the same place ("Cao" first, 10 a page): "So sánh", "Không trùng";
//   pick    — "Chọn địa điểm để so sánh": two searches, the second sorted by distance from the first;
//   compare — the two places side by side with a map, which one is kept, which value of each field it keeps, what will
//             move to it, the box "Tôi đã kiểm tra…" and "Gộp" (final, no undo).
// /moderator/merge?a=1&b=2 opens the comparison, ?a=1 the picker with A chosen ("Gộp với địa điểm khác" on a place).
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { miniMap, SOURCE_LABELS, toast } from "../lib/mod-place-ui.js";
import { esc, ic } from "../lib/ui.js";

const SEARCH_DELAY = 300;
const LEVELS = { HIGH: ["crit", "Cao"], MEDIUM: ["warn", "Trung bình"] };
const FIELDS = [["nameVi", "Tên tiếng Việt"], ["nameJa", "Tên tiếng Nhật"], ["nameEn", "Tên tiếng Anh"],
    ["address", "Địa chỉ"], ["categoryId", "Danh mục"], ["areaId", "Khu vực"], ["position", "Vị trí trên bản đồ"]];

let main = null;
let view = "queue";
let queue = { items: [], page: 0, size: 10, total: 0 };
let pick = { a: null, b: null, results: { a: [], b: [] }, ticket: 0 };
let cmp = null;            // MergeCompare
let kept = "a";            // "a" | "b"
let choice = {};           // field → "a" | "b"
let confirmed = false;
let busy = false;
let searchTimer = null;

const levelBadge = (level) => `<span class="badge ${LEVELS[level][0]}">Mức ${LEVELS[level][1]}</span>`;
const sideHtml = (s) => `<b>${esc(s.name)}</b>${s.nameJa ? ` <span class="note" lang="ja">${esc(s.nameJa)}</span>` : ""}
    <br><span class="note">${esc(SOURCE_LABELS[s.source] || s.source)} · ${s.reviewCount} review</span>`;

// ---------------------------------------------------------------- queue

function renderQueue() {
    const pages = Math.max(1, Math.ceil(queue.total / queue.size));
    main.innerHTML = `
        <div class="row mod-head">
            <div class="grow"><h2>Gộp trùng lặp</h2>
                <p class="note">Hai địa điểm đang hoạt động cách nhau không quá 200 m và có tên giống nhau (không dấu, tên này chứa tên kia). Mức Cao: cùng danh mục, cách không quá 50 m và trùng hẳn tên tiếng Nhật hoặc tiếng Anh.</p></div>
            <button class="btn" type="button" data-act="pick">${ic("compare_arrows")}Chọn địa điểm để so sánh</button>
        </div>
        ${queue.items.length ? `<div class="mod-table-wrap"><table class="mod-table">
            <thead><tr><th>Địa điểm A</th><th>Địa điểm B</th><th>Khoảng cách</th><th>Mức</th><th></th></tr></thead>
            <tbody>${queue.items.map((p) => `<tr data-a="${p.a.id}" data-b="${p.b.id}">
                <td>${sideHtml(p.a)}</td><td>${sideHtml(p.b)}</td><td>${p.distance} m</td><td>${levelBadge(p.level)}</td>
                <td><div class="row"><button class="btn sm pri" type="button" data-act="compare">So sánh</button>
                    <button class="btn sm" type="button" data-act="dismiss">Không trùng</button></div></td></tr>`).join("")}</tbody>
        </table></div>
        ${queue.total > queue.size ? `<div class="row mod-pager">
            <button class="btn sm" type="button" data-page="${queue.page - 1}" ${queue.page === 0 ? "disabled" : ""}>${ic("chevron_left")}Trước</button>
            <span class="note">Trang ${queue.page + 1}/${pages} · ${queue.total} cặp</span>
            <button class="btn sm" type="button" data-page="${queue.page + 1}" ${queue.page + 1 >= pages ? "disabled" : ""}>Sau${ic("chevron_right")}</button></div>`
            : `<p class="note">${queue.total} cặp nghi trùng.</p>`}`
        : `<div class="card">Không có cặp địa điểm nào nghi trùng. Bạn vẫn có thể chọn hai địa điểm để so sánh.</div>`}`;
}

async function loadQueue(page = queue.page) {
    try {
        queue = await apiClient.modMerges(page);
    } catch (error) {
        main.innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được hàng chờ gộp.")}</div>`;
        return;
    }
    view = "queue";
    history.replaceState(null, "", location.pathname);
    renderQueue();
}

// ---------------------------------------------------------------- pick by hand

function renderPick() {
    const box = (key, label) => {
        const chosen = pick[key];
        const results = pick.results[key];
        return `<div class="mod-act">
            <h4>${label}</h4>
            ${chosen ? `<div class="row"><b>${esc(chosen.name)}</b><button class="btn sm ghost" type="button" data-clear="${key}">Đổi</button></div>`
                : `<div class="searchbox">${ic("search")}<label class="sr-only" for="pick-${key}">Tìm ${label}</label>
                    <input id="pick-${key}" data-search="${key}" type="search" placeholder="Tên hoặc địa chỉ" maxlength="100" autocomplete="off"></div>
                   <ul class="mod-osm">${results.map((r) => `<li><span><b>${esc(r.name)}</b><br><span class="note">${esc([r.categoryName, r.address].filter(Boolean).join(" · "))}</span></span>
                    ${r.distance != null ? `<span class="note">${r.distance} m</span>` : ""}
                    <button class="btn sm pri" type="button" data-choose="${key}" data-id="${r.id}" data-name="${esc(r.name)}">Chọn</button></li>`).join("")}</ul>`}
        </div>`;
    };
    main.innerHTML = `
        <div class="row mod-head"><div class="grow"><h2>Chọn địa điểm để so sánh</h2>
            <p class="note">Chỉ địa điểm đang hoạt động. Địa điểm thứ hai được xếp theo khoảng cách tới địa điểm thứ nhất.</p></div>
            <button class="btn" type="button" data-act="queue">${ic("arrow_back")}Hàng chờ gộp</button></div>
        <div class="mod-actions">${box("a", "Địa điểm A")}${box("b", "Địa điểm B")}</div>`;
    main.querySelector("[data-search]")?.focus();
}

async function searchPick(key, q) {
    const ticket = ++pick.ticket;
    try {
        const results = await apiClient.modMergeSearch(q, key === "b" ? pick.a?.id : pick.b?.id);
        if (ticket !== pick.ticket) return;
        pick.results[key] = results.filter((r) => r.id !== pick.a?.id && r.id !== pick.b?.id);
    } catch (error) {
        toast(error.message, "error");
        return;
    }
    const input = main.querySelector(`[data-search="${key}"]`);
    const value = input?.value;
    renderPick();
    const again = main.querySelector(`[data-search="${key}"]`);
    if (again) {
        again.value = value;
        again.focus();
    }
}

function startPick(a = null) {
    view = "pick";
    pick = { a, b: null, results: { a: [], b: [] }, ticket: pick.ticket + 1 };
    renderPick();
}

// ---------------------------------------------------------------- compare

async function openCompare(aId, bId) {
    try {
        cmp = await apiClient.modMergeCompare(aId, bId);
    } catch (error) {
        toast(error.message, "error");
        await loadQueue();
        return;
    }
    view = "compare";
    history.replaceState(null, "", `?a=${aId}&b=${bId}`);
    // Keep the place with more reviews; on a tie the one from OpenStreetMap
    const score = (p) => p.counts.reviews * 10 + (p.source === "OSM" ? 1 : 0);
    kept = score(cmp.b) > score(cmp.a) ? "b" : "a";
    resetChoices();
    confirmed = false;
    renderCompare();
}

/** Each field takes the kept place's value, or the other's when the kept one has none. */
function resetChoices() {
    const k = cmp[kept];
    const other = kept === "a" ? "b" : "a";
    choice = {};
    FIELDS.forEach(([f]) => { choice[f] = empty(k, f) && !empty(cmp[other], f) ? other : kept; });
}

function value(p, field) {
    switch (field) {
        case "categoryId": return p.category.name;
        case "areaId": return p.areaName;
        case "position": return p.lat == null ? null : `${p.lat.toFixed(5)}, ${p.lng.toFixed(5)}`;
        default: return p[field];
    }
}

const empty = (p, field) => value(p, field) == null || value(p, field) === "";

function placeCard(key) {
    const p = cmp[key];
    const isKept = kept === key;
    return `<div class="card mod-card ${isKept ? "kept" : ""}">
        <div class="row"><span class="badge ${isKept ? "ok" : ""}">${key.toUpperCase()}${isKept ? " · Giữ lại" : " · Sẽ gộp vào bên kia"}</span>
            ${p.closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}</div>
        <h3>${esc(p.nameVi)}</h3>
        <dl class="mod-facts">
            ${p.nameJa ? `<dt>Tên tiếng Nhật</dt><dd lang="ja">${esc(p.nameJa)}</dd>` : ""}
            ${p.nameEn ? `<dt>Tên tiếng Anh</dt><dd>${esc(p.nameEn)}</dd>` : ""}
            <dt>Danh mục</dt><dd>${ic(p.category.icon)} ${esc(p.category.name)}</dd>
            <dt>Nguồn</dt><dd>${esc(SOURCE_LABELS[p.source] || p.source)}${p.osmId ? ` · ${esc(p.osmId)}` : ""}</dd>
            <dt>Địa chỉ</dt><dd>${esc(p.address || "Chưa có")}</dd>
            <dt>Có</dt><dd>${p.counts.reviews} review · ${p.counts.saves} lượt lưu · ${p.counts.posts} bài viết · ${p.counts.guideItems} mục cẩm nang</dd>
        </dl>
        <a class="link" href="/moderator/places?id=${p.id}" target="_blank" rel="noopener">Mở chi tiết${ic("open_in_new")}</a>
    </div>`;
}

function renderCompare() {
    const other = kept === "a" ? "b" : "a";
    const k = cmp[kept];
    const o = cmp[other];
    main.innerHTML = `
        <div class="row mod-head"><div class="grow"><h2>So sánh hai địa điểm</h2>
            <p class="note">${levelBadge(cmp.level)} ${cmp.distance >= 0 ? `Cách nhau ${cmp.distance} m.` : ""}</p></div>
            <button class="btn" type="button" data-act="queue">${ic("arrow_back")}Hàng chờ gộp</button></div>
        <div class="mod-compare">${placeCard("a")}${placeCard("b")}</div>
        <div class="pick-map mod-map" id="cmp-map" role="region" aria-label="Vị trí hai địa điểm"></div>
        <form class="card mod-card" id="merge-form" novalidate>
            <fieldset class="mod-radio"><legend><b>Giữ lại địa điểm</b></legend>
                ${["a", "b"].map((s) => `<label class="check"><input type="radio" name="kept" value="${s}" ${kept === s ? "checked" : ""}>
                    ${s.toUpperCase()}: ${esc(cmp[s].nameVi)}</label>`).join("")}</fieldset>
            <div class="mod-table-wrap"><table class="mod-table mod-fields">
                <thead><tr><th>Trường</th><th>Giá trị của A</th><th>Giá trị của B</th></tr></thead>
                <tbody>${FIELDS.map(([f, label]) => `<tr><th scope="row">${label}</th>
                    ${["a", "b"].map((s) => `<td><label class="check"><input type="radio" name="f-${f}" value="${s}" ${choice[f] === s ? "checked" : ""} ${empty(cmp[s], f) ? "disabled" : ""}>
                        <span>${esc(value(cmp[s], f) ?? "(trống)")}</span></label></td>`).join("")}</tr>`).join("")}</tbody>
            </table></div>
            <div class="alert quota">${ic("move_down")}<div>Sẽ chuyển sang “${esc(k.nameVi)}”: <b>${o.counts.reviews} review</b>, ${o.counts.saves} lượt lưu, ${o.counts.posts} bài viết, ${o.counts.guideItems} mục cẩm nang.
                ${cmp.sameAuthors ? ` ${cmp.sameAuthors} người có review ở cả hai nơi: chỉ giữ review mới hơn.` : ""}
                “${esc(o.nameVi)}” chuyển sang “Đã gộp”, liên kết cũ tự mở địa điểm được giữ.</div></div>
            <label class="check"><input type="checkbox" id="confirm" ${confirmed ? "checked" : ""}>
                Tôi đã kiểm tra, đây là cùng một địa điểm. Gộp xong không hoàn tác được.</label>
            <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
            <div class="row"><button class="btn dan solid" type="submit" id="merge-btn" ${confirmed ? "" : "disabled"}>${ic("merge")}Gộp</button>
                <button class="btn" type="button" data-act="dismiss-cmp">Không trùng</button>
                <button class="btn ghost" type="button" data-act="queue">Hủy</button></div>
        </form>`;
    if (cmp.a.lat != null && cmp.b.lat != null) {
        miniMap(main.querySelector("#cmp-map"), { lat: cmp[kept].lat, lng: cmp[kept].lng, icon: cmp[kept].category.icon },
            [{ lat: o.lat, lng: o.lng, name: o.nameVi, label: `${other.toUpperCase()} · ${cmp.distance} m`, kind: "warn" }]);
    } else {
        main.querySelector("#cmp-map").remove();
    }
}

async function doMerge() {
    if (!confirmed || busy) return;
    const other = kept === "a" ? "b" : "a";
    const fields = {};
    FIELDS.forEach(([f]) => { fields[f] = choice[f] === kept ? "KEPT" : "MERGED"; });
    busy = true;
    try {
        const done = await apiClient.modMerge({ keptId: cmp[kept].id, mergedId: cmp[other].id, fields, confirmed: true });
        toast(done.message);
        await loadQueue(0);
    } catch (error) {
        const box = main.querySelector("#form-error");
        box.textContent = error.message;
        box.hidden = false;
        box.focus();
    } finally {
        busy = false;
    }
}

async function dismiss(aId, bId) {
    try {
        const done = await apiClient.modDismissPair(aId, bId);
        toast(done.message);
    } catch (error) {
        toast(error.message, "error");
    }
    await loadQueue();
}

// ---------------------------------------------------------------- events

function bind() {
    main.addEventListener("click", async (event) => {
        const page = event.target.closest("[data-page]");
        if (page) return loadQueue(Number(page.dataset.page));
        const choose = event.target.closest("[data-choose]");
        if (choose) {
            pick[choose.dataset.choose] = { id: Number(choose.dataset.id), name: choose.dataset.name };
            pick.results = { a: [], b: [] };
            if (pick.a && pick.b) return openCompare(pick.a.id, pick.b.id);
            return renderPick();
        }
        const clear = event.target.closest("[data-clear]");
        if (clear) {
            pick[clear.dataset.clear] = null;
            return renderPick();
        }
        const button = event.target.closest("[data-act]");
        if (!button || busy) return;
        const row = button.closest("[data-a]");
        switch (button.dataset.act) {
            case "compare": return openCompare(Number(row.dataset.a), Number(row.dataset.b));
            case "dismiss": return dismiss(Number(row.dataset.a), Number(row.dataset.b));
            case "dismiss-cmp": return dismiss(cmp.a.id, cmp.b.id);
            case "pick": return startPick();
            case "queue": return loadQueue();
            default:
        }
    });
    main.addEventListener("input", (event) => {
        const key = event.target.dataset?.search;
        if (!key) return;
        clearTimeout(searchTimer);
        const q = event.target.value.trim();
        searchTimer = setTimeout(() => searchPick(key, q), SEARCH_DELAY);
    });
    main.addEventListener("change", (event) => {
        const t = event.target;
        if (t.name === "kept") {
            kept = t.value;
            resetChoices();
            renderCompare();
        } else if (t.name?.startsWith("f-")) {
            choice[t.name.slice(2)] = t.value;
        } else if (t.id === "confirm") {
            confirmed = t.checked;
            main.querySelector("#merge-btn").disabled = !confirmed;
        }
    });
    main.addEventListener("submit", (event) => {
        event.preventDefault();
        doMerge();
    });
}

// ---------------------------------------------------------------- start

const shell = await mountStaffDesk("MODERATOR", "merge");
if (shell) {
    main = shell.main;
    bind();
    const params = new URLSearchParams(location.search);
    const a = Number(params.get("a"));
    const b = Number(params.get("b"));
    if (a && b) {
        await openCompare(a, b);
    } else if (a) {
        let name = `#${a}`;
        try {
            name = (await apiClient.modPlace(a)).nameVi;
        } catch {
            // the picker still works with the id
        }
        startPick({ id: a, name });
    } else {
        await loadQueue(0);
    }
}
