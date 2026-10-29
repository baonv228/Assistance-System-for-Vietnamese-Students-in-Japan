// Pieces shared by the Moderator's place pages (Ghim mới, Báo đóng cửa, Địa điểm, Gộp trùng lặp): the toast, the
// small OpenStreetMap map with the place being checked and the places around it, and the place pin icon.
import { loadLeaflet, OSM_CREDIT, OSM_TILES } from "./map.js";
import { esc, ic } from "./ui.js";

let toastTimer = null;

export function toast(message, kind = "ok") {
    const box = document.querySelector("#toast");
    if (!box) return;
    box.textContent = message;
    box.className = `toast ${kind}`;
    box.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { box.hidden = true; }, 4000);
}

/** "Ghim cộng đồng", "OpenStreetMap"… for a place's source. */
export const SOURCE_LABELS = { OSM: "OpenStreetMap", GOOGLE: "Google Maps", TEAM: "Nhóm Senpai ơi thêm",
    MODERATOR: "Nhóm Senpai ơi thêm", USER: "Ghim cộng đồng" };

export const STATUS_LABELS = { PENDING: "Chờ duyệt", ACTIVE: "Đang hoạt động", REJECTED: "Bị từ chối",
    REMOVED: "Đã gỡ", MERGED: "Đã gộp" };

/** "Hôm nay", "3 ngày trước" (whole days, Japan or local makes no difference here). */
export function daysAgo(iso) {
    if (!iso) return "chưa có";
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86_400_000);
    return days <= 0 ? "hôm nay" : `${days} ngày trước`;
}

const pinIcon = (L, kind, icon) => L.divIcon({ className: "mpin-wrap",
    html: `<span class="mpin ${kind}">${ic(icon)}</span>`, iconSize: [30, 30], iconAnchor: [15, 15] });

/**
 * Draws (or redraws) a small map in {@code box}: the main place (dashed pin) and the others with their names. A map
 * that cannot load (offline, CDN blocked) leaves a short note instead.
 *
 * @param main   { lat, lng, icon, unset } ; with onMove the pin can be dragged or moved by a click, and
 *               onMove(lat, lng) is called after each move
 * @param others [{ lat, lng, name, label, kind }]
 * @returns the map handle { map, marker } or null
 */
export async function miniMap(box, main, others = [], onMove = null) {
    let L;
    try {
        L = await loadLeaflet();
    } catch {
        box.innerHTML = `<p class="note map-off">${ic("map")} Không tải được bản đồ. Kiểm tra kết nối mạng.</p>`;
        return null;
    }
    if (box._map) {
        box._map.remove();
    }
    const map = L.map(box, { scrollWheelZoom: false, maxZoom: 19 });
    box._map = map;
    L.tileLayer(OSM_TILES, { maxZoom: 19, attribution: OSM_CREDIT }).addTo(map);
    const marker = L.marker([main.lat, main.lng], { icon: pinIcon(L, "main", main.icon || "add_location_alt"),
        draggable: Boolean(onMove), keyboard: true, title: "Vị trí đang xét" });
    if (!main.unset) {
        marker.addTo(map);        // unset: the map opens there, the pin appears at the first click
    }
    if (onMove) {
        marker.on("dragend", () => {
            const p = marker.getLatLng();
            onMove(p.lat, p.lng);
        });
        map.on("click", (event) => {
            marker.setLatLng(event.latlng);
            if (!map.hasLayer(marker)) marker.addTo(map);
            onMove(event.latlng.lat, event.latlng.lng);
        });
    }
    const points = [[main.lat, main.lng]];
    others.forEach((o) => {
        L.marker([o.lat, o.lng], { icon: pinIcon(L, o.kind || "near", "location_on"), title: o.name })
            .bindTooltip(`${esc(o.name)}${o.label ? ` · ${esc(o.label)}` : ""}`).addTo(map);
        points.push([o.lat, o.lng]);
    });
    if (points.length > 1) {
        map.fitBounds(points, { padding: [30, 30], maxZoom: 18 });
    } else {
        map.setView(points[0], 17);
    }
    return { map, marker };
}
