// =========================================================
// ASVSJ - Admin: Danh sách tài khoản (tìm kiếm, lọc, khóa/mở khóa)
// =========================================================
import { initAdminPage, toast } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";
import {
    ageCell, falseReportCell, initialOf, paintTags, roleBadgeClass, statusBadgeClass, statusLabel, tagChips
} from "../lib/admin-ui.js";
import { runStatusFlow } from "../lib/admin-account-actions.js";

const FILTER_KEYS = ["keyword", "role", "status", "tagId"];
const PAGE_SIZES = [10, 20, 50];

// Bộ lọc và trang nằm trên địa chỉ để nút "Quay lại" của trang chi tiết khôi phục đúng danh sách
const query = new URLSearchParams(location.search);
const state = {
    keyword: query.get("keyword") || "",
    role: query.get("role") || "",
    status: query.get("status") || "",
    tagId: query.get("tagId") || "",
    page: Math.max(1, Number(query.get("page")) || 1),
    size: PAGE_SIZES.includes(Number(query.get("size"))) ? Number(query.get("size")) : 20
};

let me = null;
let options = { tags: [], moderatorCapacity: { active: 0, max: 3 } };
let rows = [];
let table = null;
let requestId = 0;

async function main() {
    me = await initAdminPage("users");
    if (!me) return;
    const mainEl = document.getElementById("adminMainContent");
    try {
        options = await adminClient.getAccountOptions();
    } catch {
        // thanh lọc tag trống; danh sách vẫn tải được
    }
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Người dùng &amp; Vai trò</h2>
                <p>Tra cứu tài khoản, xem chi tiết, đổi vai trò, khóa và mở khóa.</p>
            </div>
            <span class="badge br" id="modCounter"></span>
        </div>
        <div class="admin-filter-bar">
            <div class="admin-search-box">
                ${ic("search")}
                <input type="search" id="userSearchInput" class="admin-search-input" maxlength="100"
                       aria-label="Tìm theo tên hoặc email" placeholder="Tìm theo tên hoặc email…" value="${esc(state.keyword)}">
            </div>
            <select id="roleFilter" class="admin-filter-select" aria-label="Lọc theo vai trò">
                <option value="">Tất cả vai trò</option>
                <option value="USER">Người dùng</option>
                <option value="MODERATOR">Moderator</option>
                <option value="ACADEMIC_MANAGER">Biên tập viên tiếng Nhật</option>
                <option value="ADMIN">Quản trị viên</option>
            </select>
            <select id="tagFilter" class="admin-filter-select" aria-label="Lọc theo tag định danh">
                <option value="">Tất cả tag</option>
                ${options.tags.map((t) => `<option value="${esc(t.id)}">${esc(t.name)}</option>`).join("")}
            </select>
            <select id="statusFilter" class="admin-filter-select" aria-label="Lọc theo trạng thái">
                <option value="">Tất cả trạng thái</option>
                <option value="ACTIVE">Đang hoạt động</option>
                <option value="LOCKED">Đã khóa</option>
                <option value="DELETED">Đã xóa</option>
            </select>
            <button type="button" class="btn sm" id="clearFilters">${ic("restart_alt")} Xóa bộ lọc</button>
        </div>
        <p class="admin-inline-msg error" id="rowMessage" role="alert" hidden></p>
        <div class="admin-card-table" id="usersTable"><p class="note" style="padding:24px;">Đang tải danh sách…</p></div>`;
    table = document.getElementById("usersTable");
    document.getElementById("roleFilter").value = state.role;
    document.getElementById("statusFilter").value = state.status;
    document.getElementById("tagFilter").value = state.tagId;
    showCapacity(options.moderatorCapacity);
    bindFilters();
    await load();
}

function showCapacity(cap) {
    document.getElementById("modCounter").textContent = `Moderator: ${cap.active}/${cap.max}`;
}

function bindFilters() {
    let timer = null;
    document.getElementById("userSearchInput").addEventListener("input", (e) => {
        clearTimeout(timer);
        timer = setTimeout(() => change({ keyword: e.target.value.trim() }), 300);
    });
    document.getElementById("roleFilter").addEventListener("change", (e) => change({ role: e.target.value }));
    document.getElementById("tagFilter").addEventListener("change", (e) => change({ tagId: e.target.value }));
    document.getElementById("statusFilter").addEventListener("change", (e) => change({ status: e.target.value }));
    document.getElementById("clearFilters").addEventListener("click", clearFilters);
}

function change(patch) {
    Object.assign(state, patch, { page: 1 });
    load();
}

function clearFilters() {
    Object.assign(state, { keyword: "", role: "", status: "", tagId: "", page: 1 });
    document.getElementById("userSearchInput").value = "";
    ["roleFilter", "statusFilter", "tagFilter"].forEach((id) => { document.getElementById(id).value = ""; });
    load();
}

/** Ghi bộ lọc và trang vào địa chỉ (không thêm mục lịch sử). */
function syncAddress() {
    const params = new URLSearchParams();
    FILTER_KEYS.forEach((k) => { if (state[k]) params.set(k, state[k]); });
    if (state.page > 1) params.set("page", state.page);
    if (state.size !== 20) params.set("size", state.size);
    const text = params.toString();
    history.replaceState(null, "", location.pathname + (text ? "?" + text : ""));
}

/** Tải một trang; chỉ câu trả lời của lần gọi mới nhất được vẽ (gõ nhanh không làm bảng nhảy về kết quả cũ). */
async function load() {
    const mine = ++requestId;
    syncAddress();
    try {
        const result = await adminClient.getUsers(state);
        if (mine !== requestId) return;
        if (result.items.length === 0 && result.total > 0 && state.page > 1) {
            state.page = Math.max(1, result.totalPages);     // trang vượt quá số trang: về trang cuối
            return load();
        }
        rows = result.items;
        render(result);
    } catch (err) {
        if (mine !== requestId) return;
        table.innerHTML = `
            <div class="alert error" style="margin:16px;">${ic("error")} Không tải được dữ liệu. Vui lòng thử lại sau.</div>
            <div style="padding:0 16px 16px;"><button type="button" class="btn sm" id="retryBtn">${ic("refresh")} Thử lại</button></div>`;
        document.getElementById("retryBtn")?.addEventListener("click", load);
    }
}

function render(result) {
    if (result.items.length === 0) {
        table.innerHTML = `
            <div style="padding:40px; text-align:center;">
                <p class="note" role="status">Không tìm thấy người dùng nào.</p>
                <button type="button" class="btn sm" id="emptyClear">${ic("restart_alt")} Xóa bộ lọc</button>
            </div>`;
        document.getElementById("emptyClear")?.addEventListener("click", clearFilters);
        return;
    }
    table.innerHTML = `
        <div class="admin-table-responsive">
            <table class="admin-table">
                <thead><tr>
                    <th>Người dùng</th><th>Vai trò</th><th>Tag</th><th>Tuổi tài khoản</th>
                    <th>Báo cáo sai</th><th>Trạng thái</th><th style="width:120px;">Thao tác</th>
                </tr></thead>
                <tbody>${result.items.map(rowHtml).join("")}</tbody>
            </table>
        </div>
        <div class="admin-pagination">
            <span>Trang <b>${esc(result.page)}/${esc(result.totalPages || 1)}</b> · <b>${esc(result.total)}</b> tài khoản</span>
            <div class="pagination-controls">
                <select id="sizeSelect" class="admin-filter-select" aria-label="Số dòng mỗi trang">
                    ${PAGE_SIZES.map((s) => `<option value="${s}" ${s === state.size ? "selected" : ""}>${s} / trang</option>`).join("")}
                </select>
                <button type="button" class="btn sm pagination-btn" id="prevPage" ${result.page <= 1 ? "disabled" : ""}>${ic("chevron_left")} Trước</button>
                <button type="button" class="btn sm pagination-btn" id="nextPage" ${result.page >= result.totalPages ? "disabled" : ""}>Sau ${ic("chevron_right")}</button>
            </div>
        </div>`;
    paintTags(table);
    bindTable(result);
}

function rowHtml(user) {
    const deleted = user.status === "DELETED";
    const locked = user.status === "LOCKED";
    const detailUrl = `/admin/users/${encodeURIComponent(user.id)}${location.search}`;
    return `
        <tr>
            <td>
                <a class="user-cell" href="${esc(detailUrl)}" style="text-decoration:none; color:inherit;">
                    <span class="user-avatar">${user.avatarUrl ? `<img src="${esc(user.avatarUrl)}" alt="">` : esc(initialOf(user.displayName))}</span>
                    <span class="user-meta"><span class="name">${esc(user.displayName)}
                        ${user.id === me.id ? `<span class="badge br">Bạn</span>` : ""}</span></span>
                </a>
            </td>
            <td><span class="badge ${roleBadgeClass(user.role)}">${esc(user.roleLabel || user.role)}</span></td>
            <td>${tagChips(user.tags)}</td>
            <td>${ageCell(user)}</td>
            <td>${falseReportCell(user)}</td>
            <td><span class="badge ${statusBadgeClass(user.status)}">${esc(statusLabel(user.status))}</span></td>
            <td>${deleted ? `<span class="note">-</span>` : `
                <button type="button" class="btn sm ${locked ? "ok" : "dan"}" data-status="${esc(user.id)}">
                    ${ic(locked ? "lock_open" : "lock")} ${locked ? "Mở khóa" : "Khóa"}</button>`}</td>
        </tr>`;
}

function bindTable(result) {
    document.getElementById("prevPage")?.addEventListener("click", () => { state.page--; load(); });
    document.getElementById("nextPage")?.addEventListener("click", () => { state.page++; load(); });
    document.getElementById("sizeSelect")?.addEventListener("change", (e) => {
        state.size = Number(e.target.value);
        state.page = 1;
        load();
    });
    table.querySelectorAll("[data-status]").forEach((btn) => btn.addEventListener("click", () => {
        const user = rows.find((r) => String(r.id) === btn.dataset.status);
        if (user) runStatusFlow(user, { onMessage: showRowMessage, onChanged: refreshAfterChange });
    }));
}

/** Thông báo lỗi của một thao tác ở đầu bảng (không dùng toast để người dùng kịp đọc). */
function showRowMessage(text, isError) {
    const box = document.getElementById("rowMessage");
    if (!box) return;
    if (!isError) {
        toast(text, "info");
        return;
    }
    box.textContent = text;
    box.hidden = false;
}

async function refreshAfterChange() {
    try {
        options = await adminClient.getAccountOptions();
        showCapacity(options.moderatorCapacity);
    } catch {
        // bộ đếm giữ số cũ
    }
    await load();
}

main();
