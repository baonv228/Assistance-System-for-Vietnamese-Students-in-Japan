// "Báo đóng cửa" (Moderator, UC-M04 AF4/AF5): places that reached the "đã đóng cửa" report threshold. The system never
// sets the label by itself: the Moderator checks (the newest review, OpenStreetMap, other sources), then either
// "Xác nhận, gắn nhãn đã đóng cửa" (the pin stays, faded, reviews kept) or "Vẫn mở cửa, xóa các báo cáo". Labelled
// places are listed below with "Gỡ nhãn, địa điểm đã mở lại".
import { apiClient } from "../api/client.js";
import { mountStaffDesk } from "../components/staff-desk.js";
import { daysAgo, toast } from "../lib/mod-place-ui.js";
import { esc, fullDate, ic, osmUrl } from "../lib/ui.js";

let main = null;
let busy = false;

function placeCard(p, closed) {
    return `<div class="card" data-id="${p.id}">
        <div class="row"><b>${esc(p.name)}</b><span class="badge">${ic(p.category.icon)}${esc(p.category.name)}</span>
            ${closed ? `<span class="badge crit">Đã đóng cửa</span>` : `<span class="badge warn">${p.reports} báo đóng cửa</span>`}</div>
        <span class="note">${esc(p.address || "Chưa có địa chỉ")} · Review gần nhất: ${esc(daysAgo(p.lastReviewAt))}
            ${closed && p.closedAt ? ` · Gắn nhãn ${esc(fullDate(p.closedAt))}` : ""}</span>
        <div class="row">
            ${closed
                ? `<button class="btn sm" type="button" data-act="reopen">${ic("storefront")}Gỡ nhãn, địa điểm đã mở lại</button>`
                : `<button class="btn dan sm" type="button" data-act="close">${ic("block")}Xác nhận, gắn nhãn đã đóng cửa</button>
                   <button class="btn sm" type="button" data-act="keep">${ic("check")}Vẫn mở cửa, xóa các báo cáo</button>`}
            <a class="btn sm ghost" href="/map?place=${p.id}" target="_blank" rel="noopener">${ic("map")}Xem trên bản đồ</a>
            <a class="btn sm ghost" href="${esc(osmUrl(p))}" target="_blank" rel="noopener">${ic("travel_explore")}Tìm trên OpenStreetMap</a>
        </div>
    </div>`;
}

async function load() {
    let data;
    try {
        data = await apiClient.modClosed();
    } catch (error) {
        main.querySelector("#queue").innerHTML = `<div class="alert error" role="alert">${esc(error.message || "Không tải được danh sách.")}</div>`;
        return;
    }
    main.querySelector("#intro").textContent = `Địa điểm nhận từ ${data.threshold} báo cáo “đã đóng cửa” vào đây. `
        + "Hệ thống không tự gắn nhãn: Moderator kiểm tra (review gần nhất, OpenStreetMap) rồi mới quyết định.";
    main.querySelector("#queue").innerHTML = data.waiting.map((p) => placeCard(p, false)).join("")
        || `<div class="card">Không có địa điểm nào chờ xác minh.</div>`;
    main.querySelector("#done").innerHTML = data.closed.length
        ? `<h3>Đã gắn nhãn đóng cửa</h3>${data.closed.map((p) => placeCard(p, true)).join("")}` : "";
}

async function onClick(event) {
    const button = event.target.closest("[data-act]");
    const card = event.target.closest("[data-id]");
    if (!button || !card || busy) return;
    const id = Number(card.dataset.id);
    const call = { close: () => apiClient.modSetClosed(id, true), reopen: () => apiClient.modSetClosed(id, false),
        keep: () => apiClient.modKeepOpen(id) }[button.dataset.act];
    busy = true;
    button.disabled = true;
    try {
        const done = await call();
        toast(done.message);
    } catch (error) {
        toast(error.message, "error");
    } finally {
        busy = false;
        await load();
    }
}

const shell = await mountStaffDesk("MODERATOR", "closed");
if (shell) {
    main = shell.main;
    main.innerHTML = `
        <div><h2>Báo đóng cửa</h2><p class="note" id="intro"></p></div>
        <div class="mod-closed" id="queue" aria-live="polite"></div>
        <div class="mod-closed" id="done"></div>`;
    main.addEventListener("click", onClick);
    await load();
}
