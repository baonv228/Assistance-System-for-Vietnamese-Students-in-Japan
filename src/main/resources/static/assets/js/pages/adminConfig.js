// =========================================================
// ASVSJ - Admin System Config & Banned Keywords Controller
// =========================================================
import { initAdminPage, formatDateTime, showModal, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let currentTab = "settings"; // "settings" | "keywords"
let allSettings = [];
let allKeywords = [];
let keywordSearch = "";

async function main() {
    const user = await initAdminPage("config");
    if (!user) return;

    const mainEl = document.getElementById("adminMainContent");
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Cấu hình hệ thống & Bộ lọc từ khóa</h2>
                <p>Điều chỉnh các tham số vận hành của hệ thống và quản lý danh sách từ khóa cấm kiểm duyệt tự động.</p>
            </div>
            <div style="display:flex; gap:10px;">
                <button type="button" class="btn sm ${currentTab === 'settings' ? 'pri' : ''}" id="tabSettingsBtn">
                    ${ic("tune")} Tham số hệ thống
                </button>
                <button type="button" class="btn sm ${currentTab === 'keywords' ? 'pri' : ''}" id="tabKeywordsBtn">
                    ${ic("block")} Từ khóa cấm
                </button>
            </div>
        </div>

        <div id="tabContentArea">
            <p class="note">Đang tải dữ liệu…</p>
        </div>
    `;

    document.getElementById("tabSettingsBtn")?.addEventListener("click", () => switchTab("settings"));
    document.getElementById("tabKeywordsBtn")?.addEventListener("click", () => switchTab("keywords"));

    await loadCurrentTab();
}

function switchTab(tab) {
    if (currentTab === tab) return;
    currentTab = tab;

    const btnSettings = document.getElementById("tabSettingsBtn");
    const btnKeywords = document.getElementById("tabKeywordsBtn");

    if (tab === "settings") {
        btnSettings?.classList.add("pri");
        btnKeywords?.classList.remove("pri");
    } else {
        btnKeywords?.classList.add("pri");
        btnSettings?.classList.remove("pri");
    }

    loadCurrentTab();
}

async function loadCurrentTab() {
    const area = document.getElementById("tabContentArea");
    if (!area) return;

    if (currentTab === "settings") {
        area.innerHTML = `<p class="note">Đang tải tham số cấu hình…</p>`;
        try {
            allSettings = await adminClient.getSettings();
            renderSettingsTab(area, allSettings);
        } catch (err) {
            area.innerHTML = `<div class="alert error">${ic("error")} Lỗi tải cấu hình: ${esc(err.message)}</div>`;
        }
    } else {
        area.innerHTML = `<p class="note">Đang tải danh mục từ khóa cấm…</p>`;
        try {
            allKeywords = await adminClient.getKeywords(keywordSearch);
            renderKeywordsTab(area, allKeywords);
        } catch (err) {
            area.innerHTML = `<div class="alert error">${ic("error")} Lỗi tải từ khóa cấm: ${esc(err.message)}</div>`;
        }
    }
}

// -------------------------------------------------------------
// TAB 1: Cấu hình hệ thống (system_settings)
// -------------------------------------------------------------
function renderSettingsTab(container, settings) {
    // Nhóm settings theo group
    const groups = {};
    settings.forEach(s => {
        const g = s.group || "Khác";
        if (!groups[g]) groups[g] = [];
        groups[g].push(s);
    });

    let html = `
        <div class="info" style="margin-bottom: 16px;">
            ${ic("lightbulb")} Tham số đang áp dụng có hiệu lực ngay từ thao tác kế tiếp trên hệ thống và mỗi lần sửa được ghi lại trong nhật ký kiểm toán.
            Tham số <b>Chưa áp dụng</b> được giữ sẵn cho các chức năng sẽ làm sau: hiện hệ thống chưa dùng đến nên chưa sửa được.
        </div>
    `;

    for (const [groupName, items] of Object.entries(groups)) {
        html += `
            <div class="config-group-card">
                <div class="config-group-title">
                    ${ic(getGroupIcon(groupName))} ${esc(groupName)} (${items.length} tham số)
                </div>
                <div class="admin-table-responsive">
                    <table class="admin-table">
                        <thead>
                            <tr>
                                <th style="width: 250px;">Tên tham số</th>
                                <th style="width: 180px;">Khóa (Key)</th>
                                <th style="width: 140px;">Giá trị hiện tại</th>
                                <th>Mục đích & Mô tả</th>
                                <th style="width: 110px;">Thao tác</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${items.map(item => `
                                <tr>
                                    <td><b>${esc(item.label)}</b></td>
                                    <td><code>${esc(item.settingKey)}</code></td>
                                    <td>
                                        <span class="badge" style="font-size:14px; font-weight:700; color:var(--brand-text); background:var(--brand-soft);">
                                            ${esc(item.settingValue)}
                                        </span>
                                    </td>
                                    <td><span class="note">${esc(item.description || "-")}</span></td>
                                    <td>
                                        ${item.wired ? `
                                            <button type="button" class="btn sm" data-edit-setting="${esc(item.settingKey)}"
                                                    data-meta="${esc(JSON.stringify(item))}">
                                                ${ic("edit")} Sửa
                                            </button>
                                        ` : `
                                            <span class="badge" title="Hệ thống chưa dùng tham số này">Chưa áp dụng</span>
                                        `}
                                    </td>
                                </tr>
                            `).join("")}
                        </tbody>
                    </table>
                </div>
            </div>
        `;
    }

    container.innerHTML = html;

    container.querySelectorAll("[data-edit-setting]").forEach(btn => {
        btn.addEventListener("click", () => {
            const item = JSON.parse(btn.getAttribute("data-meta"));
            openEditSettingModal(item);
        });
    });
}

function openEditSettingModal(item) {
    const bodyHtml = `
        <div class="field">
            <label>Tham số:</label>
            <div style="font-weight:700; font-size:15px; color:var(--ink);">${esc(item.label)}</div>
            <div class="note">Mã kỹ thuật: <code>${esc(item.settingKey)}</code></div>
        </div>

        <div class="field">
            <label for="settingValueInput">Giá trị mới:</label>
            <input type="text" id="settingValueInput" class="admin-search-input" inputmode="numeric"
                   value="${esc(item.settingValue)}">
            <div class="note" style="margin-top:4px;">${esc(item.description)}</div>
            <div class="note" style="margin-top:4px;">Giá trị cho phép: số nguyên từ <b>${esc(item.min)}</b> đến <b>${esc(item.max)}</b>.</div>
        </div>
    `;

    showModal({
        title: `Cập nhật cấu hình: ${item.label}`,
        bodyHtml,
        confirmText: "Lưu thay đổi",
        confirmClass: "pri",
        onConfirm: async () => {
            const newVal = document.getElementById("settingValueInput").value.trim();
            if (!newVal) {
                toast("Giá trị không được để trống", "error");
                return true;
            }
            if (newVal === item.settingValue) {
                toast("Giá trị không có sự thay đổi", "info");
                return false;
            }

            await adminClient.updateSetting(item.settingKey, newVal);
            toast(`Đã cập nhật ${item.label} thành "${newVal}"`, "success");
            await loadCurrentTab();
            return false;
        }
    });
}

function getGroupIcon(group) {
    switch (group) {
        case "Báo cáo & Kiểm duyệt": return "flag";
        case "Giới hạn hoạt động": return "speed";
        case "Quản trị & Bảo mật": return "security";
        case "Đề xuất & Bản đồ": return "map";
        default: return "settings";
    }
}

// -------------------------------------------------------------
// TAB 2: Từ khóa cấm (banned_keywords)
// -------------------------------------------------------------
function renderKeywordsTab(container, keywords) {
    container.innerHTML = `
        <div class="admin-filter-bar">
            <div class="admin-search-box">
                ${ic("search")}
                <input type="text" id="keywordSearchInput" class="admin-search-input" 
                       placeholder="Tìm kiếm từ khóa cấm…" value="${esc(keywordSearch)}">
            </div>
            <button type="button" class="btn sm pri" id="addKeywordBtn">
                ${ic("add")} Thêm từ khóa mới
            </button>
        </div>

        <div class="admin-card-table">
            <div class="admin-table-header-bar">
                <h3>${ic("block")} Danh mục từ khóa cấm kiểm duyệt tự động (${keywords.length} từ khóa)</h3>
            </div>
            <div class="admin-table-responsive">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th style="width: 250px;">Từ khóa bị cấm</th>
                            <th style="width: 200px;">Người tạo</th>
                            <th style="width: 200px;">Thời gian tạo</th>
                            <th style="width: 120px;">Thao tác</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${keywords.length > 0 ? keywords.map(kw => `
                            <tr>
                                <td>
                                    <span class="badge crit" style="font-size:14px; padding:4px 12px;">
                                        ${ic("block")} ${esc(kw.keyword)}
                                    </span>
                                </td>
                                <td><b>${esc(kw.creatorName)}</b></td>
                                <td><span class="note">${esc(formatDateTime(kw.createdAt))}</span></td>
                                <td>
                                    <button type="button" class="btn sm dan" data-delete-kw="${kw.id}" 
                                            data-name="${esc(kw.keyword)}" title="Xóa từ khóa khỏi danh mục">
                                        ${ic("delete")} Xóa
                                    </button>
                                </td>
                            </tr>
                        `).join("") : `
                            <tr>
                                <td colspan="4" style="text-align:center; padding: 40px;" class="note">
                                    ${keywordSearch ? 'Không tìm thấy từ khóa cấm phù hợp.' : 'Chưa có từ khóa cấm nào trong danh mục.'}
                                </td>
                            </tr>
                        `}
                    </tbody>
                </table>
            </div>
        </div>
    `;

    let timer = null;
    document.getElementById("keywordSearchInput")?.addEventListener("input", (e) => {
        clearTimeout(timer);
        timer = setTimeout(() => {
            keywordSearch = e.target.value.trim();
            loadCurrentTab();
        }, 300);
    });

    document.getElementById("addKeywordBtn")?.addEventListener("click", openAddKeywordModal);

    container.querySelectorAll("[data-delete-kw]").forEach(btn => {
        btn.addEventListener("click", () => {
            const id = btn.getAttribute("data-delete-kw");
            const name = btn.getAttribute("data-name");
            openDeleteKeywordModal(id, name);
        });
    });
}

function openAddKeywordModal() {
    const bodyHtml = `
        <div class="field">
            <label for="newKeywordInput">Từ hoặc cụm từ bị cấm:</label>
            <input type="text" id="newKeywordInput" class="admin-search-input" 
                   placeholder="Ví dụ: vay nóng, cờ bạc, việc nhẹ lương cao…" autofocus>
            <div class="note">Hệ thống sẽ tự động chuyển từ khóa về chữ thường không phân biệt hoa thường khi kiểm duyệt.</div>
            <div class="note">Từ khóa cần ít nhất 3 chữ cái hoặc chữ số (tiếng Nhật, tiếng Trung: 2 chữ), và không được trùng một từ khóa đã có khi bỏ khoảng trắng và ký hiệu (ví dụ "vay-nóng" trùng "vay nóng").</div>
        </div>
    `;

    showModal({
        title: "Thêm từ khóa cấm mới",
        bodyHtml,
        confirmText: "Thêm từ khóa",
        confirmClass: "pri",
        onConfirm: async () => {
            const raw = document.getElementById("newKeywordInput")?.value.trim();
            if (!raw) {
                toast("Vui lòng nhập từ khóa", "error");
                return true;
            }

            const added = await adminClient.addKeyword(raw);
            toast(`Đã thêm từ khóa cấm: "${added?.keyword ?? raw.toLowerCase()}"`, "success");
            await loadCurrentTab();
            return false;
        }
    });
}

function openDeleteKeywordModal(id, keyword) {
    showModal({
        title: "Xóa từ khóa cấm",
        bodyHtml: `
            <p>Bạn có chắc chắn muốn xóa từ khóa <b>"${esc(keyword)}"</b> khỏi danh mục kiểm duyệt tự động?</p>
            <p class="note">Sau khi xóa, các nội dung chứa từ khóa này sẽ không còn bị bộ lọc tự động gắn cờ nữa.</p>
        `,
        confirmText: "Xóa từ khóa",
        confirmClass: "dan solid",
        onConfirm: async () => {
            await adminClient.deleteKeyword(id);
            toast(`Đã xóa từ khóa cấm "${keyword}"`, "success");
            await loadCurrentTab();
            return false;
        }
    });
}

main();
