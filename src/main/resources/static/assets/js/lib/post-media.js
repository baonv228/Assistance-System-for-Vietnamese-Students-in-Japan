// Photos and videos of a post or question drawn as a Facebook-like grid (1 photo across the width, 2 side by side,
// 3–4 one big + the rest below, 5 or more: 2 above and 3 below with "+N" on the last), and a full-screen viewer that
// opens on a tile (arrows, ← →, swipe on phones, Esc). Used by the Blog and Hỏi-Đáp lists and the post page.
// `media` items come from the API: { id, type: IMAGE | VIDEO, url, mimeType, width, height }.
import { esc, ic } from "./ui.js";

/** Tiles drawn at most; the last one says how many more the viewer holds. */
export const GRID_MAX = 5;

/** Width / height of a lone photo, kept between portrait 4:5 and landscape 1.91:1 (as Facebook does). */
export function soloRatio(item) {
    if (!item.width || !item.height) return item.type === "VIDEO" ? 16 / 9 : 4 / 3;
    return Math.min(1.91, Math.max(0.8, item.width / item.height));
}

const what = (item) => (item.type === "VIDEO" ? "video" : "ảnh");

/** The grid of a post, or "" without media. A lone video plays in place (with its controls). */
export function mediaGrid(media = []) {
    if (!media.length) return "";
    const total = media.length;
    if (total === 1 && media[0].type === "VIDEO") {
        return `<div class="pm-grid pm-n1 pm-solo-video">
            <video controls playsinline preload="metadata" src="${esc(media[0].url)}#t=0.1"></video></div>`;
    }
    const shown = media.slice(0, GRID_MAX);
    const extra = total - shown.length;
    const tiles = shown.map((item, i) => {
        const more = extra > 0 && i === shown.length - 1;
        const label = total === 1 ? `Xem ${what(item)} lớn hơn`
            : `Xem ${what(item)} ${i + 1} trên ${total}${more ? `, còn ${extra} ảnh hoặc video nữa` : ""}`;
        const body = item.type === "VIDEO"
            // #t=0.1: phones draw the first frame instead of a black box
            ? `<video muted playsinline preload="metadata" tabindex="-1" aria-hidden="true" src="${esc(item.url)}#t=0.1"></video>
               <span class="pm-play" aria-hidden="true">${ic("play_arrow")}</span>`
            : `<img src="${esc(item.url)}" alt="" loading="lazy" decoding="async"
                   ${item.width && item.height ? `width="${item.width}" height="${item.height}"` : ""}>`;
        return `<button class="pm-tile${item.type === "VIDEO" ? " pm-video" : ""}" type="button" data-media-index="${i}"
            aria-label="${esc(label)}">${body}${more ? `<span class="pm-more" aria-hidden="true">+${extra}</span>` : ""}</button>`;
    }).join("");
    const ratio = total === 1 ? ` style="--pm-ar:${soloRatio(media[0]).toFixed(3)}"` : "";
    return `<div class="pm-grid pm-n${shown.length}"${ratio}>${tiles}</div>`;
}

/**
 * Opens the viewer when a tile inside `root` is pressed. `lookup(tile)` gives `{ media, title, href }` of the post the
 * tile belongs to (`href` adds "Xem bài viết" to the viewer), or null to ignore the press.
 */
export function bindMediaGrids(root, lookup) {
    root.addEventListener("click", (event) => {
        const tile = event.target.closest(".pm-tile");
        if (!tile || !root.contains(tile)) return;
        const post = lookup(tile);
        if (!post?.media?.length) return;
        openMediaViewer(post.media, Number(tile.dataset.mediaIndex) || 0, { ...post, opener: tile });
    });
}

// ---------------------------------------------------------------- full-screen viewer

let viewer = null;
const SWIPE_MIN = 40;           // px a finger must travel sideways to change the photo

function buildViewer() {
    const dlg = document.createElement("dialog");
    dlg.className = "pm-viewer";
    dlg.setAttribute("aria-label", "Ảnh và video trong bài");
    dlg.innerHTML = `
        <div class="pm-v-bar">
            <span class="pm-v-count" aria-live="polite"></span>
            <span class="pm-v-title"></span>
            <a class="pm-v-open" href="/">${ic("open_in_new")}<span>Xem bài viết</span></a>
            <button class="pm-v-btn pm-v-x" type="button" aria-label="Đóng">${ic("close")}</button>
        </div>
        <div class="pm-v-stage">
            <button class="pm-v-btn pm-v-prev" type="button" aria-label="Ảnh trước">${ic("chevron_left")}</button>
            <div class="pm-v-item"></div>
            <button class="pm-v-btn pm-v-next" type="button" aria-label="Ảnh sau">${ic("chevron_right")}</button>
        </div>`;
    document.body.append(dlg);
    const v = {
        dlg,
        count: dlg.querySelector(".pm-v-count"),
        title: dlg.querySelector(".pm-v-title"),
        open: dlg.querySelector(".pm-v-open"),
        close: dlg.querySelector(".pm-v-x"),
        prev: dlg.querySelector(".pm-v-prev"),
        next: dlg.querySelector(".pm-v-next"),
        item: dlg.querySelector(".pm-v-item"),
        stage: dlg.querySelector(".pm-v-stage"),
        media: [],
        index: 0,
        opener: null
    };
    v.close.addEventListener("click", () => dlg.close());
    v.prev.addEventListener("click", () => step(-1));
    v.next.addEventListener("click", () => step(1));
    dlg.addEventListener("keydown", (event) => {
        // a focused video seeks with the arrows itself
        if (event.target instanceof HTMLVideoElement) return;
        if (event.key === "ArrowLeft") {
            event.preventDefault();
            step(-1);
        } else if (event.key === "ArrowRight") {
            event.preventDefault();
            step(1);
        }
    });
    let start = null;
    v.stage.addEventListener("pointerdown", (event) => {
        start = event.pointerType === "mouse" ? null : { x: event.clientX, y: event.clientY };
    });
    v.stage.addEventListener("pointerup", (event) => {
        if (!start) return;
        const dx = event.clientX - start.x;
        const dy = event.clientY - start.y;
        start = null;
        if (Math.abs(dx) >= SWIPE_MIN && Math.abs(dx) > Math.abs(dy)) step(dx < 0 ? 1 : -1);
    });
    v.stage.addEventListener("pointercancel", () => { start = null; });
    dlg.addEventListener("close", () => {
        v.item.replaceChildren();                       // stops a video that is playing
        document.documentElement.classList.remove("pm-lock");
        if (v.opener?.isConnected) v.opener.focus({ preventScroll: true });
        v.opener = null;
    });
    return v;
}

function step(delta) {
    const target = viewer.index + delta;
    if (target >= 0 && target < viewer.media.length) show(target);
}

function show(index) {
    const v = viewer;
    const item = v.media[index];
    v.index = index;
    const total = v.media.length;
    let element;
    if (item.type === "VIDEO") {
        element = document.createElement("video");
        element.controls = true;
        element.autoplay = true;
        element.playsInline = true;
        element.preload = "metadata";
    } else {
        element = document.createElement("img");
        element.alt = total > 1 ? `Ảnh ${index + 1} trên ${total}` : "Ảnh trong bài";
        element.decoding = "async";
    }
    element.src = item.url;
    v.item.replaceChildren(element);
    v.count.textContent = total > 1 ? `${index + 1} / ${total}` : "";
    v.prev.hidden = v.next.hidden = total < 2;
    v.prev.disabled = index === 0;
    v.next.disabled = index === total - 1;
    v.prev.setAttribute("aria-label", `${what(v.media[Math.max(0, index - 1)]) === "video" ? "Video" : "Ảnh"} trước`);
    v.next.setAttribute("aria-label", `${what(v.media[Math.min(total - 1, index + 1)]) === "video" ? "Video" : "Ảnh"} sau`);
    // the neighbours are fetched while this one is looked at
    [index - 1, index + 1].forEach((i) => {
        const near = v.media[i];
        if (near && near.type !== "VIDEO") new Image().src = near.url;
    });
}

/** Shows `media[index]` full screen; `title` and `href` name the post and link to it. */
export function openMediaViewer(media, index = 0, { title = "", href = "", opener = null } = {}) {
    viewer ||= buildViewer();
    viewer.media = media;
    viewer.opener = opener;
    viewer.title.textContent = title;
    viewer.open.hidden = !href;
    if (href) viewer.open.href = href;
    document.documentElement.classList.add("pm-lock");
    if (!viewer.dlg.open) viewer.dlg.showModal();
    show(Math.min(Math.max(0, index), media.length - 1));
    viewer.close.focus();
}
