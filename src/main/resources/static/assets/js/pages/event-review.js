// "Viết review" for an event (/events/{id}/review). Writes the viewer's review of this year's occurrence, or edits it
// when they wrote one already. Stars + text + up to 5 photos and one video ≤ 30 s, as in the prototype.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { isImageFile, isVideoFile, MB, shrinkToJpeg, unreadableImage, videoLength } from "../lib/media-files.js";
import { esc, eventRange, ic, loginUrl } from "../lib/ui.js";

const eventId = Number.parseInt(location.pathname.split("/").filter(Boolean)[1], 10);
const detailPath = `/events/${eventId}`;
const $ = (selector) => document.querySelector(selector);
const els = {
    gate: $("#gate"), form: $("#form"), quota: $("#quota"), formError: $("#form-error"), stars: $("#stars"),
    content: $("#content"), contentCount: $("#content-count"), linkNote: $("#link-note"),
    picks: $("#media-picks"), addImage: $("#add-image"), addVideo: $("#add-video"), imageInput: $("#image-input"),
    videoInput: $("#video-input"), imagesCount: $("#images-count"), videoNote: $("#video-note"), submit: $("#submit")
};

let form = null;                // ReviewForm from the API
let rating = 0;
let existing = [];              // editing: attached photos/video still kept, in order
let images = [];                // new photos: { blob, url, name }
let video = null;               // new video: { file, url, seconds }
let processingImages = 0;
let submitting = false;

const editing = () => Boolean(form?.review);
const imageCount = () => existing.filter((m) => m.type === "IMAGE").length + images.length;
const hasVideo = () => Boolean(video) || existing.some((m) => m.type === "VIDEO");

// ---------------------------------------------------------------- loading

async function start() {
    $("#back").href = detailPath;
    const user = await mountSiteHeader("fest");
    if (!user) {
        location.replace(loginUrl());
        return;
    }
    if (user.role !== "USER") {
        gate("Chỉ tài khoản người dùng mới viết review được.");
        return;
    }
    try {
        form = await apiClient.eventReviewForm(eventId);
    } catch (error) {
        if (error.status === 401) {
            location.replace(loginUrl());
            return;
        }
        gate(error.message || "Không tải được sự kiện.", error.status === 0 || error.status >= 500);
        return;
    }
    fill();
}

function gate(message, retry = false) {
    els.gate.innerHTML = `<div class="state ${retry ? "error" : "empty"}" role="alert">${ic(retry ? "cloud_off" : "info", "big")}
        <p>${esc(message)}</p><a class="btn sm" href="${detailPath}">Về trang sự kiện</a></div>`;
}

function fill() {
    const e = form.event;
    const own = form.review;
    document.title = `${own ? "Sửa review" : "Viết review"} – ${e.title} – Senpai ơi`;
    $("#page-title").textContent = own ? "Sửa review" : "Viết review";
    $("#event-title").textContent = e.title;
    $("#event-sub").textContent = `Review cho lần tổ chức năm ${e.reviewYear} · ${eventRange(e)}`;
    $("#submit-label").textContent = own ? "Lưu thay đổi" : "Đăng review";
    if (own) {
        rating = own.rating;
        els.content.value = own.content;
        existing = own.media.filter((m) => m.scanStatus !== "REJECTED");
    }
    els.content.maxLength = form.limits.contentMax;
    els.linkNote.textContent = form.canPostLinks
        ? "" : "Chỉ tài khoản có tag định danh mới chèn được link hoặc đăng ảnh có mã QR.";
    renderQuota();
    renderStars();
    renderMedia();
    updateCount();
    els.gate.hidden = true;
    els.form.hidden = false;
}

/** "Tài khoản mới, hôm nay: 1/3 bài viết và review · 0/2 ghim · 0/1 video" (BR-07), new reviews only. */
function renderQuota() {
    const q = form.quota;
    if (!q.newAccount || editing()) {
        els.quota.hidden = true;
        return;
    }
    const full = q.postsUsed >= q.postsLimit;
    els.quota.className = `alert quota ${full ? "full" : ""}`;
    els.quota.innerHTML = `${ic(full ? "block" : "schedule")}
        <span>Tài khoản mới, hôm nay: <b>${q.postsUsed}/${q.postsLimit}</b> bài viết và review ·
        <b>${q.pinsUsed}/${q.pinsLimit}</b> ghim · <b>${q.videosUsed}/${q.videosLimit}</b> video.
        ${full ? " Bạn đã dùng hết lượt đăng hôm nay, hãy quay lại vào ngày mai." : ""}</span>`;
    els.quota.hidden = false;
}

// ---------------------------------------------------------------- stars and text

function renderStars() {
    els.stars.innerHTML = [1, 2, 3, 4, 5].map((n) => `<label class="${n <= rating ? "on" : ""}" title="${n} sao">
        <input type="radio" name="rating" value="${n}" ${n === rating ? "checked" : ""} aria-label="${n} sao">
        <span aria-hidden="true">${n <= rating ? "★" : "☆"}</span></label>`).join("");
}

els.stars.addEventListener("change", (event) => {
    rating = Number(event.target.value);
    clearError("rating");
    renderStars();
    els.stars.querySelector(`input[value="${rating}"]`)?.focus();
});

function updateCount() {
    els.contentCount.textContent = `${els.content.value.length}/${form.limits.contentMax}`;
}
els.content.addEventListener("input", () => {
    updateCount();
    clearError("content");
});

// ---------------------------------------------------------------- photos and video

const videoQuotaUsed = () => !editing() && form.quota.newAccount && form.quota.videosUsed >= form.quota.videosLimit;

els.addImage.addEventListener("click", () => els.imageInput.click());
els.addVideo.addEventListener("click", () => els.videoInput.click());
els.imageInput.addEventListener("change", () => addImages([...els.imageInput.files]));
els.videoInput.addEventListener("change", () => addVideo(els.videoInput.files[0]));

const imageRule = () => `Tối đa ${form.limits.imagesMax} ảnh, định dạng JPG, PNG hoặc HEIC, mỗi ảnh không quá ${form.limits.imageMaxMb} MB.`;

async function addImages(files) {
    els.imageInput.value = "";
    clearError("images");
    const { imagesMax, imageMaxMb } = form.limits;
    const room = imagesMax - imageCount() - processingImages;
    if (files.length > room) {
        showError("images", imageRule());
        files = files.slice(0, Math.max(0, room));
    }
    processingImages += files.length;
    renderMedia();
    for (const file of files) {
        try {
            if (!isImageFile(file) || file.size > imageMaxMb * MB) {
                showError("images", imageRule());
                continue;
            }
            const blob = await shrinkToJpeg(file);
            if (imageCount() < imagesMax) images.push({ blob, url: URL.createObjectURL(blob), name: file.name });
        } catch {
            showError("images", unreadableImage(file));
        } finally {
            processingImages--;
            renderMedia();
        }
    }
}

async function addVideo(file) {
    els.videoInput.value = "";
    clearError("video");
    if (!file) return;
    const { videoMaxMb, videoMaxSeconds } = form.limits;
    if (!isVideoFile(file) || file.size > videoMaxMb * MB) {
        showError("video", `Video phải là MP4 hoặc MOV, dài tối đa ${videoMaxSeconds} giây và không quá ${videoMaxMb} MB.`);
        return;
    }
    const url = URL.createObjectURL(file);
    const seconds = await videoLength(url);
    if (seconds !== null && seconds > videoMaxSeconds + 0.5) {
        URL.revokeObjectURL(url);
        showError("video", `Video dài ${Math.round(seconds)} giây. Video tối đa ${videoMaxSeconds} giây.`);
        return;
    }
    if (video) URL.revokeObjectURL(video.url);
    video = { file, url, seconds };
    renderMedia();
}

function renderMedia() {
    const items = [];
    existing.forEach((m) => items.push(m.type === "VIDEO"
        ? `<li class="pick video"><video src="${esc(m.url)}" muted playsinline preload="metadata" aria-label="Video đã đăng"></video>
            <span class="badge pick-tag">${ic(m.scanStatus === "PROCESSING" ? "hourglass_top" : "videocam")}${m.scanStatus === "PROCESSING" ? "Đang quét" : "Video"}</span>
            <button class="pick-remove" type="button" data-remove-existing="${m.id}" aria-label="Bỏ video">${ic("close")}</button></li>`
        : `<li class="pick"><img src="${esc(m.url)}" alt="Ảnh đã đăng">
            ${m.scanStatus === "PROCESSING" ? `<span class="badge pick-tag">${ic("hourglass_top")}Đang quét</span>` : ""}
            <button class="pick-remove" type="button" data-remove-existing="${m.id}" aria-label="Bỏ ảnh">${ic("close")}</button></li>`));
    images.forEach((img, i) => items.push(`<li class="pick"><img src="${img.url}" alt="Ảnh mới: ${esc(img.name)}">
        <button class="pick-remove" type="button" data-remove-image="${i}" aria-label="Bỏ ảnh ${esc(img.name)}">${ic("close")}</button></li>`));
    for (let i = 0; i < processingImages; i++) {
        items.push(`<li class="pick busy" aria-label="Đang xử lý ảnh">${ic("hourglass_top")}</li>`);
    }
    if (video) {
        items.push(`<li class="pick video"><video src="${video.url}" muted playsinline preload="metadata" aria-label="Video đã chọn"></video>
            <span class="badge pick-tag">${ic("videocam")}${video.seconds === null ? "Video" : `${Math.round(video.seconds)} giây`}</span>
            <button class="pick-remove" type="button" data-remove-video aria-label="Bỏ video">${ic("close")}</button></li>`);
    }
    els.picks.innerHTML = items.join("");
    els.picks.hidden = !items.length;
    els.imagesCount.textContent = `${imageCount()}/${form.limits.imagesMax}`;
    els.addImage.disabled = imageCount() + processingImages >= form.limits.imagesMax;
    els.addVideo.disabled = hasVideo() || videoQuotaUsed();
    els.videoNote.hidden = !videoQuotaUsed();
    els.videoNote.textContent = `Tài khoản mới chỉ đăng ${form.quota.videosLimit} video mỗi ngày. Hôm nay bạn đã dùng lượt này.`;
}

els.picks.addEventListener("click", (event) => {
    const old = event.target.closest("[data-remove-existing]");
    if (old) {
        existing = existing.filter((m) => m.id !== Number(old.dataset.removeExisting));
    } else if (event.target.closest("[data-remove-image]")) {
        const [removed] = images.splice(Number(event.target.closest("[data-remove-image]").dataset.removeImage), 1);
        if (removed) URL.revokeObjectURL(removed.url);
    } else if (event.target.closest("[data-remove-video]")) {
        URL.revokeObjectURL(video.url);
        video = null;
    } else {
        return;
    }
    clearError("images");
    clearError("video");
    renderMedia();
    els.addImage.focus();
});

// ---------------------------------------------------------------- errors

function showError(field, message) {
    const box = $(`#${field}-error`);
    if (box) {
        box.textContent = message;
        box.hidden = false;
    }
    if (field === "content") els.content.setAttribute("aria-invalid", "true");
}

function clearError(field) {
    const box = $(`#${field}-error`);
    if (box) box.hidden = true;
    if (field === "content") els.content.removeAttribute("aria-invalid");
}

function formError(message) {
    els.formError.textContent = message;
    els.formError.hidden = false;
    els.formError.focus();
}

// ---------------------------------------------------------------- send

els.form.addEventListener("submit", async (event) => {
    event.preventDefault();
    if (submitting) return;
    els.formError.hidden = true;
    const text = els.content.value.trim();
    let ok = true;
    if (!rating) {
        showError("rating", "Hãy chọn số sao (1 đến 5).");
        ok = false;
    }
    if (!text) {
        showError("content", "Hãy viết vài dòng về sự kiện.");
        ok = false;
    }
    if (processingImages) {
        showError("images", "Ảnh đang được xử lý, chờ một chút rồi đăng.");
        ok = false;
    }
    if (!ok) return;

    const body = editing()
        ? { rating, content: text, keepMediaIds: existing.map((m) => m.id) }
        : { rating, content: text };
    const data = new FormData();
    data.append("review", new Blob([JSON.stringify(body)], { type: "application/json" }));
    images.forEach((img, i) => data.append("images", img.blob, `photo-${i + 1}.jpg`));
    if (video) data.append("video", video.file, video.file.name);

    submitting = true;
    els.submit.disabled = true;
    try {
        if (editing()) {
            await apiClient.updateEventReview(form.review.id, data);
            location.href = `${detailPath}?edited=1`;
        } else {
            await apiClient.createEventReview(eventId, data);
            location.href = `${detailPath}?created=1`;
        }
    } catch (error) {
        if (error.status === 401) {
            formError("Phiên đăng nhập đã hết. Hãy đăng nhập lại rồi gửi lần nữa.");
            els.formError.innerHTML = `Phiên đăng nhập đã hết. <a class="link" href="${esc(loginUrl())}">Đăng nhập</a> lại rồi gửi lần nữa.`;
        } else {
            const fields = error.fields || {};
            Object.entries(fields).forEach(([field, message]) => showError(field, message));
            formError(error.message || "Có lỗi xảy ra, vui lòng thử lại.");
        }
    } finally {
        submitting = false;
        els.submit.disabled = false;
    }
});

await start();
