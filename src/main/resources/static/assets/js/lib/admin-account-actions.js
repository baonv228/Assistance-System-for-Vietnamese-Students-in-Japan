// Luồng đổi vai trò và khóa/mở khóa tài khoản, dùng chung cho danh sách và trang chi tiết.
import { showModal, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "./ui.js";
import { ROLE_OPTIONS } from "./admin-ui.js";

/**
 * Khóa hoặc mở khóa một tài khoản: kiểm tra trước (chỉ đọc) để báo lỗi ngay khi bấm nút, rồi mới mở hộp thoại xác nhận.
 * Khi một Admin khác vừa đổi tài khoản (409) hộp thoại đóng, trang được làm mới và hiện thông báo.
 *
 * @param user     tài khoản (id, displayName, status như trang đang hiển thị)
 * @param callback onMessage(text, isError): hiện thông báo gần nút; onChanged(): tải lại dữ liệu
 */
export async function runStatusFlow(user, { onMessage, onChanged }) {
    const locking = user.status !== "LOCKED";
    const target = locking ? "LOCKED" : "ACTIVE";
    try {
        await adminClient.checkStatusChange(user.id, target);
    } catch (err) {
        onMessage(err.message, true);
        if (err.status === 404) await onChanged();
        return;
    }

    const bodyHtml = locking ? `
        <p>Bạn có chắc muốn khóa tài khoản <b>${esc(user.displayName)}</b>? Thao tác này có thể ảnh hưởng đến dữ liệu liên quan.</p>
        <p class="note">${ic("info")} Mọi phiên đăng nhập của tài khoản sẽ bị chấm dứt ngay, và bài viết, bình luận của họ
            được ẩn cho đến khi mở khóa.</p>
        <div class="field">
            <label for="lockReasonInput">Lý do khóa (ghi vào nhật ký):</label>
            <input type="text" id="lockReasonInput" class="admin-input" maxlength="500"
                   placeholder="Ví dụ: đăng nội dung lừa đảo, vi phạm nhiều lần…">
        </div>` : `
        <p>Bạn có chắc muốn mở khóa tài khoản <b>${esc(user.displayName)}</b>? Tài khoản sẽ đăng nhập lại được ngay.</p>`;

    showModal({
        title: locking ? "Khóa tài khoản" : "Mở khóa tài khoản",
        bodyHtml,
        confirmText: "Xác nhận",
        confirmClass: locking ? "dan solid" : "pri",
        onConfirm: async () => {
            const reason = locking ? (document.getElementById("lockReasonInput")?.value.trim() || "") : "";
            try {
                await adminClient.updateUserStatus(user.id, target, reason, user.status);
            } catch (err) {
                if (err.status === 409 || err.status === 404) {
                    onMessage(err.message, true);
                    await onChanged();
                    return false;
                }
                if (err.status === 400) {
                    onMessage(err.message, true);
                    return false;
                }
                throw err;      // lỗi bất ngờ: toast do hộp thoại hiện, hộp thoại giữ nguyên để thử lại
            }
            toast("Đã cập nhật trạng thái tài khoản.", "success");
            await onChanged();
            return false;
        }
    });
}

/**
 * Đổi vai trò từ ô chọn trên trang chi tiết. Chọn đúng vai trò hiện tại thì máy chủ trả "không có thay đổi".
 * Lỗi (giới hạn Moderator, Admin khác, dữ liệu cũ) được báo cạnh ô chọn và ô chọn trở về vai trò cũ.
 */
export async function runRoleChange(user, newRole, { onMessage, onChanged }) {
    try {
        await adminClient.updateUserRole(user.id, newRole, user.role);
    } catch (err) {
        onMessage(err.message, true);
        if (err.status === 409 || err.status === 404) await onChanged();
        return false;
    }
    if (newRole === user.role) {
        onMessage("Không có thay đổi nào để lưu.", false);
        return false;
    }
    toast("Đã cập nhật vai trò của người dùng.", "success");
    await onChanged();
    return true;
}

/** Các <option> của ô chọn vai trò, chọn sẵn vai trò hiện tại. */
export function roleOptionsHtml(current) {
    return ROLE_OPTIONS.map((r) =>
        `<option value="${esc(r.code)}" ${r.code === current ? "selected" : ""}>${esc(r.label)}</option>`).join("");
}
