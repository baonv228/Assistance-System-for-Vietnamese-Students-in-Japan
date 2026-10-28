// Living-area picker of "Tôi" and the member Home (V10): prefecture → municipality / ward, a quick search by name
// (romaji, kanji or kana), or "Chọn trên bản đồ" (a click, or "Vị trí của tôi" after the browser asks permission;
// the server suggests the nearest area and the user confirms). Only the area id is saved; the point never is.
// Usage: const picker = await mountAreaPicker(box, { id: "me", areaId, stage }); picker.value() → id or null;
//        picker.setStage("PRE") changes the label.
import { apiClient } from "../api/client.js";
import { loadLeaflet, OSM_CREDIT, OSM_TILES } from "../lib/map.js";
import { esc, ic } from "../lib/ui.js";

/** Label by stage: people still in (or back in) Vietnam choose the Japanese area they will go to / used to live in. */
export const AREA_LABELS = { PRE: "Khu vực bạn sắp đến", HOME: "Khu vực bạn từng sống" };
export const areaLabel = (stage) => AREA_LABELS[stage] || "Khu vực bạn đang sống";

const JAPAN = { lat: 36.2, lng: 138.25, zoom: 5 };
let pickerData = null;                       // prefectures + devLocation, loaded once per page
const lists = new Map();                     // prefecture code → its areas

const areaText = (a) => `${a.nameEn} · ${a.nameJa}`;

async function areasOf(code) {
    if (!lists.has(code)) {
        lists.set(code, await apiClient.areasIn(code));
    }
    return lists.get(code);
}

export async function mountAreaPicker(box, { id, areaId = null, stage = null }) {
    pickerData ??= await apiClient.areaPicker();
    const current = areaId ? await apiClient.area(areaId).catch(() => null) : null;
    box.innerHTML = `
        <div class="area-picker">
            <div class="two">
                <div class="field"><label for="${id}-pref">Tỉnh / thành phố</label>
                    <select class="select" id="${id}-pref"><option value="">Chưa chọn</option>
                        ${pickerData.prefectures.map((p) => `<option value="${esc(p.code)}">${esc(p.nameEn)} · ${esc(p.nameJa)}</option>`).join("")}
                    </select></div>
                <div class="field"><label for="${id}-area" data-area-label>${areaLabel(stage)}</label>
                    <select class="select" id="${id}-area" disabled><option value="">Chọn tỉnh trước</option></select></div>
            </div>
            <div class="row area-tools">
                <input class="select grow" type="search" id="${id}-find" placeholder="Tìm nhanh: Arakawa, 荒川, Osaka…"
                    autocomplete="off" aria-label="Tìm khu vực theo tên">
                <button class="btn sm" type="button" data-map-open>${ic("map")}Chọn trên bản đồ</button>
            </div>
            <div class="area-results" data-results role="listbox" aria-label="Khu vực tìm được" hidden></div>
            <p class="note area-credit">Danh sách khu vực: <a class="link" href="https://github.com/geolonia/japanese-addresses-v2"
                target="_blank" rel="noopener">Geolonia 住所データ</a> (CC BY 4.0), dựa trên dữ liệu của デジタル庁.</p>
            <div class="area-mapbox" data-mapbox hidden>
                <p class="note">Bấm vào khu vực của bạn trên bản đồ, hoặc dùng "Vị trí của tôi". Vị trí chỉ dùng để gợi ý
                    khu vực, không được lưu.</p>
                <div class="area-map" data-map></div>
                <div class="row"><button class="btn sm" type="button" data-locate>${ic("my_location")}Vị trí của tôi</button>
                    <span class="note grow" data-suggest role="status"></span>
                    <button class="btn sm pri" type="button" data-use hidden>Dùng khu vực này</button>
                    <button class="btn sm" type="button" data-map-close>Đóng</button></div>
            </div>
        </div>`;
    const pref = box.querySelector(`#${id}-pref`);
    const area = box.querySelector(`#${id}-area`);
    const find = box.querySelector(`#${id}-find`);
    const results = box.querySelector("[data-results]");
    const mapbox = box.querySelector("[data-mapbox]");
    const suggestText = box.querySelector("[data-suggest]");
    const use = box.querySelector("[data-use]");
    let suggested = null;
    let map = null;
    let marker = null;
    let findTimer = null;
    let findSeq = 0;

    async function fillAreas(code, selectedId = null) {
        area.disabled = true;
        if (!code) {
            area.innerHTML = `<option value="">Chọn tỉnh trước</option>`;
            return;
        }
        area.innerHTML = `<option value="">Đang tải…</option>`;
        const items = await areasOf(code);
        area.innerHTML = `<option value="">Chưa chọn</option>` + items.map((a) =>
            `<option value="${a.id}" ${a.id === selectedId ? "selected" : ""}>${esc(areaText(a))}</option>`).join("");
        area.disabled = false;
    }

    async function choose(a) {
        pref.value = a.prefectureCode;
        await fillAreas(a.prefectureCode, a.id);
        area.focus();
    }

    pref.addEventListener("change", () => fillAreas(pref.value).catch(() => {
        area.innerHTML = `<option value="">Không tải được danh sách</option>`;
    }));

    // Quick search: the newest text wins (an older answer arriving late is ignored)
    find.addEventListener("input", () => {
        clearTimeout(findTimer);
        const q = find.value.trim();
        if (!q) {
            results.hidden = true;
            return;
        }
        findTimer = setTimeout(async () => {
            const seq = ++findSeq;
            const found = await apiClient.searchAreas(q).catch(() => []);
            if (seq !== findSeq) return;
            const names = new Map(pickerData.prefectures.map((p) => [p.code, p.nameEn]));
            results.innerHTML = found.length
                ? found.map((a, i) => `<button type="button" role="option" data-i="${i}">${esc(a.nameEn)}, ${esc(names.get(a.prefectureCode) || "")}
                    <span class="note">${esc(a.nameJa)}</span></button>`).join("")
                : `<p class="note">Không tìm thấy khu vực nào. Thử tên khác hoặc chọn tỉnh ở trên.</p>`;
            results.hidden = false;
            results.querySelectorAll("[data-i]").forEach((b) => b.addEventListener("click", async () => {
                results.hidden = true;
                find.value = "";
                await choose(found[Number(b.dataset.i)]);
            }));
        }, 250);
    });

    // ------------------------------------------------------------ "Chọn trên bản đồ"
    async function suggestAt(lat, lng) {
        if (marker) marker.setLatLng([lat, lng]);
        else marker = window.L.marker([lat, lng]).addTo(map);
        suggestText.textContent = "Đang tìm khu vực…";
        use.hidden = true;
        try {
            suggested = await apiClient.nearestArea(lat, lng);
            const p = pickerData.prefectures.find((x) => x.code === suggested.prefectureCode);
            suggestText.innerHTML = `Khu vực gợi ý: <b>${esc(suggested.nameEn)}${p ? `, ${esc(p.nameEn)}` : ""}</b> · ${esc((p ? p.nameJa : "") + suggested.nameJa)}`;
            use.hidden = false;
        } catch (error) {
            suggested = null;
            suggestText.textContent = error.message;
        }
    }

    box.querySelector("[data-map-open]").addEventListener("click", async () => {
        mapbox.hidden = false;
        if (map) {
            map.invalidateSize();
            return;
        }
        let L;
        try {
            L = await loadLeaflet();
        } catch {
            box.querySelector("[data-map]").innerHTML = `<p class="note">${ic("map")} Không tải được bản đồ. Kiểm tra kết nối mạng.</p>`;
            return;
        }
        const chosen = area.value ? (await areasOf(pref.value)).find((a) => String(a.id) === area.value) : null;
        const start = chosen?.lat ? { lat: chosen.lat, lng: chosen.lng, zoom: 12 } : JAPAN;
        map = L.map(box.querySelector("[data-map]"), { scrollWheelZoom: false }).setView([start.lat, start.lng], start.zoom);
        L.tileLayer(OSM_TILES, { maxZoom: 19, attribution: OSM_CREDIT }).addTo(map);
        map.on("click", (event) => suggestAt(event.latlng.lat, event.latlng.lng));
    });

    box.querySelector("[data-locate]").addEventListener("click", () => {
        const dev = pickerData.devLocation;
        if (dev) {                              // test environments outside Japan (app.map.dev-location)
            map?.setView([dev.lat, dev.lng], 13);
            suggestAt(dev.lat, dev.lng);
            return;
        }
        if (!navigator.geolocation) {
            suggestText.textContent = "Trình duyệt này không lấy được vị trí. Hãy bấm lên bản đồ.";
            return;
        }
        suggestText.textContent = "Đang lấy vị trí…";
        // The browser asks the user first; nothing happens without their permission
        navigator.geolocation.getCurrentPosition((pos) => {
            map?.setView([pos.coords.latitude, pos.coords.longitude], 13);
            suggestAt(pos.coords.latitude, pos.coords.longitude);
        }, () => {
            suggestText.textContent = "Không lấy được vị trí (bạn chưa cho phép hoặc thiết bị không hỗ trợ). Hãy bấm lên bản đồ hoặc chọn trong danh sách.";
        }, { enableHighAccuracy: false, timeout: 10000, maximumAge: 600000 });
    });

    use.addEventListener("click", async () => {
        if (!suggested) return;
        await choose(suggested);
        mapbox.hidden = true;
    });
    box.querySelector("[data-map-close]").addEventListener("click", () => {
        mapbox.hidden = true;
    });

    if (current) {
        pref.value = current.prefectureCode;
        await fillAreas(current.prefectureCode, current.id).catch(() => {});
    }

    return {
        value: () => (area.value ? Number(area.value) : null),
        setStage: (s) => {
            box.querySelector("[data-area-label]").textContent = areaLabel(s);
        }
    };
}
