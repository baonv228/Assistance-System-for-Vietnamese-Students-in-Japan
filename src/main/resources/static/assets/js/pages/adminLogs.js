// =========================================================
// ASVSJ - Admin Activity Log Controller
// =========================================================
import { initAdminPage, formatDateTime, showModal } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let state = {
    page: 1,
    size: 20,
    action: ""
};

async function main() {
    const user = await initAdminPage("logs");
    if (!user) return;

    const mainEl = document.getElementById("adminMainContent");
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Nhật ký hoạt động kiểm toán</h2>
                <p>Theo dõi chi tiết các thao tác quản trị viên và hệ thống thực hiện trên toàn hệ thống (Ai làm gì, lúc nào).</p>
            </div>
        </div>

        <div class="admin-filter-bar">
            <select id="logActionFilter" class="admin-filter-select" style="min-width: 260px;">
                <option value="">Tất cả loại hành động</option>
                <option value="USER_ROLE_CHANGE">Đổi vai trò người dùng (USER_ROLE_CHANGE)</option>
                <option value="USER_STATUS_CHANGE">Đổi trạng thái tài khoản (USER_STATUS_CHANGE)</option>
                <option value="SETTING_UPDATE">Cập nhật tham số cấu hình (SETTING_UPDATE)</option>
                <option value="KEYWORD_ADD">Thêm từ khóa cấm (KEYWORD_ADD)</option>
                <option value="KEYWORD_DELETE">Xóa từ khóa cấm (KEYWORD_DELETE)</option>
            </select>

            <button type="button" class="btn sm" id="refreshLogsBtn">
                ${ic("refresh")} Làm mới
            </button>
        </div>

        <div class="admin-card-table" id="logsTableContainer">
            <p class="note" style="padding: 24px;">Đang tải nhật ký kiểm toán…</p>
        </div>
    `;

    document.getElementById("logActionFilter")?.addEventListener("change", (e) => {
        state.action = e.target.value;
        state.page = 1;
        loadLogs();
    });

    document.getElementById("refreshLogsBtn")?.addEventListener("click", loadLogs);

    await loadLogs();
}

async function loadLogs() {
    const container = document.getElementById("logsTableContainer");
    if (!container) return;

    try {
        const result = await adminClient.getLogs(state);
        renderLogsTable(container, result);
    } catch (err) {
        container.innerHTML = `
            <div class="alert error" style="margin: 16px;">
                ${ic("error")} Lỗi khi tải nhật ký: ${esc(err.message)}
            </div>
        `;
    }
}

function renderLogsTable(container, pageResult) {
    const { items, page, total, totalPages } = pageResult;

    let rowsHtml = "";
    if (items.length === 0) {
        rowsHtml = `
            <tr>
                <td colspan="6" style="text-align:center; padding: 40px;" class="note">
                    Không tìm thấy bản ghi nhật ký hoạt động nào.
                </td>
            </tr>
        `;
    } else {
        rowsHtml = items.map(log => {
            const hasDetail = (log.oldValue && log.oldValue !== "null") || (log.newValue && log.newValue !== "null");
            return `
                <tr>
                    <td><span class="note">${esc(formatDateTime(log.createdAt))}</span></td>
                    <td>
                        <b>${esc(log.actorName)}</b>
                        <div class="note" style="font-size:12px;">${esc(log.actorEmail)}</div>
                    </td>
                    <td><span class="badge ${getActionBadgeClass(log.action)}">${esc(formatAction(log.action))}</span></td>
                    <td><code>${esc(log.targetType || "-")}${log.targetId ? ` #${log.targetId}` : ""}</code></td>
                    <td>${esc(log.description || "-")}</td>
                    <td>
                        ${hasDetail ? `
                            <button type="button" class="btn sm" data-view-diff="${log.id}" data-log="${esc(JSON.stringify(log))}">
                                ${ic("visibility")} Chi tiết
                            </button>
                        ` : '<span class="note">-</span>'}
                    </td>
                </tr>
            `;
        }).join("");
    }

    container.innerHTML = `
        <div class="admin-table-responsive">
            <table class="admin-table">
                <thead>
                    <tr>
                        <th style="width: 160px;">Thời gian</th>
                        <th style="width: 180px;">Người thực hiện</th>
                        <th style="width: 150px;">Hành động</th>
                        <th style="width: 130px;">Đối tượng</th>
                        <th>Nội dung mô tả</th>
                        <th style="width: 110px;">Dữ liệu</th>
                    </tr>
                </thead>
                <tbody>
                    ${rowsHtml}
                </tbody>
            </table>
        </div>

        <div class="admin-pagination">
            <span>Hiển thị <b>${items.length}</b> / tổng số <b>${total}</b> bản ghi nhật ký (Trang ${page}/${totalPages || 1})</span>
            <div class="pagination-controls">
                <button type="button" class="btn sm pagination-btn" id="prevPageBtn" ${page <= 1 ? 'disabled' : ''}>
                    ${ic("chevron_left")} Trước
                </button>
                <button type="button" class="btn sm pagination-btn" id="nextPageBtn" ${page >= totalPages ? 'disabled' : ''}>
                    Sau ${ic("chevron_right")}
                </button>
            </div>
        </div>
    `;

    document.getElementById("prevPageBtn")?.addEventListener("click", () => {
        if (state.page > 1) {
            state.page--;
            loadLogs();
        }
    });

    document.getElementById("nextPageBtn")?.addEventListener("click", () => {
        if (state.page < totalPages) {
            state.page++;
            loadLogs();
        }
    });

    container.querySelectorAll("[data-view-diff]").forEach(btn => {
        btn.addEventListener("click", () => {
            const log = JSON.parse(btn.getAttribute("data-log"));
            openDiffModal(log);
        });
    });
}

function openDiffModal(log) {
    const bodyHtml = `
        <div class="field">
            <label>Thời gian:</label>
            <div>${esc(formatDateTime(log.createdAt))}</div>
        </div>
        <div class="field">
            <label>Người thực hiện:</label>
            <div><b>${esc(log.actorName)}</b> (${esc(log.actorEmail)})</div>
        </div>
        <div class="field">
            <label>Mô tả:</label>
            <div style="overflow-wrap:anywhere;">${esc(log.description)}</div>
        </div>
        ${log.oldValue ? `
            <div class="field">
                <label>Dữ liệu trước thay đổi (Old Value):</label>
                <pre style="background:var(--surface-2); padding:10px; border-radius:8px; overflow:auto; font-size:12.5px;"><code>${esc(formatJson(log.oldValue))}</code></pre>
            </div>
        ` : ''}
        ${log.newValue ? `
            <div class="field">
                <label>Dữ liệu sau thay đổi (New Value):</label>
                <pre style="background:var(--brand-soft); color:var(--brand-text); padding:10px; border-radius:8px; overflow:auto; font-size:12.5px;"><code>${esc(formatJson(log.newValue))}</code></pre>
            </div>
        ` : ''}
    `;

    showModal({
        title: `Chi tiết nhật ký kiểm toán #${log.id}`,
        bodyHtml,
        confirmText: "Đóng",
        confirmClass: "pri",
        onConfirm: () => false
    });
}

function formatJson(str) {
    try {
        const obj = JSON.parse(str);
        return JSON.stringify(obj, null, 2);
    } catch {
        return str;
    }
}

function formatAction(action) {
    switch (action) {
        case "USER_ROLE_CHANGE": return "Đổi vai trò";
        case "USER_STATUS_CHANGE": return "Đổi trạng thái";
        case "SETTING_UPDATE": return "Sửa cấu hình";
        case "KEYWORD_ADD": return "Thêm từ cấm";
        case "KEYWORD_DELETE": return "Xóa từ cấm";
        default: return action;
    }
}

function getActionBadgeClass(action) {
    switch (action) {
        case "USER_ROLE_CHANGE": return "br";
        case "USER_STATUS_CHANGE": return "warn";
        case "SETTING_UPDATE": return "lan";
        case "KEYWORD_ADD": return "ok";
        case "KEYWORD_DELETE": return "crit";
        default: return "";
    }
}

main();
