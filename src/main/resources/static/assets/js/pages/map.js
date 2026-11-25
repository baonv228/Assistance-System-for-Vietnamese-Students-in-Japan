// Map (UC-U14 Browse Locations on Map, UC-U15 View Location Details). OpenStreetMap through Leaflet (free, no key):
// pins by category, search (Senpai ơi places, then OpenStreetMap for signed-in people), category chips, "my
// location", a list of the places in view and the details panel with reviews and phrases.
// Address: /map, /map?place=12 opens a place, /map?q=ramen runs a search.
import { apiClient } from "../api/client.js";
import { checkNotificationsSoon, mountSiteHeader } from "../components/site-header.js";
import { avatar, esc, googleDirectionsUrl, ic, stars, tagChips, timeAgo } from "../lib/ui.js";
import {
    BASEMAPS, distance, formatDistance, glOf, knownPlaceFor, poiAt, poiInfo, poisAround, rememberBasemap, setBaseLayer,
    storedBasemap, SUBCLASS_CATEGORY
} from "../lib/basemap.js";

const L = window.L;
const SEARCH_DELAY = 350;
const FRESH_DAYS = 30;
const OLD_DAYS = 365;
const SOURCES = { OSM: "OpenStreetMap", GOOGLE: "Google Maps", TEAM: "Nhóm Senpai ơi thêm", MODERATOR: "Nhóm Senpai ơi thêm", USER: "Ghim cộng đồng" };
const ROUTES = {
    login: () => `/login?${new URLSearchParams({ next: location.pathname + location.search })}`,
    blog: (id) => `/blog/${id}`,
    event: (id) => `/events/${id}`,
    profile: (id) => `/users/${id}`
};

const $ = (s) => document.querySelector(s);
/** Places can be reviewed and pinned only in Japan (the same box as AreaService.inJapan on the server). */
const outsideJapan = ({ lat, lng }) => lat != null && lng != null && !(lat >= 20 && lat <= 46 && lng >= 122 && lng <= 154);
const ONLY_JAPAN_ATTR = 'title="Senpai ơi chỉ nhận review địa điểm ở Nhật Bản."';
const els = {
    app: $("#mapapp"), panel: $("#panel"), body: $("#panel-body"), handle: $("#sheet-handle"),
    form: $("#search-form"), q: $("#q"), clear: $("#q-clear"), suggest: $("#suggest"), evChips: $("#ev-chips"),
    catBtn: $("#cat-btn"), catPop: $("#cat-pop"), catLabel: $("#cat-label"), catIcon: $("#cat-icon"),
    locate: $("#locate"), basemap: $("#basemap"), legend: $("#legend"), toast: $("#toast"),
    pinBtn: $("#pin-btn"), pickHint: $("#pick-hint"), pickCancel: $("#pick-cancel"),
    confirmDlg: $("#confirm-dlg"), confirmText: $("#confirm-text"), confirmOk: $("#confirm-ok"),
    reportDlg: $("#report-dlg"), reportForm: $("#report-form"), reportReasons: $("#report-reasons"),
    reportNote: $("#report-note"), reportError: $("#report-error"), reportTitle: $("#report-title"),
    reportIntro: $("#report-intro"), staffDlg: $("#staff-dlg"), staffJa: $("#staff-ja"), staffVi: $("#staff-vi"),
    searchHere: $("#search-here")
};

const state = {
    user: null,
    categories: [],
    catById: new Map(),
    catByName: new Map(),
    places: new Map(),          // id → MapPlace
    filter: null,               // category id
    results: null,              // { q, title, sub, items: [PlaceOption] } after Enter in the search box
    searchAt: null,             // { center, zoom } of the map when the results were shown ("Tìm kiếm ở khu vực này")
    searchTicket: 0,            // the latest search sent: an older answer arriving late is ignored
    panel: null,                // "list" | "detail" | "osm"
    detail: null,               // PlaceDetail
    osm: null,                  // PlaceOption of an OpenStreetMap place not yet on Senpai ơi
    tab: "rv",
    reviews: { items: [], page: 0, total: 0, loading: false },
    phrases: null,              // PhraseCard[] of the place's category (the "Nhật ngữ" API, with the Member's marks)
    phraseKind: "PHRASE",       // the part shown: PHRASE "Mẫu câu" or VOCAB "Từ vựng"
    phraseReasons: null,        // the report reasons of phrases, read once when first needed
    reportPhraseId: null,       // set while the report dialog is about a phrase, not a review
    reportPlaceId: null,        // set while it is "Báo cáo địa điểm" (UC-U20)
    cameFromList: false,
    me: null,                   // { lat, lng } from "Vị trí của tôi"
    suggestTicket: 0,
    reportReviewId: null,
    eventDays: 30               // event pins: 0 (Tắt), 15 or 30 days ahead
};

let map;
let cluster;
let eventLayer;
let resultLayer;           // OpenStreetMap results of the last search (not on Senpai ơi yet)
let tempMarker = null;
let meMarker = null;
let tiles = null;
const markers = new Map();     // place id → Leaflet marker

// ---------------------------------------------------------------- start

state.user = await mountSiteHeader("map");
setTopHeight();
window.addEventListener("resize", setTopHeight);

if (!L || !L.markerClusterGroup) {
    els.body.innerHTML = `<div class="pad"><div class="alert error">Không tải được bản đồ lúc này. Hãy kiểm tra kết nối mạng rồi tải lại trang.</div></div>`;
    openPanel();
} else {
    await start();
    window.addEventListener("asvsj:notification", onScanResult);
}

/**
 * The bell heard that the photos/video of the user's review were scanned: the pins get their new figures, and the
 * place's panel, when it is open on the reviews, shows the review as it is now (published or refused).
 */
async function onScanResult({ detail }) {
    const m = /^\/map\?place=(\d+)$/.exec(detail.link || "");
    if (!m || !detail.type.startsWith("CONTENT_")) return;
    await refreshPlaces();
    const id = Number(m[1]);
    if (state.panel !== "detail" || state.detail?.id !== id) return;
    try {
        const fresh = await apiClient.mapPlace(id);
        if (state.panel !== "detail" || state.detail?.id !== id) return;
        state.detail = fresh;
    } catch {
        return;
    }
    renderPanel();
    if (state.tab === "rv") loadReviews(0);
}

async function start() {
    let overview;
    try {
        overview = await apiClient.mapOverview();
    } catch (error) {
        els.body.innerHTML = `<div class="pad"><div class="alert error">${esc(error.message)}</div></div>`;
        openPanel();
        return;
    }
    state.categories = overview.categories;
    overview.categories.forEach((c) => {
        state.catById.set(c.id, c);
        state.catByName.set(c.name, c);
    });
    overview.places.forEach((p) => state.places.set(p.id, p));
    state.devLocation = overview.devLocation;

    map = L.map("map", { zoomControl: true, keyboard: true, maxZoom: 19 })
        .setView([overview.center.lat, overview.center.lng], overview.center.zoom);
    setBasemap(storedBasemap());
    map.zoomControl.setPosition("bottomright");
    // The layers and "Vị trí của tôi" buttons sit right above the zoom buttons and the map credit, whose height
    // changes with the map style and the screen width (the credit takes two lines on a phone)
    const corner = map.getContainer().querySelector(".leaflet-bottom.leaflet-right");
    if (corner && typeof ResizeObserver === "function") {
        new ResizeObserver(() => els.app?.style.setProperty("--corner-h", `${corner.offsetHeight}px`)).observe(corner);
    }
    cluster = L.markerClusterGroup({
        showCoverageOnHover: false,
        maxClusterRadius: 48,
        iconCreateFunction: (c) => L.divIcon({ className: "mp-wrap", html: `<span class="mc">${c.getChildCount()}</span>`,
            iconSize: [42, 42] })
    }).addTo(map);
    eventLayer = L.layerGroup().addTo(map);
    resultLayer = L.layerGroup().addTo(map);
    renderChips();
    drawPins();
    loadEvents();
    map.on("moveend", () => {
        if (state.panel === "list" && !state.results) renderPanel();
        updateSearchHere();
    });
    map.on("click", (e) => {
        if (state.picking) startPin(e.latlng);
        else if (state.panel === "pin" && state.pin) movePin(e.latlng);
        else openMapShop(e.latlng);
    });
    let hoverQueued = false;
    map.on("mousemove", (e) => {
        if (hoverQueued) return;
        hoverQueued = true;
        requestAnimationFrame(() => {
            hoverQueued = false;
            map.getContainer().style.cursor = shopAt(e.latlng) ? "pointer" : "";
        });
    });

    const params = new URLSearchParams(location.search);
    if (params.get("place")) {
        openPlace(Number(params.get("place")), { fly: true, instant: true });
    } else if (params.get("q")) {
        els.q.value = params.get("q");
        runSearch(params.get("q"));
    }
}

function setTopHeight() {
    const top = document.querySelector(".site-top");
    if (top) document.documentElement.style.setProperty("--top-h", `${top.offsetHeight}px`);
    map?.invalidateSize();
}

// ---------------------------------------------------------------- pins

function categoryIcon(categoryId) {
    return state.catById.get(categoryId)?.icon || "place";
}

function pinClass(p) {
    const lit = p.reviewCount > 0 && p.status === "ACTIVE" && !p.closed;
    return [lit ? "lit" : "dim", p.source === "USER" ? "comm" : "", p.status === "PENDING" ? "pend" : "",
        p.closed ? "shut" : "", state.detail?.id === p.id ? "sel" : ""].filter(Boolean).join(" ");
}

function pinIcon(p) {
    const lit = p.reviewCount > 0 && p.status === "ACTIVE" && !p.closed;
    const size = lit ? 40 : 30;
    return L.divIcon({
        className: "mp-wrap",
        html: `<span class="mp ${pinClass(p)}">${ic(categoryIcon(p.categoryId))}${lit ? `<b>${p.reviewCount}</b>` : ""}${p.scam ? "<i>!</i>" : ""}</span>`,
        iconSize: [size, size],
        iconAnchor: [size / 2, size / 2]
    });
}

function pinLabel(p) {
    const cat = state.catById.get(p.categoryId)?.name || "";
    return [p.name, cat, p.reviewCount ? `${p.reviewCount} review` : "chưa có review",
        p.closed ? "đã đóng cửa" : "", p.scam ? "có cảnh báo lừa đảo" : "", p.status === "PENDING" ? "chờ Moderator duyệt" : ""]
        .filter(Boolean).join(", ");
}

function drawPins() {
    cluster.clearLayers();
    markers.clear();
    const shown = [...state.places.values()].filter((p) => !state.filter || p.categoryId === state.filter);
    const list = shown.map((p) => {
        const m = L.marker([p.lat, p.lng], { icon: pinIcon(p), title: pinLabel(p), alt: pinLabel(p), keyboard: true });
        m.on("click", () => openPlace(p.id));
        markers.set(p.id, m);
        return m;
    });
    cluster.addLayers(list);
}

function refreshPin(id) {
    const p = state.places.get(id);
    const m = markers.get(id);
    if (p && m) m.setIcon(pinIcon(p));
}

/** Lantern pins with "Còn N ngày" / "Đang diễn ra" under them; "Tắt" (0) hides them. */
async function loadEvents() {
    eventLayer.clearLayers();
    els.evChips.querySelectorAll("[data-days]").forEach((b) =>
        b.setAttribute("aria-pressed", String(Number(b.dataset.days) === state.eventDays)));
    if (!state.eventDays) return;
    try {
        const events = await apiClient.mapEvents(state.eventDays);
        events.forEach((e) => {
            const when = e.daysUntil === 0 ? "Đang diễn ra" : `Còn ${e.daysUntil} ngày`;
            L.marker([e.lat, e.lng], {
                icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp ev">${ic("celebration")}<span class="tagl">${when}</span></span>`,
                    iconSize: [34, 34], iconAnchor: [17, 17] }),
                title: `Sự kiện: ${e.title}, ${when}`, alt: e.title
            }).bindPopup(`<b>${esc(e.title)}</b><br><small>${esc(e.locationName)} · ${esc(when)}</small>
                <br><a class="evlink" href="${ROUTES.event(e.id)}">Xem sự kiện${ic("arrow_forward")}</a>`)
                .addTo(eventLayer);
        });
    } catch {
        // events are optional on the map
    }
}
els.evChips.addEventListener("click", (event) => {
    const chip = event.target.closest("[data-days]");
    if (!chip) return;
    state.eventDays = Number(chip.dataset.days);
    loadEvents();
});

// ---------------------------------------------------------------- "Danh mục" dropdown

function renderChips() {
    const active = state.categories.filter((c) => c.count > 0);
    const total = active.reduce((n, c) => n + c.count, 0);
    const cat = state.catById.get(state.filter);
    els.catLabel.textContent = cat ? cat.name : "Danh mục";
    els.catIcon.textContent = cat ? cat.icon : "category";
    els.catBtn.classList.toggle("on", !!cat);
    els.catPop.innerHTML = [{ id: "", name: "Tất cả danh mục", icon: "apps", count: total }, ...active].map((c) =>
        `<button class="opt" type="button" role="option" data-cat="${esc(c.id)}" aria-selected="${(state.filter || "") === c.id}">
            ${ic(c.icon)}<span>${esc(c.name)}</span><span class="note">${c.count}</span></button>`).join("");
}

function toggleCatPop(open = els.catPop.hidden) {
    els.catPop.hidden = !open;
    els.catBtn.setAttribute("aria-expanded", String(open));
    if (open) {
        hideSuggest();
        (els.catPop.querySelector('[aria-selected="true"]') || els.catPop.querySelector(".opt"))?.focus();
    }
}

els.catBtn.addEventListener("click", () => toggleCatPop());
els.catPop.addEventListener("click", (event) => {
    const opt = event.target.closest("[data-cat]");
    if (!opt) return;
    toggleCatPop(false);
    els.catBtn.focus();
    setFilter(opt.dataset.cat || null);
});
els.catPop.addEventListener("keydown", (event) => {
    const opts = [...els.catPop.querySelectorAll(".opt")];
    const i = opts.indexOf(document.activeElement);
    if (event.key === "ArrowDown") opts[Math.min(i + 1, opts.length - 1)]?.focus();
    if (event.key === "ArrowUp") opts[Math.max(i - 1, 0)]?.focus();
    if (event.key === "Escape") { toggleCatPop(false); els.catBtn.focus(); }
    if (["ArrowDown", "ArrowUp"].includes(event.key)) event.preventDefault();
});

function setFilter(categoryId) {
    state.filter = categoryId;
    state.results = null;
    renderChips();
    drawPins();
    if (categoryId) {
        showList();
    } else if (state.panel === "list") {
        closePanel();
    }
}

// ---------------------------------------------------------------- search

let suggestTimer = null;
els.q.addEventListener("input", () => {
    els.clear.hidden = !els.q.value;
    clearTimeout(suggestTimer);
    if (!els.q.value.trim()) {
        hideSuggest();
        return;
    }
    suggestTimer = setTimeout(() => suggest(els.q.value), SEARCH_DELAY);
});
els.q.addEventListener("keydown", (event) => {
    if (event.key === "Escape") hideSuggest();
    if (event.key === "ArrowDown") els.suggest.querySelector(".opt")?.focus();
});
els.suggest.addEventListener("keydown", (event) => {
    const opts = [...els.suggest.querySelectorAll(".opt")];
    const i = opts.indexOf(document.activeElement);
    if (event.key === "ArrowDown") opts[Math.min(i + 1, opts.length - 1)]?.focus();
    if (event.key === "ArrowUp") (i <= 0 ? els.q : opts[i - 1]).focus();
    if (event.key === "Escape") { hideSuggest(); els.q.focus(); }
    if (["ArrowDown", "ArrowUp"].includes(event.key)) event.preventDefault();
});
els.clear.addEventListener("click", () => {
    els.q.value = "";
    els.clear.hidden = true;
    hideSuggest();
    if (state.results) {
        state.results = null;
        resultLayer.clearLayers();
        closePanel();
    }
    els.searchHere.hidden = true;
    els.q.focus();
});
els.form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearTimeout(suggestTimer);
    els.q.blur();              // on a phone the keyboard goes away, so it does not cover the results
    runSearch(els.q.value);
});
document.addEventListener("click", (event) => {
    if (!event.target.closest(".map-top")) hideSuggest();
    if (!event.target.closest(".ddwrap") && !els.catPop.hidden) toggleCatPop(false);
});
// The legend is open on wide screens, a "Chú thích" button on phones
if (matchMedia("(min-width: 861px)").matches && els.panel.hidden) els.legend.open = true;

function hideSuggest() {
    els.suggest.hidden = true;
    els.q.setAttribute("aria-expanded", "false");
}

async function suggest(text) {
    const ticket = ++state.suggestTicket;
    let found;
    try {
        found = await apiClient.mapSearch(text, viewBox());
    } catch (error) {
        if (ticket !== state.suggestTicket) return;
        els.suggest.innerHTML = `<p class="note">${esc(error.message)}</p>`;
        showSuggest();
        return;
    }
    if (ticket !== state.suggestTicket || els.q.value.trim() !== text.trim()) return;
    const cats = found.categories.map((c) => `<button class="opt" type="button" role="option" data-cat="${esc(c.id)}">
            <span class="oi">${ic(c.icon)}</span><span class="ot"><b>${esc(c.name)}</b><small>Danh mục · ${c.count} địa điểm</small></span></button>`).join("");
    const places = found.places.map(optionHtml).join("");
    els.suggest.innerHTML = (cats ? `<h4>Danh mục</h4>${cats}` : "")
        + (places ? `<h4>Địa điểm</h4>${places}` : "")
        + (!cats && !places ? `<p class="note">Không có địa điểm nào khớp.</p>` : "")
        + externalNote(found);
    state.lastOptions = found.places;
    showSuggest();
}

function showSuggest() {
    els.suggest.hidden = false;
    els.q.setAttribute("aria-expanded", "true");
}

function externalNote(found) {
    switch (found.external) {
        case "LOGIN": return `<p class="note">${ic("info")}<a href="${esc(ROUTES.login())}">Đăng nhập</a> để tìm thêm trên OpenStreetMap.</p>`;
        case "BUSY": return `<p class="note">${ic("hourglass_top")}Bạn tìm hơi nhanh, hãy chờ vài giây rồi thử lại để thấy thêm địa điểm từ OpenStreetMap.</p>`;
        case "UNAVAILABLE":
            return ["SSL", "DNS", "NETWORK", "TIMEOUT"].includes(found.externalReason)
                ? `<p class="note">${ic("cloud_off")}Máy chủ chưa kết nối được tới OpenStreetMap, hãy thử lại sau.</p>`
                : `<p class="note">${ic("cloud_off")}OpenStreetMap đang bận, hãy thử lại sau ít phút.</p>`;
        case "SHORT": return `<p class="note">Gõ thêm để tìm trên OpenStreetMap.</p>`;
        default: return "";
    }
}

/** A search result: a Senpai ơi place (id) or an OpenStreetMap place not yet on Senpai ơi (externalId). */
function optionHtml(o) {
    const known = o.id != null ? state.places.get(o.id) : null;
    const icon = known ? categoryIcon(known.categoryId) : (state.catByName.get(o.category)?.icon || "place");
    const sub = [o.category, o.address].filter(Boolean).join(" · ");
    const meta = known
        ? (known.reviewCount ? `<span class="stars">★</span> ${known.rating ?? ""}<br>${known.reviewCount} review` : "Chưa có review")
        : `<span class="badge">OSM</span>`;
    const dist = state.me ? `<br>${formatDistance(distance(state.me, o))}` : "";
    return `<button class="opt" type="button" role="option" ${o.id != null ? `data-place="${o.id}"` : `data-osm="${esc(o.externalId)}"`}>
        <span class="oi ${o.id == null ? "osm" : ""}">${ic(icon)}</span>
        <span class="ot"><b>${esc(o.name)}</b><small>${esc(sub)}</small></span>
        <span class="meta">${meta}${dist}</span></button>`;
}

els.suggest.addEventListener("click", (event) => {
    const opt = event.target.closest(".opt");
    if (!opt) return;
    hideSuggest();
    if (opt.dataset.cat) {
        els.q.value = "";
        els.clear.hidden = true;
        setFilter(opt.dataset.cat);
    } else if (opt.dataset.place) {
        openPlace(Number(opt.dataset.place), { fly: true });
    } else if (opt.dataset.osm) {
        const o = (state.lastOptions || []).find((x) => x.externalId === opt.dataset.osm);
        if (o) openOsm(o);
    }
});

/**
 * The search as on Google Maps: the places inside the view on screen, nearest to its centre first, and the map stays
 * where it is. Only when the view holds none does the server look all over Japan (scope WIDE); the map then goes to
 * the best result (and close ones), never zooms out to all of them.
 */
async function runSearch(text) {
    const q = text.trim();
    hideSuggest();
    if (!q) return;
    ++state.suggestTicket;
    const ticket = ++state.searchTicket;
    els.searchHere.hidden = true;
    els.body.innerHTML = `<p class="skeleton">Đang tìm…</p>`;
    state.panel = "list";
    openPanel();
    try {
        const found = await apiClient.mapSearch(q, viewBox());
        if (ticket !== state.searchTicket) return;
        const wide = found.scope === "WIDE";
        const n = found.places.length;
        state.results = {
            q,
            title: `Kết quả cho “${q}”`,
            sub: !n ? "" : wide ? `${n} địa điểm ngoài vùng đang xem` : `${n} địa điểm trong vùng đang xem`,
            items: found.places,
            note: (wide && n ? `<p class="note">${ic("travel_explore")}Không có kết quả trong vùng đang xem, đây là kết quả ở nơi khác.</p>` : "")
                + externalNote(found),
            categories: found.categories
        };
        state.lastOptions = found.places;
        showList();
        drawResultPins(found.places);
        state.searchAt = { center: map.getCenter(), zoom: map.getZoom() };
        if (wide && n) {
            // the best result (the server's order weighs the name and the nearness), with those of the next two that
            // stand close to it: never zoomed out to every result
            const pts = found.places.filter((o) => o.lat != null);
            const first = L.latLng(pts[0].lat, pts[0].lng);
            const nearest = [pts[0], ...pts.slice(1, 3).filter((o) => first.distanceTo([o.lat, o.lng]) <= 1500)];
            map.once("moveend", markSearchView);
            // into the part of the map that is not behind the list panel / sheet
            const v = visibleRect();
            const size = map.getSize();
            const bounds = L.latLngBounds(nearest.map((o) => [o.lat, o.lng]));
            map.fitBounds(nearest.length === 1 ? bounds.pad(0.002) : bounds, {
                paddingTopLeft: [v.left + 60, v.top + 90],
                paddingBottomRight: [size.x - v.right + 60, size.y - v.bottom + 40],
                maxZoom: nearest.length === 1 ? Math.max(map.getZoom(), 16) : 16
            });
        }
    } catch (error) {
        if (ticket !== state.searchTicket) return;
        els.body.innerHTML = `<div class="pad"><div class="alert error">${esc(error.message)}</div></div>`;
    }
}

/**
 * The part of the map the user can see, in map pixels: without the list panel on its left (wide screens) or the sheet
 * at the bottom (phones), so the search does not list places hidden behind them.
 */
function visibleRect() {
    const size = map.getSize();
    const rect = { left: 0, top: 0, right: size.x, bottom: size.y };
    if (!els.panel.hidden) {
        const box = map.getContainer().getBoundingClientRect();
        const panel = els.panel.getBoundingClientRect();
        if (matchMedia("(min-width: 861px)").matches) rect.left = Math.min(size.x - 80, Math.max(0, panel.right - box.left));
        else rect.bottom = Math.max(80, Math.min(size.y, panel.top - box.top));
    }
    return rect;
}

/** The box on screen for the search, within the world's limits (a map zoomed far out repeats the world sideways). */
function viewBox() {
    if (!map) return {};
    const v = visibleRect();
    const b = L.latLngBounds(map.containerPointToLatLng([v.left, v.bottom]), map.containerPointToLatLng([v.right, v.top]));
    const r = (x) => Math.round(x * 1e5) / 1e5;
    const south = Math.max(-90, b.getSouth());
    const north = Math.min(90, b.getNorth());
    const west = Math.max(-180, b.getWest());
    const east = Math.min(180, b.getEast());
    if (!(south < north && west < east)) return {};
    return { south: r(south), west: r(west), north: r(north), east: r(east) };
}

function markSearchView() {
    state.searchAt = { center: map.getCenter(), zoom: map.getZoom() };
    updateSearchHere();
}

/** "Tìm kiếm ở khu vực này" shows once the user has moved the map away from the results of a search. */
function updateSearchHere() {
    if (!state.results?.q || !state.searchAt || state.picking) {
        els.searchHere.hidden = true;
        return;
    }
    const moved = map.getZoom() !== state.searchAt.zoom
        || map.latLngToContainerPoint(state.searchAt.center).distanceTo(map.latLngToContainerPoint(map.getCenter())) > 80;
    els.searchHere.hidden = !moved;
}

els.searchHere.addEventListener("click", () => {
    const q = state.results?.q;
    if (!q) return;
    els.q.value = q;
    els.clear.hidden = false;
    runSearch(q);
});

// ---------------------------------------------------------------- list panel

/** Dark pins for the OpenStreetMap places of a search; Senpai ơi places already have their own pin. */
function drawResultPins(options) {
    resultLayer.clearLayers();
    options.filter((o) => o.id == null && o.lat != null).forEach((o) => {
        const icon = state.catByName.get(o.category)?.icon || "place";
        L.marker([o.lat, o.lng], {
            icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp osm">${ic(icon)}</span>`, iconSize: [28, 28], iconAnchor: [14, 14] }),
            title: `${o.name}, OpenStreetMap, chưa có trên Senpai ơi`, alt: o.name
        }).on("click", () => openOsm(o)).addTo(resultLayer);
    });
}

function showList() {
    state.panel = "list";
    state.detail = null;
    state.osm = null;
    removeTemp();
    renderPanel();
    openPanel();
}

function placesInView() {
    const bounds = map.getBounds();
    const list = [...state.places.values()]
        .filter((p) => (!state.filter || p.categoryId === state.filter) && p.status === "ACTIVE" && bounds.contains([p.lat, p.lng]));
    if (state.me) {
        list.sort((a, b) => distance(state.me, a) - distance(state.me, b));
    } else {
        list.sort((a, b) => b.reviewCount - a.reviewCount || (b.rating ?? 0) - (a.rating ?? 0) || a.name.localeCompare(b.name, "vi"));
    }
    return list;
}

function renderList() {
    let title;
    let sub;
    let items;
    let note = "";
    if (state.results) {
        title = state.results.title;
        items = state.results.items;
        sub = state.results.sub || `${items.length} địa điểm`;
        note = state.results.note || "";
    } else {
        const cat = state.catById.get(state.filter);
        items = placesInView().map((p) => ({ id: p.id, name: p.name, category: cat?.name || state.catById.get(p.categoryId)?.name, lat: p.lat, lng: p.lng }));
        title = cat ? cat.name : "Địa điểm trong vùng đang xem";
        sub = `${items.length} địa điểm · ${state.me ? "gần bạn nhất trước" : "nhiều review nhất trước"}`;
    }
    const body = items.length
        ? `<div class="list-items" role="list">${items.map(optionHtml).join("")}</div>`
        : `<div class="pad"><p class="note">Không có địa điểm nào khớp.${state.results ? "" : " Hãy kéo hoặc thu nhỏ bản đồ."}</p></div>`;
    return `<div class="pad list-head"><div class="grow"><h2>${esc(title)}</h2><p class="note">${esc(sub)}</p>${note}</div>
            <button class="icon-btn" type="button" data-act="close-list" aria-label="Đóng danh sách">${ic("close")}</button></div>${body}`;
}

// ---------------------------------------------------------------- details panel

async function openPlace(id, { fly = false, instant = false } = {}) {
    state.cameFromList = state.panel === "list";
    state.panel = "detail";
    state.osm = null;
    state.tab = "rv";
    state.phrases = null;
    state.reviews = { items: [], page: 0, total: 0, loading: false };
    removeTemp();
    els.body.innerHTML = `<p class="skeleton">Đang tải…</p>`;
    openPanel();
    const previous = state.detail?.id;
    try {
        state.detail = await apiClient.mapPlace(id);
    } catch (error) {
        state.detail = null;
        els.body.innerHTML = `<div class="pad"><div class="alert error">${esc(error.message)}</div>
            <p><button class="btn sm" type="button" data-act="close">${ic("close")}Đóng</button></p></div>`;
        return;
    }
    const d = state.detail;
    if (previous) refreshPin(previous);
    refreshPin(d.id);
    setUrl({ place: d.id });
    if (fly && d.lat != null) map.setView([d.lat, d.lng], Math.max(map.getZoom(), 16), { animate: !instant });
    if (d.lat != null && !state.places.has(d.id)) showTemp(d.lat, d.lng, d.categoryIcon);
    renderPanel();
    loadReviews(0);
}

function openOsm(option) {
    state.cameFromList = state.panel === "list";
    const previous = state.detail?.id;
    state.panel = "osm";
    state.detail = null;
    state.osm = option;
    if (previous) refreshPin(previous);
    renderPanel();
    openPanel();
    showTemp(option.lat, option.lng, state.catByName.get(option.category)?.icon || "place");
    map.setView([option.lat, option.lng], Math.max(map.getZoom(), 17));
}

function showTemp(lat, lng, icon) {
    removeTemp();
    tempMarker = L.marker([lat, lng], {
        icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp osm sel">${ic(icon)}</span>`, iconSize: [32, 32], iconAnchor: [16, 16] }),
        keyboard: false, interactive: false
    }).addTo(map);
}

function removeTemp() {
    if (tempMarker) {
        tempMarker.remove();
        tempMarker = null;
    }
}

function closeLabel() {
    return state.cameFromList ? `${ic("arrow_back")}Danh sách` : `${ic("close")}Đóng`;
}

function badges(d) {
    const out = [`<span class="badge">${ic(d.categoryIcon)}${esc(d.categoryName)}</span>`, `<span class="badge">${esc(SOURCES[d.source] || d.source)}</span>`];
    if (d.status === "PENDING") out.push(`<span class="badge warn">Chờ Moderator duyệt</span>`);
    if (d.closed) out.push(`<span class="badge crit">Đã đóng cửa (Moderator xác nhận)</span>`);
    else if (d.closedReports >= d.closedThreshold) out.push(`<span class="badge warn">Nhiều người báo đã đóng cửa, đang chờ xác minh</span>`);
    return out.join("");
}

function renderDetail() {
    const d = state.detail;
    const names = [d.nameJa ? `<span class="ja">${esc(d.nameJa)}</span>` : "", esc(d.nameEn || ""), esc(d.address || "")].filter(Boolean).join(" · ");
    const scam = d.scamWarnings.length ? `<div class="warn-box" role="note"><span class="row">${ic("warning")}<b>Cảnh báo lừa đảo</b></span>
            ${d.scamWarnings.map((w) => `<a href="${ROUTES.blog(w.blogId)}">${esc(w.title)}</a>`).join("")}
            <a class="btn sm" href="${ROUTES.blog(d.scamWarnings[0].blogId)}">Đọc bài cảnh báo</a></div>` : "";
    const rating = d.reviewCount
        ? `<span class="stars" aria-hidden="true">${stars(Math.round(d.rating))}</span><b>${d.rating}</b><span class="note">${d.reviewCount} review từ người dùng Senpai ơi</span>`
        : `<span class="note">Chưa có review. Hãy là người đầu tiên.</span>`;
    const staff = state.user && !d.canAct;
    const reviewBtn = d.myReviewId
        ? `<button class="btn pri sm" type="button" data-act="edit-review" data-id="${d.myReviewId}">${ic("edit")}Sửa review của bạn</button>`
        : `<button class="btn pri sm" type="button" data-act="write" ${outsideJapan(d) ? `disabled ${ONLY_JAPAN_ATTR}` : staff || d.status !== "ACTIVE" ? "disabled" : ""}>${ic("rate_review")}Viết review</button>`;
    const saveBtn = `<button class="btn sm ${d.saved ? "on" : ""}" type="button" data-act="save" aria-pressed="${d.saved}"
            ${state.user && !d.canAct ? "disabled" : ""}>${ic(d.saved ? "bookmark_added" : "bookmark")}${d.saved ? "Đã lưu" : "Lưu"}</button>`;
    // "Báo cáo địa điểm": "Đã đóng cửa" or "Sai thông tin", one of each per member
    const doneAll = (d.closed || d.reportedClosed) && d.reportedWrong;
    const closedBtn = d.status !== "ACTIVE" ? "" : doneAll
        ? `<button class="btn sm on" type="button" disabled>${ic("flag")}Bạn đã báo cáo địa điểm này</button>`
        : `<button class="btn sm" type="button" data-act="report-place" ${staff ? "disabled" : ""}>${ic("flag")}Báo cáo địa điểm</button>`;
    return `<div class="pad">
            <div class="panel-head"><div class="row grow">${badges(d)}</div>
                <button class="btn sm" type="button" data-act="back">${closeLabel()}</button></div>
            ${d.mergedFrom ? `<p class="note">${ic("merge")}Địa điểm bạn mở đã được gộp vào địa điểm này.</p>` : ""}
            <h2 class="place-title">${esc(d.name)}</h2>
            <p class="place-sub">${names}</p>
            ${scam}
            <div class="rating-row">${rating}</div>
            <div class="actions-row">${reviewBtn}${saveBtn}
                <a class="btn sm" href="${esc(googleDirectionsUrl(d))}" target="_blank" rel="noopener" title="Mở chỉ đường Google Maps">${ic("directions")}Chỉ đường</a>
                ${closedBtn}</div>
        </div>
        <div class="tabs2" role="tablist">
            <button type="button" role="tab" data-tab="rv" aria-selected="${state.tab === "rv"}">Review</button>
            <button type="button" role="tab" data-tab="vc" aria-selected="${state.tab === "vc"}">Từ vựng và mẫu câu</button>
        </div>
        <div class="pad" id="tab-body" role="tabpanel">${state.tab === "rv" ? reviewsHtml() : phrasesHtml()}</div>`;
}

function renderOsm() {
    const o = state.osm;
    return `<div class="pad">
            <div class="panel-head"><div class="row grow"><span class="badge">${ic(state.catByName.get(o.category)?.icon || "place")}${esc(o.category || "Địa điểm")}</span>
                <span class="badge">OpenStreetMap</span></div>
                <button class="btn sm" type="button" data-act="back">${closeLabel()}</button></div>
            <h2 class="place-title">${esc(o.name)}</h2>
            <p class="place-sub">${[o.nameJa && o.nameJa !== o.name ? `<span class="ja">${esc(o.nameJa)}</span>` : "", esc(o.address || "")].filter(Boolean).join(" · ")}</p>
            <div class="rating-row"><span class="badge">Chưa có review</span></div>
            <div class="actions-row">
                <button class="btn pri sm" type="button" data-act="write-osm" ${outsideJapan(o) ? `disabled ${ONLY_JAPAN_ATTR}` : state.user && state.user.role !== "USER" ? "disabled" : ""}>${ic("rate_review")}Viết review đầu tiên</button>
                <a class="btn sm" href="${esc(googleDirectionsUrl(o))}" target="_blank" rel="noopener">${ic("directions")}Chỉ đường</a></div>
            <p class="note osm-note">${ic("info")}${outsideJapan(o) ? "Senpai ơi chỉ nhận review địa điểm ở Nhật Bản."
                : "Địa điểm này có trên OpenStreetMap nhưng chưa có trên Senpai ơi. Địa điểm sẽ được thêm khi có người viết review, lưu, hoặc gắn vào bài viết."}</p>
        </div>`;
}

function freshness(iso) {
    const days = (Date.now() - new Date(iso).getTime()) / 86400000;
    if (days <= FRESH_DAYS) return `<span class="badge ok">Còn mới</span>`;
    if (days > OLD_DAYS) return `<span class="badge warn">Hơn 1 năm, cần xác minh lại</span>`;
    return "";
}

function reviewHtml(r) {
    const a = r.author;
    const who = a.id ? `<a href="${esc(ROUTES.profile(a.id))}"><b>${esc(a.displayName)}</b></a>` : `<b>${esc(a.displayName)}</b>`;
    const badgesHtml = [tagChips(a.tags), a.rank ? `<span class="badge lan">${esc(a.rank)}</span>` : "",
        a.roleLabel ? `<span class="badge br">${esc(a.roleLabel)}</span>` : ""].join("");
    const media = r.media.length ? `<div class="media">${r.media.map((m) => m.type === "VIDEO"
        ? `<video src="${esc(m.url)}" controls preload="metadata"></video>`
        : `<a href="${esc(m.url)}" target="_blank" rel="noopener"><img src="${esc(m.url)}" alt="Ảnh trong review của ${esc(a.displayName)}" loading="lazy"></a>`).join("")}</div>` : "";
    const canAct = state.detail?.canAct && !r.mine && r.status === "PUBLISHED";
    const guest = !state.user;
    const like = `<button class="btn sm ${r.liked ? "on" : ""}" type="button" data-act="like" data-id="${r.id}" aria-pressed="${r.liked}"
            ${!guest && !canAct ? "disabled" : ""}>${ic("thumb_up")}Thích${r.likeCount ? ` (${r.likeCount})` : ""}</button>`;
    const report = r.mine ? "" : `<button class="btn sm" type="button" data-act="report" data-id="${r.id}" ${!guest && !canAct ? "disabled" : ""}>${ic("flag")}Báo cáo</button>`;
    return `<article class="review ${r.mine ? "mine" : ""}" id="rv-${r.id}">
        <div class="review-head">${avatar(a)}<div class="who">${who}<div class="badges">${badgesHtml}</div></div></div>
        <div class="row"><span class="stars" aria-label="${r.rating} trên 5 sao">${stars(r.rating)}</span>${freshness(r.createdAt)}
            ${r.helpful ? `<span class="badge lan">${ic("verified")}Hữu ích</span>` : ""}
            ${r.status === "PROCESSING" ? `<span class="badge warn">Đang xử lý ảnh/video</span>` : ""}</div>
        <p>${esc(r.content)}</p>
        ${media}
        <div class="foot"><span class="note" title="${esc(new Date(r.createdAt).toLocaleString("vi-VN"))}">${esc(timeAgo(r.createdAt))}${r.edited ? " · đã sửa" : ""}</span>
            <span class="grow"></span>${r.mine
        ? `<button class="btn sm" type="button" data-act="edit-review" data-id="${r.id}">${ic("edit")}Sửa</button>
           <button class="btn sm dan" type="button" data-act="delete-review" data-id="${r.id}">${ic("delete")}Xóa</button>`
        : `${like}${report}`}</div>
    </article>`;
}

function reviewsHtml() {
    const rv = state.reviews;
    if (!rv.items.length) return rv.loading ? `<p class="note">Đang tải review…</p>` : `<p class="note">Chưa ai viết review về địa điểm này.</p>`;
    const more = rv.items.length < rv.total
        ? `<div class="more"><button class="btn sm" type="button" data-act="more" ${rv.loading ? "disabled" : ""}>Xem thêm review</button></div>` : "";
    return rv.items.map(reviewHtml).join("") + more;
}

const PHRASE_KINDS = [["PHRASE", "Mẫu câu"], ["VOCAB", "Từ vựng"]];

/**
 * "Từ vựng và mẫu câu" of the place's category, for use on the spot (not for studying: no flash cards or marks here):
 * one part at a time with its count, cards with their topic, "Đưa cho nhân viên xem" (phrases only), "Nghe", "Báo lỗi".
 */
function phrasesHtml() {
    if (state.phrases == null) return `<p class="note">Đang tải…</p>`;
    if (!state.phrases.length) return `<p class="note">Danh mục này chưa có từ vựng và mẫu câu.</p>`;
    const count = (kind) => state.phrases.filter((p) => p.kind === kind).length;
    const list = state.phrases.filter((p) => p.kind === state.phraseKind);
    return `<div class="seg pkinds" role="group" aria-label="Loại">${PHRASE_KINDS.map(([kind, label]) =>
            `<button type="button" data-pkind="${kind}" aria-pressed="${kind === state.phraseKind}" ${count(kind) ? "" : "disabled"}>${label} (${count(kind)})</button>`).join("")}</div>
        ${list.map(phraseCard).join("")}`;
}

function phraseCard(p) {
    const staffViewer = state.user && state.user.role !== "USER";
    return `<article class="pcard" data-phrase="${p.id}">
            ${p.topics.length ? `<div class="row">${p.topics.map((t) => `<span class="badge br">${esc(t.name)}</span>`).join("")}</div>` : ""}
            <div class="pja" lang="ja">${esc(p.japanese)}</div>
            ${p.romaji ? `<div class="note">${esc(p.romaji)}</div>` : ""}
            <div>${esc(p.meaningVi)}</div>
            <div class="row">
                ${p.kind === "PHRASE" ? `<button class="btn sm pri" type="button" data-act="staff-phrase">${ic("front_hand")}Đưa cho nhân viên xem</button>` : ""}
                <button class="btn sm" type="button" data-act="speak" data-text="${esc(p.japanese)}">${ic("volume_up")}Nghe</button>
                ${staffViewer ? "" : `<button class="btn sm" type="button" data-act="report-phrase">${ic("flag")}Báo lỗi</button>`}
            </div>
        </article>`;
}

/** "Đưa cho nhân viên xem": the phrase as large as the screen allows, with its meaning. */
function showStaff(phrase) {
    els.staffJa.textContent = phrase.japanese;
    els.staffVi.textContent = phrase.meaningVi;
    els.staffDlg.showModal();
}

async function loadReviews(page) {
    const d = state.detail;
    if (!d) return;
    state.reviews.loading = true;
    renderTab();
    try {
        const res = await apiClient.mapPlaceReviews(d.id, page);
        if (state.detail?.id !== d.id) return;
        state.reviews.items = page === 0 ? res.items : state.reviews.items.concat(res.items);
        state.reviews.page = page;
        state.reviews.total = res.total;
    } catch (error) {
        toast(error.message, "error");
    } finally {
        state.reviews.loading = false;
        renderTab();
    }
}

async function loadPhrases() {
    const d = state.detail;
    try {
        const list = await apiClient.phrases({ category: d.categoryId });
        if (state.detail?.id === d.id) {
            state.phrases = list;
            state.phraseKind = list.some((p) => p.kind === "PHRASE") ? "PHRASE" : "VOCAB";
        }
    } catch (error) {
        state.phrases = [];
        toast(error.message, "error");
    }
    renderTab();
}

function renderTab() {
    const box = document.getElementById("tab-body");
    if (box && state.panel === "detail") box.innerHTML = state.tab === "rv" ? reviewsHtml() : phrasesHtml();
}

// ---------------------------------------------------------------- panel

function renderPanel() {
    if (state.panel === "list") els.body.innerHTML = renderList();
    else if (state.panel === "detail" && state.detail) els.body.innerHTML = renderDetail();
    else if (state.panel === "osm" && state.osm) els.body.innerHTML = renderOsm();
    else if (state.panel === "write" && state.write) els.body.innerHTML = renderWrite();
    else if (state.panel === "pin" && state.pin) els.body.innerHTML = renderPin();
}

function openPanel() {
    els.panel.hidden = false;
    els.legend.open = false;                    // the legend would cover the map next to the panel
    els.app.classList.add("with-panel");
    setTimeout(() => map?.invalidateSize(), 0);
}

function closePanel() {
    const previous = state.detail?.id;
    state.panel = null;
    state.detail = null;
    state.osm = null;
    els.panel.hidden = true;
    els.panel.classList.remove("full");
    els.app.classList.remove("with-panel");
    removeTemp();
    if (matchMedia("(min-width: 861px)").matches) els.legend.open = true;
    if (previous) refreshPin(previous);
    setUrl({});
    setTimeout(() => map?.invalidateSize(), 0);
}

function back() {
    if (state.cameFromList && (state.results || state.filter)) {
        const previous = state.detail?.id;
        setUrl({});
        showList();
        if (previous) refreshPin(previous);
    } else {
        closePanel();
    }
}

els.handle.addEventListener("click", () => els.panel.classList.toggle("full"));

els.body.addEventListener("click", async (event) => {
    const btn = event.target.closest("[data-act], [data-place], [data-osm], [data-tab], [data-pkind]");
    if (!btn) return;
    if (btn.dataset.place) return openPlace(Number(btn.dataset.place), { fly: true });
    if (btn.dataset.osm) {
        const o = (state.results?.items || []).find((x) => x.externalId === btn.dataset.osm);
        return o && openOsm(o);
    }
    if (btn.dataset.tab) {
        state.tab = btn.dataset.tab;
        els.body.querySelectorAll("[data-tab]").forEach((b) => b.setAttribute("aria-selected", String(b.dataset.tab === state.tab)));
        if (state.tab === "vc" && state.phrases == null) loadPhrases();
        return renderTab();
    }
    if (btn.dataset.pkind) {
        state.phraseKind = btn.dataset.pkind;
        return renderTab();
    }
    if (btn.dataset.act === "staff-phrase" || btn.dataset.act === "report-phrase") {
        const id = Number(btn.closest("[data-phrase]").dataset.phrase);
        const phrase = state.phrases.find((p) => p.id === id);
        return btn.dataset.act === "staff-phrase" ? showStaff(phrase) : openPhraseReport(id);
    }
    switch (btn.dataset.act) {
        case "close-list":
            state.results = null;
            els.searchHere.hidden = true;
            resultLayer.clearLayers();
            els.q.value = "";
            els.clear.hidden = true;
            if (state.filter) setFilter(null);
            else closePanel();
            break;
        case "close":
        case "back":
            back();
            break;
        case "more":
            loadReviews(state.reviews.page + 1);
            break;
        case "save":
            await toggleSave();
            break;
        case "like":
            await toggleLike(Number(btn.dataset.id));
            break;
        case "report":
            openReport(Number(btn.dataset.id));
            break;
        case "write":
            openWrite({ placeId: state.detail.id, placeName: state.detail.name });
            break;
        case "write-osm":
            openWrite({ osm: state.osm, placeName: state.osm.name });
            break;
        case "edit-review":
            openEdit(Number(btn.dataset.id));
            break;
        case "delete-review":
            await deleteReview(Number(btn.dataset.id));
            break;
        case "report-place":
            openPlaceReport();
            break;
        case "to-clean":
            await setBasemap("clean", true);
            toast(state.basemap === "clean" ? "Đã chuyển sang bản đồ gọn. Bấm vào cửa hàng, nhà ga… để chọn." : "Máy này chưa mở được bản đồ gọn.");
            break;
        case "speak":
            speak(btn.dataset.text);
            break;
        default:
    }
});

// ---------------------------------------------------------------- actions

function needLogin() {
    if (state.user) return false;
    location.href = ROUTES.login();
    return true;
}

async function toggleSave() {
    if (needLogin()) return;
    const d = state.detail;
    const on = !d.saved;
    try {
        await apiClient.saveMapPlace(d.id, on);
        d.saved = on;
        renderPanel();
        toast(on ? "Đã lưu địa điểm." : "Đã bỏ lưu địa điểm.");
    } catch (error) {
        toast(error.message, "error");
    }
}

async function toggleLike(reviewId) {
    if (needLogin()) return;
    const r = state.reviews.items.find((x) => x.id === reviewId);
    if (!r) return;
    try {
        const res = await apiClient.likeReview(reviewId, !r.liked);
        r.liked = res.active;
        r.likeCount = res.count;
        r.helpful = res.count > 10;
        renderTab();
    } catch (error) {
        toast(error.message, "error");
    }
}

function openReport(reviewId) {
    if (needLogin()) return;
    state.reportReviewId = reviewId;
    state.reportPhraseId = null;
    state.reportPlaceId = null;
    showReportDialog("Báo cáo review", "Moderator sẽ xem xét. Mỗi nội dung bạn chỉ báo cáo được một lần.",
        state.detail.reportReasons);
}

/**
 * "Báo cáo địa điểm" (user decision 20/11/2026): "Đã đóng cửa" (the Moderators look once enough people say so) or
 * "Sai thông tin" (they look at once), always with a reason; a kind already reported is greyed out.
 */
function openPlaceReport() {
    if (needLogin()) return;
    const d = state.detail;
    state.reportPlaceId = d.id;
    state.reportReviewId = null;
    state.reportPhraseId = null;
    const kinds = [
        { id: "CLOSED", label: `Đã đóng cửa (${Math.min(d.closedReports, d.closedThreshold)}/${d.closedThreshold} người đã báo)`,
            off: d.closed || d.reportedClosed },
        { id: "WRONG_INFO", label: "Sai thông tin: tên, vị trí, danh mục hoặc địa chỉ", off: d.reportedWrong }
    ];
    showReportDialog("Báo cáo địa điểm",
        `“${d.name}”: Moderator sẽ đọc lý do của bạn rồi sửa, gắn nhãn hoặc gỡ địa điểm. Bạn sẽ nhận thông báo khi xử lý xong.`,
        kinds, { noteLabel: "Lý do (bắt buộc)", noteMax: 300,
            placeholder: "Ví dụ: tiệm đã dọn đi từ tháng 9; ghim nằm sai bên kia đường; tên đúng là …" });
}

/** "Báo lỗi" on a phrase: its own reasons (Sai tiếng Nhật, Sai nghĩa…), handled by the Japanese content editor. */
async function openPhraseReport(phraseId) {
    if (needLogin()) return;
    try {
        state.phraseReasons ??= (await apiClient.phraseOverview()).reportReasons;
    } catch (error) {
        return toast(error.message, "error");
    }
    state.reportPhraseId = phraseId;
    state.reportPlaceId = null;
    showReportDialog("Báo lỗi mẫu câu",
        "Biên tập viên nội dung tiếng Nhật sẽ xem xét. Mỗi nội dung bạn chỉ báo cáo được một lần.", state.phraseReasons);
}

function showReportDialog(title, note, reasons,
                          { noteLabel = "Ghi chú thêm (không bắt buộc)", noteMax = 500, placeholder = "" } = {}) {
    els.reportTitle.textContent = title;
    els.reportIntro.textContent = note;
    els.reportReasons.innerHTML = `<legend class="sr-only">Lý do</legend>`
        + reasons.map((r) => `<label class="${r.off ? "off" : ""}"><input type="radio" name="reason" value="${esc(r.id)}" ${r.off ? "disabled" : ""}>
            <span>${esc(r.label)}${r.off ? " · bạn đã báo" : ""}</span></label>`).join("");
    els.reportNote.value = "";
    els.reportNote.maxLength = noteMax;
    els.reportNote.placeholder = placeholder;
    els.reportForm.querySelector('label[for="report-note"]').textContent = noteLabel;
    els.reportError.hidden = true;
    els.reportDlg.showModal();
}

els.reportDlg.querySelector("[data-close]").addEventListener("click", () => els.reportDlg.close());
els.staffDlg.querySelector('[data-act="speak-staff"]').addEventListener("click", () => speak(els.staffJa.textContent));
els.reportForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const reason = els.reportForm.querySelector('input[name="reason"]:checked');
    if (!reason) {
        els.reportError.textContent = "Hãy chọn lý do báo cáo.";
        els.reportError.hidden = false;
        return;
    }
    const body = { reasonId: Number(reason.value), note: els.reportNote.value.trim() || null };
    try {
        if (state.reportPlaceId != null) {
            if (!body.note) {
                els.reportError.textContent = "Hãy ghi lý do để Moderator kiểm tra.";
                els.reportError.hidden = false;
                els.reportNote.focus();
                return;
            }
            const res = await apiClient.reportPlace(state.reportPlaceId, { kind: reason.value, note: body.note });
            els.reportDlg.close();
            const d = state.detail;
            if (d?.id === state.reportPlaceId) {
                if (reason.value === "CLOSED") {
                    d.closedReports = res.count;
                    d.reportedClosed = true;
                } else {
                    d.reportedWrong = true;
                }
                renderPanel();
            }
            toast(reason.value === "WRONG_INFO" ? "Cảm ơn bạn. Moderator sẽ kiểm tra và sửa thông tin địa điểm."
                : res.queued ? `Đủ ${res.threshold} báo cáo. Moderator sẽ kiểm tra trước khi gắn nhãn “Đã đóng cửa”.`
                    : `Đã ghi nhận. Khi đủ ${res.threshold} người báo, Moderator sẽ kiểm tra.`);
            return;
        }
        if (state.reportPhraseId != null) {
            await apiClient.reportPhrase(state.reportPhraseId, body);
            els.reportDlg.close();
            toast("Cảm ơn bạn. Báo cáo đã được gửi.");
            return;
        }
        await apiClient.reportReview(state.reportReviewId, body);
        els.reportDlg.close();
        toast("Đã gửi báo cáo cho Moderator.");
        loadReviews(0);
    } catch (error) {
        els.reportError.textContent = error.message;
        els.reportError.hidden = false;
    }
});

function speak(text) {
    try {
        if (!("speechSynthesis" in window)) throw new Error();
        const u = new SpeechSynthesisUtterance(text);
        u.lang = "ja-JP";
        u.rate = 0.85;
        speechSynthesis.cancel();
        speechSynthesis.speak(u);
    } catch {
        toast("Thiết bị này không hỗ trợ đọc tiếng Nhật.", "error");
    }
}

// ---------------------------------------------------------------- write / edit / delete review (UC-U16..18)
// The form lives in the panel. New photos are kept as File objects with a preview URL; on an edit the attached
// files can be removed (keepMediaIds). The text is copied to state on every keystroke so a re-render keeps it.

const IMAGE_TYPES = "image/jpeg,image/png,image/heic,image/heif,.heic,.heif";
const VIDEO_TYPES = "video/mp4,video/quicktime,.mov";

async function openWrite({ placeId = null, osm = null, placeName, rating = 0, content = "" }) {
    if (needLogin()) return;
    let compose;
    try {
        compose = await apiClient.reviewCompose(placeId);
    } catch (error) {
        toast(error.message, "error");
        return;
    }
    if (compose.myReviewId) return openEdit(compose.myReviewId);
    startWrite({ mode: "new", placeId, osm, placeName, rating, content, keep: [], limits: compose.limits,
        quota: compose.quota });
}

async function openEdit(reviewId) {
    if (needLogin()) return;
    let data;
    try {
        data = await apiClient.reviewEditData(reviewId);
    } catch (error) {
        toast(error.message, "error");
        return;
    }
    startWrite({ mode: "edit", reviewId, placeId: data.placeId, placeName: data.placeName, rating: data.rating,
        content: data.content, keep: data.media, status: data.status, limits: data.limits, quota: data.quota });
}

function startWrite(form) {
    state.write = { ...form, images: [], video: null, busy: false, errors: {}, from: state.panel };
    state.panel = "write";
    renderPanel();
    openPanel();
    els.panel.classList.add("full");
    els.body.scrollTop = 0;
    els.body.querySelector(".star-pick button")?.focus();
}

function quotaNote(w) {
    if (!w.quota.newAccount || w.mode === "edit") return "";
    const left = Math.max(0, w.quota.postsLimit - w.quota.postsUsed);
    return `<p class="info">${ic("schedule")}Tài khoản mới (dưới ${w.quota.newAccountDays} ngày): hôm nay còn ${left}/${w.quota.postsLimit} lượt đăng bài viết hoặc review, ${Math.max(0, w.quota.videosLimit - w.quota.videosUsed)} video.</p>`;
}

function renderWrite() {
    const w = state.write;
    const imagesNow = w.keep.filter((m) => m.type === "IMAGE").length + w.images.length;
    const hasVideo = w.keep.some((m) => m.type === "VIDEO") || w.video;
    const err = (field) => w.errors[field] ? `<p class="field-error" role="alert">${esc(w.errors[field])}</p>` : "";
    const kept = w.keep.map((m) => `<span class="thumb">${m.type === "VIDEO" ? `<span class="vid">${ic("movie")}Video</span>` : `<img src="${esc(m.url)}" alt="">`}
            ${m.scanStatus === "PROCESSING" ? `<small>Đang quét</small>` : ""}
            <button type="button" class="x" data-act="remove-kept" data-id="${m.id}" aria-label="Bỏ ${m.type === "VIDEO" ? "video" : "ảnh"} này">${ic("close")}</button></span>`).join("");
    const fresh = w.images.map((f, i) => `<span class="thumb"><img src="${esc(f.preview)}" alt="">
            <button type="button" class="x" data-act="remove-image" data-index="${i}" aria-label="Bỏ ảnh ${esc(f.file.name)}">${ic("close")}</button></span>`).join("");
    const video = w.video ? `<span class="thumb"><span class="vid">${ic("movie")}${esc(w.video.name)}</span>
            <button type="button" class="x" data-act="remove-video" aria-label="Bỏ video">${ic("close")}</button></span>` : "";
    return `<div class="pad">
        <div class="panel-head"><div class="grow"><p class="note">${w.mode === "edit" ? "Sửa review" : "Viết review"}</p>
            <h2 class="place-title">${esc(w.placeName)}</h2></div>
            <button class="btn sm" type="button" data-act="cancel-write">${ic("arrow_back")}Quay lại</button></div>
        ${w.mode === "edit" && w.status === "REJECTED" ? `<p class="alert error">Review chưa được đăng vì ảnh hoặc video vi phạm Quy tắc cộng đồng. Hãy sửa rồi lưu lại.</p>` : ""}
        ${quotaNote(w)}
        <form class="rv-form" id="rv-form" novalidate>
            <fieldset class="field star-field">
                <legend>Đánh giá <span class="req">*</span></legend>
                <div class="star-pick" role="radiogroup" aria-label="Số sao">${[1, 2, 3, 4, 5].map((n) =>
        `<button type="button" role="radio" aria-checked="${w.rating === n}" aria-label="${n} sao" data-act="star" data-n="${n}" class="${w.rating >= n ? "on" : ""}">★</button>`).join("")}
                    <span class="note">${w.rating ? `${w.rating}/5` : "Chọn số sao"}</span></div>
                ${err("rating")}
            </fieldset>
            <div class="field">
                <label for="rv-content">Nội dung <span class="req">*</span></label>
                <textarea id="rv-content" rows="6" maxlength="${w.limits.contentMax}" placeholder="Bạn thấy nơi này thế nào? Có mẹo gì cho người mới không?">${esc(w.content)}</textarea>
                <p class="note counter" id="rv-count">${w.content.length}/${w.limits.contentMax} · ít nhất ${w.limits.contentMin} ký tự</p>
                ${err("content")}
            </div>
            <div class="field">
                <span class="label">Ảnh và video (không bắt buộc)</span>
                <div class="thumbs">${kept}${fresh}${video}</div>
                <div class="row">
                    <button class="btn sm" type="button" data-act="pick-images" ${imagesNow >= w.limits.imagesMax ? "disabled" : ""}>${ic("add_photo_alternate")}Thêm ảnh (${imagesNow}/${w.limits.imagesMax})</button>
                    <button class="btn sm" type="button" data-act="pick-video" ${hasVideo ? "disabled" : ""}>${ic("videocam")}Thêm video</button>
                </div>
                <p class="note">JPG, PNG hoặc HEIC, mỗi ảnh ≤ ${w.limits.imageMaxMb} MB · video ≤ ${w.limits.videoMaxSeconds} giây. Ảnh và video được quét tự động trước khi hiện cho mọi người.</p>
                ${err("images")}${err("video")}
                <input type="file" id="rv-images" accept="${IMAGE_TYPES}" multiple hidden>
                <input type="file" id="rv-video" accept="${VIDEO_TYPES}" hidden>
            </div>
            ${w.errors._ ? `<p class="alert error" role="alert">${esc(w.errors._)}</p>` : ""}
            <div class="row end">
                <button class="btn" type="button" data-act="cancel-write">Hủy</button>
                <button class="btn pri" type="submit" ${w.busy ? "disabled" : ""}>${w.busy ? "Đang gửi…" : w.mode === "edit" ? "Lưu thay đổi" : "Đăng review"}</button>
            </div>
        </form></div>`;
}

function rerenderWrite() {
    const top = els.body.scrollTop;
    renderPanel();
    els.body.scrollTop = top;
}

els.body.addEventListener("input", (event) => {
    if (event.target.id !== "rv-content" || !state.write) return;
    state.write.content = event.target.value;
    const count = document.getElementById("rv-count");
    if (count) count.textContent = `${event.target.value.length}/${state.write.limits.contentMax} · ít nhất ${state.write.limits.contentMin} ký tự`;
});

els.body.addEventListener("change", (event) => {
    const w = state.write;
    if (!w) return;
    if (event.target.id === "rv-images") {
        const room = w.limits.imagesMax - w.keep.filter((m) => m.type === "IMAGE").length - w.images.length;
        const picked = [...event.target.files];
        picked.slice(0, Math.max(0, room)).forEach((file) => w.images.push({ file, preview: URL.createObjectURL(file) }));
        w.errors.images = picked.length > room ? `Tối đa ${w.limits.imagesMax} ảnh.` : undefined;
        rerenderWrite();
    } else if (event.target.id === "rv-video") {
        w.video = event.target.files[0] || null;
        w.errors.video = undefined;
        rerenderWrite();
    }
});

els.body.addEventListener("click", (event) => {
    const btn = event.target.closest("[data-act]");
    const w = state.write;
    if (!btn || !w || state.panel !== "write") return;
    switch (btn.dataset.act) {
        case "star":
            w.rating = Number(btn.dataset.n);
            w.errors.rating = undefined;
            rerenderWrite();
            els.body.querySelector(`[data-act="star"][data-n="${w.rating}"]`)?.focus();
            break;
        case "pick-images":
            document.getElementById("rv-images").click();
            break;
        case "pick-video":
            document.getElementById("rv-video").click();
            break;
        case "remove-image":
            URL.revokeObjectURL(w.images[Number(btn.dataset.index)].preview);
            w.images.splice(Number(btn.dataset.index), 1);
            rerenderWrite();
            break;
        case "remove-video":
            w.video = null;
            rerenderWrite();
            break;
        case "remove-kept":
            w.keep = w.keep.filter((m) => m.id !== Number(btn.dataset.id));
            rerenderWrite();
            break;
        case "cancel-write":
            cancelWrite();
            break;
        default:
    }
});

els.body.addEventListener("submit", async (event) => {
    if (event.target.id !== "rv-form") return;
    event.preventDefault();
    await submitWrite();
});

/** Same checks as the server, so most mistakes show at once (the server checks again). */
function checkWrite(w) {
    const errors = {};
    if (!w.rating) errors.rating = "Hãy chọn số sao.";
    const length = w.content.trim().length;
    if (length < w.limits.contentMin) errors.content = `Nội dung cần ít nhất ${w.limits.contentMin} ký tự.`;
    else if (length > w.limits.contentMax) errors.content = "Nội dung tối đa 2.000 ký tự.";
    if (w.images.some((f) => f.file.size > w.limits.imageMaxMb * 1024 * 1024)) {
        errors.images = `Ảnh phải là JPG, PNG hoặc HEIC và không quá ${w.limits.imageMaxMb} MB.`;
    }
    return errors;
}

async function submitWrite() {
    const w = state.write;
    w.errors = checkWrite(w);
    if (Object.keys(w.errors).length) {
        rerenderWrite();
        els.body.querySelector(".field-error")?.scrollIntoView({ block: "center" });
        return;
    }
    const body = w.mode === "edit"
        ? { rating: w.rating, content: w.content.trim(), keepMediaIds: w.keep.map((m) => m.id) }
        : { placeId: w.placeId, externalPlaceId: w.osm?.externalId || null, rating: w.rating, content: w.content.trim() };
    const form = new FormData();
    form.append("review", new Blob([JSON.stringify(body)], { type: "application/json" }));
    w.images.forEach((f) => form.append("images", f.file));
    if (w.video) form.append("video", w.video);
    w.busy = true;
    rerenderWrite();
    try {
        const saved = w.mode === "edit" ? await apiClient.updateReview(w.reviewId, form) : await apiClient.createReview(form);
        w.images.forEach((f) => URL.revokeObjectURL(f.preview));
        state.write = null;
        els.panel.classList.remove("full");
        toast(saved.changed === false ? "Không có thay đổi nào."
            : saved.status === "PROCESSING"
                ? "Đã gửi. Ảnh/video đang được quét, review hiện cho mọi người sau khi đạt."
                : w.mode === "edit" ? "Đã lưu thay đổi." : "Đã đăng review. +10 điểm.");
        if (saved.status === "PROCESSING") checkNotificationsSoon();
        await refreshPlaces();
        resultLayer.clearLayers();
        state.results = null;
        openPlace(saved.placeId, { fly: true });
    } catch (error) {
        w.busy = false;
        w.errors = { ...(error.fields || {}) };
        if (!Object.keys(w.errors).length || error.status === 409 || error.status === 429) w.errors._ = error.message;
        rerenderWrite();
        els.body.querySelector(".field-error, .alert.error")?.scrollIntoView({ block: "center" });
    }
}

async function cancelWrite() {
    const w = state.write;
    const changed = w.images.length || w.video || (w.mode === "new" ? w.content.trim() || w.rating : false);
    if (changed && !(await confirmBox("Bỏ review đang viết?", "Bỏ"))) return;
    w.images.forEach((f) => URL.revokeObjectURL(f.preview));
    state.write = null;
    els.panel.classList.remove("full");
    if (w.osm) {
        state.panel = "osm";
        renderPanel();
    } else if (w.placeId) {
        openPlace(w.placeId);
    } else {
        closePanel();
    }
}

async function deleteReview(reviewId) {
    if (!(await confirmBox("Xóa review này? Điểm của review này sẽ được thu lại.", "Xóa"))) return;
    try {
        await apiClient.deleteReview(reviewId);
        toast("Đã xóa review. Điểm của review này được thu lại.");
        await refreshPlaces();
        if (state.detail) openPlace(state.detail.id);
    } catch (error) {
        toast(error.message, "error");
    }
}

/** Reloads the pins after a review changed their figures (count, stars, a new OpenStreetMap place). */
async function refreshPlaces() {
    try {
        const overview = await apiClient.mapOverview();
        state.places = new Map(overview.places.map((p) => [p.id, p]));
        overview.categories.forEach((c) => state.catById.set(c.id, c));
        state.categories = overview.categories;
        renderChips();
        drawPins();
    } catch {
        // the pins refresh on the next visit
    }
}

function confirmBox(text, okLabel) {
    els.confirmText.textContent = text;
    els.confirmOk.textContent = okLabel;
    els.confirmDlg.returnValue = "";
    els.confirmDlg.showModal();
    return new Promise((resolve) => {
        els.confirmDlg.addEventListener("close", () => resolve(els.confirmDlg.returnValue === "ok"), { once: true });
    });
}

// ---------------------------------------------------------------- "Cắm ghim" (UC-U19)
// Pick a point → form (names, category, first review) in the panel, the pin draggable to fine-tune. OpenStreetMap places
// right there are offered first ("Có phải nơi này?"): reviewing one of them needs no pin. Before sending, approved
// places with a similar name within 200 m are shown ("Có thể trùng"): review that place, or "Vẫn gửi".

const PIN_SUGGEST_M = 60;

els.pinBtn.addEventListener("click", () => {
    if (needLogin()) return;
    if (state.user.role !== "USER") {
        toast("Chỉ tài khoản người dùng mới cắm ghim được.", "error");
        return;
    }
    if (state.panel === "write" || state.panel === "pin") return;
    closePanel();
    state.picking = true;
    els.app.classList.add("picking");
    els.pickHint.hidden = false;
    if (map.getZoom() < 16) toast("Phóng to tới đúng chỗ rồi chạm để đặt ghim.");
});

function stopPicking() {
    state.picking = false;
    els.app.classList.remove("picking");
    els.pickHint.hidden = true;
}
els.pickCancel.addEventListener("click", stopPicking);

async function startPin(latlng) {
    stopPicking();
    if (outsideJapan(latlng)) {
        toast("Vị trí này không ở Nhật Bản. Hãy chọn một điểm ở Nhật.", "error");
        return;
    }
    let compose;
    try {
        compose = await apiClient.pinCompose();
    } catch (error) {
        toast(error.message, "error");
        return;
    }
    if (compose.newAccount && compose.pinsUsed >= compose.pinsLimit) {
        toast(`Tài khoản mới chỉ được cắm ${compose.pinsLimit} ghim mỗi ngày.`, "error");
        return;
    }
    state.pin = { lat: latlng.lat, lng: latlng.lng, nameJa: "", nameEn: "", nameVi: "", categoryId: "", address: "",
        rating: 0, content: "", images: [], compose, errors: {}, busy: false, dups: null, suggestions: [] };
    state.detail = null;
    state.osm = null;
    state.panel = "pin";
    removeTemp();
    tempMarker = L.marker(latlng, {
        draggable: true, keyboard: true, title: "Ghim mới (kéo để chỉnh vị trí)",
        icon: L.divIcon({ className: "mp-wrap", html: `<span class="mp newpin">${ic("add_location_alt")}</span>`, iconSize: [34, 34], iconAnchor: [17, 17] })
    }).addTo(map);
    tempMarker.on("dragend", () => {
        const p = tempMarker.getLatLng();
        state.pin.lat = p.lat;
        state.pin.lng = p.lng;
        state.pin.dups = null;
        loadPinSuggestions();
    });
    renderPanel();
    openPanel();
    els.panel.classList.add("full");
    loadPinSuggestions();
}

/** A click on the map while the pin form is open moves the pin there. */
function movePin(latlng) {
    tempMarker?.setLatLng(latlng);
    state.pin.lat = latlng.lat;
    state.pin.lng = latlng.lng;
    state.pin.dups = null;
    loadPinSuggestions();
}

/** OpenStreetMap / Senpai ơi places right at the pin: maybe the place is already on the map. */
async function loadPinSuggestions() {
    const pin = state.pin;
    if (!pin) return;
    const at = { lat: pin.lat, lng: pin.lng };
    let items = localNearby(L.latLng(at.lat, at.lng));
    if (!items) {
        try {
            items = (await apiClient.mapNearby(at.lat, at.lng)).places;
        } catch {
            items = [];
        }
    }
    if (state.pin !== pin) return;
    pin.suggestions = items.filter((o) => distance(at, o) <= PIN_SUGGEST_M).slice(0, 5);
    const box = document.getElementById("pin-suggest");
    if (box) box.outerHTML = suggestionsHtml(pin);
}

function suggestionsHtml(pin) {
    if (!pin.suggestions.length) return `<div id="pin-suggest"></div>`;
    return `<div class="suggest-box" id="pin-suggest"><b>Có phải là một trong các nơi này? Viết review luôn, không cần cắm ghim:</b>
        ${pin.suggestions.map((o, i) => `<button class="opt" type="button" data-act="pin-use" data-index="${i}">
            <span class="oi ${o.id == null ? "osm" : ""}">${ic(o.id != null ? categoryIcon(state.places.get(o.id)?.categoryId) : state.catByName.get(o.category)?.icon || "place")}</span>
            <span class="ot"><b>${esc(o.name)}</b><small>${esc(o.category || "")} · ${formatDistance(distance(pin, o))}</small></span>
            <span class="meta">Viết review</span></button>`).join("")}</div>`;
}

function renderPin() {
    const p = state.pin;
    const c = p.compose;
    const err = (field) => p.errors[field] ? `<p class="field-error" role="alert">${esc(p.errors[field])}</p>` : "";
    const fresh = p.images.map((f, i) => `<span class="thumb"><img src="${esc(f.preview)}" alt="">
            <button type="button" class="x" data-act="pin-remove-image" data-index="${i}" aria-label="Bỏ ảnh ${esc(f.file.name)}">${ic("close")}</button></span>`).join("");
    const dups = p.dups && p.dups.length ? `<div class="dup-box" role="alert"><b>${ic("warning")}Có thể trùng với địa điểm đã có</b>
        ${p.dups.map((d) => `<button class="opt" type="button" data-act="pin-dup" data-id="${d.id}">
            <span class="oi">${ic("location_on")}</span><span class="ot"><b>${esc(d.name)}</b><small>${esc([d.nameJa, d.categoryName].filter(Boolean).join(" · "))} · cách ${d.distance} m</small></span>
            <span class="meta">Viết review cho nơi này</span></button>`).join("")}
        <div class="row end"><button class="btn sm" type="button" data-act="pin-force">Vẫn gửi ghim mới</button></div></div>` : "";
    return `<div class="pad">
        <div class="panel-head"><div class="grow"><p class="note">Cắm ghim</p><h2 class="place-title">Địa điểm mới</h2></div>
            <button class="btn sm" type="button" data-act="cancel-pin">${ic("close")}Hủy</button></div>
        <p class="note">${ic("open_with")}Kéo ghim trên bản đồ để chỉnh đúng vị trí. ${p.compose?.noApprovalNeeded ? "Bạn đủ điểm để ghim lên bản đồ ngay, không chờ duyệt, +15 điểm." : "Ghim mới cần Moderator duyệt; được duyệt bạn nhận +15 điểm."}</p>
        ${c.newAccount ? `<p class="info">${ic("schedule")}Tài khoản mới: hôm nay còn ${Math.max(0, c.pinsLimit - c.pinsUsed)}/${c.pinsLimit} lượt cắm ghim.</p>` : ""}
        ${suggestionsHtml(p)}
        <form class="rv-form pin-form" id="pin-form" novalidate>
            <div class="two-col">
                <div class="field"><label for="pin-nameJa">Tên tiếng Nhật</label>
                    <input type="text" id="pin-nameJa" data-pin="nameJa" maxlength="160" value="${esc(p.nameJa)}" lang="ja" placeholder="例: はな食堂"></div>
                <div class="field"><label for="pin-nameEn">Tên tiếng Anh / romaji</label>
                    <input type="text" id="pin-nameEn" data-pin="nameEn" maxlength="160" value="${esc(p.nameEn)}" placeholder="VD: Hana Shokudo"></div>
            </div>
            ${err("names")}
            <div class="two-col">
                <div class="field"><label for="pin-nameVi">Tên tiếng Việt (không bắt buộc)</label>
                    <input type="text" id="pin-nameVi" data-pin="nameVi" maxlength="160" value="${esc(p.nameVi)}" placeholder="VD: Quán cơm Hana"></div>
                <div class="field"><label for="pin-category">Danh mục <span class="req">*</span></label>
                    <select id="pin-category" data-pin="categoryId"><option value="">Chọn danh mục</option>
                        ${c.categories.map((k) => `<option value="${esc(k.id)}" ${p.categoryId === k.id ? "selected" : ""}>${esc(k.name)}</option>`).join("")}</select>
                    ${err("categoryId")}</div>
            </div>
            <div class="field"><label for="pin-address">Địa chỉ (không bắt buộc)</label>
                <input type="text" id="pin-address" data-pin="address" maxlength="255" value="${esc(p.address)}" placeholder="VD: Higashi-Nippori 5, Arakawa"></div>
            ${err("position")}
            <h3>Review đầu tiên</h3>
            <fieldset class="field star-field">
                <legend>Đánh giá <span class="req">*</span></legend>
                <div class="star-pick" role="radiogroup" aria-label="Số sao">${[1, 2, 3, 4, 5].map((n) =>
        `<button type="button" role="radio" aria-checked="${p.rating === n}" aria-label="${n} sao" data-act="pin-star" data-n="${n}" class="${p.rating >= n ? "on" : ""}">★</button>`).join("")}
                    <span class="note">${p.rating ? `${p.rating}/5` : "Chọn số sao"}</span></div>
                ${err("rating")}
            </fieldset>
            <div class="field">
                <label for="pin-content">Nội dung <span class="req">*</span></label>
                <textarea id="pin-content" data-pin="content" rows="5" maxlength="${c.limits.contentMax}" placeholder="Nơi này có gì? Giờ mở cửa, giá cả, mẹo cho người mới…">${esc(p.content)}</textarea>
                <p class="note counter" id="pin-count">${p.content.length}/${c.limits.contentMax} · ít nhất ${c.limits.contentMin} ký tự</p>
                ${err("content")}
            </div>
            <div class="field">
                <span class="label">Ảnh (không bắt buộc)</span>
                <div class="thumbs">${fresh}</div>
                <div class="row"><button class="btn sm" type="button" data-act="pin-pick-images" ${p.images.length >= c.limits.imagesMax ? "disabled" : ""}>${ic("add_photo_alternate")}Thêm ảnh (${p.images.length}/${c.limits.imagesMax})</button></div>
                ${err("images")}
                <input type="file" id="pin-images" accept="${IMAGE_TYPES}" multiple hidden>
            </div>
            ${dups}
            ${p.errors._ ? `<p class="alert error" role="alert">${esc(p.errors._)}</p>` : ""}
            <div class="row end">
                <button class="btn" type="button" data-act="cancel-pin">Hủy</button>
                <button class="btn pri" type="submit" ${p.busy ? "disabled" : ""}>${p.busy ? "Đang gửi…" : "Gửi ghim"}</button>
            </div>
        </form></div>`;
}

function rerenderPin() {
    const top = els.body.scrollTop;
    renderPanel();
    els.body.scrollTop = top;
}

els.body.addEventListener("input", (event) => {
    const field = event.target.dataset?.pin;
    if (!field || !state.pin) return;
    state.pin[field] = event.target.value;
    if (["nameJa", "nameEn", "nameVi"].includes(field)) state.pin.dups = null;
    if (field === "content") {
        const count = document.getElementById("pin-count");
        if (count) count.textContent = `${event.target.value.length}/${state.pin.compose.limits.contentMax} · ít nhất ${state.pin.compose.limits.contentMin} ký tự`;
    }
});

els.body.addEventListener("change", (event) => {
    const p = state.pin;
    if (!p) return;
    if (event.target.dataset?.pin === "categoryId") p.categoryId = event.target.value;
    if (event.target.id === "pin-images") {
        const room = p.compose.limits.imagesMax - p.images.length;
        const picked = [...event.target.files];
        picked.slice(0, Math.max(0, room)).forEach((file) => p.images.push({ file, preview: URL.createObjectURL(file) }));
        p.errors.images = picked.length > room ? `Tối đa ${p.compose.limits.imagesMax} ảnh.` : undefined;
        rerenderPin();
    }
});

els.body.addEventListener("click", async (event) => {
    const btn = event.target.closest("[data-act]");
    const p = state.pin;
    if (!btn || !p || state.panel !== "pin") return;
    switch (btn.dataset.act) {
        case "pin-star":
            p.rating = Number(btn.dataset.n);
            p.errors.rating = undefined;
            rerenderPin();
            els.body.querySelector(`[data-act="pin-star"][data-n="${p.rating}"]`)?.focus();
            break;
        case "pin-pick-images":
            document.getElementById("pin-images").click();
            break;
        case "pin-remove-image":
            URL.revokeObjectURL(p.images[Number(btn.dataset.index)].preview);
            p.images.splice(Number(btn.dataset.index), 1);
            rerenderPin();
            break;
        case "pin-use": {
            const o = p.suggestions[Number(btn.dataset.index)];
            leavePin();
            if (o.id != null) openWrite({ placeId: o.id, placeName: o.name, rating: p.rating, content: p.content });
            else openWrite({ osm: o, placeName: o.name, rating: p.rating, content: p.content });
            break;
        }
        case "pin-dup": {
            const d = p.dups.find((x) => x.id === Number(btn.dataset.id));
            leavePin();
            openWrite({ placeId: d.id, placeName: d.name, rating: p.rating, content: p.content });
            break;
        }
        case "pin-force":
            await submitPin(true);
            break;
        case "cancel-pin":
            if ((p.nameJa || p.nameEn || p.content.trim() || p.images.length)
                && !(await confirmBox("Bỏ ghim đang cắm?", "Bỏ"))) return;
            leavePin();
            closePanel();
            break;
        default:
    }
});

els.body.addEventListener("submit", async (event) => {
    if (event.target.id !== "pin-form") return;
    event.preventDefault();
    await submitPin(false);
});

function leavePin() {
    state.pin?.images.forEach((f) => URL.revokeObjectURL(f.preview));
    state.pin = null;
    els.panel.classList.remove("full");
    removeTemp();
}

/** Same checks as the server first; then the "Có thể trùng" check unless the user chose "Vẫn gửi". */
async function submitPin(force) {
    const p = state.pin;
    const limits = p.compose.limits;
    const errors = {};
    if (!p.nameJa.trim() && !p.nameEn.trim()) errors.names = "Hãy nhập ít nhất một tên (tiếng Nhật hoặc tiếng Anh).";
    if (!p.categoryId) errors.categoryId = "Hãy chọn danh mục.";
    if (!p.rating) errors.rating = "Hãy chấm sao cho review đầu tiên.";
    const length = p.content.trim().length;
    if (length < limits.contentMin) errors.content = `Review đầu tiên cần ít nhất ${limits.contentMin} ký tự.`;
    else if (length > limits.contentMax) errors.content = "Review đầu tiên tối đa 2.000 ký tự.";
    p.errors = errors;
    if (Object.keys(errors).length) {
        rerenderPin();
        els.body.querySelector(".field-error")?.scrollIntoView({ block: "center" });
        return;
    }
    const names = [p.nameJa, p.nameEn, p.nameVi].map((n) => n.trim()).filter(Boolean);
    if (!force) {
        try {
            p.dups = await apiClient.pinDuplicates(p.lat, p.lng, names);
        } catch {
            p.dups = [];
        }
        if (p.dups.length) {
            rerenderPin();
            els.body.querySelector(".dup-box")?.scrollIntoView({ block: "center" });
            return;
        }
    }
    const body = { lat: p.lat, lng: p.lng, nameJa: p.nameJa.trim() || null, nameEn: p.nameEn.trim() || null,
        nameVi: p.nameVi.trim() || null, categoryId: p.categoryId, address: p.address.trim() || null,
        rating: p.rating, content: p.content.trim(), confirmDuplicate: force };
    const form = new FormData();
    form.append("pin", new Blob([JSON.stringify(body)], { type: "application/json" }));
    p.images.forEach((f) => form.append("images", f.file));
    p.busy = true;
    rerenderPin();
    try {
        const saved = await apiClient.createPin(form);
        leavePin();
        toast(saved.approved ? "Ghim đã lên bản đồ. +15 điểm." : "Đã gửi ghim kèm review. Chờ Moderator duyệt.");
        if (saved.reviewStatus === "PROCESSING") checkNotificationsSoon();
        await refreshPlaces();
        openPlace(saved.placeId, { fly: true });
    } catch (error) {
        p.busy = false;
        p.errors = { ...(error.fields || {}) };
        if (error.status === 409) {
            p.dups = await apiClient.pinDuplicates(p.lat, p.lng, names).catch(() => []);
        } else if (!Object.keys(p.errors).length || error.status === 429) {
            p.errors._ = error.message;
        }
        rerenderPin();
        els.body.querySelector(".field-error, .alert.error, .dup-box")?.scrollIntoView({ block: "center" });
    }
}

// ---------------------------------------------------------------- my location (never sent to the server)

function showMyLocation(lat, lng) {
    state.me = { lat, lng };
    els.locate.classList.add("on");
    els.locate.innerHTML = ic("my_location");
    if (meMarker) meMarker.remove();
    meMarker = L.marker([lat, lng], {
        icon: L.divIcon({ className: "mp-wrap", html: `<span class="me-dot"></span>`, iconSize: [18, 18], iconAnchor: [9, 9] }),
        title: "Vị trí của bạn", keyboard: false
    }).addTo(map);
    map.setView([lat, lng], 16);
    if (state.panel === "list") renderPanel();
}

els.locate.addEventListener("click", () => {
    // Test environments outside Japan: the server fixes "my location" (app.map.dev-location)
    if (state.devLocation) {
        showMyLocation(state.devLocation.lat, state.devLocation.lng);
        toast(`Vị trí giả lập cho môi trường thử nghiệm: ${state.devLocation.label}`);
        return;
    }
    if (!navigator.geolocation) {
        toast("Không lấy được vị trí. Hãy cho phép truy cập vị trí trong trình duyệt.", "error");
        return;
    }
    els.locate.disabled = true;
    navigator.geolocation.getCurrentPosition((pos) => {
        els.locate.disabled = false;
        showMyLocation(pos.coords.latitude, pos.coords.longitude);
    }, () => {
        els.locate.disabled = false;
        toast("Không lấy được vị trí. Hãy cho phép truy cập vị trí trong trình duyệt.", "error");
    }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 60000 });
});

// ---------------------------------------------------------------- shops drawn on the map itself
// The "Gọn" style draws OpenStreetMap shops, restaurants, stations… (layers poi_*). Clicking one opens it: the
// Senpai ơi place when we have its OSM id, else the "chưa có trên Senpai ơi" panel with "Viết review đầu tiên"
// (the server looks the place up on Nominatim and adds it when the review is saved).

function glMap() {
    return glOf(tiles, state.basemap);
}

/** The named map shop under the pointer (a few pixels around it), or null. */
function shopAt(latlng) {
    return poiAt(glMap(), latlng);
}

function openMapShop(latlng) {
    if (state.panel === "write") return;         // never drop a review being written
    const f = shopAt(latlng);
    if (!f) {
        showNearby(latlng);
        return;
    }
    const o = shopOption(f, latlng);
    if (o.id != null) openPlace(o.id);
    else openOsm(o);
}

/** A map shop as a search option: the Senpai ơi place it already is (id), else an OpenStreetMap option (externalId). */
function shopOption(f, latlng) {
    const s = poiInfo(f, latlng);
    const known = knownPlaceFor(state.places.values(), s.osmId, [s.name, s.nameJa, ...s.otherNames], s.lat, s.lng,
        s.cls === "railway");
    if (known) {
        return { id: known.id, name: known.name, category: state.catById.get(known.categoryId)?.name, lat: known.lat,
            lng: known.lng };
    }
    const category = state.catById.get(SUBCLASS_CATEGORY[s.subclass] || SUBCLASS_CATEGORY[s.cls] || "other");
    return { id: null, externalId: s.osmId, name: s.name, nameJa: s.nameJa, category: category?.name || "Địa điểm khác",
        address: null, source: "OSM", lat: s.lat, lng: s.lng };
}

/**
 * "Gọn" style: the named shops, stations, parks… the map itself draws within ~120 px of the click, plus Senpai ơi
 * places within 150 m, nearest first. Instant and independent of any map service. Null in the "Chi tiết" style.
 */
function localNearby(latlng) {
    const gl = glMap();
    if (!gl || !gl.isStyleLoaded()) return null;
    const seen = new Set();
    const items = [];
    for (const place of state.places.values()) {
        if (place.status === "ACTIVE" && distance(latlng, place) <= 150) {
            seen.add(`id:${place.id}`);
            items.push({ id: place.id, name: place.name, category: state.catById.get(place.categoryId)?.name,
                lat: place.lat, lng: place.lng });
        }
    }
    for (const f of poisAround(gl, latlng, 120)) {
        const o = shopOption(f, latlng);
        const key = o.id != null ? `id:${o.id}` : `osm:${o.externalId}`;
        if (!seen.has(key)) {
            seen.add(key);
            items.push(o);
        }
    }
    return items.sort((a, b) => distance(latlng, a) - distance(latlng, b)).slice(0, 15);
}

// ---------------------------------------------------------------- "Địa điểm quanh đây" (click anywhere)
// "Gọn" style: the places the map itself draws around the click (localNearby, instant, guests too). "Chi tiết" style
// (a picture without data): the server asks OpenStreetMap (Photon reverse, else Nominatim) and adds the Senpai ơi places
// within 150 m. Zoomed far out a click means nothing, so it only starts from street level.

const NEARBY_ZOOM = 15;
let nearbyTicket = 0;

function tapMarker(latlng) {
    removeTemp();
    tempMarker = L.marker(latlng, {
        icon: L.divIcon({ className: "mp-wrap", html: `<span class="me-dot tap"></span>`, iconSize: [18, 18], iconAnchor: [9, 9] }),
        keyboard: false, interactive: false
    }).addTo(map);
}

const SWITCH_NOTE = `<p class="note">${ic("layers")}Ở bản đồ chi tiết, danh sách lấy từ dịch vụ OpenStreetMap nên đôi khi thiếu.
    <button class="link" type="button" data-act="to-clean">Chuyển sang bản đồ gọn</button> để bấm thẳng vào từng cửa hàng, nhà ga, công viên.</p>`;

async function showNearby(latlng) {
    if (map.getZoom() < NEARBY_ZOOM) {
        toast("Phóng to bản đồ rồi bấm vào chỗ bạn muốn để xem các địa điểm quanh đó.");
        return;
    }
    const local = localNearby(latlng);
    if (local) {
        ++nearbyTicket;
        state.cameFromList = false;
        state.detail = null;
        state.osm = null;
        state.panel = "list";
        tapMarker(latlng);
        state.results = { title: "Địa điểm quanh đây", items: local,
            note: local.length ? "" : `<p class="note">Không có địa điểm có tên nào quanh đây. Hãy bấm gần cửa hàng, nhà ga hoặc công viên hơn.</p>` };
        state.lastOptions = local;
        renderPanel();
        openPanel();
        resultLayer.clearLayers();
        return;
    }
    if (!state.user) {
        toast("Hãy đăng nhập để chọn địa điểm trên bản đồ chi tiết, hoặc đổi sang bản đồ gọn.");
        return;
    }
    const ticket = ++nearbyTicket;
    state.cameFromList = false;
    state.detail = null;
    state.osm = null;
    state.panel = "list";
    tapMarker(latlng);
    els.body.innerHTML = `<p class="skeleton">Đang tìm các địa điểm quanh đây…</p>`;
    openPanel();
    try {
        const found = await apiClient.mapNearby(latlng.lat, latlng.lng);
        if (ticket !== nearbyTicket) return;
        state.results = {
            title: "Địa điểm quanh đây",
            items: found.places,
            note: (found.places.length ? externalNote(found) : `${externalNote(found)}<p class="note">Không có địa điểm có tên nào trong khoảng 150 m. Hãy bấm gần cửa hàng, nhà ga hoặc công viên hơn.</p>`) + (state.basemap === "detail" ? SWITCH_NOTE : "")
        };
        state.lastOptions = found.places;
        renderPanel();
        drawResultPins(found.places);
    } catch (error) {
        if (ticket === nearbyTicket) els.body.innerHTML = `<div class="pad"><div class="alert error">${esc(error.message)}</div></div>`;
    }
}

// ---------------------------------------------------------------- map style

async function setBasemap(key, remember = false) {
    const shown = await setBaseLayer(map, key, tiles);
    if (shown.fellBack && state.basemap) toast("Không tải được bản đồ gọn, đang dùng bản đồ chi tiết.", "error");
    tiles = shown.layer;
    key = shown.key;
    const b = BASEMAPS[key];
    state.basemap = key;
    const next = BASEMAPS[key === "clean" ? "detail" : "clean"].label;
    els.basemap.title = `Đổi sang bản đồ ${next.toLowerCase()}`;
    els.basemap.setAttribute("aria-label", `Kiểu bản đồ: ${b.label}. Bấm để đổi sang ${next.toLowerCase()}`);
    els.basemap.classList.toggle("on", key === "detail");
    if (remember) rememberBasemap(key);
}

els.basemap.addEventListener("click", async () => {
    if (!map) return;
    await setBasemap(state.basemap === "clean" ? "detail" : "clean", true);
    toast(`Bản đồ ${BASEMAPS[state.basemap].label.toLowerCase()}`);
});

// ---------------------------------------------------------------- helpers

function setUrl(params) {
    const query = new URLSearchParams(params).toString();
    history.replaceState(null, "", query ? `/map?${query}` : "/map");
}

let toastTimer = null;
function toast(message, kind = "ok") {
    els.toast.textContent = message;
    els.toast.className = `toast ${kind}`;
    els.toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { els.toast.hidden = true; }, 3500);
}
