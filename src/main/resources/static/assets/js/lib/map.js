// OpenStreetMap maps with Leaflet (free, no key). Leaflet is loaded from unpkg with SRI the first time a map is shown.

const LEAFLET = {
    css: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.css",
    cssHash: "sha256-p4NxAoJBhIIN+hmNHrzRCf9tD/miZyoHS5obTRR9BMY=",
    js: "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
    jsHash: "sha256-20nQCchB9co0qIjJZRGuk2/Z9VM+kNiyxNV1lvTlZBo="
};
export const OSM_TILES = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
export const OSM_CREDIT = '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a>';

let leafletLoading = null;

/** Resolves with window.L once Leaflet is loaded; a failed load is tried again on the next call. */
export function loadLeaflet() {
    if (!leafletLoading) {
        leafletLoading = new Promise((resolve, reject) => {
            const css = Object.assign(document.createElement("link"),
                { rel: "stylesheet", href: LEAFLET.css, integrity: LEAFLET.cssHash, crossOrigin: "" });
            const js = Object.assign(document.createElement("script"),
                { src: LEAFLET.js, integrity: LEAFLET.jsHash, crossOrigin: "", async: true });
            js.onload = () => (window.L ? resolve(window.L) : reject(new Error("Leaflet")));
            js.onerror = () => {
                leafletLoading = null;                  // try again on the next render
                js.remove();
                reject(new Error("Leaflet"));
            };
            document.head.append(css, js);
        });
    }
    return leafletLoading;
}
