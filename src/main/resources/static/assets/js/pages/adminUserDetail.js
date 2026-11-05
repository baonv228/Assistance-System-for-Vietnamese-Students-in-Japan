// =========================================================
// ASVSJ - Admin: Chi tiết tài khoản (xem, đổi vai trò, khóa/mở khóa)
// =========================================================
import { initAdminPage, formatDateTime } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";
import {
    actionBadgeClass, actionLabel, formatDate, initialOf, paintTags, roleBadgeClass, statusBadgeClass, statusLabel,
    tagChips
} from "../lib/admin-ui.js";
import { roleOptionsHtml, runRoleChange, runStatusFlow } from "../lib/admin-account-actions.js";

// /admin/users/{id}: id nằm cuối đường dẫn
const userId = Number(location.pathname.split("/").filter(Boolean).pop());
let me = null;
let mainEl = null;

async function main() {
    me = await initAdminPage("users");
    if (!me) return;
    mainEl = document.getElementById("adminMainContent");
    await load();
}

/** Địa chỉ danh sách kèm đúng bộ lọc và trang mà người dùng đã rời đi (query string được giữ khi mở chi tiết). */
const listUrl = () => "/admin/users" + location.search;

async function load() {
    try {
        render(await adminClient.getAccountDetail(userId));
    } catch (err) {
        if (err.status === 404) {
            mainEl.innerHTML = `
                <div class="alert error">${ic("error")} Không tìm thấy tài khoản này.</div>
                <div><a class="btn sm" href="${esc(listUrl())}">${ic("arrow_back")} Quay lại danh sách</a></div>`;
            return;
        }
        mainEl.innerHTML = `
            <div class="alert error">${ic("error")} Không tải được dữ liệu. Vui lòng thử lại sau.</div>
            <div><button type="button" class="btn sm" id="retryBtn">${ic("refresh")} Thử lại</button>
                <a class="btn sm" href="${esc(listUrl())}">${ic("arrow_back")} Quay lại</a></div>`;
        document.getElementById("retryBtn")?.addEventListener("click", load);
    }
}

function render(detail) {
    const { basic, access, safety, moderatorCapacity: cap } = detail;
    const self = basic.id === me.id;
    const deleted = basic.status === "DELETED";
    // Trạng thái trang đang hiển thị, gửi kèm khi đổi để máy chủ phát hiện dữ liệu đã cũ
    const user = { id: basic.id, displayName: basic.displayName, status: basic.status, role: access.role };
    const locked = basic.status === "LOCKED";

    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div class="admin-detail-head">
                <a class="btn sm" href="${esc(listUrl())}">${ic("arrow_back")} Quay lại</a>
                <span class="user-avatar">${basic.avatarUrl ? `<img src="${esc(basic.avatarUrl)}" alt="">` : esc(initialOf(basic.displayName))}</span>
                <div><h2>${esc(basic.displayName)}</h2>
                    <p>${esc(basic.email)}</p></div>
            </div>
            ${deleted ? "" : `<button type="button" class="btn sm ${locked ? "ok" : "dan"}" id="statusBtn">
                ${ic(locked ? "lock_open" : "lock")} ${locked ? "Mở khóa tài khoản" : "Khóa tài khoản"}</button>`}
        </div>
        <p class="admin-inline-msg error" id="detailMessage" role="alert" hidden></p>
        <div class="admin-detail-grid">
            <section class="admin-panel" aria-labelledby="basicTitle">
                <h3 id="basicTitle">${ic("badge")} Thông tin cơ bản</h3>
                <dl class="admin-dl">
                    <dt>Tên hiển thị</dt><dd>${esc(basic.displayName)} ${self ? `<span class="badge br">Bạn</span>` : ""}</dd>
                    <dt>Email</dt><dd>${esc(basic.email)}</dd>
                    <dt>Ngày đăng ký</dt><dd>${esc(formatDate(basic.registeredAt))}</dd>
                    <dt>Đăng nhập gần nhất</dt><dd>${esc(basic.lastLoginAt ? formatDateTime(basic.lastLoginAt) : "Chưa có")}</dd>
                    <dt>Tuổi tài khoản</dt><dd>${esc(basic.accountAgeDays)} ngày ${basic.newAccount ? `<span class="badge warn">Mới</span>` : ""}</dd>
                    <dt>Trạng thái</dt><dd><span class="badge ${statusBadgeClass(basic.status)}">${esc(statusLabel(basic.status))}</span></dd>
                </dl>
            </section>
            <section class="admin-panel" aria-labelledby="accessTitle">
                <h3 id="accessTitle">${ic("key")} Vai trò và định danh</h3>
                <dl class="admin-dl">
                    <dt>Vai trò hiện tại</dt><dd><span class="badge ${roleBadgeClass(access.role)}">${esc(access.roleLabel)}</span></dd>
                    <dt>Tag định danh</dt><dd>${tagChips(access.tags)}</dd>
                    <dt>Tổng điểm</dt><dd>${esc(access.points)}</dd>
                    <dt>Hạng</dt><dd>${esc(access.rank ? access.rank.name : "-")}</dd>
                </dl>
                ${roleControlHtml(access, cap, self, deleted)}
            </section>
            <section class="admin-panel" aria-labelledby="safetyTitle">
                <h3 id="safetyTitle">${ic("shield")} An toàn và kiểm duyệt</h3>
                <dl class="admin-dl">
                    <dt>Báo cáo sai</dt><dd>${esc(safety.falseReportCount)}</dd>
                    ${safety.reportMutedUntil ? `<dt>Khóa báo cáo đến</dt><dd><span class="badge crit">${esc(formatDate(safety.reportMutedUntil))}</span></dd>` : ""}
                    <dt>Bài viết, review đã bị gỡ</dt><dd>${esc(safety.removedContentCount)}</dd>
                </dl>
                <h4 style="margin:4px 0 0;">Hoạt động gần đây liên quan</h4>
                ${logsHtml(safety.recentActivity)}
            </section>
        </div>`;
    paintTags(mainEl);
    bind(user);
}

function roleControlHtml(access, cap, self, deleted) {
    if (deleted) return `<p class="note">Tài khoản đã xóa: không đổi được vai trò.</p>`;
    return `
        <div class="admin-role-box">
            <div class="admin-role-row">
                <label class="sr-only" for="roleSelect">Vai trò mới</label>
                <select id="roleSelect" class="select" ${self ? "disabled" : ""}>${roleOptionsHtml(access.role)}</select>
                <button type="button" class="btn sm pri" id="roleBtn" ${self ? "disabled" : ""}>${ic("check")} Xác nhận</button>
                <span class="badge br">Moderator: ${esc(cap.active)}/${esc(cap.max)}</span>
            </div>
            ${self ? `<p class="note">Bạn không thể đổi vai trò của chính mình.</p>` : ""}
            <p class="admin-inline-msg" id="roleMessage" role="status" hidden></p>
        </div>`;
}

function logsHtml(items) {
    if (!items || items.length === 0) return `<p class="note">Chưa có hoạt động nào được ghi.</p>`;
    return `<ul class="admin-log-list">${items.map((log) => `
        <li><span><span class="badge ${actionBadgeClass(log.action)}">${esc(actionLabel(log.action))}</span>
            <span class="note">${esc(formatDateTime(log.createdAt))} · ${esc(log.actorName)}</span></span>
            <span>${esc(log.description || "-")}</span></li>`).join("")}</ul>`;
}

function bind(user) {
    const message = (text, isError) => {
        const box = document.getElementById("roleMessage") || document.getElementById("detailMessage");
        if (!box) return;
        box.textContent = text;
        box.classList.toggle("error", !!isError);
        box.hidden = false;
    };
    document.getElementById("statusBtn")?.addEventListener("click", () =>
        runStatusFlow(user, { onMessage: (t, e) => showTop(t, e), onChanged: load }));
    document.getElementById("roleBtn")?.addEventListener("click", async () => {
        const select = document.getElementById("roleSelect");
        const button = document.getElementById("roleBtn");
        button.disabled = true;        // chống bấm đôi trong lúc đang gửi
        const changed = await runRoleChange(user, select.value, { onMessage: message, onChanged: load });
        if (!changed) {
            select.value = user.role;  // lỗi hoặc không đổi: ô chọn trở về vai trò cũ
            button.disabled = false;
        }
    });
}

function showTop(text, isError) {
    const box = document.getElementById("detailMessage");
    if (!box) return;
    box.textContent = text;
    box.classList.toggle("error", !!isError);
    box.hidden = false;
}

main();
