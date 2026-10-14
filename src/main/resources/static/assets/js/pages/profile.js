// "Tôi": points and rank, new-account limits, identity tag, area and stage, account actions (as in the prototype).
import { apiClient } from "../api/client.js";
import { logout, mountSiteHeader } from "../components/site-header.js";
import { esc, ic, LOGO_SVG, STAGES, tagChips } from "../lib/ui.js";

const soon = (feature) => `/pages/coming-soon.html?f=${feature}`;
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

        <div class="card">
            <div class="row"><b>${p.points} điểm</b><span class="badge lan">${esc(current.name)}</span><span class="grow"></span>
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
        </div>

        ${p.newAccount ? `<div class="card">
            <div class="row"><b>Tài khoản mới</b><span class="badge warn">Tạo ${p.accountAgeDays === 0 ? "hôm nay" : `${p.accountAgeDays} ngày trước`}</span></div>
            <p class="note">Trong ${p.newAccountDays} ngày đầu, mỗi ngày bạn đăng được tối đa ${p.today.maxPosts} bài viết hoặc review,
                ${p.today.maxPins} ghim mới và ${p.today.maxVideos} video. Giới hạn này giúp chống spam.</p>
            <p class="note">Hôm nay: ${p.today.posts}/${p.today.maxPosts} bài viết và review · ${p.today.pins}/${p.today.maxPins} ghim · ${p.today.videos}/${p.today.maxVideos} video</p>
        </div>` : ""}

        ${p.pendingTag
            ? `<div class="info">Yêu cầu tag ${esc(p.pendingTag)} đang chờ duyệt. Ảnh minh chứng tự xóa sau 14 ngày nếu chưa được duyệt.</div>`
            : `<a class="btn" href="${soon("tag")}">${ic("verified")}Xin tag định danh</a>`}

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

        <div class="actions">
            <a class="btn" href="${soon("public-profile")}">${ic("badge")}Xem hồ sơ công khai của tôi</a>
            <a class="btn" href="${soon("notebook")}">${ic("bookmark")}Sổ tay tiếng Nhật</a>
            <a class="btn" href="${soon("saved")}">${ic("bookmarks")}Đã lưu</a>
            <a class="btn" href="/pages/terms.html?doc=tos">${ic("gavel")}Điều khoản và chính sách</a>
            <button class="btn" type="button" id="logout">${ic("logout")}Đăng xuất</button>
        </div>

        <div id="danger"><button class="btn dan" type="button" id="delete-ask">${ic("delete")}Xóa tài khoản</button></div>`;

    document.querySelector("#save").addEventListener("click", save);
    document.querySelector("#logout").addEventListener("click", logout);
    document.querySelector("#delete-ask").addEventListener("click", askDelete);
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
            không thể khôi phục. Bài viết và review của bạn được xóa hoặc ẩn danh (nhóm đang chốt).</p>
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
            window.location.replace("/pages/login.html?deleted=1");
        } catch (error) {
            event.target.disabled = false;
            danger.querySelector(".note").textContent = error.message;
        }
    });
}
