// =========================================================
// ASVSJ - Admin Dashboard Controller
// =========================================================
import { initAdminPage, formatDateTime } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

async function main() {
    const user = await initAdminPage("dashboard");
    if (!user) return;

    const mainEl = document.getElementById("adminMainContent");
    mainEl.innerHTML = `<p class="note">Đang tải dữ liệu tổng quan…</p>`;

    try {
        const stats = await adminClient.getStats();
        renderDashboard(mainEl, stats, user);
    } catch (err) {
        mainEl.innerHTML = `
            <div class="alert error">
                ${ic("error")} Có lỗi khi tải dữ liệu thống kê: ${esc(err.message)}
            </div>
            <button class="btn sm" id="retryBtn">${ic("refresh")} Thử lại</button>
        `;
        document.getElementById("retryBtn")?.addEventListener("click", main);
    }
}

function renderDashboard(container, stats, user) {
    const isModFull = stats.moderatorCount >= stats.maxModerators;

    container.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Tổng quan hệ thống</h2>
                <p>Xin chào <b>${esc(user.displayName)}</b>. Đây là bảng theo dõi tổng quan các chỉ số tài khoản và hoạt động hệ thống.</p>
            </div>
            <div>
                <a href="/pages/admin/users.html" class="btn sm pri">${ic("person_add")} Quản lý người dùng</a>
            </div>
        </div>

        ${isModFull ? `
            <div class="info" style="background: var(--lantern-soft); border-left: 4px solid var(--lantern);">
                ${ic("info")} <b>Lưu ý chỉ tiêu Moderator:</b> Số lượng Moderator đang hoạt động đã đạt giới hạn tối đa 
                (<b>${stats.moderatorCount}/${stats.maxModerators}</b> tài khoản). Hệ thống sẽ không cho phép thăng cấp thêm Moderator trừ khi nâng mức trần cấu hình.
            </div>
        ` : ""}

        <div class="admin-stats-grid">
            <div class="admin-stat-card">
                <div class="admin-stat-icon blue">${ic("groups")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.totalUsers}</span>
                    <span class="lbl">Tổng tài khoản</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon green">${ic("person")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.userRoleCount}</span>
                    <span class="lbl">Người dùng (Kouhai / Mentor)</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon amber">${ic("verified_user")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.moderatorCount} / ${stats.maxModerators}</span>
                    <span class="lbl">Moderator (Tối đa ${stats.maxModerators})</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon" style="background:#E0F2FE; color:#0284C7;">${ic("school")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.academicManagerCount}</span>
                    <span class="lbl">QL Học thuật</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon" style="background:#F3E8FF; color:#7E22CE;">${ic("shield_person")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.adminCount}</span>
                    <span class="lbl">Quản trị viên</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon red">${ic("lock")}</div>
                <div class="admin-stat-info">
                    <span class="num" style="${stats.lockedUsers > 0 ? 'color:var(--crit);' : ''}">${stats.lockedUsers}</span>
                    <span class="lbl">Tài khoản bị khóa</span>
                </div>
            </div>

            <div class="admin-stat-card">
                <div class="admin-stat-icon" style="background:#FEF3C7; color:#B45309;">${ic("flag")}</div>
                <div class="admin-stat-info">
                    <span class="num">${stats.falseReportUsersCount}</span>
                    <span class="lbl">Vi phạm báo cáo sai</span>
                </div>
            </div>
        </div>

        <div class="admin-card-table">
            <div class="admin-table-header-bar">
                <h3>${ic("history")} Nhật ký kiểm toán gần đây (10 hoạt động mới nhất)</h3>
                <a href="/pages/admin/activity-log.html" class="link">Xem tất cả nhật ký &rarr;</a>
            </div>
            <div class="admin-table-responsive">
                <table class="admin-table">
                    <thead>
                        <tr>
                            <th style="width: 170px;">Thời gian</th>
                            <th style="width: 200px;">Người thực hiện</th>
                            <th style="width: 180px;">Hành động</th>
                            <th>Mô tả chi tiết</th>
                        </tr>
                    </thead>
                    <tbody>
                        ${stats.recentLogs && stats.recentLogs.length > 0 ? stats.recentLogs.map(log => `
                            <tr>
                                <td><span class="note">${esc(formatDateTime(log.createdAt))}</span></td>
                                <td>
                                    <b>${esc(log.actorName)}</b>
                                    <div class="note" style="font-size:12px;">${esc(log.actorEmail)}</div>
                                </td>
                                <td><span class="badge ${getActionBadgeClass(log.action)}">${esc(formatAction(log.action))}</span></td>
                                <td>${esc(log.description || "-")}</td>
                            </tr>
                        `).join("") : `
                            <tr>
                                <td colspan="4" style="text-align:center; padding: 32px;" class="note">
                                    Chưa có nhật ký hoạt động nào được ghi nhận.
                                </td>
                            </tr>
                        `}
                    </tbody>
                </table>
            </div>
        </div>
    `;
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
