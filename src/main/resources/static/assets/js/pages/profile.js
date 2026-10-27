// "Tôi": points and rank, new-account limits, identity tag, area and stage, account actions (as in the prototype).
import { apiClient } from "../api/client.js";
import { logout, mountSiteHeader } from "../components/site-header.js";
import { esc, ic, LOGO_SVG, STAGES, tagChips } from "../lib/ui.js";

const soon = (feature) => `/coming-soon?f=${feature}`;
const main = document.querySelector("#me");

/** Perks of each rank, keyed by the minimum points in table ranks. */
const PERKS = {
    0: ["Viết review, blog, câu luyện tập", "Xin tag định danh"],
    100: ["Bài viết được cộng 5 điểm ưu tiên khi xếp hạng Bài nổi bật", "Huy hiệu trên hồ sơ"],
    300: ["Bài viết được cộng 10 điểm ưu tiên khi xếp hạng Bài nổi bật", "Câu trả lời trong Hỏi đáp được hiện trước"]
};
const PROVIDERS = { GOOGLE: "Google", FACEBOOK: "Facebook" };

await mountSiteHeader("me");
let areas = [];
try {
    const [profile, areaList] = await Promise.all([apiClient.profile(), apiClient.areas()]);
    areas = areaList;
    render(profile);
} catch (error) {
    main.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
}

function render(p) {
    const ranks = p.ranks;
    const index = Math.max(0, ranks.filter((r) => p.points >= r.minPoints).length - 1);
    const current = ranks[index];
    const next = ranks[index + 1];
    const progress = next ? Math.min(100, ((p.points - current.minPoints) / (next.minPoints - current.minPoints)) * 100) : 100;
    const methods = [p.hasPassword ? "Email và mật khẩu" : null, ...p.linkedProviders.map((x) => PROVIDERS[x] || x)]
        .filter(Boolean).join(", ");

    main.innerHTML = `
        <div class="greet">${LOGO_SVG}<div>
            <h2>${esc(p.displayName)}</h2>
            <div class="row">${tagChips(p.tags) || '<span class="note">Chưa có tag định danh</span>'}
                ${p.role !== "USER" ? `<span class="badge br">${esc(p.roleLabel)}</span>` : ""}
                ${p.reportMutedUntil ? `<span class="badge crit">Bị khóa báo cáo đến ${new Date(p.reportMutedUntil).toLocaleDateString("vi-VN")}</span>` : ""}</div>
            <p class="note">${esc(p.email)} · Đăng nhập bằng: ${esc(methods)}</p>
        </div></div>

        <div class="card" id="points">
            <div class="row"><b>${p.points} điểm</b><span class="badge lan">${ic("military_tech")}${esc(current.name)}</span><span class="grow"></span>
                <span class="note">${next ? `Còn ${next.minPoints - p.points} điểm nữa lên ${esc(next.name)}` : "Hạng cao nhất"}</span></div>
            <div class="bar2"><span style="width:${progress}%"></span></div>
            <div class="ranks">${ranks.map((r, i) => `
                <div class="rank ${i <= index ? "got" : ""}">
                    <div class="row"><b>${esc(r.name)}</b><span class="note">từ ${r.minPoints} điểm</span>
                        ${i <= index ? `<span class="badge ok">${ic("check")}Đã đạt</span>` : ""}</div>
                    <ul>${(PERKS[r.minPoints] || []).map((x) => `<li>${esc(x)}</li>`).join("")}</ul>
                </div>`).join("")}
            </div>
            <p class="note">Review +10 · Ghim được duyệt +15 · Câu luyện tập +2 · Câu được đánh dấu đúng +3 ·
                Câu trả lời hay nhất +5 · Bài bị gỡ −20 · Tự xóa bài thì điểm của bài đó được thu lại</p>
            <div class="row"><button class="btn sm" type="button" id="history-toggle" aria-expanded="false"
                aria-controls="history">${ic("history")}Lịch sử điểm</button></div>
            <div id="history" aria-live="polite" hidden></div>
        </div>

        ${p.newAccount ? `<div class="card">
            <div class="row"><b>Tài khoản mới</b><span class="badge warn">Tạo ${p.accountAgeDays === 0 ? "hôm nay" : `${p.accountAgeDays} ngày trước`}</span></div>
            <p class="note">Trong ${p.newAccountDays} ngày đầu, mỗi ngày bạn đăng được tối đa ${p.today.maxPosts} bài viết hoặc review,
                ${p.today.maxPins} ghim mới và ${p.today.maxVideos} video. Giới hạn này giúp chống spam.</p>
            <p class="note">Hôm nay: ${p.today.posts}/${p.today.maxPosts} bài viết và review · ${p.today.pins}/${p.today.maxPins} ghim · ${p.today.videos}/${p.today.maxVideos} video</p>
        </div>` : ""}

        ${p.pendingTag
            ? `<div class="info">Yêu cầu tag ${esc(p.pendingTag)} đang chờ duyệt. Ảnh minh chứng tự xóa sau 14 ngày nếu chưa được duyệt.</div>`
            : `<a class="btn" href="/tag-request">${ic("verified")}Xin tag định danh</a>`}

        <div class="card">
            <h3>${ic("person_pin_circle")} Khu vực và giai đoạn</h3>
            <p class="note">Không bắt buộc. Dùng để gợi ý việc cần làm, review và sự kiện gần bạn. Chỉ lưu khu vực, không lưu địa chỉ; chỉ mình bạn thấy.</p>
            <div class="two">
                <div class="field"><label for="me-area">Khu vực sinh sống</label>
                    <select class="select" id="me-area"><option value="">Chưa chọn</option>
                    ${areas.map((a) => `<option value="${a.id}" ${a.id === p.areaId ? "selected" : ""}>${esc(a.name)}</option>`).join("")}</select></div>
                <div class="field"><label for="me-stage">Giai đoạn</label>
                    <select class="select" id="me-stage"><option value="">Chưa chọn</option>
                    ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === p.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
            </div>
            <div class="row"><button class="btn sm" type="button" id="save">Lưu</button><span class="note" id="saved" role="status"></span></div>
        </div>

        <div class="card" id="password-card">
            <div class="row"><h3>${ic("password")} Mật khẩu</h3><span class="grow"></span>
                <button class="btn sm" type="button" id="pw-open">${p.hasPassword ? "Đổi mật khẩu" : "Tạo mật khẩu"}</button></div>
            <p class="note">${p.hasPassword ? "Đổi mật khẩu sẽ đăng xuất bạn khỏi các thiết bị khác."
                : "Tài khoản này đăng nhập bằng Google/Facebook. Tạo mật khẩu để đăng nhập được cả bằng email."}</p>
            <form id="pw-form" method="post" novalidate hidden>
                <div class="alert error" id="pw-error" role="alert" hidden></div>
                ${p.hasPassword ? `<div class="field"><label for="pw-current">Mật khẩu hiện tại</label>
                    <input class="select" id="pw-current" name="currentPassword" type="password" autocomplete="current-password" maxlength="72"></div>` : ""}
                <div class="field"><label for="pw-new">Mật khẩu mới</label>
                    <input class="select" id="pw-new" name="password" type="password" autocomplete="new-password" maxlength="72">
                    <span class="note">8–72 ký tự, có cả chữ và số.</span></div>
                <div class="field"><label for="pw-confirm">Nhập lại mật khẩu mới</label>
                    <input class="select" id="pw-confirm" name="confirmPassword" type="password" autocomplete="new-password" maxlength="72"></div>
                <div class="row"><button class="btn sm pri" type="submit">Lưu mật khẩu</button>
                    <button class="btn sm" type="button" id="pw-cancel">Hủy</button></div>
            </form>
            <p class="note" id="pw-done" role="status"></p>
        </div>

        <div class="actions">
            <a class="btn" href="${soon("public-profile")}">${ic("badge")}Xem hồ sơ công khai của tôi</a>
            <a class="btn" href="${soon("notebook")}">${ic("bookmark")}Sổ tay tiếng Nhật</a>
            <a class="btn" href="/saved">${ic("bookmarks")}Đã lưu (${p.savedCount})</a>
            <a class="btn" href="/terms?doc=tos">${ic("gavel")}Điều khoản và chính sách</a>
            <button class="btn" type="button" id="logout">${ic("logout")}Đăng xuất</button>
        </div>

        <div id="danger"><button class="btn dan" type="button" id="delete-ask">${ic("delete")}Xóa tài khoản</button></div>`;

    document.querySelector("#save").addEventListener("click", save);
    document.querySelector("#logout").addEventListener("click", logout);
    document.querySelector("#delete-ask").addEventListener("click", askDelete);
    bindPassword(p);
    bindHistory();
}

/** UC-U10 "Lịch sử điểm": newest first, 20 a page; content still visible is a link. */
function bindHistory() {
    const toggle = document.querySelector("#history-toggle");
    const box = document.querySelector("#history");
    const show = async (page) => {
        box.innerHTML = `<p class="note">Đang tải…</p>`;
        let data;
        try {
            data = await apiClient.pointHistory(page);
        } catch (error) {
            box.innerHTML = `<div class="alert error">${esc(error.message)}</div>`;
            return;
        }
        if (!data.total) {
            // Points given before the history was kept (e.g. imported sample data) have no lines
            box.innerHTML = `<p class="note">${data.points
                ? "Chưa có lần cộng hoặc trừ điểm nào được ghi lại."
                : "Bạn chưa có điểm nào. Viết review đầu tiên để nhận 10 điểm."}</p>`;
            return;
        }
        const pages = Math.ceil(data.total / data.size);
        box.innerHTML = `<ul class="point-list">${data.items.map((x) => `<li>
                <span class="delta ${x.delta < 0 ? "minus" : "plus"}">${x.delta > 0 ? "+" : "−"}${Math.abs(x.delta)}</span>
                <span class="what"><b>${esc(x.reasonLabel)}</b>${x.title
                    ? ` · ${x.link ? `<a class="link" href="${esc(x.link)}">${esc(x.title)}</a>` : `<span class="note">${esc(x.title)}</span>`}`
                    : ` · <span class="note">Nội dung không còn</span>`}</span>
                <span class="note when">${new Date(x.createdAt).toLocaleDateString("vi-VN")}</span></li>`).join("")}</ul>
            ${pages > 1 ? `<div class="row pager">
                <button class="btn sm" type="button" data-page="${data.page - 1}" ${data.page === 0 ? "disabled" : ""} aria-label="Trang trước">‹</button>
                <span class="note">Trang ${data.page + 1}/${pages}</span>
                <button class="btn sm" type="button" data-page="${data.page + 1}" ${data.page + 1 >= pages ? "disabled" : ""} aria-label="Trang sau">›</button></div>` : ""}`;
    };
    toggle.addEventListener("click", () => {
        const open = box.hidden;
        box.hidden = !open;
        toggle.setAttribute("aria-expanded", String(open));
        if (open) show(0);
    });
    box.addEventListener("click", (event) => {
        const b = event.target.closest("[data-page]");
        if (b) show(Number(b.dataset.page));
    });
    // From the points on Home (/profile#points): the card is drawn after loading, so scroll to it now
    if (location.hash === "#points") document.querySelector("#points").scrollIntoView({ block: "start" });
}

/** UC-U06 "Đổi mật khẩu" / AF3 "Tạo mật khẩu" for an account made with Google/Facebook. */
function bindPassword(p) {
    const form = document.querySelector("#pw-form");
    const open = document.querySelector("#pw-open");
    const errorBox = document.querySelector("#pw-error");
    const done = document.querySelector("#pw-done");
    const close = () => {
        form.reset();
        form.hidden = true;
        open.hidden = false;
        errorBox.hidden = true;
        form.querySelectorAll("[aria-invalid]").forEach((el) => el.removeAttribute("aria-invalid"));
    };
    open.addEventListener("click", () => {
        done.textContent = "";
        form.hidden = false;
        open.hidden = true;
        form.querySelector("input").focus();
    });
    document.querySelector("#pw-cancel").addEventListener("click", close);
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const button = form.querySelector("[type=submit]");
        button.disabled = true;
        try {
            const message = await apiClient.changePassword({ currentPassword: form.currentPassword?.value ?? null,
                password: form.password.value, confirmPassword: form.confirmPassword.value });
            close();
            done.textContent = message;
            if (!p.hasPassword) {
                render(await apiClient.profile());        // "Tạo mật khẩu" becomes "Đổi mật khẩu"
                document.querySelector("#pw-done").textContent = message;
            }
        } catch (error) {
            const fields = error.fields || {};
            form.querySelectorAll("input").forEach((el) => {
                if (fields[el.name]) el.setAttribute("aria-invalid", "true");
                else el.removeAttribute("aria-invalid");
            });
            errorBox.textContent = [...new Set(Object.values(fields))].join(" ") || error.message;
            errorBox.hidden = false;
        } finally {
            button.disabled = false;
        }
    });
}

async function save() {
    const status = document.querySelector("#saved");
    const areaId = document.querySelector("#me-area").value;
    const stage = document.querySelector("#me-stage").value;
    try {
        const updated = await apiClient.updateProfile({ areaId: areaId ? Number(areaId) : null, stage: stage || null });
        render(updated);
        document.querySelector("#saved").textContent = "Đã lưu.";
    } catch (error) {
        status.textContent = error.message;
    }
}

function askDelete() {
    const danger = document.querySelector("#danger");
    danger.innerHTML = `<div class="card warncard">
        <b>Xóa tài khoản?</b>
        <p class="note">Hồ sơ, điểm và tag bị xóa; yêu cầu tag đang chờ và ảnh minh chứng bị hủy. Bạn sẽ bị đăng xuất và
            không thể khôi phục. Bài viết, câu hỏi, bình luận và câu trả lời của bạn sẽ bị ẩn với mọi người; review của
            bạn được xóa hoặc ẩn danh (nhóm đang chốt).</p>
        <div class="row"><button class="btn dan solid" type="button" id="delete-yes">Xóa tài khoản</button>
            <button class="btn" type="button" id="delete-no">Hủy</button></div>
    </div>`;
    document.querySelector("#delete-no").addEventListener("click", () => {
        danger.innerHTML = `<button class="btn dan" type="button" id="delete-ask">${ic("delete")}Xóa tài khoản</button>`;
        document.querySelector("#delete-ask").addEventListener("click", askDelete);
    });
    document.querySelector("#delete-yes").addEventListener("click", async (event) => {
        event.target.disabled = true;
        try {
            await apiClient.deleteAccount();
            window.location.replace("/login?deleted=1");
        } catch (error) {
            event.target.disabled = false;
            danger.querySelector(".note").textContent = error.message;
        }
    });
}
