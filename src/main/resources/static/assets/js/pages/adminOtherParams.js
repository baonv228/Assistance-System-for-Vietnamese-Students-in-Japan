// Thẻ "Tham số hệ thống khác": các cài đặt ngoài hai thẻ chống spam (đăng nhập, mã xác thực, bài nổi bật…).
// Mỗi tham số sửa riêng bằng hộp thoại; tham số chưa được hệ thống đọc chỉ xem, ghi "Chưa áp dụng".
import { formatDateTime, showModal, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let root = null;
let shownElsewhere = new Set();

/** @param hiddenKeys khóa đã nằm ở hai thẻ chống spam, không lặp lại ở đây */
export async function mountOtherParams(container, hiddenKeys) {
    root = container;
    shownElsewhere = new Set(hiddenKeys);
    await reload();
}

async function reload() {
    const all = await adminClient.getSettings();
    render(all.filter((s) => !shownElsewhere.has(s.settingKey)));
}

function render(settings) {
    const byGroup = new Map();
    settings.forEach((s) => {
        const group = s.group || "Khác";
        if (!byGroup.has(group)) byGroup.set(group, []);
        byGroup.get(group).push(s);
    });
    root.innerHTML = `
        <section class="admin-panel" aria-labelledby="otherTitle">
            <h3 id="otherTitle">${ic("settings")} Tham số hệ thống khác</h3>
            <p class="note" style="margin:0;">Tham số đang áp dụng có hiệu lực từ thao tác kế tiếp và mỗi lần sửa được ghi vào nhật ký.</p>
            ${[...byGroup].map(([name, items]) => groupHtml(name, items)).join("")}
        </section>`;
    root.querySelectorAll("[data-edit-setting]").forEach((btn) => btn.addEventListener("click", () => {
        const item = settings.find((s) => s.settingKey === btn.dataset.editSetting);
        if (item) openEdit(item);
    }));
}

function groupHtml(name, items) {
    return `
        <div class="admin-table-responsive">
            <table class="admin-table">
                <caption style="text-align:left; font-weight:700; padding:8px 0;">${esc(name)}</caption>
                <thead><tr><th>Tham số</th><th style="width:110px;">Giá trị</th><th>Mô tả</th><th style="width:130px;">Thao tác</th></tr></thead>
                <tbody>${items.map((item) => `
                    <tr>
                        <td><b>${esc(item.label)}</b><div class="note"><code>${esc(item.settingKey)}</code>
                            · cập nhật ${esc(formatDateTime(item.updatedAt))}</div></td>
                        <td><span class="badge br"><b>${esc(item.settingValue)}</b></span></td>
                        <td><span class="note">${esc(item.description || "-")}</span></td>
                        <td>${item.wired
                            ? `<button type="button" class="btn sm" data-edit-setting="${esc(item.settingKey)}">${ic("edit")} Sửa</button>`
                            : `<span class="badge" title="Hệ thống chưa dùng tham số này">Chưa áp dụng</span>`}</td>
                    </tr>`).join("")}
                </tbody>
            </table>
        </div>`;
}

function openEdit(item) {
    showModal({
        title: `Sửa: ${item.label}`,
        bodyHtml: `
            <div class="field">
                <label for="settingValueInput">Giá trị mới</label>
                <input id="settingValueInput" class="admin-input" inputmode="numeric" value="${esc(item.settingValue)}">
                <p class="note" style="margin:0;">Số nguyên từ <b>${esc(item.min)}</b> đến <b>${esc(item.max)}</b>.</p>
            </div>`,
        confirmText: "Lưu thay đổi",
        confirmClass: "pri",
        onConfirm: async () => {
            const value = document.getElementById("settingValueInput").value.trim();
            if (value === item.settingValue) {
                toast("Không có thay đổi nào để lưu.", "info");
                return false;
            }
            await adminClient.updateSetting(item.settingKey, value);    // lỗi: toast do hộp thoại hiện, hộp thoại giữ nguyên
            toast("Đã lưu cấu hình.", "success");
            await reload();
            return false;
        }
    });
}
