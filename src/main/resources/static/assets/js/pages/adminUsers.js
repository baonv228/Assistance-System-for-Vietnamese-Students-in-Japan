// =========================================================
// ASVSJ - Admin Users & Roles Controller
// =========================================================
import { initAdminPage, formatDateTime, showModal, closeModal, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";

let state = {
    keyword: "",
    role: "",
    status: "",
    page: 1,
    size: 15
};

let loggedInUser = null;

async function main() {
    loggedInUser = await initAdminPage("users");
    if (!loggedInUser) return;

    const mainEl = document.getElementById("adminMainContent");
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Quản lý người dùng & Phân quyền</h2>
                <p>Tra cứu tài khoản, điều chỉnh vai trò quản trị và khóa/mở khóa tài khoản người dùng.</p>
            </div>
        </div>

        <div class="admin-filter-bar">
            <div class="admin-search-box">
                ${ic("search")}
                <input type="text" id="userSearchInput" class="admin-search-input" 
                       placeholder="Tìm kiếm theo tên hoặc email…" value="${esc(state.keyword)}">
            </div>

            <select id="userRoleFilter" class="admin-filter-select">
                <option value="">Tất cả vai trò</option>
                <option value="USER">Người dùng (USER)</option>
                <option value="MODERATOR">Moderator (Tối đa 3)</option>
                <option value="ACADEMIC_MANAGER">QL Học thuật</option>
                <option value="ADMIN">Quản trị viên (ADMIN)</option>
            </select>

            <select id="userStatusFilter" class="admin-filter-select">
                <option value="">Tất cả trạng thái</option>
                <option value="ACTIVE">Hoạt động (ACTIVE)</option>
                <option value="LOCKED">Đã khóa (LOCKED)</option>
                <option value="DELETED">Đã xóa (DELETED)</option>
            </select>

            <button type="button" class="btn sm" id="resetFilterBtn">${ic("restart_alt")} Đặt lại</button>
        </div>

        <div class="admin-card-table" id="usersTableContainer">
            <p class="note" style="padding: 24px;">Đang tải danh sách người dùng…</p>
        </div>
    `;

    bindEvents();
    await loadUsers();
}

function bindEvents() {
    let debounceTimer = null;
    const searchInput = document.getElementById("userSearchInput");
    searchInput?.addEventListener("input", (e) => {
        clearTimeout(debounceTimer);
        debounceTimer = setTimeout(() => {
            state.keyword = e.target.value.trim();
            state.page = 1;
            loadUsers();
        }, 300);
    });

    document.getElementById("userRoleFilter")?.addEventListener("change", (e) => {
        state.role = e.target.value;
        state.page = 1;
        loadUsers();
    });

    document.getElementById("userStatusFilter")?.addEventListener("change", (e) => {
        state.status = e.target.value;
        state.page = 1;
        loadUsers();
    });

    document.getElementById("resetFilterBtn")?.addEventListener("click", () => {
        state.keyword = "";
        state.role = "";
        state.status = "";
        state.page = 1;
        const input = document.getElementById("userSearchInput");
        if (input) input.value = "";
        const roleSel = document.getElementById("userRoleFilter");
        if (roleSel) roleSel.value = "";
        const statSel = document.getElementById("userStatusFilter");
        if (statSel) statSel.value = "";
        loadUsers();
    });
}

async function loadUsers() {
    const container = document.getElementById("usersTableContainer");
    if (!container) return;

    try {
        const result = await adminClient.getUsers(state);
        renderTable(container, result);
    } catch (err) {
        container.innerHTML = `
            <div class="alert error" style="margin: 16px;">
                ${ic("error")} Lỗi tải danh sách người dùng: ${esc(err.message)}
            </div>
        `;
    }
}

function renderTable(container, pageResult) {
    const { items, page, total, totalPages } = pageResult;

    let rowsHtml = "";
    if (items.length === 0) {
        rowsHtml = `
            <tr>
                <td colspan="7" style="text-align:center; padding: 40px;" class="note">
                    Không tìm thấy người dùng nào phù hợp với điều kiện tìm kiếm.
                </td>
            </tr>
        `;
    } else {
        rowsHtml = items.map(user => {
            const isMe = loggedInUser && loggedInUser.id === user.id;
            const isLocked = user.status === "LOCKED";
            // A deleted account keeps its role and status (the server refuses changes to it)
            const isDeleted = user.status === "DELETED";
            const initial = (user.displayName || "U").charAt(0).toUpperCase();

            return `
                <tr>
                    <td>
                        <div class="user-cell">
                            <div class="user-avatar">
                                ${user.avatarUrl ? `<img src="${esc(user.avatarUrl)}" alt="">` : esc(initial)}
                            </div>
                            <div class="user-meta">
                                <span class="name">
                                    ${esc(user.displayName)}
                                    ${isMe ? '<span class="badge br" style="font-size:11px; margin-left:4px;">Bạn</span>' : ''}
                                </span>
                                <span class="email">${esc(user.email)}</span>
                            </div>
                        </div>
                    </td>
                    <td><span class="badge ${getRoleBadgeClass(user.role)}">${esc(user.roleLabel || user.role)}</span></td>
                    <td><span class="badge ${getStatusBadgeClass(user.status)}">${esc(formatStatus(user.status))}</span></td>
                    <td><b>${user.points}</b> đ</td>
                    <td>
                        ${user.falseReportCount > 0 ? `
                            <span class="badge warn" title="${user.falseReportCount} lần báo cáo sai">${user.falseReportCount}</span>
                        ` : '<span class="note">0</span>'}
                    </td>
                    <td><span class="note">${esc(formatDateTime(user.createdAt))}</span></td>
                    <td>
                        ${isDeleted ? '<span class="note">-</span>' : `
                        <div style="display:flex; gap:6px;">
                            <button type="button" class="btn sm" data-edit-role="${user.id}" 
                                    data-user="${esc(JSON.stringify(user))}"
                                    ${isMe ? 'disabled title="Không thể tự đổi vai trò của chính mình"' : 'title="Phân quyền tài khoản"'}>
                                ${ic("manage_accounts")} Vai trò
                            </button>
                            
                            ${isLocked ? `
                                <button type="button" class="btn sm ok" data-toggle-status="${user.id}" 
                                        data-name="${esc(user.displayName)}" data-next="ACTIVE" title="Mở khóa tài khoản">
                                    ${ic("lock_open")} Mở khóa
                                </button>
                            ` : `
                                <button type="button" class="btn sm dan" data-toggle-status="${user.id}" 
                                        data-name="${esc(user.displayName)}" data-next="LOCKED"
                                        ${isMe ? 'disabled title="Không thể tự khóa tài khoản của chính mình"' : 'title="Khóa tài khoản"'}>
                                    ${ic("lock")} Khóa
                                </button>
                            `}
                        </div>
                        `}
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
                        <th>Người dùng</th>
                        <th style="width: 150px;">Vai trò</th>
                        <th style="width: 120px;">Trạng thái</th>
                        <th style="width: 90px;">Điểm</th>
                        <th style="width: 110px;">Báo cáo sai</th>
                        <th style="width: 150px;">Ngày tham gia</th>
                        <th style="width: 180px;">Thao tác</th>
                    </tr>
                </thead>
                <tbody>
                    ${rowsHtml}
                </tbody>
            </table>
        </div>

        <div class="admin-pagination">
            <span>Hiển thị <b>${items.length}</b> / tổng số <b>${total}</b> tài khoản (Trang ${page}/${totalPages || 1})</span>
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

    // Gắn sự kiện phân trang
    document.getElementById("prevPageBtn")?.addEventListener("click", () => {
        if (state.page > 1) {
            state.page--;
            loadUsers();
        }
    });

    document.getElementById("nextPageBtn")?.addEventListener("click", () => {
        if (state.page < totalPages) {
            state.page++;
            loadUsers();
        }
    });

    // Gắn sự kiện Đổi vai trò
    container.querySelectorAll("[data-edit-role]").forEach(btn => {
        btn.addEventListener("click", () => {
            const userData = JSON.parse(btn.getAttribute("data-user"));
            openChangeRoleModal(userData);
        });
    });

    // Gắn sự kiện Khóa / Mở khóa
    container.querySelectorAll("[data-toggle-status]").forEach(btn => {
        btn.addEventListener("click", () => {
            const userId = btn.getAttribute("data-toggle-status");
            const userName = btn.getAttribute("data-name");
            const nextStatus = btn.getAttribute("data-next");
            openToggleStatusModal(userId, userName, nextStatus);
        });
    });
}

function openChangeRoleModal(user) {
    const roles = [
        { code: "USER", label: "Người dùng (Kouhai / Mentor)", note: "Quyền thành viên tiêu chuẩn" },
        { code: "MODERATOR", label: "Moderator (Tối đa 3 người)", note: "Kiểm duyệt nội dung, duyệt ghim địa điểm, xử lý khiếu nại" },
        { code: "ACADEMIC_MANAGER", label: "Người quản lý học thuật", note: "Quản lý mẫu câu và kiểm duyệt bài luyện tập tiếng Nhật" },
        { code: "ADMIN", label: "Quản trị viên (Admin)", note: "Toàn quyền quản trị tài khoản, cấu hình và theo dõi kiểm toán" }
    ];

    const bodyHtml = `
        <div class="field">
            <label>Người dùng:</label>
            <div style="font-weight:600; color:var(--ink);">${esc(user.displayName)} (${esc(user.email)})</div>
            <div class="note">Vai trò hiện tại: <b>${esc(user.roleLabel || user.role)}</b></div>
        </div>

        <div class="field">
            <label for="newRoleSelect">Chọn vai trò mới:</label>
            <select id="newRoleSelect" class="select" style="width:100%;">
                ${roles.map(r => `
                    <option value="${r.code}" ${r.code === user.role ? 'selected' : ''}>
                        ${r.label}
                    </option>
                `).join("")}
            </select>
        </div>

        <div class="info" style="font-size:13px;" id="modLimitNotice">
            ${ic("info")} Lưu ý: Số Moderator hoạt động cùng một thời điểm không vượt quá cấu hình <b>Số lượng Moderator tối đa</b> (tối đa 3).
        </div>
    `;

    showModal({
        title: `Phân quyền cho ${user.displayName}`,
        bodyHtml,
        confirmText: "Lưu vai trò",
        confirmClass: "pri",
        onConfirm: async () => {
            const newRole = document.getElementById("newRoleSelect").value;
            if (newRole === user.role) {
                toast("Vai trò không thay đổi", "info");
                return false;
            }

            await adminClient.updateUserRole(user.id, newRole);
            toast(`Đã cập nhật vai trò của ${user.displayName} thành ${newRole}`, "success");
            await loadUsers();
            return false;
        }
    });
}

function openToggleStatusModal(userId, userName, nextStatus) {
    const isLocking = nextStatus === "LOCKED";
    const title = isLocking ? `Khóa tài khoản ${userName}` : `Mở khóa tài khoản ${userName}`;

    const bodyHtml = isLocking ? `
        <div class="field">
            <p>Bạn có chắc chắn muốn <b>khóa tài khoản</b> của <b>${esc(userName)}</b>?</p>
            <p class="note">Khi bị khóa, tài khoản sẽ không thể đăng nhập vào hệ thống Senpai ơi.</p>
        </div>
        <div class="field">
            <label for="lockReasonInput">Lý do khóa tài khoản (lưu nhật ký kiểm toán):</label>
            <input type="text" id="lockReasonInput" class="admin-search-input" maxlength="500"
                   placeholder="Ví dụ: Đăng tải nội dung lừa đảo, vi phạm nhiều lần…">
        </div>
    ` : `
        <div class="field">
            <p>Xác nhận <b>mở khóa</b> cho tài khoản <b>${esc(userName)}</b>?</p>
            <p class="note">Tài khoản sẽ được khôi phục trạng thái hoạt động bình thường ngay sau khi mở khóa.</p>
        </div>
    `;

    showModal({
        title,
        bodyHtml,
        confirmText: isLocking ? "Xác nhận khóa" : "Mở khóa",
        confirmClass: isLocking ? "dan solid" : "pri",
        onConfirm: async () => {
            const reason = isLocking ? (document.getElementById("lockReasonInput")?.value || "") : "Admin mở khóa";
            await adminClient.updateUserStatus(userId, nextStatus, reason);
            toast(isLocking ? `Đã khóa tài khoản ${userName}` : `Đã mở khóa tài khoản ${userName}`, "success");
            await loadUsers();
            return false;
        }
    });
}

function getRoleBadgeClass(role) {
    switch (role) {
        case "MODERATOR": return "badge-role-moderator";
        case "ACADEMIC_MANAGER": return "badge-role-academic";
        case "ADMIN": return "badge-role-admin";
        default: return "badge-role-user";
    }
}

function getStatusBadgeClass(status) {
    switch (status) {
        case "ACTIVE": return "badge-status-active";
        case "LOCKED": return "badge-status-locked";
        case "DELETED": return "badge-status-deleted";
        default: return "";
    }
}

function formatStatus(status) {
    switch (status) {
        case "ACTIVE": return "Hoạt động";
        case "LOCKED": return "Đã khóa";
        case "DELETED": return "Đã xóa";
        default: return status;
    }
}

main();
