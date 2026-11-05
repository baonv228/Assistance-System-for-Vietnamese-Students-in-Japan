// Nhãn, huy hiệu và định dạng dùng chung cho các trang quản trị (danh sách tài khoản, chi tiết, tổng quan, nhật ký).
import { esc } from "./ui.js";

const ROLE_BADGES = {
    USER: "badge-role-user",
    MODERATOR: "badge-role-moderator",
    ACADEMIC_MANAGER: "badge-role-academic",
    ADMIN: "badge-role-admin"
};

const STATUS_BADGES = {
    ACTIVE: "badge-status-active",
    LOCKED: "badge-status-locked",
    DELETED: "badge-status-deleted"
};

const STATUS_LABELS = { ACTIVE: "Đang hoạt động", LOCKED: "Đã khóa", DELETED: "Đã xóa" };

/** Vai trò có thể gán trong màn hình quản trị: mã, nhãn, mô tả quyền. */
export const ROLE_OPTIONS = [
    { code: "USER", label: "Người dùng", note: "Quyền thành viên tiêu chuẩn" },
    { code: "MODERATOR", label: "Moderator", note: "Kiểm duyệt nội dung, duyệt ghim địa điểm, xử lý khiếu nại" },
    { code: "ACADEMIC_MANAGER", label: "Biên tập viên nội dung tiếng Nhật", note: "Quản lý mẫu câu, từ vựng và xử lý báo lỗi" },
    { code: "ADMIN", label: "Quản trị viên", note: "Toàn quyền quản trị tài khoản, cấu hình và nhật ký" }
];

const ACTION_LABELS = {
    USER_ROLE_CHANGE: "Đổi vai trò",
    USER_STATUS_CHANGE: "Đổi trạng thái",
    SETTING_UPDATE: "Sửa cấu hình",
    KEYWORD_ADD: "Thêm từ cấm",
    KEYWORD_UPDATE: "Sửa từ cấm",
    KEYWORD_DELETE: "Xóa từ cấm"
};

const ACTION_BADGES = {
    USER_ROLE_CHANGE: "br",
    USER_STATUS_CHANGE: "warn",
    SETTING_UPDATE: "lan",
    KEYWORD_ADD: "ok",
    KEYWORD_UPDATE: "lan",
    KEYWORD_DELETE: "crit"
};

export const roleBadgeClass = (role) => ROLE_BADGES[role] || "badge-role-user";
export const statusBadgeClass = (status) => STATUS_BADGES[status] || "";
export const statusLabel = (status) => STATUS_LABELS[status] || status;
export const actionLabel = (action) => ACTION_LABELS[action] || action;
export const actionBadgeClass = (action) => ACTION_BADGES[action] || "";

/** Ngày dd/mm/yyyy theo giờ Nhật (ngày nghiệp vụ của hệ thống). */
export function formatDate(iso) {
    if (!iso) return "-";
    const date = new Date(iso);
    if (Number.isNaN(date.getTime())) return "-";
    return date.toLocaleDateString("vi-VN", {
        day: "2-digit", month: "2-digit", year: "numeric", timeZone: "Asia/Tokyo"
    });
}

/** "yyyy-MM-dd" (ngày của khoảng thống kê) → "dd/mm". */
export const shortDay = (isoDate) => isoDate.slice(8, 10) + "/" + isoDate.slice(5, 7);

/** "yyyy-MM-dd" → "dd/mm/yyyy". */
export const fullDay = (isoDate) => isoDate.split("-").reverse().join("/");

/** Chữ cái đầu của tên, cho ảnh đại diện khi chưa có ảnh. */
export const initialOf = (name) => (name || "U").trim().charAt(0).toUpperCase() || "U";

/** Các chip tag định danh (chỉ đọc); "Không" khi chưa có tag. Màu tag là dữ liệu từ cơ sở dữ liệu nên được escape. */
export function tagChips(tags) {
    if (!tags || tags.length === 0) return `<span class="note">Không</span>`;
    return `<span class="admin-tags">${tags.map((t) =>
        `<span class="admin-tag" data-color="${esc(t.color)}">${esc(t.name)}</span>`).join("")}</span>`;
}

/** Huy hiệu "Mới" khi tài khoản còn mới, kèm số ngày. */
export function ageCell(user) {
    const badge = user.newAccount ? ` <span class="badge warn">Mới</span>` : "";
    return `<span>${esc(user.accountAgeDays)} ngày</span>${badge}`;
}

/** Số báo cáo sai, kèm huy hiệu khi đang bị khóa quyền báo cáo. */
export function falseReportCell(user) {
    const count = `<b>${esc(user.falseReportCount)}</b>`;
    if (!user.reportMutedUntil) return count;
    return `${count} <span class="badge crit">Khóa báo cáo đến ${esc(formatDate(user.reportMutedUntil))}</span>`;
}

/** Gắn màu của tag (data-color) bằng JS: CSP-friendly và không đưa dữ liệu vào thuộc tính style. */
export function paintTags(root) {
    root.querySelectorAll(".admin-tag[data-color]").forEach((el) => {
        const color = el.dataset.color;
        if (/^#[0-9a-fA-F]{3,8}$/.test(color)) {
            el.style.setProperty("--tag-color", color);
        }
    });
}
