// The base map shared by the Map (pages/map.js) and the Moderator's map workspace (pages/mod-map.js): the two map
// styles, MapLibre loading, the shops the "Gọn" style draws (clickable), and distances. Leaflet (window.L) is loaded by
// the page itself with SRI.

export const OSM_CREDIT = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors';

/**
 * Map styles. "Gọn" is OpenFreeMap "Liberty" (vector map drawn by MapLibre; free, no key, no sign-up): calm like
 * Google Maps, labels in Latin letters and Japanese, so our pins stand out. MapLibre (~800 KB) loads only for it; if
 * it cannot run (no WebGL, blocked CDN) the map falls back to "Chi tiết", the standard OpenStreetMap map with every
 * shop and house number. The choice is kept in this browser.
 */
export const BASEMAPS = {
    clean: { label: "Gọn", style: "https://tiles.openfreemap.org/styles/liberty",
        attribution: `<a href="https://openfreemap.org" target="_blank" rel="noopener">OpenFreeMap</a> &copy; <a href="https://www.openmaptiles.org/" target="_blank" rel="noopener">OpenMapTiles</a> ${OSM_CREDIT}` },
    detail: { label: "Chi tiết", url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        options: { maxZoom: 19, attribution: OSM_CREDIT } }
};
const MAPLIBRE = [
    { css: "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.css",
        hash: "sha384-MinO0mNliZ3vwppuPOUnGa+iq619pfMhLVUXfC4LHwSCvF9H+6P/KO4Q7qBOYV5V" },
    { js: "https://unpkg.com/maplibre-gl@4.7.1/dist/maplibre-gl.js",
        hash: "sha384-SYKAG6cglRMN0RVvhNeBY0r3FYKNOJtznwA0v7B5Vp9tr31xAHsZC0DqkQ/pZDmj" },
    { js: "https://unpkg.com/@maplibre/maplibre-gl-leaflet@0.1.4/leaflet-maplibre-gl.js",
        hash: "sha384-tXYNKOHx4T02jMP7YYCtBxPIv1B5gaA5mcVPBzqMp6d7VzWzxJgI2aWF/nJLrQdS" }
];
const BASEMAP_KEY = "asvsj.map.basemap";
let maplibreLoading = null;

/** The style chosen before in this browser ("clean" when none or when storage is blocked). */
export function storedBasemap() {
    try {
        return BASEMAPS[localStorage.getItem(BASEMAP_KEY)] ? localStorage.getItem(BASEMAP_KEY) : "clean";
    } catch {
        return "clean";
    }
}

export function rememberBasemap(key) {
    try {
        localStorage.setItem(BASEMAP_KEY, key);
    } catch {
        // storage blocked: the choice lasts for this visit
    }
}

/** Loads MapLibre and its Leaflet bridge once (CSS and scripts in order, each checked with SRI). */
export function loadMaplibre() {
    if (!maplibreLoading) {
        maplibreLoading = MAPLIBRE.reduce((ready, file) => ready.then(() => new Promise((resolve, reject) => {
            const el = file.css
                ? Object.assign(document.createElement("link"), { rel: "stylesheet", href: file.css })
                : Object.assign(document.createElement("script"), { src: file.js });
            el.integrity = file.hash;
            el.crossOrigin = "anonymous";
            el.onload = resolve;
            el.onerror = reject;
            document.head.append(el);
        })), Promise.resolve()).then(() => {
            const canvas = document.createElement("canvas");
            const webgl = canvas.getContext("webgl2") || canvas.getContext("webgl");
            if (!window.L.maplibreGL || !window.maplibregl || !webgl) throw new Error("MapLibre");
        });
        maplibreLoading.catch(() => { maplibreLoading = null; });
    }
    return maplibreLoading;
}

/**
 * The tile layer of a style, added to the map in place of {@code previous}. "Gọn" falls back to "Chi tiết" when
 * MapLibre cannot run; the answer says which style the map now shows ({@code fellBack} when it had to).
 */
export async function setBaseLayer(map, key, previous) {
    let b = BASEMAPS[key] || BASEMAPS.clean;
    let fellBack = false;
    if (b.style) {
        try {
            await loadMaplibre();
        } catch {
            key = "detail";
            b = BASEMAPS.detail;
            fellBack = true;
        }
    }
    if (previous) previous.remove();
    const L = window.L;
    const layer = b.style
        ? L.maplibreGL({ style: b.style, attribution: b.attribution, maxZoom: 20 }).addTo(map)
        : L.tileLayer(b.url, b.options).addTo(map);
    return { key: BASEMAPS[key] ? key : "clean", layer, fellBack };
}

/** The MapLibre map under a "Gọn" layer, or null ("Chi tiết" is a picture without data). */
export function glOf(layer, key) {
    return key === "clean" && layer?.getMaplibreMap ? layer.getMaplibreMap() : null;
}

// ---------------------------------------------------------------- shops drawn by the "Gọn" style

export const POI_LAYERS = ["poi_r1", "poi_r7", "poi_r20", "poi_transit"];

/** OpenMapTiles poi subclass (the OSM value) → Senpai ơi category, only to label the panel. */
export const SUBCLASS_CATEGORY = {
    convenience: "konbini",
    restaurant: "food", fast_food: "food", cafe: "food", food_court: "food", bakery: "food", pub: "food",
    bar: "food", ice_cream: "food", confectionery: "food",
    station: "station", halt: "station", subway: "station", train_station: "station", subway_entrance: "station",
    supermarket: "super", department_store: "super", mall: "super", variety_store: "super", general: "super",
    townhall: "ward", hospital: "hospital", clinic: "hospital", doctors: "hospital", dentist: "hospital",
    pharmacy: "pharmacy", chemist: "pharmacy", bank: "bank", atm: "bank", post_office: "bank",
    school: "school", university: "school", college: "school", language_school: "school",
    laundry: "laundry", dry_cleaning: "laundry", park: "park", garden: "park", place_of_worship: "temple",
    shrine: "temple", temple: "temple", mobile_phone: "phone", electronics: "phone", estate_agent: "housing"
};

/** The named map shops within {@code px} pixels of a point (bus stops left out). */
export function poisAround(gl, latlng, px) {
    if (!gl || !gl.isStyleLoaded()) return [];
    const layers = POI_LAYERS.filter((id) => gl.getLayer(id));
    if (!layers.length) return [];
    const p = gl.project([latlng.lng, latlng.lat]);
    return gl.queryRenderedFeatures([[p.x - px, p.y - px], [p.x + px, p.y + px]], { layers })
        .filter((f) => f.properties.name && f.id != null && f.properties.class !== "bus" && osmIdOf(f.id));
}

/** The named map shop under the pointer (a few pixels around it), or null. */
export function poiAt(gl, latlng) {
    return poisAround(gl, latlng, 10)[0] || null;
}

/** OpenFreeMap tiles are made by Planetiler: feature id = OSM id × 10 + 1 (node), 2 (way) or 3 (relation). */
export function osmIdOf(featureId) {
    const n = Number(featureId);
    const type = { 1: "N", 2: "W", 3: "R" }[n % 10];
    return type && Number.isFinite(n) ? `${type}${Math.floor(n / 10)}` : null;
}

/** The names and position of a map shop: {osmId, name (Latin), nameJa, subclass, cls, lat, lng}. */
export function poiInfo(f, latlng) {
    const pr = f.properties;
    const name = pr["name:latin"] || pr["name:en"] || pr.name_en || pr.name;
    const nameJa = pr["name:nonlatin"] || pr["name:ja"] || (pr.name !== name ? pr.name : null);
    const [lng, lat] = f.geometry?.type === "Point" ? f.geometry.coordinates : [latlng.lng, latlng.lat];
    return { osmId: osmIdOf(f.id), name, nameJa, otherNames: [pr.name, pr["name:en"]], subclass: pr.subclass,
        cls: pr.class, lat, lng };
}

/** A name as compared: no accents, case or punctuation, without the word for station ("Ga Nippori" = "日暮里駅"). */
export function nameKey(name) {
    let s = String(name || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/đ/gi, "d").toLowerCase().trim();
    s = s.replace(/^(nha ga|ga) /, "").replace(/( railway station| train station| station| eki|駅)$/, "");
    return s.replace(/[^\p{L}\p{N}]/gu, "");
}

/**
 * The Senpai ơi place a map shop already is: the same OSM id, or the same name close by (a station has a point per
 * line, so 600 m between stations; 80 m otherwise), as the server decides when a review is saved.
 */
export function knownPlaceFor(places, osmId, names, lat, lng, station) {
    const keys = new Set(names.filter(Boolean).map(nameKey).filter(Boolean));
    let best = null;
    for (const p of places) {
        if (osmId && p.osmId === osmId) return p;
        const near = distance({ lat, lng }, p);
        const limit = station && p.categoryId === "station" ? 600 : 80;
        if (near <= limit && [p.name, p.nameJa, p.nameEn].some((n) => keys.has(nameKey(n)))
            && (!best || near < best.near)) {
            best = { place: p, near };
        }
    }
    return best?.place || null;
}

// ---------------------------------------------------------------- distances

/** Metres between two {lat, lng} (equirectangular: plenty for the short distances of a map). */
export function distance(a, b) {
    const x = (b.lng - a.lng) * Math.PI / 180 * Math.cos((a.lat + b.lat) / 2 * Math.PI / 180);
    const y = (b.lat - a.lat) * Math.PI / 180;
    return Math.sqrt(x * x + y * y) * 6371000;
}

export function formatDistance(m) {
    return m < 1000 ? `${Math.round(m / 10) * 10} m` : `${(m / 1000).toFixed(1)} km`;
}
