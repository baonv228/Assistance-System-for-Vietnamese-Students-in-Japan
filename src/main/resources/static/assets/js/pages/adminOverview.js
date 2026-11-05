// =========================================================
// ASVSJ - Admin: Tổng quan hệ thống (chỉ đọc)
// =========================================================
import { initAdminPage } from "../components/admin-layout.js";
import { adminClient } from "../api/admin-client.js";
import { esc, ic } from "../lib/ui.js";
import { fullDay, shortDay } from "../lib/admin-ui.js";

const PRESETS = [
    { key: "today", label: "Hôm nay" },
    { key: "last7days", label: "7 ngày qua" },
    { key: "last30days", label: "30 ngày qua" },
    { key: "custom", label: "Tùy chọn" }
];

const DATE_ERROR = "Khoảng ngày không hợp lệ. Ngày bắt đầu phải trước ngày kết thúc và không được ở tương lai.";
const NO_DATA = "Không có dữ liệu trong khoảng thời gian đã chọn.";

// Khoảng đang chọn; "custom" giữ hai ngày người dùng nhập
const state = { period: "last7days", from: "", to: "" };
let lastOverview = null;
let mainEl = null;
let userName = "";

async function main() {
    const user = await initAdminPage("dashboard");
    if (!user) return;
    userName = user.displayName;
    mainEl = document.getElementById("adminMainContent");
    await load();
}

/** Tải số liệu theo khoảng đang chọn và vẽ lại; lỗi tải thì hiện thông báo kèm nút thử lại. */
async function load() {
    try {
        lastOverview = await adminClient.getOverview(state);
        render(lastOverview);
    } catch (err) {
        // Khoảng ngày sai: giữ dữ liệu đang hiển thị, chỉ báo lỗi dưới bộ chọn
        if (err.status === 400 && lastOverview) {
            showDateError(err.message);
            return;
        }
        mainEl.innerHTML = `
            <div class="alert error">${ic("error")} Không tải được dữ liệu. Vui lòng thử lại sau.</div>
            <div><button type="button" class="btn sm" id="retryBtn">${ic("refresh")} Thử lại</button></div>`;
        document.getElementById("retryBtn")?.addEventListener("click", load);
    }
}

function render(data) {
    const { accounts, reviewTrend, quotas, configuration, staff } = data;
    mainEl.innerHTML = `
        <div class="admin-page-header">
            <div>
                <h2>Tổng quan hệ thống</h2>
                <p>Xin chào <b>${esc(userName)}</b>. Số liệu tài khoản, review, hạn mức dịch vụ và cấu hình đang áp dụng.</p>
            </div>
        </div>
        ${periodBar(data.period)}
        ${tilesHtml(accounts)}
        ${data.empty ? `<div class="admin-inline-msg" role="status">${NO_DATA}</div>` : ""}
        <div class="admin-split">
            <section class="admin-panel" aria-labelledby="trendTitle">
                <h3 id="trendTitle">${ic("bar_chart")} Review mới mỗi ngày</h3>
                ${chartHtml(reviewTrend.days)}
            </section>
            <section class="admin-panel" aria-label="Báo cáo bị bác">
                <h3>${ic("flag")} Báo cáo sai / bị bác</h3>
                <div class="admin-stat-info">
                    <span class="num">${esc(reviewTrend.dismissedReports)}</span>
                    <span class="lbl">Báo cáo được Moderator bác trong khoảng đã chọn</span>
                </div>
                <div class="admin-stat-sub">Tổng review trong khoảng: <b>${esc(reviewTrend.totalReviews)}</b></div>
            </section>
        </div>
        <section class="admin-panel" aria-labelledby="quotaTitle">
            <h3 id="quotaTitle">${ic("speed")} Hạn mức dịch vụ</h3>
            <div class="admin-quota-list">${quotas.map(quotaHtml).join("")}</div>
        </section>
        <section class="admin-panel" aria-labelledby="cfgTitle">
            <h3 id="cfgTitle">${ic("tune")} Cấu hình hiện tại</h3>
            ${configuration.map(configGroupHtml).join("")}
        </section>
        <section class="admin-panel" aria-labelledby="staffTitle">
            <h3 id="staffTitle">${ic("badge")} Nhân sự</h3>
            <p style="margin:0;">Moderator <b>${esc(staff.moderators.active)}/${esc(staff.moderators.max)}</b>
                · Biên tập viên tiếng Nhật <b>${esc(staff.academicManagers)}</b>
                · Quản trị viên <b>${esc(staff.admins)}</b></p>
            <div class="admin-shortcuts">
                <a class="btn sm" href="/admin/users">${ic("group")} Người dùng &amp; Vai trò</a>
                <a class="btn sm" href="/admin/system-config">${ic("tune")} Cấu hình &amp; Từ khóa</a>
                <a class="btn sm" href="/admin/activity-log">${ic("history")} Nhật ký hoạt động</a>
            </div>
        </section>`;
    bindPeriod();
}

function periodBar(period) {
    const custom = state.period === "custom";
    return `
        <div class="admin-period" role="group" aria-label="Khoảng thời gian thống kê">
            ${PRESETS.map((p) => `<button type="button" class="btn sm ${state.period === p.key ? "on" : ""}"
                data-preset="${p.key}" aria-pressed="${state.period === p.key}">${p.label}</button>`).join("")}
            <div class="admin-period-custom" id="customRange" ${custom ? "" : "hidden"}>
                <div class="field"><label for="rangeFrom">Từ ngày</label>
                    <input type="date" id="rangeFrom" class="admin-input" value="${esc(state.from)}"></div>
                <div class="field"><label for="rangeTo">Đến ngày</label>
                    <input type="date" id="rangeTo" class="admin-input" value="${esc(state.to)}"></div>
                <button type="button" class="btn sm pri" id="applyRange">Áp dụng</button>
            </div>
            <span class="note">Đang xem: ${esc(fullDay(period.from))} – ${esc(fullDay(period.to))} (giờ Nhật)</span>
        </div>
        <p class="field-error" id="rangeError" role="alert" hidden></p>`;
}

function bindPeriod() {
    document.querySelectorAll("[data-preset]").forEach((btn) => btn.addEventListener("click", () => {
        state.period = btn.dataset.preset;
        if (state.period === "custom") {
            // Chỉ mở ô nhập ngày; dữ liệu được tải khi bấm "Áp dụng"
            document.getElementById("customRange").hidden = false;
            document.querySelectorAll("[data-preset]").forEach((b) =>
                b.classList.toggle("on", b.dataset.preset === "custom"));
            return;
        }
        load();
    }));
    document.getElementById("applyRange")?.addEventListener("click", () => {
        state.from = document.getElementById("rangeFrom").value;
        state.to = document.getElementById("rangeTo").value;
        if (!state.from || !state.to || state.from > state.to) {
            showDateError(DATE_ERROR);
            return;
        }
        load();
    });
}

/** Báo lỗi khoảng ngày dưới bộ chọn, đánh dấu hai ô ngày; dữ liệu cũ vẫn nằm trên màn hình. */
function showDateError(message) {
    const box = document.getElementById("rangeError");
    if (!box) return;
    box.textContent = message;
    box.hidden = false;
    ["rangeFrom", "rangeTo"].forEach((id) => document.getElementById(id)?.setAttribute("aria-invalid", "true"));
}

function tilesHtml(a) {
    const tile = (icon, tone, value, label, sub = "") => `
        <div class="admin-stat-card">
            <div class="admin-stat-icon ${tone}">${ic(icon)}</div>
            <div class="admin-stat-info"><span class="num">${esc(value)}</span><span class="lbl">${esc(label)}</span>
                ${sub ? `<span class="admin-stat-sub">${esc(sub)}</span>` : ""}</div>
        </div>`;
    return `
        <div class="admin-stats-grid">
            ${tile("groups", "blue", a.totalAccounts, "Tổng tài khoản",
                `${a.newAccounts} mới trong ${a.newAccountDays} ngày`)}
            ${tile("person", "green", a.totalUsers, "Người dùng")}
            ${tile("verified_user", "amber", a.totalModerators, "Moderator")}
            ${tile("school", "blue", a.totalAcademicManagers, "Biên tập viên tiếng Nhật")}
            ${tile("shield_person", "blue", a.totalAdmins, "Quản trị viên")}
            ${tile("lock", "red", a.totalLocked, "Tài khoản bị khóa")}
        </div>`;
}

/** Cột theo ngày; số ngày nhiều thì khung cuộn ngang. Có mô tả văn bản cho trình đọc màn hình. */
function chartHtml(days) {
    const max = Math.max(1, ...days.map((d) => d.count));
    const summary = days.map((d) => `${fullDay(d.date)}: ${d.count}`).join("; ");
    return `
        <div class="admin-chart" role="img" aria-label="Review mới mỗi ngày. ${esc(summary)}">
            <div class="admin-chart-bars">
                ${days.map((d) => `
                    <div class="admin-bar" title="${esc(fullDay(d.date))}: ${d.count} review">
                        <span class="admin-bar-value">${d.count}</span>
                        <div class="admin-bar-track"><div class="admin-bar-fill ${d.count === 0 ? "zero" : ""}"
                            style="height:${Math.round((d.count / max) * 100)}%"></div></div>
                        <span class="admin-bar-day">${esc(shortDay(d.date))}</span>
                    </div>`).join("")}
            </div>
        </div>`;
}

/** Thanh hạn mức: từ 80% thì đỏ và có chữ cảnh báo (không chỉ dựa vào màu). */
function quotaHtml(q) {
    const used = Number.isInteger(q.used) ? q.used : q.used.toFixed(2);
    return `
        <div class="admin-quota ${q.alert ? "hot" : ""}">
            <div class="admin-quota-head"><b>${esc(q.label)}</b>
                <span>${esc(used)} / ${esc(q.limit)} ${esc(q.unit)} <span class="note">(${esc(q.percent)}%, ${esc(q.window)})</span></span></div>
            <div class="admin-quota-bar" role="progressbar" aria-label="${esc(q.label)}" aria-valuemin="0"
                 aria-valuemax="100" aria-valuenow="${esc(q.percent)}">
                <div class="admin-quota-fill" style="width:${esc(q.percent)}%"></div></div>
            ${q.alert ? `<div class="admin-quota-warn">${ic("warning")} Đã dùng từ 80% hạn mức</div>` : ""}
        </div>`;
}

function configGroupHtml(group) {
    return `
        <div>
            <p class="note" style="margin:0 0 4px;"><b>${esc(group.title)}</b></p>
            <dl class="admin-kv">${group.params.map((p) =>
                `<div><dt>${esc(p.label)}</dt><dd>${esc(p.value)}</dd></div>`).join("")}</dl>
        </div>`;
}

main();
