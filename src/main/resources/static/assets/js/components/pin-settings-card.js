// "Duyệt ghim tự động" card on the Moderator's overview (/moderator). The Moderators set the rule for new pins
// (user decision 20/11/2026): on/off, the points a member needs, and how many of one member's pins a day go on the map
// at once. GET/PUT /api/moderator/pin-settings (feature/place PinSettingsService).
import { apiClient } from "../api/client.js";
import { esc, ic } from "../lib/ui.js";

/** Draws the card at the end of {@code box}; a failed load leaves a short note instead. */
export async function mountPinSettingsCard(box) {
    const card = document.createElement("section");
    card.className = "card pin-settings";
    card.setAttribute("aria-labelledby", "ps-title");
    card.innerHTML = `<h3 id="ps-title">${ic("rule")} Duyệt ghim tự động</h3><p class="note">Đang tải…</p>`;
    box.append(card);
    let s;
    try {
        s = await apiClient.modPinSettings();
    } catch (error) {
        card.querySelector(".note").textContent = error.message || "Không tải được cấu hình duyệt ghim.";
        return;
    }
    render(card, s);
}

/** "Từ hạng “Người chỉ đường” trở lên." when the points are a rank's start, else the plain number. */
function whoPasses(s, minPoints) {
    const rank = s.ranks.find((r) => r.minPoints === minPoints);
    return rank ? `Tức là từ hạng “${rank.name}” trở lên.` : `Thành viên có từ ${minPoints} điểm trở lên.`;
}

function render(card, s) {
    const rankHint = s.ranks.map((r) => `${esc(r.name)} từ ${r.minPoints} điểm`).join(" · ");
    card.innerHTML = `
        <h3 id="ps-title">${ic("rule")} Duyệt ghim tự động</h3>
        <p class="note">Ghim mới của thành viên đủ điểm lên bản đồ ngay, không chờ duyệt (+15 điểm như khi được duyệt).
            Ghim “Có thể trùng” mà người gửi vẫn gửi luôn chờ Moderator. Mọi thay đổi được ghi vào nhật ký hoạt động.</p>
        <form class="ps-form" novalidate>
            <div class="alert error" role="alert" tabindex="-1" hidden></div>
            <label class="check"><input type="checkbox" name="autoApprove" ${s.autoApprove ? "checked" : ""}>
                Bật tự động duyệt ghim</label>
            <div class="ps-row">
                <div class="field"><label for="ps-points">Điểm tối thiểu</label>
                    <input class="input" id="ps-points" name="minPoints" type="number" min="0" max="100000" step="1" value="${s.minPoints}">
                    <span class="note" id="ps-who">${esc(whoPasses(s, s.minPoints))}</span></div>
                <div class="field"><label for="ps-day">Số ghim tự duyệt tối đa mỗi người mỗi ngày</label>
                    <input class="input" id="ps-day" name="perDay" type="number" min="1" max="50" step="1" value="${s.perDay}">
                    <span class="note">Ghim thứ ${s.perDay + 1} trong ngày trở đi chờ duyệt.</span></div>
            </div>
            <p class="note">${ic("military_tech")} Hạng: ${rankHint}</p>
            <div class="row"><button class="btn pri" type="submit">${ic("save")}Lưu</button>
                <a class="btn ghost" href="/moderator/pins">${ic("add_location_alt")}Ghim đang chờ duyệt: ${s.waiting}</a></div>
        </form>`;
    const form = card.querySelector("form");
    const points = form.querySelector("#ps-points");
    const day = form.querySelector("#ps-day");
    const toggle = () => {
        const on = form.autoApprove.checked;
        points.disabled = !on;
        day.disabled = !on;
    };
    toggle();
    form.autoApprove.addEventListener("change", toggle);
    points.addEventListener("input", () => {
        form.querySelector("#ps-who").textContent = whoPasses(s, Number(points.value) || 0);
    });
    form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const button = form.querySelector("[type=submit]");
        const error = form.querySelector(".alert");
        error.hidden = true;
        form.querySelectorAll("[aria-invalid]").forEach((el) => el.removeAttribute("aria-invalid"));
        button.disabled = true;
        try {
            const saved = await apiClient.modSavePinSettings({ autoApprove: form.autoApprove.checked,
                minPoints: Number(points.value), perDay: Number(day.value) });
            render(card, saved);
            const note = document.createElement("p");
            note.className = "alert success";
            note.setAttribute("role", "status");
            note.textContent = "Đã lưu cấu hình duyệt ghim.";
            card.querySelector("form").prepend(note);
        } catch (failure) {
            error.textContent = Object.values(failure.fields || {}).join(" ") || failure.message;
            error.hidden = false;
            if (failure.fields?.minPoints) points.setAttribute("aria-invalid", "true");
            if (failure.fields?.perDay) day.setAttribute("aria-invalid", "true");
            error.focus();
        } finally {
            button.disabled = false;
        }
    });
}
