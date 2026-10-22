// =========================================================
// Layout dùng chung cho các trang Quản trị viên (Admin)
// =========================================================
import { currentUser } from "../api/client.js";
import { logout } from "./site-header.js";
import { esc, ic, LOGO_SVG } from "../lib/ui.js";

// Địa chỉ gọn, không ".html" (common/web/CleanUrls)
const NAV_ITEMS = [
    { key: "dashboard", label: "Tổng quan", icon: "space_dashboard", path: "/admin" },
    { key: "users", label: "Người dùng & Vai trò", icon: "group", path: "/admin/users" },
    { key: "config", label: "Cấu hình & Từ khóa", icon: "tune", path: "/admin/system-config" },
    { key: "logs", label: "Nhật ký hoạt động", icon: "history", path: "/admin/activity-log" }
];

let currentUserData = null;

/**
 * Khởi tạo giao diện trang quản trị, kiểm tra quyền ADMIN và dựng khung Header + Sidebar.
 * @param {string} activeKey Key của trang đang kích hoạt (dashboard | users | config | logs)
 * @returns {Promise<Object>} Thông tin tài khoản người dùng đang đăng nhập
 */
export async function initAdminPage(activeKey) {
    const user = await currentUser();
    if (!user) {
        window.location.replace("/login");
        return null;
    }
    if (user.role !== "ADMIN") {
        window.location.replace(user.homePath || "/");
        return null;
    }
    currentUserData = user;

    document.body.innerHTML = `
        <header class="site-top">
            <a class="logo" href="/admin">${LOGO_SVG}Senpai ơi</a>
            <span class="badge br">${esc(user.roleLabel || "Quản trị viên")}</span>
            <span class="grow"></span>
            <div class="site-account">
                <a class="btn sm" href="/">${ic("home")}<span class="who">Trang người dùng</span></a>
                <a class="btn sm" href="/profile">${ic("account_circle")}<span class="who">${esc(user.displayName)}</span></a>
                <button class="btn sm" type="button" id="adminLogoutBtn" title="Đăng xuất">${ic("logout")}<span class="sr-only">Đăng xuất</span></button>
            </div>
        </header>
        <div class="desk">
            <nav class="desk-side" aria-label="Menu quản trị">
                <span class="side-title">Quản trị hệ thống</span>
                ${NAV_ITEMS.map(item => `
                    <a href="${item.path}" ${item.key === activeKey ? 'aria-current="page"' : ''}>
                        ${ic(item.icon)}${esc(item.label)}
                    </a>
                `).join("")}
            </nav>
            <main class="desk-main" id="adminMainContent">
                <p class="note">Đang tải dữ liệu…</p>
            </main>
        </div>
        <div class="admin-toast-container" id="adminToastContainer"></div>
    `;

    document.getElementById("adminLogoutBtn")?.addEventListener("click", logout);
    return user;
}

/**
 * Hiển thị thông báo Toast nhanh góc màn hình.
 * @param {string} message Nội dung thông báo
 * @param {'success'|'error'|'info'} type Loại thông báo
 */
export function toast(message, type = "info") {
    const container = document.getElementById("adminToastContainer");
    if (!container) return;

    const el = document.createElement("div");
    el.className = `admin-toast ${type}`;
    let iconName = "info";
    if (type === "success") iconName = "check_circle";
    if (type === "error") iconName = "error";

    el.innerHTML = `${ic(iconName)}<span>${esc(message)}</span>`;
    container.appendChild(el);

    setTimeout(() => {
        el.style.opacity = "0";
        el.style.transition = "opacity .3s ease";
        setTimeout(() => el.remove(), 300);
    }, 3500);
}

/**
 * Hiển thị hộp thoại Modal xác nhận hoặc thao tác nhập liệu.
 */
export function showModal({ title, bodyHtml, confirmText = "Lưu", confirmClass = "pri", onConfirm }) {
    closeModal(); // Đóng modal cũ nếu có

    const backdrop = document.createElement("div");
    backdrop.className = "admin-modal-backdrop";
    backdrop.id = "activeAdminModal";
    backdrop.innerHTML = `
        <div class="admin-modal" role="dialog" aria-modal="true" aria-labelledby="modalTitle">
            <div class="modal-header">
                <h3 id="modalTitle">${esc(title)}</h3>
                <button type="button" class="modal-close-btn" id="modalCloseBtn" aria-label="Đóng">${ic("close")}</button>
            </div>
            <div class="modal-body">
                ${bodyHtml}
            </div>
            <div class="modal-footer">
                <button type="button" class="btn sm" id="modalCancelBtn">Hủy</button>
                <button type="button" class="btn sm ${confirmClass}" id="modalConfirmBtn">${esc(confirmText)}</button>
            </div>
        </div>
    `;

    document.body.appendChild(backdrop);

    const close = () => closeModal();
    backdrop.querySelector("#modalCloseBtn")?.addEventListener("click", close);
    backdrop.querySelector("#modalCancelBtn")?.addEventListener("click", close);
    backdrop.addEventListener("click", (e) => {
        if (e.target === backdrop) close();
    });

    const confirmBtn = backdrop.querySelector("#modalConfirmBtn");
    if (confirmBtn && onConfirm) {
        confirmBtn.addEventListener("click", async () => {
            confirmBtn.setAttribute("disabled", "true");
            try {
                const keepOpen = await onConfirm();
                if (!keepOpen) closeModal();
            } catch (err) {
                toast(err.message || "Đã xảy ra lỗi", "error");
            } finally {
                confirmBtn.removeAttribute("disabled");
            }
        });
    }
}

/**
 * Đóng Modal đang mở.
 */
export function closeModal() {
    const modal = document.getElementById("activeAdminModal");
    if (modal) modal.remove();
}

/**
 * Định dạng ngày giờ thân thiện cho người dùng Việt Nam.
 */
export function formatDateTime(isoString) {
    if (!isoString) return "-";
    try {
        const d = new Date(isoString);
        return d.toLocaleString("vi-VN", {
            day: "2-digit",
            month: "2-digit",
            year: "numeric",
            hour: "2-digit",
            minute: "2-digit"
        });
    } catch {
        return isoString;
    }
}
