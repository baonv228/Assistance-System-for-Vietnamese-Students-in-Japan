// UC-U09 "Xin tag định danh": choose a tag (search by name; tags already held cannot be chosen), the proof type it
// accepts (none for a portrait-only tag such as "Người Nhật"), one photo, and the consent box. A request already
// waiting replaces the form (AF4). The photo is shrunk in the browser before upload, like other photos.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { esc, ic, loginUrl, tagChips } from "../lib/ui.js";
import { isImageFile, MB, shrinkToJpeg, unreadableImage } from "../lib/media-files.js";

const main = document.querySelector("#tag-page");
let compose = null;
let chosen = null;          // the TagOption picked
let photo = null;           // { blob, url }
let busy = false;

await mountSiteHeader("me");

const dateText = (iso) => new Date(iso).toLocaleDateString("vi-VN");

function render() {
    if (compose.pending) {
        const p = compose.pending;
        main.innerHTML = `
            <a class="link" href="/profile">${ic("arrow_back")}Trang Tôi</a>
            <h2>Xin tag định danh</h2>
            <div class="info">${ic("hourglass_top")} Yêu cầu tag <b>${esc(p.tagName)}</b> đang chờ duyệt (gửi ngày ${esc(dateText(p.submittedAt))}).
                Ảnh minh chứng tự xóa sau ${compose.keepDays} ngày nếu chưa được duyệt (ngày ${esc(dateText(p.expiresAt))}).
                Bạn gửi được yêu cầu mới khi có kết quả.</div>`;
        return;
    }
    main.innerHTML = `
        <a class="link" href="/profile">${ic("arrow_back")}Trang Tôi</a>
        <div><h2>Xin tag định danh</h2>
            <p class="note">Tag cho mọi người biết bạn là ai (đang du học, đã đi làm, giáo viên…). Moderator đối chiếu ảnh minh chứng rồi mới cấp tag.</p></div>
        <form class="card tag-form" id="tag-form" method="post" novalidate>
            <div class="alert error" id="tag-error" role="alert" tabindex="-1" hidden></div>
            <div class="field"><label for="tag-search">Chọn tag</label>
                <input class="select" id="tag-search" type="search" placeholder="Tìm tag theo tên" autocomplete="off"></div>
            <div class="tag-options" id="tag-options" role="radiogroup" aria-label="Tag"></div>
            <div id="tag-details"></div>
            <div class="row"><button class="btn pri" type="submit" id="tag-submit">${ic("send")}Gửi yêu cầu</button>
                <a class="btn" href="/profile">Hủy</a></div>
        </form>`;
    renderOptions("");
    renderDetails();
    main.querySelector("#tag-search").addEventListener("input", (event) => renderOptions(event.target.value));
    main.querySelector("#tag-options").addEventListener("change", (event) => {
        chosen = compose.tags.find((t) => String(t.id) === event.target.value) || null;
        renderDetails();
    });
    main.querySelector("#tag-form").addEventListener("submit", submit);
}

/** Tag cards matching the search (accents and case ignored); held tags are shown but cannot be chosen. */
function renderOptions(query) {
    const fold = (s) => s.normalize("NFD").replace(/\p{Diacritic}/gu, "").replace(/đ/gi, "d").toLowerCase();
    const q = fold(query.trim());
    const list = compose.tags.filter((t) => !q || fold(t.name).includes(q));
    main.querySelector("#tag-options").innerHTML = list.map((t) => `
        <label class="tag-option ${t.owned ? "owned" : ""}">
            <input type="radio" name="tag" value="${t.id}" ${t.owned ? "disabled" : ""} ${chosen?.id === t.id ? "checked" : ""}>
            <span>${tagChips([t])}
                ${t.owned ? `<span class="badge ok">${ic("check")}Đã có</span>` : ""}
                <br><span class="note">${esc(t.description)}</span></span>
        </label>`).join("") || `<p class="note">Không có tag nào khớp.</p>`;
}

function renderDetails() {
    const box = main.querySelector("#tag-details");
    if (!chosen) {
        box.innerHTML = "";
        return;
    }
    const selfie = chosen.selfieOnly;
    box.innerHTML = `
        ${selfie ? `<p class="note">${ic("face")} Tag này chỉ cần một ảnh chân dung của bạn.</p>`
            : `<div class="field"><label for="tag-proof">Loại minh chứng</label>
                <select class="select" id="tag-proof"><option value="">Chọn loại minh chứng</option>
                    ${chosen.proofTypes.map((p) => `<option value="${p.id}">${esc(p.name)}</option>`).join("")}</select></div>`}
        <div class="field"><b>${selfie ? "Ảnh chân dung" : "Ảnh minh chứng"}</b>
            <div class="row"><button class="btn sm" type="button" id="tag-pick">${ic("add_photo_alternate")}Chọn ảnh</button>
                <input class="sr-only" type="file" id="tag-file" accept="image/jpeg,image/png,image/heic,image/heif,.jpg,.jpeg,.png,.heic,.heif" tabindex="-1" aria-hidden="true">
                <span class="note">JPG, PNG hoặc HEIC, tối đa ${compose.photoMaxMb} MB.</span></div>
            ${photo ? `<img class="tag-preview" src="${photo.url}" alt="Ảnh đã chọn">` : ""}
            ${selfie ? "" : `<p class="note">Có thể che số thẻ và thông tin không cần thiết, chỉ cần thấy tên và loại giấy tờ.</p>`}</div>
        <label class="check"><input type="checkbox" id="tag-consent">
            Tôi đồng ý cho Kiểm duyệt viên xem ảnh này. Ảnh sẽ bị xóa sau khi duyệt hoặc sau ${compose.keepDays} ngày.</label>`;
    box.querySelector("#tag-pick").addEventListener("click", () => box.querySelector("#tag-file").click());
    box.querySelector("#tag-file").addEventListener("change", (event) => pickPhoto(event.target.files[0]));
}

async function pickPhoto(file) {
    if (!file) return;
    if (!isImageFile(file) || file.size > compose.photoMaxMb * MB) {
        return showError("Ảnh phải là JPG, PNG hoặc HEIC và không quá 20 MB.");
    }
    // "Gửi yêu cầu" waits until the photo is ready, so a quick click never sends the form without it
    const submitButton = main.querySelector("#tag-submit");
    submitButton.disabled = true;
    try {
        const blob = await shrinkToJpeg(file);
        if (photo) URL.revokeObjectURL(photo.url);
        photo = { blob, url: URL.createObjectURL(blob) };
        const proof = main.querySelector("#tag-proof")?.value;
        const consent = main.querySelector("#tag-consent").checked;
        renderDetails();
        if (proof) main.querySelector("#tag-proof").value = proof;
        main.querySelector("#tag-consent").checked = consent;
    } catch {
        showError(unreadableImage(file));
    } finally {
        submitButton.disabled = false;
    }
}

async function submit(event) {
    event.preventDefault();
    if (busy) return;
    const form = new FormData();
    form.append("request", new Blob([JSON.stringify({ tagId: chosen?.id ?? null,
        proofTypeId: Number(main.querySelector("#tag-proof")?.value) || null,
        consent: main.querySelector("#tag-consent")?.checked ?? false })], { type: "application/json" }));
    if (photo) form.append("photo", photo.blob, "proof.jpg");
    busy = true;
    main.querySelector("#tag-submit").disabled = true;
    try {
        const done = await apiClient.submitTagRequest(form);
        compose.pending = done.pending;
        render();
    } catch (error) {
        showError([...new Set(Object.values(error.fields || {}))].join(" ") || error.message);
    } finally {
        busy = false;
        const button = main.querySelector("#tag-submit");
        if (button) button.disabled = false;
    }
}

function showError(message) {
    const box = main.querySelector("#tag-error");
    box.textContent = message;
    box.hidden = false;
    box.focus();
}

try {
    compose = await apiClient.tagCompose();
    render();
} catch (error) {
    if (error.status === 401) {
        window.location.replace(loginUrl());
    } else {
        main.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
    }
}
