// The Moderator's map workspace, page /moderator/places (UC-M07, UC-M08; user decision: one map page for places and,
// later, new pins). A big map with the same base map as /map ("Gọn" / "Chi tiết", lib/basemap.js) and every place of
// Senpai ơi in every status; the filters (category, source, status, prefecture) narrow the pins. The panel on the left
// is one of:
//   list   — the places in view, nearest to the centre first;
//   view   — a place: facts, actions (Sửa thông tin, Đánh dấu đã đóng cửa, Gỡ khỏi bản đồ / Khôi phục, Gộp), history;
//   edit   — the form; the pink pin on the map is dragged (or the map clicked) to move the place;
//   remove — the reason for "Gỡ khỏi bản đồ";
//   add    — "Thêm địa điểm": click the map where the place is, then the form (a Moderator's place is on the map at
//            once). A similar name within 200 m lights those pins up and asks "Mở địa điểm đó" / "Vẫn lưu".
// The search box flies to a place of Senpai ơi or to an area of Japan ("Shinjuku", "Osaka"); the prefecture filter
// also flies there. Address: /moderator/places?id=12 opens a place, ?new=1 starts "Thêm địa điểm".
// The same page serves /moderator/pins (<body data-view="pins">: the queue of pins to approve, "pin" mode) and
// /moderator/closed (data-view="closed": the places reported "đã đóng cửa", decided in the place's details).
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, SOURCE_LABELS, STATUS_LABELS, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic, osmUrl, stars } from "../lib/ui.js";
import {
    BASEMAPS, distance, formatDistance, glOf, knownPlaceFor, nameKey, poiAt, poiInfo, rememberBasemap, setBaseLayer,
    storedBasemap, SUBCLASS_CATEGORY
} from "../lib/basemap.js";

const L = window.L;
/**
 * "places" (/moderator/places), "pins" (/moderator/pins: the panel starts with the queue of pins to approve) or
 * "closed" (/moderator/closed: the places reported "đã đóng cửa" to check).
 */
const VIEW = ["pins", "closed"].includes(document.body.dataset.view) ? document.body.dataset.view : "places";
const START = { lat: 35.6895, lng: 139.6917, zoom: 11 };       // Tokyo
const LIST_MAX = 100;
const SUGGEST_DELAY = 250;
const STATUS_CLASS = { ACTIVE: "ok", PENDING: "lan", REMOVED: "crit", REJECTED: "crit", MERGED: "" };
const SOURCE_FILTERS = [["OSM", "OpenStreetMap"], ["MODERATOR", "Nhóm Senpai ơi thêm"], ["USER", "Ghim cộng đồng"],
    ["GOOGLE", "Google Maps"]];

let main;
let map;
let cluster;
let tiles = null;
let editMarker = null;
let resultLayer;                             // dark pins: OpenStreetMap places of a search, not on Senpai ơi yet
let tempMarker = null;                       // the OpenStreetMap place being looked at
const markers = new Map();                   // place id → Leaflet marker

const state = {
    lists: { categories: [], prefectures: [] },
    catById: new Map(),
    pins: new Map(),                         // id → MapPin
    truncated: false,
    limit: 0,
    filters: { category: "", source: "", status: "", prefecture: "" },
    mode: "list",                            // list | view | edit | remove | add | results | osm | pin
    queue: null,                             // pins waiting for approval, oldest first ({ items, categories })
    duplicatesOnly: false,                   // the queue's "Chỉ ghim “Có thể trùng”"
    closedQueue: null,                       // "Báo đóng cửa": { waiting, closed, threshold }
    pin: null,                               // PinDetail of the pin being checked ("pin" mode)
    nearLit: [],                             // ids of the places lit around that pin (similar names)
    results: null,                           // { q, items, external, externalReason, scope } of an OpenStreetMap search
    osm: null,                               // the OpenStreetMap place shown ("osm" mode): a PlaceOption without id
    selected: null,
    info: null,                              // PlaceInfo of the selected place
    draft: null,                             // the form being filled (edit, add)
    duplicates: [],                          // "Có thể trùng" answered by a save
    basemap: null,
    busy: false,
    suggestTicket: 0
};

// ---------------------------------------------------------------- page

function renderShell() {
    main.classList.add("mm-page");
    main.innerHTML = `
        <h1 class="sr-only">Địa điểm</h1>
        <div class="mm-bar">
            <div class="mm-search">
                <div class="searchbox">${ic("search")}<label class="sr-only" for="mm-q">Tìm địa điểm hoặc khu vực</label>
                    <input id="mm-q" type="search" placeholder="Tìm địa điểm hoặc khu vực: Shinjuku, Osaka…" maxlength="100"
                        autocomplete="off" role="combobox" aria-expanded="false" aria-controls="mm-suggest"></div>
                <div class="mm-suggest" id="mm-suggest" role="listbox" aria-label="Gợi ý" hidden></div>
            </div>
            <label class="sr-only" for="f-cat">Danh mục</label><select class="input" id="f-cat"><option value="">Tất cả danh mục</option></select>
            <label class="sr-only" for="f-src">Nguồn</label><select class="input" id="f-src"><option value="">Mọi nguồn</option>
                ${SOURCE_FILTERS.map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>
            <label class="sr-only" for="f-st">Trạng thái</label><select class="input" id="f-st"><option value="">Mọi trạng thái</option>
                ${Object.entries(STATUS_LABELS).map(([v, l]) => `<option value="${v}">${esc(l)}</option>`).join("")}</select>
            <label class="sr-only" for="f-pref">Tỉnh</label><select class="input" id="f-pref"><option value="">Mọi tỉnh</option></select>
            <button class="btn pri" type="button" id="mm-add">${ic("add_location_alt")}Thêm địa điểm</button>
        </div>
        <div class="mm-body">
            <aside class="mm-panel" id="mm-panel" aria-live="polite"></aside>
            <div class="mm-mapwrap">
                <div class="mm-map" id="mm-map" role="region" aria-label="Bản đồ địa điểm"></div>
                <div class="mm-hint" id="mm-hint" hidden></div>
                <button class="mm-fab" type="button" id="mm-basemap">${ic("layers")}</button>
            </div>
        </div>`;
    [["#f-cat", "category"], ["#f-src", "source"], ["#f-st", "status"], ["#f-pref", "prefecture"]].forEach(([sel, key]) =>
        main.querySelector(sel).addEventListener("change", (event) => setFilter(key, event.target.value)));
    main.querySelector("#mm-add").addEventListener("click", startAdd);
    main.querySelector("#mm-basemap").addEventListener("click", async () => {
        await setBasemap(state.basemap === "clean" ? "detail" : "clean");
        rememberBasemap(state.basemap);
        toast(`Bản đồ ${BASEMAPS[state.basemap].label.toLowerCase()}`);
    });
    const panel = main.querySelector("#mm-panel");
    panel.addEventListener("click", onPanelClick);
    panel.addEventListener("input", onPanelInput);
    panel.addEventListener("change", onPanelInput);
    panel.addEventListener("submit", (event) => {
        event.preventDefault();
        if (event.target.id === "place-form") savePlace(false);
        if (event.target.id === "remove-form") removePlace();
    });
    wireSearch();
}

function renderLists() {
    const cat = main.querySelector("#f-cat");
    if (cat.options.length === 1) {
        cat.insertAdjacentHTML("beforeend", state.lists.categories.map((c) => `<option value="${esc(c.id)}">${esc(c.name)}</option>`).join(""));
    }
    const pref = main.querySelector("#f-pref");
    if (pref.options.length === 1) {
        pref.insertAdjacentHTML("beforeend", state.lists.prefectures.map((p) => `<option value="${esc(p.code)}">${esc(p.name)}</option>`).join(""));
    }
}

async function setFilter(key, value) {
    state.filters[key] = value;
    await Promise.all([loadPins(), key === "category" ? loadQueue() : null]);
    if (key === "prefecture" && value) flyToPrefecture(value);
}

// ---------------------------------------------------------------- map

async function startMap() {
    map = L.map("mm-map", { zoomControl: true, keyboard: true, maxZoom: 19 }).setView([START.lat, START.lng], START.zoom);
    map.zoomControl.setPosition("bottomright");
    await setBasemap(storedBasemap());
    cluster = L.markerClusterGroup({
        showCoverageOnHover: false,
        maxClusterRadius: 44,
        iconCreateFunction: (c) => L.divIcon({ className: "mp-wrap", html: `<span class="mc">${c.getChildCount()}</span>`,
            iconSize: [40, 40] })
    }).addTo(map);
    map.on("moveend", () => {
        if (state.mode === "list") renderPanel();
    });
    resultLayer = L.layerGroup().addTo(map);
    map.on("click", (event) => {
        // "Thêm địa điểm", first click on a shop the map draws: take that shop from OpenStreetMap instead of a bare pin
        if (state.mode === "add" && state.draft?.lat == null && shopAt(event.latlng)) openMapShop(event.latlng);
        else if (state.mode === "add" || state.mode === "edit") placeDraft(event.latlng);
        else if (state.mode !== "remove") openMapShop(event.latlng);
    });
    // a pointer over a shop the "Gọn" map draws: it can be clicked
    let hoverQueued = false;
    map.on("mousemove", (event) => {
        if (hoverQueued) return;
        hoverQueued = true;
        requestAnimationFrame(() => {
            hoverQueued = false;
            const placing = state.mode === "add" || state.mode === "edit";
            map.getContainer().style.cursor = placing ? "crosshair" : shopAt(event.latlng) ? "pointer" : "";
        });
    });
}

// ---------------------------------------------------------------- shops on the map and OpenStreetMap places

const shopAt = (latlng) => poiAt(glOf(tiles, state.basemap), latlng);

/** The pins as the shared matcher expects them ({ name, nameJa, nameEn, osmId, categoryId, lat, lng }). */
function pinPlaces() {
    return [...state.pins.values()].filter((p) => p.status === "ACTIVE" || p.status === "PENDING")
        .map((p) => ({ ...p, name: p.nameVi }));
}

/**
 * A click on a shop the "Gọn" map draws: the place of Senpai ơi it already is (same OSM id, or same name close by),
 * else that shop as an OpenStreetMap place with "Thêm vào Senpai ơi".
 */
function openMapShop(latlng) {
    const f = shopAt(latlng);
    if (!f) return;
    const s = poiInfo(f, latlng);
    const known = knownPlaceFor(pinPlaces(), s.osmId, [s.name, s.nameJa, ...s.otherNames], s.lat, s.lng, s.cls === "railway");
    if (known) {
        openPlace(known.id);
        return;
    }
    const category = state.catById.get(SUBCLASS_CATEGORY[s.subclass] || SUBCLASS_CATEGORY[s.cls] || "other");
    openOsm({ id: null, externalId: s.osmId, name: s.name, nameJa: s.nameJa, category: category?.name || "Địa điểm khác",
        address: null, source: "OSM", lat: s.lat, lng: s.lng });
}

function showTemp(lat, lng) {
    removeTemp();
    tempMarker = L.marker([lat, lng], {
        icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp osm sel">${ic("add_location_alt")}</span>`,
            iconSize: [32, 32], iconAnchor: [16, 16] }),
        keyboard: false, zIndexOffset: 900
    }).addTo(map);
}

function removeTemp() {
    tempMarker?.remove();
    tempMarker = null;
}

function openOsm(option) {
    removeEditMarker();
    hint(null);
    select(null);
    state.mode = "osm";
    state.osm = option;
    state.info = null;
    state.draft = null;
    showTemp(option.lat, option.lng);
    renderPanel();
}

/** The search on OpenStreetMap, within the view (or all of Japan, nearest first, when the view holds nothing). */
async function searchOsm(q) {
    removeEditMarker();
    removeTemp();
    hint(null);
    select(null);
    state.mode = "results";
    state.osm = null;
    state.info = null;
    state.results = { q, items: null };
    renderPanel();
    const b = map.getBounds();
    const r = (x) => Math.round(x * 1e5) / 1e5;
    let found;
    try {
        found = await apiClient.mapSearch(q, { south: r(Math.max(-90, b.getSouth())), west: r(Math.max(-180, b.getWest())),
            north: r(Math.min(90, b.getNorth())), east: r(Math.min(180, b.getEast())) });
    } catch (error) {
        if (state.results?.q !== q) return;
        state.results = { q, items: [], error: error.message };
        renderPanel();
        return;
    }
    if (state.results?.q !== q || state.mode !== "results") return;
    state.results = { q, items: found.places, external: found.external, externalReason: found.externalReason,
        scope: found.scope };
    drawResultPins();
    renderPanel();
    if (found.scope === "WIDE" && found.places.length) {
        const first = found.places[0];
        map.setView([first.lat, first.lng], Math.max(map.getZoom(), 16));
    }
}

function drawResultPins() {
    resultLayer.clearLayers();
    (state.results?.items || []).filter((o) => o.id == null && o.lat != null).forEach((o) => {
        const icon = state.lists.categories.find((c) => c.name === o.category)?.icon || "place";
        L.marker([o.lat, o.lng], {
            icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp osm">${ic(icon)}</span>`, iconSize: [28, 28], iconAnchor: [14, 14] }),
            title: `${o.name}, OpenStreetMap, chưa có trên Senpai ơi`, alt: o.name
        }).on("click", () => openOsm(o)).addTo(resultLayer);
    });
}

function clearResults() {
    state.results = null;
    resultLayer?.clearLayers();
}

/** "Thêm vào Senpai ơi": the server looks the place up on OpenStreetMap and fills the form (or names the place we have). */
async function pickOsm() {
    const o = state.osm;
    if (!o || state.busy) return;
    state.busy = true;
    try {
        const pick = await apiClient.modPickOsm(o.externalId);
        if (pick.existingId) {
            toast("Địa điểm này đã có trên Senpai ơi.");
            await openPlace(pick.existingId, { fly: true });
            return;
        }
        removeTemp();
        state.mode = "add";
        state.osm = null;
        state.duplicates = [];
        state.draft = { ...pick.prefill, confirmDuplicate: false };
        const at = L.latLng(state.draft.lat ?? o.lat, state.draft.lng ?? o.lng);
        state.draft.lat = null;                      // placeDraft puts the pink pin there and draws the form
        history.replaceState(null, "", "?new=1");
        placeDraft(at);
        if (map.getZoom() < 17) map.setView(at, 17);
    } catch (error) {
        toast(error.message, "error");
    } finally {
        state.busy = false;
    }
}

async function setBasemap(key) {
    const shown = await setBaseLayer(map, key, tiles);
    if (shown.fellBack && state.basemap) toast("Không tải được bản đồ gọn, đang dùng bản đồ chi tiết.", "error");
    tiles = shown.layer;
    state.basemap = shown.key;
    const next = BASEMAPS[shown.key === "clean" ? "detail" : "clean"].label.toLowerCase();
    const button = main.querySelector("#mm-basemap");
    button.title = `Đổi sang bản đồ ${next}`;
    button.setAttribute("aria-label", `Kiểu bản đồ: ${BASEMAPS[shown.key].label}. Bấm để đổi sang ${next}`);
}

const categoryIcon = (id) => state.catById.get(id)?.icon || "place";

function pinClass(p) {
    const lit = p.status === "ACTIVE" && p.reviewCount > 0 && !p.closed;
    return [lit ? "lit" : "dim", p.source === "USER" ? "comm" : "", p.status === "PENDING" ? "pend" : "",
        p.status === "REMOVED" || p.status === "REJECTED" ? "gone" : "", p.status === "MERGED" ? "merged" : "",
        p.closed ? "shut" : "", state.selected === p.id ? "sel" : "",
        state.duplicates.some((d) => d.id === p.id) || state.nearLit.includes(p.id) ? "dup" : ""].filter(Boolean).join(" ");
}

function pinIcon(p) {
    const lit = p.status === "ACTIVE" && p.reviewCount > 0 && !p.closed;
    const size = lit ? 38 : 30;
    return L.divIcon({
        className: "mp-wrap",
        html: `<span class="mp ${pinClass(p)}">${ic(categoryIcon(p.categoryId))}${lit ? `<b>${p.reviewCount}</b>` : ""}${p.closedReports ? `<i title="Báo đóng cửa">${p.closedReports}</i>` : ""}</span>`,
        iconSize: [size, size],
        iconAnchor: [size / 2, size / 2]
    });
}

function pinLabel(p) {
    return [p.nameVi, state.catById.get(p.categoryId)?.name, STATUS_LABELS[p.status] || p.status,
        p.closed ? "đã đóng cửa" : "", p.reviewCount ? `${p.reviewCount} review` : ""].filter(Boolean).join(", ");
}

function drawPins() {
    cluster.clearLayers();
    markers.clear();
    const list = [...state.pins.values()].map((p) => {
        const m = L.marker([p.lat, p.lng], { icon: pinIcon(p), title: pinLabel(p), alt: pinLabel(p), keyboard: true });
        m.on("click", () => {
            if (state.mode === "add" || state.mode === "edit") return;      // a click there moves the pin being placed
            // checking a pin: a place around it is picked as the place to merge it into
            if (state.mode === "pin" && state.pin?.nearby.some((n) => n.id === p.id)) {
                pickMergeTarget(p.id);
                return;
            }
            if (p.status === "PENDING") openPin(p.id);
            else openPlace(p.id);
        });
        markers.set(p.id, m);
        return m;
    });
    cluster.addLayers(list);
}

function refreshPin(id) {
    const p = state.pins.get(id);
    const m = markers.get(id);
    if (p && m) m.setIcon(pinIcon(p));
}

function select(id) {
    if (state.nearLit.length) setLit([]);
    const previous = state.selected;
    state.selected = id;
    if (previous) refreshPin(previous);
    if (id) refreshPin(id);
}

/** The pink pin of the form (add, edit): dragged, or moved by a click on the map. */
function placeDraft(latlng) {
    if (!state.draft) return;
    state.draft.lat = latlng.lat;
    state.draft.lng = latlng.lng;
    const icon = L.divIcon({ className: "mp-wrap", html: `<span class="mp newpin">${ic(categoryIcon(state.draft.categoryId) || "add_location_alt")}</span>`,
        iconSize: [34, 34], iconAnchor: [17, 17] });
    if (!editMarker) {
        editMarker = L.marker(latlng, { icon, draggable: true, keyboard: true, zIndexOffset: 1000, title: "Vị trí mới" }).addTo(map);
        editMarker.on("dragend", () => {
            const at = editMarker.getLatLng();
            state.draft.lat = at.lat;
            state.draft.lng = at.lng;
            updatePositionNote();
        });
    } else {
        editMarker.setLatLng(latlng).setIcon(icon);
    }
    hint(null);
    if (state.mode === "add" && !main.querySelector("#place-form")) {
        renderPanel();
        // a click from far out is a rough spot: zoom in to the street, where the pin is dragged into place
        if (map.getZoom() < 16) map.setView(latlng, 17);
    }
    updatePositionNote();
}

function removeEditMarker() {
    editMarker?.remove();
    editMarker = null;
}

function hint(html) {
    const box = main.querySelector("#mm-hint");
    box.hidden = !html;
    box.innerHTML = html || "";
}

/** The part of the map not behind the panel is the whole map here (the panel sits beside it). */
function placesInView() {
    const bounds = map.getBounds();
    const centre = map.getCenter();
    return [...state.pins.values()]
        .filter((p) => bounds.contains([p.lat, p.lng]))
        .sort((a, b) => distance(centre, a) - distance(centre, b));
}

async function flyToPrefecture(code) {
    try {
        const areas = (await apiClient.areasIn(code)).filter((a) => a.lat != null);
        if (!areas.length) return;
        const median = (xs) => xs.sort((a, b) => a - b)[Math.floor(xs.length / 2)];
        const lat = median(areas.map((a) => a.lat));
        const lng = median(areas.map((a) => a.lng));
        // islands far out at sea (Tokyo's Ogasawara, 1,000 km south) would zoom the map out to the ocean
        const near = areas.filter((a) => Math.abs(a.lat - lat) <= 2.5 && Math.abs(a.lng - lng) <= 2.5);
        map.fitBounds(L.latLngBounds(near.map((a) => [a.lat, a.lng])), { padding: [30, 30], maxZoom: 12 });
    } catch {
        // the filter still applies; the map just stays where it is
    }
}

// ---------------------------------------------------------------- search: a place of Senpai ơi or an area

let suggestTimer = null;

function wireSearch() {
    const q = main.querySelector("#mm-q");
    const box = main.querySelector("#mm-suggest");
    q.addEventListener("input", () => {
        clearTimeout(suggestTimer);
        suggestTimer = setTimeout(() => suggest(q.value.trim()), SUGGEST_DELAY);
    });
    q.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
            event.preventDefault();
            clearTimeout(suggestTimer);
            suggest(q.value.trim()).then(() => box.querySelector(".opt")?.click());
        }
        if (event.key === "ArrowDown") {
            event.preventDefault();
            box.querySelector(".opt")?.focus();
        }
        if (event.key === "Escape") hideSuggest();
    });
    box.addEventListener("keydown", (event) => {
        const opts = [...box.querySelectorAll(".opt")];
        const i = opts.indexOf(document.activeElement);
        if (event.key === "ArrowDown") opts[Math.min(i + 1, opts.length - 1)]?.focus();
        if (event.key === "ArrowUp") (i <= 0 ? q : opts[i - 1]).focus();
        if (event.key === "Escape") { hideSuggest(); q.focus(); }
        if (["ArrowDown", "ArrowUp"].includes(event.key)) event.preventDefault();
    });
    box.addEventListener("click", (event) => {
        const opt = event.target.closest(".opt");
        if (!opt) return;
        hideSuggest();
        if (opt.dataset.osmSearch) {
            searchOsm(opt.dataset.osmSearch);
        } else if (opt.dataset.place) {
            openPlace(Number(opt.dataset.place), { fly: true });
        } else if (opt.dataset.pref) {
            flyToPrefecture(opt.dataset.pref);
            if (state.mode === "view") closeDetail();
        } else if (opt.dataset.lat) {
            map.setView([Number(opt.dataset.lat), Number(opt.dataset.lng)], 14);
            if (state.mode === "view") closeDetail();
        }
    });
    document.addEventListener("click", (event) => {
        if (!event.target.closest(".mm-search")) hideSuggest();
    });
}

function hideSuggest() {
    main.querySelector("#mm-suggest").hidden = true;
    main.querySelector("#mm-q").setAttribute("aria-expanded", "false");
}

async function suggest(text) {
    const box = main.querySelector("#mm-suggest");
    const ticket = ++state.suggestTicket;
    if (!text) {
        hideSuggest();
        return;
    }
    const key = nameKey(text);
    const places = key ? [...state.pins.values()]
        .filter((p) => [p.nameVi, p.nameJa, p.nameEn].some((n) => nameKey(n).includes(key)))
        .slice(0, 6) : [];
    let areas = [];
    if (key.length >= 2 || /[぀-ヿ一-鿿]/.test(text)) {
        try {
            areas = (await apiClient.searchAreas(text)).slice(0, 5);
        } catch {
            areas = [];
        }
    }
    if (ticket !== state.suggestTicket) return;
    // prefectures first: "osaka" means Osaka, before its wards and the towns whose name holds the word
    const prefectures = key ? state.lists.prefectures
        .filter((p) => p.name.split(" · ").some((n) => nameKey(n.replace(/\s+(prefecture|to|fu|ken)$/i, "")).startsWith(key)))
        .slice(0, 3) : [];
    const prefHtml = prefectures.map((p) => `<button class="opt" type="button" role="option" data-pref="${esc(p.code)}">
            <span class="oi">${ic("map")}</span><span class="ot"><b>${esc(p.name)}</b><small>Tỉnh · bay tới cả tỉnh</small></span></button>`).join("");
    const placeHtml = places.map((p) => `<button class="opt" type="button" role="option" data-place="${p.id}">
            <span class="oi">${ic(categoryIcon(p.categoryId))}</span><span class="ot"><b>${esc(p.nameVi)}</b>
            <small>${esc(STATUS_LABELS[p.status] || p.status)}${p.nameJa ? ` · ${esc(p.nameJa)}` : ""}</small></span></button>`).join("");
    const areaHtml = areas.filter((a) => a.lat != null).map((a) => `<button class="opt" type="button" role="option"
            data-lat="${a.lat}" data-lng="${a.lng}"><span class="oi">${ic("location_city")}</span>
            <span class="ot"><b>${esc(a.name)}</b><small>Khu vực · bay tới đây</small></span></button>`).join("");
    // OpenStreetMap only on request (one press = one request to the free service), within the view
    const osmHtml = key.length >= 2 || /[぀-ヿ一-鿿]/.test(text)
        ? `<h4>OpenStreetMap</h4><button class="opt" type="button" role="option" data-osm-search="${esc(text)}">
            <span class="oi osm">${ic("travel_explore")}</span><span class="ot"><b>Tìm “${esc(text)}” trên OpenStreetMap</b>
            <small>Trong vùng đang xem, để thêm vào Senpai ơi</small></span></button>` : "";
    box.innerHTML = (placeHtml ? `<h4>Địa điểm trên Senpai ơi</h4>${placeHtml}` : "")
        + (prefHtml || areaHtml ? `<h4>Khu vực</h4>${prefHtml}${areaHtml}` : "")
        + osmHtml
        + (!placeHtml && !prefHtml && !areaHtml && !osmHtml ? `<p class="note">Không có địa điểm hay khu vực nào khớp.</p>` : "");
    box.hidden = false;
    main.querySelector("#mm-q").setAttribute("aria-expanded", "true");
}

// ---------------------------------------------------------------- panel

function renderPanel() {
    const box = main.querySelector("#mm-panel");
    switch (state.mode) {
        case "view": box.innerHTML = state.info ? viewHtml() : `<p class="note pad">Đang tải…</p>`; break;
        case "edit": box.innerHTML = formHtml(); break;
        case "remove": box.innerHTML = removeHtml(); break;
        case "add": box.innerHTML = state.draft?.lat == null ? addStartHtml() : formHtml(); break;
        case "results": box.innerHTML = resultsHtml(); break;
        case "osm": box.innerHTML = osmPlaceHtml(); break;
        case "pin": box.innerHTML = state.pin ? pinHtml() : `<p class="note pad">Đang tải…</p>`; break;
        default: box.innerHTML = VIEW === "pins" ? queueHtml() : VIEW === "closed" ? closedQueueHtml() : listHtml();
    }
}

const statusBadge = (status) => `<span class="badge ${STATUS_CLASS[status] ?? ""}">${esc(STATUS_LABELS[status] || status)}</span>`;

function listHtml() {
    const items = placesInView();
    const counts = {};
    items.forEach((p) => { counts[p.status] = (counts[p.status] || 0) + 1; });
    const summary = Object.entries(counts).map(([s, n]) => `${esc(STATUS_LABELS[s] || s)} ${n}`).join(" · ");
    const centre = map.getCenter();
    return `<div class="mm-head"><h2>Địa điểm trong vùng đang xem</h2>
            <p class="note">${items.length} địa điểm${summary ? ` · ${summary}` : ""}</p>
            ${state.truncated ? `<div class="alert quota">Có hơn ${state.limit} địa điểm khớp bộ lọc, bản đồ chỉ vẽ ${state.limit} địa điểm mới nhất. Hãy chọn thêm bộ lọc.</div>` : ""}</div>
        ${items.length ? `<div class="mm-list">${items.slice(0, LIST_MAX).map((p) => `
            <button class="mod-item" type="button" data-open="${p.id}">
                <span class="row"><b>${esc(p.nameVi)}</b>${statusBadge(p.status)}${p.closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}
                    ${p.closedReports ? `<span class="badge warn">${p.closedReports} báo đóng cửa</span>` : ""}</span>
                ${p.nameJa ? `<span class="note" lang="ja">${esc(p.nameJa)}</span>` : ""}
                <span class="row note"><span class="badge">${ic(categoryIcon(p.categoryId))}${esc(state.catById.get(p.categoryId)?.name || "")}</span>
                    ${esc(SOURCE_LABELS[p.source] || p.source)} · ${p.reviewCount} review · ${formatDistance(distance(centre, p))}</span>
            </button>`).join("")}</div>
            ${items.length > LIST_MAX ? `<p class="note pad">Đang hiện ${LIST_MAX} địa điểm gần giữa bản đồ nhất. Phóng to để xem những nơi khác.</p>` : ""}`
        : `<p class="note pad">Không có địa điểm nào khớp bộ lọc trong vùng này. Hãy kéo hoặc thu nhỏ bản đồ.</p>`}`;
}

function viewHtml() {
    const p = state.info;
    const active = p.status === "ACTIVE";
    return `<div class="mm-detail mod-card">
        <button class="btn sm ghost mm-back" type="button" data-act="back">${ic("arrow_back")}${state.results ? "Kết quả tìm" : VIEW === "closed" ? "Báo cáo địa điểm" : "Danh sách"}</button>
        <div class="row"><h3>${esc(p.nameVi)}</h3>${statusBadge(p.status)}${p.closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}</div>
        ${p.status === "REMOVED" ? `<div class="alert error">${ic("visibility_off")} Đã gỡ khỏi bản đồ: ${esc(p.removedReason || "")}. Review và lượt lưu vẫn được giữ, bấm “Khôi phục” để đưa trở lại.</div>` : ""}
        ${p.status === "REJECTED" ? `<div class="alert error">Ghim bị từ chối: ${esc(p.rejectReason || "")}</div>` : ""}
        ${p.status === "MERGED" ? `<div class="alert quota">${ic("merge")} Đã gộp vào <a class="link" href="?id=${p.mergedIntoId}" data-open="${p.mergedIntoId}" data-fly="1">${esc(p.mergedIntoName || "#" + p.mergedIntoId)}</a>.</div>` : ""}
        ${p.status === "PENDING" ? `<div class="alert quota">Ghim đang chờ duyệt. <button class="btn sm pri" type="button" data-pin="${p.id}">${ic("fact_check")}Duyệt ghim này</button></div>` : ""}
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
        ${active && p.wrongReports ? `<div class="mod-act">
            <h4>${ic("report")} ${p.wrongReports} người báo “sai thông tin”</h4>
            <p class="note">Đọc lý do bên dưới, sửa bằng “Sửa thông tin” (hoặc gỡ địa điểm) rồi bấm “Đã xử lý”. Người báo nhận thông báo.</p>
            <div class="row mod-buttons">
                <button class="btn pri" type="button" data-act="resolve-wrong">${ic("task_alt")}Đã xử lý</button>
                <button class="btn" type="button" data-act="dismiss-wrong">${ic("do_not_disturb_on")}Báo cáo không đúng</button>
            </div></div>` : ""}
        ${p.reports?.length ? `<h4>Báo cáo đang chờ</h4><ol class="mod-history mm-reports">${p.reports.map((r) => `<li>
            <span><span class="badge ${r.kind === "WRONG_INFO" ? "warn" : "crit"}">${r.kind === "WRONG_INFO" ? "Sai thông tin" : "Đã đóng cửa"}</span>
                ${r.note ? esc(r.note) : `<span class="note">(không ghi lý do)</span>`}</span>
            <span class="note">${esc(r.userName)} · ${esc(fullDate(r.at))}</span></li>`).join("")}</ol>` : ""}
        ${active && !p.closed && p.closedReports ? `<div class="mod-act">
            <h4>${ic("storefront")} ${p.closedReports} người báo “đã đóng cửa”</h4>
            <p class="note">Hệ thống không tự gắn nhãn. Hãy kiểm tra review gần nhất, OpenStreetMap${p.osmId ? ` (<a class="link" href="${esc(osmUrl(p))}" target="_blank" rel="noopener">mở ${esc(p.osmId)}</a>)` : ""} hoặc nguồn khác rồi quyết định.</p>
            <div class="row mod-buttons">
                <button class="btn dan" type="button" data-act="close">${ic("block")}Xác nhận, gắn nhãn đã đóng cửa</button>
                <button class="btn" type="button" data-act="keep">${ic("check")}Vẫn mở cửa, xóa các báo cáo</button>
            </div></div>` : ""}
        ${p.lat == null ? `<p class="note">Địa điểm cũ từ Google Maps chưa có vị trí. Bấm “Sửa thông tin” rồi bấm lên bản đồ để đặt vị trí.</p>` : ""}
        <div class="row mod-buttons">
            ${active || p.status === "REMOVED" ? `<button class="btn" type="button" data-act="edit">${ic("edit")}Sửa thông tin</button>` : ""}
            ${active && (p.closed || !p.closedReports) ? `<button class="btn" type="button" data-act="${p.closed ? "reopen" : "close"}">${ic("storefront")}${p.closed ? "Gỡ nhãn, địa điểm đã mở lại" : "Đánh dấu đã đóng cửa"}</button>` : ""}
            ${active ? `<button class="btn dan" type="button" data-act="remove">${ic("visibility_off")}Gỡ khỏi bản đồ</button>` : ""}
            ${p.status === "REMOVED" ? `<button class="btn pri" type="button" data-act="restore">${ic("undo")}Khôi phục</button>` : ""}
            ${active ? `<a class="btn" href="/moderator/merge?a=${p.id}">${ic("merge")}Gộp với địa điểm khác</a>` : ""}
            ${active ? `<a class="btn ghost" href="/map?place=${p.id}" target="_blank" rel="noopener">${ic("open_in_new")}Xem như người dùng</a>` : ""}
        </div>
        <h4>Lịch sử</h4>
        ${p.history.length ? `<ol class="mod-history">${p.history.map((h) => `<li><span>${esc(h.text)}</span>
            <span class="note">${esc(h.actorName)} · ${esc(fullDate(h.at))}</span></li>`).join("")}</ol>` : `<p class="note">Chưa có thay đổi nào.</p>`}
    </div>`;
}

function removeHtml() {
    const p = state.info;
    return `<form class="mm-detail mod-card" id="remove-form" novalidate>
        <h3>Gỡ “${esc(p.nameVi)}” khỏi bản đồ?</h3>
        <p class="note">Địa điểm không còn hiện trên bản đồ, tìm kiếm và bài viết${p.reviewCount ? `; ${p.reviewCount} review sẽ bị ẩn` : ""}. Không xóa hẳn: Moderator khôi phục được bất cứ lúc nào.</p>
        <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
        <div class="field"><label for="r-reason">Lý do <span class="req" aria-hidden="true">*</span></label>
            <select class="input" id="r-reason"><option value="">Chọn lý do</option>
                ${p.removeReasons.map((r) => `<option value="${r.id}">${esc(r.label)}</option>`).join("")}</select></div>
        <div class="field"><label for="r-note">Ghi chú</label>
            <textarea class="input short" id="r-note" maxlength="200" rows="2"></textarea></div>
        <div class="row"><button class="btn dan solid" type="submit">${ic("visibility_off")}Gỡ khỏi bản đồ</button>
            <button class="btn" type="button" data-act="cancel">Hủy</button></div>
    </form>`;
}

function externalNote(r) {
    if (r.error) return `<div class="alert error">${esc(r.error)}</div>`;
    switch (r.external) {
        case "BUSY": return `<p class="note">${ic("hourglass_top")}Bạn tìm hơi nhanh, hãy chờ vài giây rồi thử lại.</p>`;
        case "UNAVAILABLE": return `<p class="note">${ic("cloud_off")}OpenStreetMap đang bận hoặc chưa kết nối được, hãy thử lại sau. Bạn vẫn có thể bấm “Thêm địa điểm” để cắm ghim tay.</p>`;
        case "SHORT": return `<p class="note">Gõ ít nhất 2 ký tự để tìm trên OpenStreetMap.</p>`;
        default: return "";
    }
}

function resultsHtml() {
    const r = state.results;
    const head = `<div class="mm-head"><button class="btn sm ghost mm-back" type="button" data-act="back">${ic("arrow_back")}Danh sách</button>
        <h2>“${esc(r.q)}” trên OpenStreetMap</h2>`;
    if (!r.items) return `${head}<p class="note">Đang tìm…</p></div>`;
    const wide = r.scope === "WIDE" && r.items.length
        ? `<p class="note">${ic("travel_explore")}Không có kết quả trong vùng đang xem, đây là kết quả ở nơi khác.</p>` : "";
    return `${head}<p class="note">${r.items.length} địa điểm${r.scope === "AREA" ? " trong vùng đang xem" : ""}. Địa điểm màu tối là nơi Senpai ơi chưa có.</p>
            ${wide}${externalNote(r)}</div>
        ${r.items.length ? `<div class="mm-list">${r.items.map((o, i) => `
            <button class="mod-item" type="button" ${o.id != null ? `data-open="${o.id}" data-fly="1"` : `data-osm-idx="${i}"`}>
                <span class="row"><b>${esc(o.name)}</b>${o.id != null ? `<span class="badge ok">Đã có trên Senpai ơi</span>` : `<span class="badge">OSM</span>`}</span>
                ${o.nameJa && o.nameJa !== o.name ? `<span class="note" lang="ja">${esc(o.nameJa)}</span>` : ""}
                <span class="note">${esc([o.category, o.address].filter(Boolean).join(" · "))}</span>
            </button>`).join("")}</div>`
        : `<p class="note pad">Không tìm thấy trên OpenStreetMap. Bạn có thể bấm “Thêm địa điểm” rồi bấm lên bản đồ để cắm ghim tay.</p>`}`;
}

function osmPlaceHtml() {
    const o = state.osm;
    return `<div class="mm-detail mod-card">
        <button class="btn sm ghost mm-back" type="button" data-act="back">${ic("arrow_back")}${state.results ? "Kết quả tìm" : "Danh sách"}</button>
        <div class="row"><h3>${esc(o.name)}</h3><span class="badge">Chưa có trên Senpai ơi</span></div>
        <dl class="mod-facts">
            ${o.nameJa && o.nameJa !== o.name ? `<dt>Tên tiếng Nhật</dt><dd lang="ja">${esc(o.nameJa)}</dd>` : ""}
            <dt>Danh mục</dt><dd>${esc(o.category || "Địa điểm khác")}</dd>
            ${o.address ? `<dt>Địa chỉ</dt><dd>${esc(o.address)}</dd>` : ""}
            <dt>Nguồn</dt><dd>OpenStreetMap · <a class="link" href="${esc(osmUrl({ osmId: o.externalId, lat: o.lat, lng: o.lng, name: o.name }))}" target="_blank" rel="noopener">${esc(o.externalId)}</a></dd>
        </dl>
        <p class="note">Thêm vào Senpai ơi: tên, địa chỉ, vị trí và danh mục lấy sẵn từ OpenStreetMap, bạn chỉ cần xem lại rồi lưu.</p>
        <div class="row"><button class="btn pri" type="button" data-act="pick-osm">${ic("add_location_alt")}Thêm vào Senpai ơi</button>
            <button class="btn" type="button" data-act="back">Hủy</button></div>
    </div>`;
}

function addStartHtml() {
    return `<div class="mm-detail mod-card">
        <h3>Thêm địa điểm</h3>
        <p>${ic("ads_click")} <b>Bấm lên bản đồ</b> đúng chỗ của địa điểm. Có thể tìm khu vực ở ô tìm kiếm để bay tới đó trước.</p>
        <p>${ic("storefront")} Nếu bản đồ đã vẽ sẵn cửa hàng, nhà ga, công viên đó, <b>bấm thẳng vào nó</b>: tên, địa chỉ và
            danh mục được lấy sẵn từ OpenStreetMap. Hoặc gõ tên ở ô tìm kiếm rồi chọn “Tìm trên OpenStreetMap”.</p>
        <p class="note">Địa điểm Moderator thêm hiện trên bản đồ ngay, không cần duyệt.</p>
        <div class="row"><button class="btn" type="button" data-act="cancel">Hủy</button></div>
    </div>`;
}

function formHtml() {
    const d = state.draft;
    const editing = state.mode === "edit";
    const title = editing ? `Sửa thông tin “${esc(state.info.nameVi)}”` : d.osmId ? "Thêm vào Senpai ơi" : "Thêm địa điểm";
    const option = (v, l, cur) => `<option value="${esc(v)}" ${String(v) === String(cur ?? "") ? "selected" : ""}>${esc(l)}</option>`;
    return `<form class="mm-detail mod-card" id="place-form" novalidate>
        <h3>${title}</h3>
        ${d.osmId ? `<p class="note">${ic("public")} Dữ liệu gốc từ OpenStreetMap (mã ${esc(d.osmId)}). Mã OSM không sửa được; thay đổi ở đây chỉ lưu trên Senpai ơi, không ghi lên OpenStreetMap.</p>` : ""}
        <div class="alert error" id="form-error" role="alert" tabindex="-1" hidden></div>
        ${state.duplicates.length ? `<div class="alert quota full" role="alert"><div><b>Có thể trùng với địa điểm đã có</b> (đang sáng viền cam trên bản đồ):
            <ul class="mod-near">${state.duplicates.map((n) => `<li><b>${esc(n.name)}</b><span class="note">${esc(n.categoryName)} · ${n.distance} m · ${n.reviewCount} review</span>
                <button class="btn sm" type="button" data-open="${n.id}" data-fly="1">Mở địa điểm đó</button></li>`).join("")}</ul>
            <button class="btn sm pri" type="button" data-act="save-anyway">Vẫn lưu</button></div></div>` : ""}
        <p class="note mm-pos" id="pos-note">${positionNote()}</p>
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
                ${state.lists.categories.map((c) => option(c.id, c.name, d.categoryId)).join("")}</select></div>
        <p class="note">${ic("location_city")} Khu vực (quận, thành phố) tự lấy theo vị trí trên bản đồ khi lưu.</p>
        <div class="row"><button class="btn pri" type="submit">${ic("save")}${editing ? "Lưu thay đổi" : d.osmId ? "Thêm vào Senpai ơi" : "Thêm địa điểm"}</button>
            <button class="btn" type="button" data-act="cancel">Hủy</button></div>
    </form>`;
}

function positionNote() {
    const d = state.draft;
    return d.lat == null
        ? `${ic("ads_click")} Bấm lên bản đồ để đặt vị trí.`
        : `${ic("pan_tool")} Kéo ghim hồng trên bản đồ hoặc bấm chỗ khác để đổi vị trí (${d.lat.toFixed(5)}, ${d.lng.toFixed(5)}).`;
}

function updatePositionNote() {
    const note = main.querySelector("#pos-note");
    if (note && state.draft) note.innerHTML = positionNote();
}

function onPanelInput(event) {
    if (event.target.id === "q-dup" && event.type === "change") {
        state.duplicatesOnly = event.target.checked;
        loadQueue();
        return;
    }
    const key = event.target.dataset?.bind;
    if (key && state.draft) state.draft[key] = event.target.value;
    if (key === "categoryId" && editMarker && state.draft.lat != null) placeDraft(L.latLng(state.draft.lat, state.draft.lng));
}

// ---------------------------------------------------------------- actions

async function onPanelClick(event) {
    const open = event.target.closest("[data-open]");
    if (open) {
        event.preventDefault();
        await openPlace(Number(open.dataset.open), { fly: Boolean(open.dataset.fly) || !inView(Number(open.dataset.open)) });
        return;
    }
    const pinRow = event.target.closest("[data-pin]");
    if (pinRow) {
        await openPin(Number(pinRow.dataset.pin));
        return;
    }
    const osmRow = event.target.closest("[data-osm-idx]");
    if (osmRow) {
        const o = state.results?.items?.[Number(osmRow.dataset.osmIdx)];
        if (o) {
            openOsm(o);
            if (!map.getBounds().contains([o.lat, o.lng])) map.setView([o.lat, o.lng], Math.max(map.getZoom(), 16));
        }
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button || state.busy) return;
    switch (button.dataset.act) {
        case "back":
            if (state.results && state.mode !== "results") {
                // back to the search, to add the next shop of the list
                removeTemp();
                removeEditMarker();
                select(null);
                state.mode = "results";
                state.osm = null;
                state.info = null;
                state.draft = null;
                history.replaceState(null, "", location.pathname);
                renderPanel();
            } else {
                closeDetail();
            }
            break;
        case "pick-osm":
            await pickOsm();
            break;
        case "approve":
        case "reject":
        case "merge":
            await pinAct(button.dataset.act, button);
            break;
        case "edit": {
            const p = state.info;
            state.mode = "edit";
            state.duplicates = [];
            state.draft = { osmId: p.osmId, nameVi: p.nameVi, nameJa: p.nameJa, nameEn: p.nameEn, address: p.address,
                categoryId: p.category.id, lat: p.lat, lng: p.lng, confirmDuplicate: false };
            renderPanel();
            if (p.lat != null) placeDraft(L.latLng(p.lat, p.lng));
            else hint(`${ic("ads_click")} Bấm lên bản đồ để đặt vị trí cho “${esc(p.nameVi)}”.`);
            break;
        }
        case "remove":
            state.mode = "remove";
            renderPanel();
            break;
        case "cancel":
            cancelForm();
            break;
        case "save-anyway":
            await savePlace(true);
            break;
        case "restore":
            await act(() => apiClient.modRestorePlace(state.info.id));
            break;
        case "close":
            await act(() => apiClient.modSetClosed(state.info.id, true));
            break;
        case "reopen":
            await act(() => apiClient.modSetClosed(state.info.id, false));
            break;
        case "keep":
            await act(() => apiClient.modKeepOpen(state.info.id));
            break;
        case "resolve-wrong":
            await act(() => apiClient.modResolveWrongInfo(state.info.id));
            break;
        case "dismiss-wrong":
            await act(() => apiClient.modDismissWrongInfo(state.info.id));
            break;
        default:
    }
}

function inView(id) {
    const p = state.pins.get(id);
    return Boolean(p && map.getBounds().contains([p.lat, p.lng]));
}

function startAdd() {
    removeEditMarker();
    removeTemp();
    clearResults();
    state.osm = null;
    select(null);
    state.mode = "add";
    state.info = null;
    state.duplicates = [];
    state.draft = { osmId: null, nameVi: "", nameJa: "", nameEn: "", address: "", categoryId: "", lat: null, lng: null };
    history.replaceState(null, "", "?new=1");
    hint(`${ic("ads_click")} Bấm lên bản đồ đúng chỗ của địa điểm mới. <button class="btn sm" type="button" data-hint="cancel">Hủy</button>`);
    main.querySelector("#mm-hint [data-hint]")?.addEventListener("click", cancelForm);
    renderPanel();
}

function cancelForm() {
    removeEditMarker();
    hint(null);
    const duplicates = state.duplicates.map((d) => d.id);
    state.duplicates = [];
    duplicates.forEach(refreshPin);
    if (state.mode === "edit" || state.mode === "remove") {
        state.mode = "view";
        renderPanel();
    } else if (state.results) {
        select(null);
        state.mode = "results";
        state.draft = null;
        history.replaceState(null, "", location.pathname);
        renderPanel();
    } else {
        closeDetail();
    }
}

function closeDetail() {
    removeEditMarker();
    removeTemp();
    clearResults();
    state.osm = null;
    hint(null);
    select(null);
    state.mode = "list";
    state.info = null;
    state.draft = null;
    history.replaceState(null, "", location.pathname);
    renderPanel();
}

async function savePlace(confirm) {
    if (state.busy) return;
    const d = state.draft;
    const body = { osmId: d.osmId || null, nameVi: d.nameVi, nameJa: d.nameJa, nameEn: d.nameEn, address: d.address,
        categoryId: d.categoryId || null, lat: d.lat, lng: d.lng, confirmDuplicate: confirm };
    state.busy = true;
    try {
        const saved = state.mode === "edit"
            ? await apiClient.modUpdatePlace(state.info.id, body)
            : await apiClient.modCreatePlace(body);
        if (!saved.placeId) {
            state.duplicates = saved.duplicates;
            state.duplicates.forEach((n) => refreshPin(n.id));
            renderPanel();
            return;
        }
        toast(saved.message);
        state.duplicates = [];
        removeEditMarker();
        if (d.osmId && state.results?.items) {
            // the shop is on Senpai ơi now: its dark pin leaves the search results
            state.results.items = state.results.items.filter((o) => o.externalId !== d.osmId);
            drawResultPins();
        }
        await loadPins();
        await openPlace(saved.placeId);
    } catch (error) {
        showError(error.message, error.fields);
    } finally {
        state.busy = false;
    }
}

function showError(message, fields = {}) {
    const box = main.querySelector("#form-error");
    if (!box) return toast(message, "error");
    const all = [...new Set(Object.values(fields || {}).filter((v) => typeof v === "string"))];
    box.textContent = all.length > 1 ? all.join(" ") : message;
    box.hidden = false;
    const ids = { nameVi: "#p-vi", categoryId: "#p-cat", position: "#pos-note", reasonId: "#r-reason", note: "#r-note" };
    main.querySelectorAll("[aria-invalid]").forEach((el) => el.removeAttribute("aria-invalid"));
    Object.keys(fields || {}).forEach((f) => main.querySelector(ids[f])?.setAttribute("aria-invalid", "true"));
    box.focus();
}

async function removePlace() {
    const reasonId = main.querySelector("#r-reason").value;
    if (!reasonId) return showError("Hãy chọn lý do gỡ.", { reasonId: true });
    await act(() => apiClient.modRemovePlace(state.info.id, Number(reasonId), main.querySelector("#r-note").value.trim()));
}

async function act(call) {
    state.busy = true;
    try {
        const done = await call();
        toast(done.message);
        const id = state.info.id;
        await Promise.all([loadPins(), loadClosedQueue()]);
        // "Báo đóng cửa": a place decided leaves the queue, so on to the next one waiting
        const next = VIEW === "closed" && !state.closedQueue?.waiting.some((p) => p.id === id)
            ? state.closedQueue?.waiting[0]?.id : null;
        await openPlace(next || id, { fly: Boolean(next) });
    } catch (error) {
        if (state.mode === "remove") showError(error.message, error.fields);
        else toast(error.message, "error");
    } finally {
        state.busy = false;
    }
}

// ---------------------------------------------------------------- new pins (UC-M05, UC-M06)

function queueHtml() {
    const q = state.queue;
    const head = `<div class="mm-head"><h2>Ghim chờ duyệt</h2>
        <p class="note">Ghim người dùng tự cắm cần Moderator duyệt mới hiện trên bản đồ. Ghim cũ nhất ở trên cùng; trên bản đồ là ghim viền nét đứt.</p>
        <label class="check"><input type="checkbox" id="q-dup" ${state.duplicatesOnly ? "checked" : ""}> Chỉ ghim “Có thể trùng”</label></div>`;
    if (!q) return `${head}<p class="note pad">Đang tải…</p>`;
    if (!q.items.length) {
        return `${head}<p class="note pad">${state.filters.category || state.duplicatesOnly ? "Không có ghim nào khớp bộ lọc." : "Không có ghim nào chờ duyệt."}</p>`;
    }
    return `${head}<div class="mm-list">${q.items.map((p) => `
        <button class="mod-item" type="button" data-pin="${p.id}">
            <span class="row"><b>${esc(p.nameVi)}</b>${p.possibleDuplicate ? `<span class="badge warn">${ic("content_copy")}Có thể trùng</span>` : ""}</span>
            ${p.nameJa || p.nameEn ? `<span class="note">${esc([p.nameJa, p.nameEn].filter(Boolean).join(" · "))}</span>` : ""}
            <span class="row note"><span class="badge">${ic(p.category.icon)}${esc(p.category.name)}</span>
                ${esc(p.authorName)} · ${esc(daysAgo(p.createdAt))} · ${p.nearby} địa điểm trong 200 m</span>
        </button>`).join("")}</div>`;
}

function pinHtml() {
    const d = state.pin;
    const r = d.review;
    return `<div class="mm-detail mod-card">
        <button class="btn sm ghost mm-back" type="button" data-act="back">${ic("arrow_back")}${VIEW === "pins" ? "Ghim chờ duyệt" : "Danh sách"}</button>
        <div class="row"><h3>${esc(d.nameVi)}</h3><span class="badge lan">Chờ duyệt</span></div>
        <dl class="mod-facts">
            ${d.nameJa ? `<dt>Tên tiếng Nhật</dt><dd lang="ja">${esc(d.nameJa)}</dd>` : ""}
            ${d.nameEn ? `<dt>Tên tiếng Anh</dt><dd>${esc(d.nameEn)}</dd>` : ""}
            <dt>Danh mục</dt><dd>${ic(d.category.icon)} ${esc(d.category.name)}</dd>
            <dt>Địa chỉ</dt><dd>${esc(d.address || "Chưa có")}</dd>
            <dt>Người gửi</dt><dd>${esc(d.authorName)} · ${esc(fullDate(d.createdAt))}</dd>
        </dl>
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
        ${d.nearby.length ? `<p class="note">${ic("ads_click")} Đang hiện quanh ghim trên bản đồ; tên giống thì sáng viền cam. Bấm vào một địa điểm trên bản đồ để chọn gộp vào đó.</p>
            <ul class="mod-near">${d.nearby.map((n) => `<li>
                <b>${esc(n.name)}</b><span class="note">${esc(n.categoryName)} · ${n.distance} m · ${n.reviewCount} review</span>
                ${n.similar ? `<span class="badge warn">Tên giống</span>` : ""}</li>`).join("")}</ul>`
            : `<p class="note">Không có địa điểm nào đang hoạt động trong bán kính 200 m.</p>`}
        <div class="alert error" id="act-error" role="alert" tabindex="-1" hidden></div>
        <div class="mod-act">
            <h4>Duyệt</h4>
            <div class="field"><label for="a-name">Tên tiếng Việt</label>
                <input class="input" id="a-name" maxlength="160" value="${esc(d.nameVi)}" autocomplete="off"></div>
            <div class="field"><label for="a-cat">Danh mục</label>
                <select class="input" id="a-cat">${d.categories.map((c) => `<option value="${esc(c.id)}" ${c.id === d.category.id ? "selected" : ""}>${esc(c.name)}</option>`).join("")}</select></div>
            <button class="btn pri" type="button" data-act="approve" ${d.scanning ? "disabled" : ""}>${ic("check")}Duyệt</button>
            ${d.scanning ? `<p class="note">${ic("hourglass_top")} Ảnh đang được quét. Duyệt được khi có kết quả.</p>` : ""}
        </div>
        ${d.nearby.length ? `<div class="mod-act">
            <h4>Gộp vào địa điểm có sẵn</h4>
            <div class="field"><label for="a-target">Địa điểm</label>
                <select class="input" id="a-target"><option value="">Chọn địa điểm (hoặc bấm trên bản đồ)</option>
                    ${d.nearby.map((n) => `<option value="${n.id}">${esc(n.name)} (${n.distance} m)</option>`).join("")}</select></div>
            <button class="btn" type="button" data-act="merge">${ic("merge")}Gộp</button>
            <p class="note">Review đầu tiên chuyển sang địa điểm đó nếu người gửi chưa có review ở đó.</p>
        </div>` : ""}
        <div class="mod-act">
            <h4>Từ chối</h4>
            <div class="field"><label for="a-reason">Lý do từ chối</label>
                <select class="input" id="a-reason"><option value="">Chọn lý do</option>
                    ${d.rejectReasons.map((x) => `<option value="${x.id}">${esc(x.label)}</option>`).join("")}</select></div>
            <button class="btn dan" type="button" data-act="reject">${ic("block")}Từ chối</button>
            <p class="note">Review đầu tiên bị xóa, người gửi được báo lý do.</p>
        </div>
    </div>`;
}

/** Lights up the places around the pin whose names look alike (and lets the others go back to normal). */
function setLit(ids) {
    const before = state.nearLit;
    state.nearLit = ids;
    [...new Set([...before, ...ids])].forEach(refreshPin);
}

async function openPin(id) {
    removeEditMarker();
    removeTemp();
    hint(null);
    state.osm = null;
    state.info = null;
    state.draft = null;
    state.mode = "pin";
    state.pin = null;
    select(id);
    history.replaceState(null, "", `?id=${id}`);
    renderPanel();
    try {
        state.pin = await apiClient.modPin(id);
    } catch (error) {
        toast(error.message, "error");
        await Promise.all([loadPins(), loadQueue()]);
        closeDetail();
        return;
    }
    if (state.selected !== id || state.mode !== "pin") return;
    renderPanel();
    const d = state.pin;
    setLit(d.nearby.filter((n) => n.similar).map((n) => n.id));
    const points = [[d.lat, d.lng], ...d.nearby.map((n) => [n.lat, n.lng])];
    if (points.length > 1) map.fitBounds(points, { padding: [70, 70], maxZoom: 18 });
    else map.setView(points[0], Math.max(map.getZoom(), 17));
}

function pickMergeTarget(id) {
    const select = main.querySelector("#a-target");
    if (!select) return;
    select.value = String(id);
    const n = state.pin.nearby.find((x) => x.id === id);
    select.closest(".mod-act")?.scrollIntoView({ behavior: "smooth", block: "nearest" });
    toast(`Đã chọn “${n?.name || ""}” để gộp ghim vào. Bấm “Gộp” để xác nhận.`);
}

async function pinAct(kind, button) {
    const d = state.pin;
    const value = (sel) => main.querySelector(sel)?.value || "";
    const error = (message) => {
        const box = main.querySelector("#act-error");
        box.textContent = message;
        box.hidden = false;
        box.focus();
    };
    let call;
    if (kind === "approve") {
        call = () => apiClient.modApprovePin(d.id, { nameVi: value("#a-name").trim(), categoryId: value("#a-cat") });
    } else if (kind === "reject") {
        if (!value("#a-reason")) return error("Hãy chọn lý do từ chối.");
        call = () => apiClient.modRejectPin(d.id, Number(value("#a-reason")));
    } else {
        if (!value("#a-target")) return error("Hãy chọn địa điểm để gộp vào (trong danh sách hoặc bấm trên bản đồ).");
        call = () => apiClient.modMergePin(d.id, Number(value("#a-target")));
    }
    state.busy = true;
    button.disabled = true;
    try {
        const done = await call();
        toast(done.message);
        setLit([]);
        await Promise.all([loadPins(), loadQueue()]);
        // the queue: on to the oldest pin left; elsewhere: the place the pin became (or the list)
        if (VIEW === "pins" && state.queue?.items.length) await openPin(state.queue.items[0].id);
        else if (VIEW === "pins") closeDetail();
        else if (done.placeId) await openPlace(done.placeId);
        else closeDetail();
    } catch (failure) {
        error(failure.message);
        if (failure.status === 409 || failure.status === 404) await Promise.all([loadPins(), loadQueue()]);
    } finally {
        state.busy = false;
        button.disabled = false;
    }
}

async function loadQueue() {
    if (VIEW !== "pins") return;
    try {
        state.queue = await apiClient.modPins({ category: state.filters.category, duplicates: state.duplicatesOnly || "" });
    } catch (error) {
        state.queue = { items: [] };
        toast(error.message || "Không tải được danh sách ghim.", "error");
    }
    if (state.mode === "list") renderPanel();
}

// ---------------------------------------------------------------- "Báo đóng cửa" (UC-M04 AF4/AF5)

function closedQueueHtml() {
    const q = state.closedQueue;
    const head = `<div class="mm-head"><h2>Báo cáo địa điểm</h2>
        <p class="note">${q ? `Địa điểm vào đây khi có từ ${q.threshold} báo “đã đóng cửa”, hoặc ngay khi có một báo “sai thông tin”. ` : ""}Hệ thống không tự sửa hay gắn nhãn: Moderator đọc lý do rồi quyết định. Trên bản đồ, số đỏ ở góc ghim là số báo cáo.</p></div>`;
    if (!q) return `${head}<p class="note pad">Đang tải…</p>`;
    const row = (p, closed) => `<button class="mod-item" type="button" data-open="${p.id}" data-fly="1">
            <span class="row"><b>${esc(p.name)}</b>${closed ? `<span class="badge crit">Đã đóng cửa</span>` : ""}
                ${!closed && p.reports ? `<span class="badge crit">${p.reports} báo đóng cửa</span>` : ""}
                ${p.wrongReports ? `<span class="badge warn">${p.wrongReports} báo sai thông tin</span>` : ""}</span>
            <span class="row note"><span class="badge">${ic(p.category.icon)}${esc(p.category.name)}</span>
                Review gần nhất: ${esc(daysAgo(p.lastReviewAt))}${closed && p.closedAt ? ` · gắn nhãn ${esc(fullDate(p.closedAt))}` : ""}</span>
            ${p.address ? `<span class="note">${esc(p.address)}</span>` : ""}
        </button>`;
    return `${head}
        ${q.waiting.length ? `<div class="mm-list">${q.waiting.map((p) => row(p, false)).join("")}</div>`
            : `<p class="note pad">Không có địa điểm nào chờ xác minh.</p>`}
        ${q.closed.length ? `<div class="mm-head"><h2>Đã gắn nhãn đóng cửa</h2></div>
            <div class="mm-list">${q.closed.map((p) => row(p, true)).join("")}</div>` : ""}`;
}

async function loadClosedQueue() {
    if (VIEW !== "closed") return;
    try {
        state.closedQueue = await apiClient.modClosed();
    } catch (error) {
        state.closedQueue = { waiting: [], closed: [], threshold: 5 };
        toast(error.message || "Không tải được danh sách.", "error");
    }
    if (state.mode === "list") renderPanel();
}

// ---------------------------------------------------------------- data

async function openPlace(id, { fly = false } = {}) {
    removeEditMarker();
    removeTemp();
    state.osm = null;
    hint(null);
    state.mode = "view";
    state.draft = null;
    const lit = state.duplicates;
    state.duplicates = [];
    lit.forEach((d) => refreshPin(d.id));
    state.info = null;
    select(id);
    history.replaceState(null, "", `?id=${id}`);
    renderPanel();
    try {
        state.info = await apiClient.modPlace(id);
    } catch (error) {
        toast(error.message, "error");
        closeDetail();
        return;
    }
    if (state.selected !== id) return;                // another place was opened meanwhile
    renderPanel();
    const p = state.info;
    if (p.lat != null && (fly || !map.getBounds().contains([p.lat, p.lng]))) {
        map.setView([p.lat, p.lng], Math.max(map.getZoom(), 16));
    }
}

async function loadPins() {
    let answer;
    try {
        answer = await apiClient.modPlaceMap({ ...state.filters });
    } catch (error) {
        main.querySelector("#mm-panel").innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được các địa điểm.")}</div>`;
        return;
    }
    state.lists = { categories: answer.categories, prefectures: answer.prefectures };
    state.catById = new Map(answer.categories.map((c) => [c.id, c]));
    state.pins = new Map(answer.pins.map((p) => [p.id, p]));
    state.truncated = answer.truncated;
    state.limit = answer.limit;
    renderLists();
    drawPins();
    if (state.mode === "list") renderPanel();
}

// ---------------------------------------------------------------- start

const shell = await mountStaffDesk("MODERATOR", VIEW);
if (shell) {
    main = shell.main;
    renderShell();
    await startMap();
    await Promise.all([loadPins(), loadQueue(), loadClosedQueue()]);
    const params = new URLSearchParams(location.search);
    const id = Number(params.get("id"));
    if (params.get("new")) {
        startAdd();
    } else if (id) {
        // a waiting pin opens for approval, any other place shows its details
        if (state.pins.get(id)?.status === "PENDING") await openPin(id);
        else await openPlace(id, { fly: true });
    } else if (VIEW === "pins" && state.queue?.items.length) {
        await openPin(state.queue.items[0].id);           // straight to work: the oldest pin waiting
    } else if (VIEW === "closed" && state.closedQueue?.waiting.length) {
        await openPlace(state.closedQueue.waiting[0].id, { fly: true });     // the most reported place first
    } else {
        renderPanel();
    }
}
