// Hai thẻ tham số an toàn ("Giới hạn cho tài khoản mới", "Báo cáo và minh chứng"): sửa nhiều ô, lưu một lần.
import { showModal, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let groups = [];
let original = new Map();   // khóa → giá trị đã lưu gần nhất (chuỗi)
let root = null;
let discarded = false;      // người dùng đã chọn "Bỏ thay đổi" và đang rời trang

/** Số ô đang khác giá trị đã lưu. Trang dùng để cảnh báo khi rời đi mà chưa lưu. */
export function hasUnsavedChanges() {
    return !discarded && changedValues().size > 0;
}

export async function mountSafety(container) {
    root = container;
    groups = await adminClient.getSafetyConfig();
    remember();
    render();
}

function remember() {
    original = new Map(groups.flatMap((g) => g.params).map((p) => [p.key, String(p.value)]));
}

function render() {
    root.innerHTML = `
        <form id="safetyForm" class="admin-panel" novalidate>
            ${groups.map(groupHtml).join("")}
            <div class="admin-savebar">
                <button type="submit" class="btn pri" id="safetySave">${ic("save")} Lưu</button>
                <span class="admin-inline-msg" id="safetyMessage" role="status" hidden></span>
            </div>
        </form>`;
    const form = document.getElementById("safetyForm");
    form.addEventListener("submit", (e) => { e.preventDefault(); save(); });
    form.addEventListener("input", (e) => {
        // Lỗi của ô biến mất khi người dùng sửa ô đó
        if (e.target.matches("input[data-key]")) clearError(e.target);
    });
}

function groupHtml(group) {
    return `
        <fieldset style="border:0; padding:0; margin:0; display:grid; gap:12px;">
            <legend style="padding:0; font-weight:700; font-size:16px; margin-bottom:8px;">${esc(group.title)}</legend>
            <div class="admin-param-grid">${group.params.map(paramHtml).join("")}</div>
        </fieldset>`;
}

function paramHtml(p) {
    const id = `param-${p.key}`;
    if (!p.editable) {
        return `
            <div class="admin-param">
                <label for="${esc(id)}">${esc(p.label)} <span class="badge">Chưa áp dụng</span></label>
                <input id="${esc(id)}" class="admin-input" value="${esc(p.value)}" disabled>
                <p class="note">${esc(p.description)}</p>
            </div>`;
    }
    return `
        <div class="admin-param">
            <label for="${esc(id)}">${esc(p.label)}</label>
            <input id="${esc(id)}" class="admin-input" type="number" step="1" inputmode="numeric"
                   min="${esc(p.min)}" max="${esc(p.max)}" value="${esc(p.value)}" data-key="${esc(p.key)}"
                   aria-describedby="${esc(id)}-help">
            <p class="field-error" id="${esc(id)}-error" role="alert" hidden></p>
            <p class="note" id="${esc(id)}-help">${esc(p.description)} (${esc(p.min)}–${esc(p.max)})</p>
        </div>`;
}

/** Chỉ các ô đã sửa mới được gửi; ô bị xóa trống gửi chuỗi rỗng để máy chủ báo giá trị không hợp lệ. */
function changedValues() {
    const changed = new Map();
    root?.querySelectorAll("input[data-key]").forEach((input) => {
        if (input.value.trim() !== original.get(input.dataset.key)) {
            changed.set(input.dataset.key, input.value.trim());
        }
    });
    return changed;
}

async function save() {
    const button = document.getElementById("safetySave");
    const message = document.getElementById("safetyMessage");
    message.hidden = true;
    clearAllErrors();
    const changed = changedValues();
    if (changed.size === 0) {
        show(message, "Không có thay đổi nào để lưu.", false);
        return;
    }
    button.disabled = true;      // chống bấm đôi
    try {
        const result = await adminClient.saveSafetyConfig(Object.fromEntries(changed));
        groups = result.groups;
        remember();
        render();
        toast("Đã lưu cấu hình chống spam.", "success");
    } catch (err) {
        const fields = err.fields || {};
        const keys = Object.keys(fields);
        if (err.status === 400 && keys.length > 0) {
            keys.forEach((key) => showError(key, fields[key]));
        } else {
            toast("Đã xảy ra lỗi. Vui lòng thử lại sau.", "error");      // ô giữ nguyên nội dung đã nhập
        }
    } finally {
        const again = document.getElementById("safetySave");
        if (again) again.disabled = false;
    }
}

function showError(key, text) {
    const input = document.getElementById(`param-${key}`);
    const error = document.getElementById(`param-${key}-error`);
    if (!input || !error) return;
    input.setAttribute("aria-invalid", "true");
    input.setAttribute("aria-describedby", `param-${key}-error`);
    error.textContent = text;
    error.hidden = false;
}

function clearError(input) {
    input.removeAttribute("aria-invalid");
    const error = document.getElementById(`${input.id}-error`);
    if (error) error.hidden = true;
}

function clearAllErrors() {
    root.querySelectorAll("input[data-key]").forEach(clearError);
}

function show(el, text, isError) {
    el.textContent = text;
    el.classList.toggle("error", isError);
    el.hidden = false;
}

/**
 * Chặn rời trang khi còn thay đổi chưa lưu: click vào liên kết trong trang (kể cả thanh bên) hỏi lại bằng hộp thoại,
 * còn đóng tab/tải lại do trình duyệt tự hỏi (trình duyệt không cho đổi nội dung câu hỏi đó).
 */
export function guardLeavingPage() {
    window.addEventListener("beforeunload", (e) => {
        if (hasUnsavedChanges()) {
            e.preventDefault();
            e.returnValue = "";
        }
    });
    document.addEventListener("click", (e) => {
        const link = e.target.closest?.("a[href]");
        if (!link || e.defaultPrevented || link.target === "_blank" || !hasUnsavedChanges()) return;
        const url = new URL(link.href, location.href);
        if (url.origin !== location.origin) return;
        e.preventDefault();
        showModal({
            title: "Thay đổi chưa lưu",
            bodyHtml: `<p>Bạn có thay đổi chưa lưu. Bạn có muốn bỏ các thay đổi này không?</p>`,
            confirmText: "Bỏ thay đổi",
            confirmClass: "dan solid",
            onConfirm: async () => {
                discarded = true;         // đã chọn bỏ thay đổi: cảnh báo của trình duyệt không hiện lần nữa
                window.location.assign(link.href);
                return true;
            }
        });
    });
}
