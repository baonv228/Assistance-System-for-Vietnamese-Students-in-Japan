// "Tôi": points and rank, new-account limits, identity tag, area and stage, "Chủ đề quan tâm", guide progress
// ("Cẩm nang của tôi"), account actions (as in the prototype).
import { apiClient } from "../api/client.js";
import { mountAreaPicker } from "../components/area-picker.js";
import { mountInterestPicker } from "../components/interest-picker.js";
import { logout, mountSiteHeader } from "../components/site-header.js";
import { esc, ic, LOGO_SVG, STAGES, tagChips } from "../lib/ui.js";

const main = document.querySelector("#me");

/** Perks of each rank, keyed by the minimum points in table ranks. */
const PERKS = {
    0: ["Viết review, blog", "Xin tag định danh"],
    100: ["Bài viết được cộng 5 điểm ưu tiên khi xếp hạng Bài nổi bật", "Huy hiệu trên hồ sơ"],
    300: ["Bài viết được cộng 10 điểm ưu tiên khi xếp hạng Bài nổi bật", "Câu trả lời trong Hỏi đáp được hiện trước", "Ghim địa điểm mới lên bản đồ ngay, không cần chờ duyệt"]
};
const PROVIDERS = { GOOGLE: "Google", FACEBOOK: "Facebook" };

let areaPicker = null;
/** "Chủ đề quan tâm" of the page drawn last; a choice not saved yet is carried over when the page is drawn again. */
let interestPicker = null;
/** "/profile#guides" (the count of "Việc tiếp theo" on Home) scrolls once, not again each time the page is drawn. */
let guidesScrolled = false;
let interestsScrolled = false;
await mountSiteHeader("me");
try {
    render(await apiClient.profile());
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
            <p class="note">Review +10 · Ghim được duyệt +15 ·
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

        <div class="card" id="area">
            <h3>${ic("person_pin_circle")} Khu vực và giai đoạn</h3>
            <p class="note">Không bắt buộc. Dùng để gợi ý việc cần làm, review và sự kiện gần bạn. Chỉ lưu khu vực, không lưu địa chỉ; chỉ mình bạn thấy.</p>
            <div class="field"><label for="me-stage">Giai đoạn</label>
                <select class="select" id="me-stage"><option value="">Chưa chọn</option>
                ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === p.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
            <div id="me-area-box"><p class="note">Đang tải danh sách khu vực…</p></div>
            <div class="row"><button class="btn sm" type="button" id="save">Lưu</button><span class="note" id="saved" role="status"></span></div>
        </div>

        ${p.role === "USER" ? `<div class="card" id="interests">
            <h3>${ic("interests")} Chủ đề quan tâm</h3>
            <p class="note">Không bắt buộc. Chỉ mình bạn thấy.</p>
            <div id="interests-box" aria-busy="true"><p class="note">Đang tải…</p></div>
            <div class="row"><button class="btn sm" type="button" id="interests-save" disabled>Lưu</button>
                <span class="note" id="interests-saved" role="status"></span></div>
        </div>` : ""}

        ${p.role === "USER" ? `<div class="card" id="guides">
            <div class="row"><h3>${ic("menu_book")} Cẩm nang của tôi</h3><span class="grow"></span>
                <a class="btn sm" href="/guides">Mở Cẩm nang${ic("arrow_forward")}</a></div>
            <div id="guides-body" aria-busy="true"><p class="note">Đang tải…</p></div>
        </div>` : ""}

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
            <a class="btn" href="/users/${p.id}">${ic("badge")}Xem hồ sơ công khai của tôi</a>
            <a class="btn" href="/saved">${ic("bookmarks")}Đã lưu (${p.savedCount})</a>
            ${p.role === "USER" ? `<a class="btn" href="/messages">${ic("chat")}Tin nhắn</a>` : ""}
            <a class="btn" href="/terms?doc=tos">${ic("gavel")}Điều khoản và chính sách</a>
            <button class="btn" type="button" id="logout">${ic("logout")}Đăng xuất</button>
        </div>

        <div id="danger"><button class="btn dan" type="button" id="delete-ask">${ic("delete")}Xóa tài khoản</button></div>`;

    document.querySelector("#save").addEventListener("click", save);
    document.querySelector("#logout").addEventListener("click", logout);
    document.querySelector("#delete-ask").addEventListener("click", askDelete);
    bindPassword(p);
    bindHistory();
    const areaShown = bindArea(p);
    // Drawn again after "Lưu" of area and stage, so "Giai đoạn của bạn" follows the new stage
    if (p.role === "USER") {
        bindInterests(areaShown);
        loadGuides(areaShown);
    }
    // From "Điều chỉnh" on the page "Dành cho bạn" (/profile#area): the card is drawn after loading, so scroll now
    if (location.hash === "#area") document.querySelector("#area").scrollIntoView({ block: "start" });
}

/**
 * "Cẩm nang của tôi" (Members): done/total per stage with the member's own stage marked, the checklists left half done
 * and the guides finished last. Done = every task of a checklist ticked, or "Đánh dấu đã xong" on any other guide.
 * `areaShown` settles once the area picker above it is drawn (it makes the page taller), for "/profile#guides".
 */
async function loadGuides(areaShown) {
    const box = document.querySelector("#guides-body");
    let data;
    try {
        data = await apiClient.myGuideProgress();
    } catch (error) {
        if (box.isConnected) {
            box.innerHTML = `<div class="alert error" role="alert">${esc(error.message)}</div>`;
            box.removeAttribute("aria-busy");
        }
        return;
    }
    if (!box.isConnected) return;             // the page was drawn again meanwhile and loads its own copy
    const stageRows = data.stages.map((s) => `<li>
            <span class="what"><a class="link" href="/guides?category=${encodeURIComponent(s.code)}">${esc(s.title)}</a>${s.current
                ? `<span class="badge br">${ic("flag")}Giai đoạn của bạn</span>` : ""}</span>
            <span class="bar2" aria-hidden="true"><span style="width:${s.total ? Math.round((s.done / s.total) * 100) : 0}%"></span></span>
            <span class="note count">${s.total ? `${s.done}/${s.total}<span class="sr-only"> cẩm nang đã xong</span>` : "Chưa có"}</span>
        </li>`).join("");
    const started = data.inProgress.map((g) => `<li>
            <span class="what"><a class="link" href="/guides/${encodeURIComponent(g.id)}?tab=preparation">${esc(g.title)}</a>${g.stageTitle
                ? `<span class="note"> · ${esc(g.stageTitle)}</span>` : ""}</span>
            <span class="bar2" aria-hidden="true"><span style="width:${Number(g.percent) || 0}%"></span></span>
            <span class="note">${g.tasksDone}/${g.tasksTotal} việc${g.nextTask ? ` · Tiếp theo: ${esc(g.nextTask)}` : ""}</span>
        </li>`).join("");
    const finished = data.recentlyDone.map((g) => `<li>
            <span class="what">${ic("check_circle", "done-ic")}<a class="link" href="/guides/${encodeURIComponent(g.id)}">${esc(g.title)}</a></span>
            <span class="note">${g.stageTitle ? `${esc(g.stageTitle)} · ` : ""}Xong ngày ${esc(new Date(g.doneAt).toLocaleDateString("vi-VN"))}</span>
        </li>`).join("");
    box.innerHTML = `<p class="note">Cẩm nang có danh sách chuẩn bị xong khi bạn đánh dấu hết các việc; cẩm nang khác xong khi bạn bấm “Đánh dấu đã xong”.</p>
        <ul class="guide-stages">${stageRows}</ul>
        ${started ? `<h4 class="guide-sub">Đang làm dở</h4><ul class="guide-list">${started}</ul>` : ""}
        ${finished ? `<h4 class="guide-sub">Đã xong gần đây</h4><ul class="guide-list">${finished}</ul>` : ""}
        ${started || finished ? "" : '<p class="note">Bạn chưa bắt đầu cẩm nang nào. Mở Cẩm nang để xem việc cần làm ở từng giai đoạn.</p>'}`;
    box.removeAttribute("aria-busy");
    // The card is drawn after loading, so scroll to it now, once the area picker above it has its height
    if (location.hash === "#guides" && !guidesScrolled) {
        guidesScrolled = true;
        await areaShown;
        if (box.isConnected) document.querySelector("#guides").scrollIntoView({ block: "start" });
    }
}

/**
 * "Chủ đề quan tâm" (Members): the topics "Dành cho bạn" suggests first, chosen with toggles and saved with "Lưu" (the
 * whole choice). A choice not saved yet survives the page being drawn again (after "Lưu" of area and stage).
 * `areaShown` settles once the area picker above it is drawn, for "/profile#interests".
 */
async function bindInterests(areaShown) {
    const box = document.querySelector("#interests-box");
    const save = document.querySelector("#interests-save");
    const status = document.querySelector("#interests-saved");
    const kept = interestPicker?.changed() ? interestPicker.value() : null;
    interestPicker = null;
    let picker;
    try {
        picker = await mountInterestPicker(box, { id: "me", selected: kept, onChange: () => { status.textContent = ""; } });
    } catch (error) {
        if (box.isConnected) {
            box.innerHTML = `<div class="alert error" role="alert">${esc(error.message)}</div>`;
            box.removeAttribute("aria-busy");
        }
        return;
    }
    if (!box.isConnected) return;             // the page was drawn again meanwhile and loads its own copy
    interestPicker = picker;
    box.removeAttribute("aria-busy");
    save.disabled = false;
    save.addEventListener("click", async () => {
        save.disabled = true;                   // one save at a time
        status.textContent = "";
        try {
            await picker.save();
            status.textContent = "Đã lưu. “Dành cho bạn” trên Trang chủ đã theo các chủ đề này.";
        } catch (error) {
            status.textContent = error.message;
        } finally {
            save.disabled = false;
        }
    });
    // From "/profile#interests": the card is drawn after loading, so scroll to it now, below the area picker
    if (location.hash === "#interests" && !interestsScrolled) {
        interestsScrolled = true;
        await areaShown;
        if (box.isConnected) document.querySelector("#interests").scrollIntoView({ block: "start" });
    }
}

/** The area picker (prefecture → area, quick search, map); its label follows the stage chosen above it. */
async function bindArea(p) {
    const box = document.querySelector("#me-area-box");
    const save = document.querySelector("#save");
    save.disabled = true;                       // no save before the picker shows the current area
    areaPicker = null;
    try {
        areaPicker = await mountAreaPicker(box, { id: "me", areaId: p.areaId, stage: p.stage });
        document.querySelector("#me-stage").addEventListener("change", (e) => areaPicker.setStage(e.target.value));
        save.disabled = false;
    } catch (error) {
        box.innerHTML = `<p class="alert error">${esc(error.message)}</p>`;
    }
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
    const areaId = areaPicker ? areaPicker.value() : null;
    const stage = document.querySelector("#me-stage").value;
    try {
        const updated = await apiClient.updateProfile({ areaId, stage: stage || null });
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
            không thể khôi phục. Bài viết, câu hỏi, bình luận, câu trả lời và tin nhắn của bạn sẽ bị ẩn với mọi người; review của
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
