// Event detail (/events/{id}): cover, dates and place, "Album từ review", "Mẹo từ Moderator", the rating of every
// year, a small OpenStreetMap map, and the reviews with Thích / Báo cáo (Members) and Sửa / Xóa (own review).
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { coverHtml } from "../lib/fest-art.js";
import { loadLeaflet, OSM_CREDIT, OSM_TILES } from "../lib/map.js";
import {
    avatar, esc, eventRange, eventStateClass, eventStateLabel, fullDate, googleDirectionsUrl, ic, loginUrl, osmUrl,
    stars, tagChips, timeAgo
} from "../lib/ui.js";

const MSG = {
    NOT_FOUND: "Không tìm thấy sự kiện. Sự kiện có thể đã bị xóa.",
    LOAD: "Không tải được sự kiện. Hãy thử lại.",
    ERROR: "Có lỗi xảy ra, vui lòng thử lại.",
    REASON: "Hãy chọn lý do báo cáo."
};
/** What the author sees on their own review that others do not see yet (or any more). */
const STATUS_NOTE = {
    PROCESSING: ["hourglass_top", "Ảnh và video đang được quét. Trong lúc này chỉ bạn thấy review."],
    REJECTED: ["block", "Review chưa được đăng vì nội dung hoặc ảnh vi phạm Quy tắc cộng đồng. Bạn có thể sửa rồi gửi lại."],
    HIDDEN: ["visibility_off", "Review đang bị ẩn vì có nhiều báo cáo, chờ Moderator xem xét."]
};

const content = document.querySelector("#content");
const toastBox = document.querySelector("#toast");
const eventId = Number.parseInt(location.pathname.split("/").filter(Boolean)[1], 10);
const reviewPath = `/events/${eventId}/review`;

let user = null;
let data = null;
let reportId = null;
const map = { el: null, map: null, open: false, ticket: 0 };

// ---------------------------------------------------------------- loading

async function load() {
    if (!Number.isInteger(eventId) || eventId <= 0) {
        renderState("empty", MSG.NOT_FOUND);
        return;
    }
    try {
        render(await apiClient.event(eventId));
    } catch (error) {
        if (error.status === 404) {
            renderState("empty", MSG.NOT_FOUND);
        } else {
            renderState("error", error.message || MSG.LOAD,
                `<button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button>`);
        }
    }
}

function renderState(kind, message, action = `<a class="btn sm" href="/events">Về danh sách sự kiện</a>`) {
    document.title = "Sự kiện – Senpai ơi";
    content.innerHTML = `<div class="state ${kind}" role="${kind === "error" ? "alert" : "status"}">
        ${ic(kind === "error" ? "cloud_off" : "search_off", "big")}<p>${esc(message)}</p>${action}</div>`;
}

// ---------------------------------------------------------------- render

function render(next) {
    data = next;
    const e = data.event;
    document.title = `${e.title} – Senpai ơi`;
    const count = e.reviewCount;
    content.innerHTML = `
        <article class="card post-full event-full">
            <div class="row meta">
                <span class="badge ev">${ic("celebration")}Lễ hội và sự kiện</span>
                <span class="badge ${eventStateClass(e)}">${esc(eventStateLabel(e))}</span>
            </div>
            <div class="festcover">${coverHtml(e)}</div>
            <div>
                <h1>${esc(e.title)}</h1>
                <p class="note">${esc(eventRange(e))} · ${esc(e.locationName)} · ${esc(e.costNote || "")}</p>
            </div>
            ${albumHtml()}
            ${data.tip ? `<div class="prac"><b>${ic("campaign")} Mẹo từ Moderator:</b> ${esc(data.tip)}</div>` : ""}
            <div class="row rating-line">${count
                ? `<span class="stars" aria-label="${e.avgRating} trên 5 sao">${stars(Math.round(e.avgRating))}</span>
                   <b>${e.avgRating.toFixed(1)}</b><span class="note">${count} review, gồm cả các năm trước</span>`
                : `<span class="note">Chưa có review.</span>`}</div>
            <div class="row">${writeButton()}</div>
            ${placeHtml()}
        </article>
        <section class="card reviews" aria-labelledby="reviews-title">
            <h2 id="reviews-title">Review <span class="note">(${data.reviews.filter((r) => r.status === "PUBLISHED").length})</span></h2>
            ${data.reviews.map(reviewHtml).join("") || `<p class="note">Chưa ai viết review về sự kiện này.</p>`}
        </section>`;
    mountMap();
}

function albumHtml() {
    if (!data.albumCount) return "";
    return `<div class="album">
        <div class="note"><b>${ic("photo_library")} Album từ review (${data.albumCount} ảnh)</b></div>
        <div class="album-grid">${data.album.map((p, i) => `<button type="button" data-act="photo" data-index="${i}"
            aria-label="Xem ảnh ${i + 1} của ${esc(p.authorName)}, năm ${p.year}"><img src="${esc(p.url)}" alt="" loading="lazy"></button>`).join("")}</div>
    </div>`;
}

/** "Viết review năm 2026", "Sửa review năm 2026", or why not yet. */
function writeButton() {
    const e = data.event;
    if (!e.canReview) {
        return `<span class="note">${ic("schedule")} Viết review được khi sự kiện bắt đầu.</span>`;
    }
    if (data.viewer.signedIn && !data.viewer.member) return "";      // staff accounts do not review
    const href = data.viewer.signedIn ? reviewPath : loginUrl(reviewPath);
    const label = data.viewer.myReviewId ? `Sửa review năm ${e.reviewYear}` : `Viết review năm ${e.reviewYear}`;
    return `<a class="btn pri sm" href="${esc(href)}">${ic(data.viewer.myReviewId ? "edit" : "rate_review")}${esc(label)}</a>`;
}

function placeHtml() {
    const e = data.event;
    if (e.lat == null || e.lng == null) {
        return `<p class="note">${ic("location_off")} Sự kiện chưa gắn vị trí trên bản đồ.</p>`;
    }
    const place = { name: e.locationName, lat: e.lat, lng: e.lng };
    return `<section class="place-map-box" aria-labelledby="place-title">
        <div class="row place-map-head">
            <h2 id="place-title">${ic("map")}Nơi tổ chức</h2>
            <button class="btn sm" type="button" data-act="toggle-map" aria-expanded="${map.open}">
                ${ic(map.open ? "visibility_off" : "visibility")}${map.open ? "Ẩn bản đồ" : "Hiện bản đồ"}</button>
        </div>
        <ul class="place-list"><li>
            <a class="place-name" href="${esc(osmUrl(place))}" target="_blank" rel="noopener" title="Xem trên OpenStreetMap">
                ${ic("location_on")}<span><b>${esc(e.locationName)}</b></span></a>
            <a class="btn sm" href="${esc(googleDirectionsUrl(place))}" target="_blank" rel="noopener"
                title="Chỉ đường bằng Google Maps">${ic("directions")}Chỉ đường</a>
        </li></ul>
        ${map.open ? `<div id="map-slot"></div>` : ""}
    </section>`;
}

function reviewHtml(r) {
    const status = STATUS_NOTE[r.status];
    const published = r.status === "PUBLISHED";
    // Members like and report others' published reviews; guests see the buttons and are sent to sign in
    const canAct = published && !r.mine && (data.viewer.member || !data.viewer.signedIn);
    return `<article class="review" data-id="${r.id}">
        ${status && r.mine ? `<div class="alert warnbox">${ic(status[0])} ${esc(status[1])}</div>` : ""}
        <div class="row who">
            ${avatar(r.author)}<b>${esc(r.author.displayName)}</b>
            ${tagChips(r.author.tags)}
            ${r.author.rankName ? `<span class="badge rank">${ic("workspace_premium")}${esc(r.author.rankName)}</span>` : ""}
            <span class="badge lan">Năm ${r.year}</span>
        </div>
        <div class="row">
            <span class="stars" aria-label="${r.rating} trên 5 sao">${stars(r.rating)}</span>
            <time class="note" datetime="${esc(r.createdAt)}" title="${esc(fullDate(r.createdAt))}">${timeAgo(r.createdAt)}</time>
            ${r.edited ? `<span class="note">· đã sửa</span>` : ""}
        </div>
        <div class="body">${esc(r.content)}</div>
        ${mediaHtml(r.media)}
        <div class="row">
            ${canAct ? `<button class="btn sm" type="button" data-act="like" aria-pressed="${r.liked}">
                ${ic("thumb_up")}Thích · ${r.likeCount}</button>` : `<span class="note">${ic("thumb_up")} ${r.likeCount}</span>`}
            ${canAct ? `<button class="btn sm ghost" type="button" data-act="report">${ic("flag")}Báo cáo</button>` : ""}
            ${r.mine ? `${r.status !== "HIDDEN" && r.year === data.event.reviewYear
                    ? `<a class="btn sm ghost" href="${reviewPath}">${ic("edit")}Sửa</a>` : ""}
                <button class="btn sm ghost dan" type="button" data-act="delete">${ic("delete")}Xóa</button>` : ""}
        </div>
    </article>`;
}

function mediaHtml(media) {
    if (!media.length) return "";
    return `<div class="media-grid count-${Math.min(media.length, 4)}">${media.map((m) => m.type === "VIDEO"
        ? `<video controls preload="metadata" src="${esc(m.url)}"></video>`
        : `<a href="${esc(m.url)}" target="_blank" rel="noopener"><img src="${esc(m.url)}" alt="Ảnh trong review" loading="lazy"
             ${m.width ? `width="${m.width}" height="${m.height}"` : ""}></a>`).join("")}</div>`;
}

// ---------------------------------------------------------------- map (one pin, built only when shown)

async function mountMap() {
    const slot = document.getElementById("map-slot");
    const e = data.event;
    if (!slot) return;
    if (!map.el) {
        map.el = document.createElement("div");
        map.el.className = "place-map";
        map.el.setAttribute("role", "region");
        map.el.setAttribute("aria-label", "Bản đồ nơi tổ chức");
    }
    slot.replaceWith(map.el);
    if (map.map) {
        map.map.invalidateSize();
        return;
    }
    const ticket = ++map.ticket;
    let L;
    try {
        L = await loadLeaflet();
    } catch {
        if (ticket === map.ticket) {
            map.el.innerHTML = `<p class="map-off">${ic("map")}Không tải được bản đồ lúc này.</p>`;
        }
        return;
    }
    if (ticket !== map.ticket || !map.el.isConnected) return;
    map.el.innerHTML = "";
    const m = L.map(map.el, { scrollWheelZoom: false }).setView([e.lat, e.lng], 16);
    L.tileLayer(OSM_TILES, { maxZoom: 19, attribution: OSM_CREDIT }).addTo(m);
    const pin = L.divIcon({ className: "map-pin", html: ic("celebration"), iconSize: [34, 34], iconAnchor: [17, 32] });
    L.marker([e.lat, e.lng], { icon: pin, title: e.locationName, alt: e.locationName }).addTo(m);
    map.map = m;
}

// ---------------------------------------------------------------- actions

/** Runs an action; a 401 sends the guest to sign in and back here, a 404 reloads the page. */
async function guarded(action) {
    try {
        return await action();
    } catch (error) {
        if (error.status === 401) {
            location.href = loginUrl();
        } else if (error.status === 404) {
            toast(error.message || MSG.ERROR, "error");
            await load();
        } else {
            toast(error.message || MSG.ERROR, "error");
        }
        return undefined;
    }
}

content.addEventListener("click", async (event) => {
    if (event.target.closest("[data-retry]")) {
        content.innerHTML = `<p class="note">Đang tải…</p>`;
        await load();
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button || button.disabled) return;
    const reviewId = Number(button.closest("[data-id]")?.dataset.id);
    switch (button.dataset.act) {
        case "toggle-map":
            map.open = !map.open;
            if (!map.open) map.ticket++;
            render(data);
            content.querySelector('[data-act="toggle-map"]')?.focus();
            break;
        case "photo":
            openViewer(Number(button.dataset.index));
            break;
        case "like": {
            if (!user) {
                location.href = loginUrl();
                return;
            }
            const on = button.getAttribute("aria-pressed") !== "true";
            button.disabled = true;
            const result = await guarded(() => apiClient.likeEventReview(reviewId, on));
            if (result) {
                const review = data.reviews.find((r) => r.id === reviewId);
                Object.assign(review, { liked: result.liked, likeCount: result.likeCount });
                render(data);
            } else if (button.isConnected) {
                button.disabled = false;
            }
            break;
        }
        case "report":
            if (!user) {
                location.href = loginUrl();
                return;
            }
            openReport(reviewId);
            break;
        case "delete": {
            if (!await confirmDialog("Xóa review này? Ảnh, video và điểm của review cũng bị xóa. Không thể hoàn tác.")) return;
            const done = await guarded(() => apiClient.deleteEventReview(reviewId));
            if (done !== undefined) {
                toast("Đã xóa review. Điểm của review này được thu lại.");
                await load();
            }
            break;
        }
        default:
    }
});

// ---------------------------------------------------------------- album viewer

const viewer = document.querySelector("#viewer-dlg");
let photoIndex = 0;

function openViewer(index) {
    photoIndex = index;
    showPhoto();
    viewer.showModal();
}

function showPhoto() {
    const p = data.album[photoIndex];
    const img = document.querySelector("#viewer-img");
    img.src = p.url;
    img.alt = `Ảnh của ${p.authorName}, năm ${p.year}`;
    document.querySelector("#viewer-caption").textContent =
        `${photoIndex + 1}/${data.album.length} · ${p.authorName} · năm ${p.year}`;
    document.querySelector("#viewer-prev").disabled = photoIndex === 0;
    document.querySelector("#viewer-next").disabled = photoIndex === data.album.length - 1;
}

const step = (delta) => {
    const next = photoIndex + delta;
    if (next >= 0 && next < data.album.length) {
        photoIndex = next;
        showPhoto();
    }
};
document.querySelector("#viewer-prev").addEventListener("click", () => step(-1));
document.querySelector("#viewer-next").addEventListener("click", () => step(1));
viewer.addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft") step(-1);
    if (event.key === "ArrowRight") step(1);
});

// ---------------------------------------------------------------- report dialog

const reportDlg = document.querySelector("#report-dlg");
const reportForm = document.querySelector("#report-form");
const reportError = document.querySelector("#report-error");

function openReport(id) {
    reportId = id;
    document.querySelector("#report-reasons").innerHTML = `<legend class="sr-only">Lý do</legend>`
        + data.reportReasons.map((r) => `<label class="check"><input type="radio" name="reason" value="${r.id}"><span>${esc(r.label)}</span></label>`).join("");
    document.querySelector("#report-note").value = "";
    reportError.hidden = true;
    reportDlg.showModal();
}

reportForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    const reason = reportForm.querySelector('input[name="reason"]:checked');
    if (!reason) {
        reportError.textContent = MSG.REASON;
        reportError.hidden = false;
        return;
    }
    const submit = document.querySelector("#report-submit");
    submit.disabled = true;
    try {
        await apiClient.reportEventReview(reportId, {
            reasonId: Number(reason.value),
            note: document.querySelector("#report-note").value.trim() || null
        });
        reportDlg.close();
        toast("Cảm ơn bạn. Báo cáo đã được gửi.");                       // MSG38
        await load();
    } catch (error) {
        if (error.status === 401) {
            reportError.innerHTML = `Phiên đăng nhập đã hết. <a class="link" href="${esc(loginUrl())}">Đăng nhập</a> lại rồi gửi báo cáo lần nữa.`;
        } else {
            reportError.textContent = error.message || MSG.ERROR;     // e.g. already reported
        }
        reportError.hidden = false;
        if (error.status === 404) await load();
    } finally {
        submit.disabled = false;
    }
});

// ---------------------------------------------------------------- confirm dialog and toast

const confirmDlg = document.querySelector("#confirm-dlg");

function confirmDialog(text) {
    document.querySelector("#confirm-text").textContent = text;
    confirmDlg.showModal();
    return new Promise((resolve) => {
        const ok = document.querySelector("#confirm-ok");
        const done = (value) => {
            ok.removeEventListener("click", onOk);
            confirmDlg.removeEventListener("close", onClose);
            if (confirmDlg.open) confirmDlg.close();
            resolve(value);
        };
        const onOk = () => done(true);
        const onClose = () => done(false);
        ok.addEventListener("click", onOk);
        confirmDlg.addEventListener("close", onClose);
    });
}

document.querySelectorAll("dialog [data-close]").forEach((b) => b.addEventListener("click", () => b.closest("dialog").close()));
document.querySelectorAll("dialog").forEach((d) => d.addEventListener("click", (event) => {
    if (event.target === d) d.close();          // click on the backdrop
}));

let toastTimer;
function toast(message, kind = "ok") {
    toastBox.textContent = message;
    toastBox.className = `toast ${kind}`;
    toastBox.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { toastBox.hidden = true; }, 3500);
}

// ---------------------------------------------------------------- start

user = await mountSiteHeader("fest");
await load();
announceSaved();

/** Back from "Viết review" (?created=1 / ?edited=1): say whether the review is public or still being scanned. */
function announceSaved() {
    const params = new URLSearchParams(location.search);
    const edited = params.has("edited");
    if (!(params.has("created") || edited) || !data) return;
    params.delete("created");
    params.delete("edited");
    history.replaceState(null, "", `${location.pathname}${params.size ? `?${params}` : ""}`);
    const mine = data.reviews.find((r) => r.mine && r.year === data.event.reviewYear);
    if (mine?.status === "PROCESSING") {
        toast(`${edited ? "Đã lưu thay đổi" : "Đã gửi"}. Ảnh và video đang được quét, trong lúc này chỉ bạn thấy review.`);
    } else {
        toast(edited ? "Đã lưu thay đổi." : "Đã đăng review. +10 điểm.");
    }
}
