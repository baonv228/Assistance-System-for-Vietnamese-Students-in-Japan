// Thẻ "Từ khóa cấm": danh sách huy hiệu đỏ, thêm, sửa tại chỗ và xóa.
import { toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let root = null;
let keywords = [];
let editingId = null;

export async function mountKeywords(container) {
    root = container;
    await reload();
}

async function reload() {
    keywords = await adminClient.getKeywords();
    // Danh sách hiển thị theo thứ tự tạo, từ khóa mới nằm cuối
    keywords.sort((a, b) => a.id - b.id);
    render();
}

function render() {
    root.innerHTML = `
        <section class="admin-panel" aria-labelledby="kwTitle">
            <h3 id="kwTitle">${ic("block")} Từ khóa cấm</h3>
            <form class="admin-kw-form" id="kwForm" novalidate>
                <div class="field">
                    <label for="kwInput">Thêm từ khóa</label>
                    <input id="kwInput" class="admin-input" maxlength="100" autocomplete="off"
                           placeholder="Ví dụ: vay nóng, cá độ, visa nhanh…">
                    <p class="field-error" id="kwError" role="alert" hidden></p>
                </div>
                <button type="submit" class="btn pri" id="kwAdd" style="margin-top:24px;">${ic("add")} Thêm</button>
            </form>
            <p class="note" style="margin:0;">Chữ hoa/thường và dấu tiếng Việt không ảnh hưởng khi lọc. Chỉ nội dung gửi sau khi
                lưu mới bị chặn; bài đã đăng không bị quét lại.</p>
            ${keywords.length === 0 ? `<p class="note">Chưa có từ khóa cấm nào.</p>` : `
            <ul class="admin-kw-list">${keywords.map(itemHtml).join("")}</ul>`}
        </section>`;
    document.getElementById("kwForm").addEventListener("submit", (e) => { e.preventDefault(); add(); });
    document.getElementById("kwInput").addEventListener("input", clearError);
    root.querySelectorAll("[data-edit]").forEach((b) => b.addEventListener("click", () => startEdit(Number(b.dataset.edit))));
    root.querySelectorAll("[data-del]").forEach((b) => b.addEventListener("click", () => remove(Number(b.dataset.del))));
    root.querySelectorAll("[data-edit-form]").forEach((f) => f.addEventListener("submit", (e) => {
        e.preventDefault();
        saveEdit(Number(f.dataset.editForm), f.querySelector("input").value);
    }));
    root.querySelectorAll("[data-cancel]").forEach((b) => b.addEventListener("click", () => { editingId = null; render(); }));
    root.querySelector("[data-edit-form] input")?.focus();
}

function itemHtml(kw) {
    if (kw.id === editingId) {
        return `
            <li><form class="admin-kw-edit" data-edit-form="${esc(kw.id)}" novalidate>
                <label class="sr-only" for="kwEdit${esc(kw.id)}">Sửa từ khóa</label>
                <input id="kwEdit${esc(kw.id)}" class="admin-input" maxlength="100" value="${esc(kw.keyword)}"
                       autocomplete="off">
                <button type="submit" class="btn sm pri" aria-label="Lưu từ khóa">${ic("check")}</button>
                <button type="button" class="btn sm" data-cancel aria-label="Hủy sửa">${ic("close")}</button>
            </form>
            <p class="field-error" id="kwEditError" role="alert" hidden></p></li>`;
    }
    return `
        <li class="admin-kw">
            <button type="button" class="admin-kw-text" data-edit="${esc(kw.id)}" title="Bấm để sửa">${esc(kw.keyword)}</button>
            <button type="button" class="admin-kw-x" data-del="${esc(kw.id)}" aria-label="Xóa từ khóa ${esc(kw.keyword)}">${ic("close")}</button>
        </li>`;
}

async function add() {
    const input = document.getElementById("kwInput");
    const button = document.getElementById("kwAdd");
    const value = input.value.trim();
    clearError();
    if (!value) {
        showError("Hãy nhập một từ khóa hợp lệ.");
        return;
    }
    button.disabled = true;
    try {
        await adminClient.addKeyword(value);
        toast("Đã cập nhật danh sách từ khóa.", "success");
        await reload();
    } catch (err) {
        // Lỗi do người dùng (rỗng, quá ngắn/dài, trùng) hiện dưới ô nhập và giữ chữ đã gõ; lỗi khác là toast
        if (err.status === 400) showError(err.message);
        else toast("Đã xảy ra lỗi. Vui lòng thử lại sau.", "error");
    } finally {
        button.disabled = false;
    }
}

function startEdit(id) {
    editingId = id;
    render();
}

async function saveEdit(id, value) {
    const text = value.trim();
    if (!text) {
        showEditError("Hãy nhập một từ khóa hợp lệ.");
        return;
    }
    try {
        await adminClient.updateKeyword(id, text);
        editingId = null;
        toast("Đã cập nhật danh sách từ khóa.", "success");
        await reload();
    } catch (err) {
        if (err.status === 404) {
            // Admin khác vừa xóa từ khóa này: tải lại danh sách
            editingId = null;
            toast("Từ khóa này không còn trong danh sách.", "error");
            await reload();
        } else if (err.status === 400) {
            showEditError(err.message);
        } else {
            toast("Đã xảy ra lỗi. Vui lòng thử lại sau.", "error");
        }
    }
}

async function remove(id) {
    try {
        await adminClient.deleteKeyword(id);
        toast("Đã cập nhật danh sách từ khóa.", "success");
    } catch (err) {
        // Từ khóa đã bị Admin khác xóa trước: coi như đã xóa, danh sách được tải lại bên dưới
        if (err.status !== 400 && err.status !== 404) {
            toast("Đã xảy ra lỗi. Vui lòng thử lại sau.", "error");
            return;
        }
    }
    await reload();
}

function showError(text) {
    const error = document.getElementById("kwError");
    document.getElementById("kwInput").setAttribute("aria-invalid", "true");
    error.textContent = text;
    error.hidden = false;
}

function clearError() {
    document.getElementById("kwInput")?.removeAttribute("aria-invalid");
    const error = document.getElementById("kwError");
    if (error) error.hidden = true;
}

function showEditError(text) {
    const error = document.getElementById("kwEditError");
    if (!error) return;
    error.textContent = text;
    error.hidden = false;
}
