// UC-M07 Lễ hội và sự kiện (Moderator): the events table with Sửa / Xóa, and the "Đăng sự kiện" / "Sửa sự kiện" form.
// The position is picked on an OpenStreetMap map (click to place the pin) or taken from an ASVSJ place; without a
// position the event shows in the list only. The cover photo is optional (the page draws an illustration otherwise).
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { coverHtml } from "../lib/fest-art.js";
import { loadLeaflet, OSM_CREDIT, OSM_TILES } from "../lib/map.js";
import { isImageFile, MB, shrinkToJpeg, unreadableImage } from "../lib/media-files.js";
import { esc, eventRange, eventStateClass, eventStateLabel, ic } from "../lib/ui.js";

const MAP_WINDOW = 30;
const DEFAULT_VIEW = [35.7279, 139.7707];      // Nippori, where the team's sample places are
const SEARCH_DELAY = 250;

let main = null;
let events = [];
let editing = null;          // the ModEvent being edited, or null for a new event
let deleting = null;         // id waiting for "Xóa" to be confirmed
let draft = emptyDraft();
let pickMap = null;          // { map, marker }
let searchTimer = null;
let searchTicket = 0;

function emptyDraft() {
    return { title: "", startDate: "", endDate: "", locationName: "", costNote: "", tip: "", lat: null, lng: null,
        cover: null, coverUrl: null, removeCover: false };
}

// ---------------------------------------------------------------- page

function renderPage() {
    main.innerHTML = `
        <div>
            <h2>Lễ hội và sự kiện</h2>
            <p class="note">Ghim sự kiện chỉ hiện trên bản đồ khi sự kiện đang diễn ra, hoặc sẽ bắt đầu trong 15 hay 30 ngày tới (người dùng chọn). Sự kiện không gắn vị trí chỉ hiện trong danh sách.</p>
        </div>
        <div id="table"></div>
        <form class="card write-card mod-form" id="event-form" method="post" novalidate>
            <h3 id="form-title"></h3>
            <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
            <div class="split">
                <div class="col">
                    <div class="field">
                        <label for="fe-title">Tên sự kiện <span class="req" aria-hidden="true">*</span></label>
                        <input class="input" id="fe-title" data-bind="title" maxlength="160" placeholder="Lễ hội Halloween phố Yanaka" autocomplete="off">
                    </div>
                    <div class="two-dates">
                        <div class="field"><label for="fe-from">Từ ngày <span class="req" aria-hidden="true">*</span></label>
                            <input class="input" type="date" id="fe-from" data-bind="startDate"></div>
                        <div class="field"><label for="fe-to">Đến ngày <span class="req" aria-hidden="true">*</span></label>
                            <input class="input" type="date" id="fe-to" data-bind="endDate"></div>
                    </div>
                    <div class="field">
                        <label for="fe-place">Tên nơi tổ chức</label>
                        <input class="input" id="fe-place" data-bind="locationName" maxlength="160" placeholder="Phố Yanaka Ginza" autocomplete="off">
                    </div>
                    <div class="field">
                        <label for="fe-cost">Chi phí</label>
                        <input class="input" id="fe-cost" data-bind="costNote" maxlength="120" placeholder="Miễn phí" autocomplete="off">
                    </div>
                    <div class="field">
                        <span class="label-like"><b>Ảnh bìa</b> <span class="note">(không bắt buộc)</span></span>
                        <div class="cover-preview" id="cover-preview"></div>
                        <div class="row">
                            <button class="btn sm" type="button" id="cover-pick">${ic("add_photo_alternate")}Chọn ảnh</button>
                            <button class="btn sm ghost dan" type="button" id="cover-remove" hidden>${ic("delete")}Bỏ ảnh</button>
                            <input class="sr-only" type="file" id="cover-input" tabindex="-1" aria-hidden="true"
                                   accept="image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif">
                        </div>
                        <p class="note">Chỉ dùng ảnh có quyền sử dụng. Không có ảnh thì trang tự vẽ hình minh họa.</p>
                    </div>
                    <div class="field">
                        <label for="fe-tip">Mẹo cho người đi</label>
                        <textarea class="input short" id="fe-tip" data-bind="tip" maxlength="500" rows="3"></textarea>
                    </div>
                </div>
                <div class="col">
                    <div class="field">
                        <label for="fe-search">Vị trí trên bản đồ</label>
                        <div class="searchbox">${ic("search")}
                            <input id="fe-search" type="search" placeholder="Tìm nhanh địa điểm có sẵn" autocomplete="off" maxlength="100">
                        </div>
                        <div class="place-picks" id="place-picks"></div>
                    </div>
                    <div class="pick-map" id="pick-map" role="region" aria-label="Bản đồ chọn vị trí"></div>
                    <p class="note" id="map-note"></p>
                </div>
            </div>
            <div class="row">
                <button class="btn pri" type="submit" id="fe-submit"></button>
                <button class="btn" type="button" id="fe-cancel" hidden>Hủy</button>
            </div>
        </form>`;
    bindForm();
}

function renderTable() {
    const box = main.querySelector("#table");
    if (!events.length) {
        box.innerHTML = `<div class="card">Chưa có sự kiện nào. Đăng sự kiện đầu tiên ở form bên dưới.</div>`;
        return;
    }
    box.innerHTML = `<div class="mod-table-wrap"><table class="mod-table">
        <thead><tr><th>Sự kiện</th><th>Thời gian</th><th>Nơi tổ chức</th><th>Review</th><th>Trạng thái</th><th></th></tr></thead>
        <tbody>${events.map(({ event: e }) => `<tr data-id="${e.id}">
            <td><a class="link" href="/events/${e.id}" target="_blank" rel="noopener"><b>${esc(e.title)}</b></a></td>
            <td>${esc(eventRange(e))}</td>
            <td>${esc(e.locationName)}${e.lat == null ? ` <span class="badge">Không gắn bản đồ</span>` : ""}</td>
            <td>${e.reviewCount}</td>
            <td>${statusBadges(e)}</td>
            <td><div class="row">${deleting === e.id
                ? `<span class="note">Xóa sự kiện${e.reviewCount ? ` và ${e.reviewCount} review` : ""}?</span>
                   <button class="btn sm dan solid" type="button" data-act="delete">Xóa</button>
                   <button class="btn sm" type="button" data-act="delete-no">Hủy</button>`
                : `<button class="btn sm" type="button" data-act="edit">Sửa</button>
                   <button class="btn sm dan" type="button" data-act="delete-ask">Xóa</button>`}</div></td>
        </tr>`).join("")}</tbody></table></div>`;
}

/** The event's state, plus whether its pin is on the map now (30-day window). */
function statusBadges(e) {
    const onMap = e.lat != null && e.state !== "PAST" && e.daysUntil <= MAP_WINDOW;
    return `<span class="badge ${eventStateClass(e)}">${esc(eventStateLabel(e))}</span>
        ${onMap ? `<span class="badge">${ic("location_on")}Trên bản đồ</span>` : ""}`;
}

async function loadEvents() {
    try {
        events = await apiClient.modEvents();
        renderTable();
    } catch (error) {
        main.querySelector("#table").innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được danh sách sự kiện.")}</div>`;
    }
}

// ---------------------------------------------------------------- form

function bindForm() {
    const form = main.querySelector("#event-form");
    form.addEventListener("input", (event) => {
        const key = event.target.dataset.bind;
        if (key) draft[key] = event.target.value;
    });
    form.addEventListener("submit", submit);
    main.querySelector("#fe-cancel").addEventListener("click", () => startNew());
    main.querySelector("#cover-pick").addEventListener("click", () => main.querySelector("#cover-input").click());
    main.querySelector("#cover-input").addEventListener("change", (event) => pickCover(event.target.files[0]));
    main.querySelector("#cover-remove").addEventListener("click", () => {
        if (draft.cover) URL.revokeObjectURL(draft.cover.url);
        draft.cover = null;
        draft.removeCover = Boolean(draft.coverUrl);
        draft.coverUrl = null;
        renderCover();
    });
    main.querySelector("#fe-search").addEventListener("input", (event) => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(() => searchPlaces(event.target.value), SEARCH_DELAY);
    });
    main.querySelector("#place-picks").addEventListener("click", (event) => {
        const button = event.target.closest("[data-lat]");
        if (!button) return;
        draft.lat = Number(button.dataset.lat);
        draft.lng = Number(button.dataset.lng);
        if (!draft.locationName.trim()) {
            draft.locationName = button.dataset.name;
            main.querySelector("#fe-place").value = draft.locationName;
        }
        main.querySelector("#place-picks").innerHTML = "";
        main.querySelector("#fe-search").value = "";
        renderPin(true);
    });
    main.querySelector("#map-note").addEventListener("click", (event) => {
        if (!event.target.closest("[data-clear]")) return;
        draft.lat = null;
        draft.lng = null;
        renderPin();
    });
    main.querySelector("#table").addEventListener("click", onTableClick);
}

function fillForm() {
    main.querySelector("#form-title").textContent = editing ? "Sửa sự kiện" : "Đăng sự kiện";
    main.querySelector("#fe-submit").innerHTML = editing ? `${ic("save")}Lưu thay đổi` : `${ic("send")}Đăng sự kiện`;
    main.querySelector("#fe-cancel").hidden = !editing;
    main.querySelector("#form-error").hidden = true;
    main.querySelectorAll("[data-bind]").forEach((input) => { input.value = draft[input.dataset.bind] ?? ""; });
    renderCover();
    renderPin(true);
}

function startNew() {
    editing = null;
    if (draft.cover) URL.revokeObjectURL(draft.cover.url);
    draft = emptyDraft();
    fillForm();
}

function startEdit(id) {
    const row = events.find((m) => m.event.id === id);
    if (!row) return;
    const e = row.event;
    editing = row;
    if (draft.cover) URL.revokeObjectURL(draft.cover.url);
    draft = { ...emptyDraft(), title: e.title, startDate: e.startDate, endDate: e.endDate,
        locationName: e.locationName, costNote: e.costNote || "", tip: row.tip || "",
        lat: e.lat, lng: e.lng, coverUrl: e.coverUrl };
    fillForm();
    main.querySelector("#event-form").scrollIntoView({ behavior: "smooth", block: "start" });
    main.querySelector("#fe-title").focus({ preventScroll: true });
}

function renderCover() {
    const box = main.querySelector("#cover-preview");
    const url = draft.cover?.url || draft.coverUrl;
    box.innerHTML = url
        ? `<img class="festart photo" src="${esc(url)}" alt="Ảnh bìa đã chọn">`
        : coverHtml({ id: editing?.event.id || 0, title: draft.title });
    main.querySelector("#cover-remove").hidden = !url;
}

async function pickCover(file) {
    main.querySelector("#cover-input").value = "";
    if (!file) return;
    if (!isImageFile(file) || file.size > 20 * MB) {
        formError("Ảnh bìa phải là JPG, PNG hoặc HEIC và không quá 20 MB.");
        return;
    }
    try {
        const blob = await shrinkToJpeg(file);
        if (draft.cover) URL.revokeObjectURL(draft.cover.url);
        draft.cover = { blob, url: URL.createObjectURL(blob) };
        renderCover();
    } catch {
        formError(unreadableImage(file));
    }
}

// ---------------------------------------------------------------- position

async function searchPlaces(text) {
    const box = main.querySelector("#place-picks");
    const q = text.trim();
    const mine = ++searchTicket;
    if (q.length < 2) {
        box.innerHTML = "";
        return;
    }
    try {
        const places = await apiClient.modEventPlaces(q);
        if (mine !== searchTicket) return;
        box.innerHTML = places.map((p) => `<button type="button" data-lat="${p.lat}" data-lng="${p.lng}" data-name="${esc(p.name)}">
            <b>${ic("location_on")} ${esc(p.name)}</b>${p.address ? `<span class="note">${esc(p.address)}</span>` : ""}</button>`).join("")
            || `<p class="note">Không có địa điểm nào khớp. Hãy bấm lên bản đồ để đặt vị trí.</p>`;
    } catch (error) {
        if (mine === searchTicket) box.innerHTML = `<p class="note">${esc(error.message || "Không tìm được địa điểm.")}</p>`;
    }
}

async function mountMap() {
    const el = main.querySelector("#pick-map");
    let L;
    try {
        L = await loadLeaflet();
    } catch {
        el.innerHTML = `<p class="map-off">${ic("map")}Không tải được bản đồ. Vẫn đăng được sự kiện không gắn vị trí.</p>`;
        return;
    }
    const map = L.map(el).setView(DEFAULT_VIEW, 14);
    L.tileLayer(OSM_TILES, { maxZoom: 19, attribution: OSM_CREDIT }).addTo(map);
    map.on("click", (event) => {
        draft.lat = Number(event.latlng.lat.toFixed(6));
        draft.lng = Number(event.latlng.lng.toFixed(6));
        renderPin();
    });
    pickMap = { map, marker: null, L };
    renderPin(true);
}

/** Draws the pin (or none) and the note under the map; `center` moves the map to the pin. */
function renderPin(center = false) {
    const has = draft.lat != null && draft.lng != null;
    main.querySelector("#map-note").innerHTML = has
        ? `Đã đặt vị trí. <button class="link" type="button" data-clear>Bỏ vị trí</button>`
        : "Bấm lên bản đồ để đặt vị trí. Để trống thì sự kiện chỉ hiện trong danh sách.";
    if (!pickMap) return;
    const { map, L } = pickMap;
    if (pickMap.marker) {
        pickMap.marker.remove();
        pickMap.marker = null;
    }
    if (has) {
        const icon = L.divIcon({ className: "map-pin", html: ic("celebration"), iconSize: [34, 34], iconAnchor: [17, 32] });
        pickMap.marker = L.marker([draft.lat, draft.lng], { icon, title: "Vị trí sự kiện" }).addTo(map);
        if (center) map.setView([draft.lat, draft.lng], 16);
    } else if (center) {
        map.setView(DEFAULT_VIEW, 14);
    }
}

// ---------------------------------------------------------------- save and delete

function formError(message) {
    const box = main.querySelector("#form-error");
    box.textContent = message;
    box.hidden = false;
    box.focus();
}

async function submit(event) {
    event.preventDefault();
    main.querySelector("#form-error").hidden = true;
    if (!draft.title.trim()) return formError("Hãy nhập tên sự kiện.");
    if (!draft.startDate || !draft.endDate) return formError("Hãy chọn ngày bắt đầu và ngày kết thúc.");
    if (draft.endDate < draft.startDate) return formError("Ngày kết thúc phải bằng hoặc sau ngày bắt đầu.");

    const body = {
        title: draft.title, startDate: draft.startDate, endDate: draft.endDate, locationName: draft.locationName,
        costNote: draft.costNote, tip: draft.tip, lat: draft.lat, lng: draft.lng,
        removeCover: Boolean(editing) && draft.removeCover && !draft.cover
    };
    const data = new FormData();
    data.append("event", new Blob([JSON.stringify(body)], { type: "application/json" }));
    if (draft.cover) data.append("cover", draft.cover.blob, "cover.jpg");
    const button = main.querySelector("#fe-submit");
    button.disabled = true;
    try {
        const saved = editing
            ? await apiClient.modUpdateEvent(editing.event.id, data)
            : await apiClient.modCreateEvent(data);
        toast(saved?.message || (editing ? "Đã lưu thay đổi." : "Đã đăng."));
        startNew();
        await loadEvents();
    } catch (error) {
        if (error.status === 401) {
            location.replace("/login");
            return;
        }
        formError(error.message || "Có lỗi xảy ra, vui lòng thử lại.");
    } finally {
        button.disabled = false;
    }
}

async function onTableClick(event) {
    const button = event.target.closest("[data-act]");
    if (!button) return;
    const id = Number(button.closest("[data-id]").dataset.id);
    switch (button.dataset.act) {
        case "edit":
            deleting = null;
            renderTable();
            startEdit(id);
            break;
        case "delete-ask":
            deleting = id;
            renderTable();
            main.querySelector('[data-act="delete"]')?.focus();
            break;
        case "delete-no":
            deleting = null;
            renderTable();
            break;
        case "delete":
            button.disabled = true;
            try {
                await apiClient.modDeleteEvent(id);
                toast("Đã xóa sự kiện.");
                if (editing?.event.id === id) startNew();
            } catch (error) {
                toast(error.message || "Có lỗi xảy ra, vui lòng thử lại.", "error");
            }
            deleting = null;
            await loadEvents();
            break;
        default:
    }
}

let toastTimer;
function toast(message, kind = "ok") {
    const box = document.querySelector("#toast");
    box.textContent = message;
    box.className = `toast ${kind}`;
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { box.hidden = true; }, 4000);
}

// ---------------------------------------------------------------- start

const shell = await mountStaffDesk("MODERATOR", "events");
if (shell) {
    main = shell.main;
    document.title = "Lễ hội và sự kiện – Moderator – Senpai ơi";
    renderPage();
    startNew();
    await loadEvents();
    mountMap();
}
