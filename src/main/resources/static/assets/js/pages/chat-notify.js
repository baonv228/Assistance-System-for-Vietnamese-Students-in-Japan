// "Cài đặt thông báo": the member's choices for new chat messages (feature/chat/notify) in a dialog of its own, opened
// from the "Tin nhắn" page. "Thông báo qua e-mail" and "Thông báo trên trình duyệt" are member-wide switches saved at
// once (the digest e-mail names these labels: keep them); "Trình duyệt này" turns Web Push on or off for this browser
// only (lib/push.js). The in-app notice of the header has no switch: it is always on. Neither an e-mail nor a push
// ever carries the message text.

import { apiClient } from "../api/client.js";
import { appleMobile, homeScreenApp, IN_APP_ADVICE, inAppBrowser } from "../lib/device.js";
import { esc, ic, loginUrl } from "../lib/ui.js";

const STYLES = "/assets/css/chat-media.css";

const TEXT = {
    loading: "Đang tải…",
    saving: "Đang lưu…",
    saved: "Đã lưu cài đặt thông báo.",
    checking: "Đang kiểm tra…",
    http: "Thông báo trên trình duyệt chỉ bật được khi mở Senpai ơi qua địa chỉ https. Bạn đang mở qua địa chỉ http, "
        + "nên trình duyệt không cho phép.",
    iphone: "Trên iPhone và iPad, thông báo chỉ hoạt động khi Senpai ơi được thêm vào Màn hình chính: trong Safari, bấm "
        + "nút Chia sẻ → “Thêm vào MH chính”, rồi mở Senpai ơi từ biểu tượng đó và bật ở đây (cần iOS 16.4 trở lên). "
        + "Lần đầu mở từ biểu tượng, bạn cần đăng nhập và nhập mật khẩu khôi phục thêm một lần.",
    denied: "Bạn đã chặn thông báo của Senpai ơi trên trình duyệt này. Muốn bật lại, bấm biểu tượng ổ khóa (hoặc ⓘ) cạnh "
        + "địa chỉ trang → Thông báo → Cho phép, rồi tải lại trang.",
    unsupported: "Trình duyệt này không hỗ trợ thông báo đẩy. Bạn vẫn nhận được thông báo ngay trên trang và qua e-mail.",
    noModule: "Chưa tải được phần thông báo trên trình duyệt. Hãy tải lại trang rồi thử lại.",
    on: "Đã bật trên trình duyệt này.",
    off: "Chưa bật trên trình duyệt này.",
    memberOff: "“Thông báo trên trình duyệt” đang tắt ở trên, nên trình duyệt này chưa nhận được thông báo.",
    enable: "Bật thông báo trên trình duyệt này",
    disable: "Tắt trên trình duyệt này",
    enabled: "Đã bật thông báo trên trình duyệt này.",
    disabled: "Đã tắt thông báo trên trình duyệt này.",
    notAllowed: "Bạn chưa cho phép thông báo nên chưa bật được. Hãy bấm lại và chọn “Cho phép”.",
    enableFailed: "Không bật được thông báo trên trình duyệt này. Hãy thử lại sau.",
    signedOut: "Bạn chưa đăng nhập."
};

let dialog = null;
let els = null;
let settings = null;                                    // { emailEnabled, pushEnabled } once loaded
let pushLoading = null;                                 // import of lib/push.js, once
let deviceBusy = false;

/**
 * Opens the dialog (made on first use) with the current values loaded. Resolves when it is closed again.
 */
export function openNotifySettings() {
    ensureStyles();
    build();
    say("");
    if (!dialog.open) {
        // showModal() puts the focus on the first control (the switches wait for their values: "Đóng")
        if (typeof dialog.showModal === "function") dialog.showModal(); else dialog.setAttribute("open", "");
    }
    load();
    return new Promise((resolve) => dialog.addEventListener("close", () => resolve(), { once: true }));
}

/** The dialog's look lives in chat-media.css, which the "Tin nhắn" page links; any other page gets it here. */
function ensureStyles() {
    const present = [...document.querySelectorAll('link[rel="stylesheet"]')]
        .some((link) => new URL(link.href, location.href).pathname === STYLES);
    if (!present) {
        const link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = STYLES;
        document.head.append(link);
    }
}

function build() {
    if (dialog) return;
    dialog = document.createElement("dialog");
    dialog.className = "cn-dlg";
    dialog.setAttribute("aria-labelledby", "cn-title");
    dialog.setAttribute("aria-describedby", "cn-desc");
    dialog.innerHTML = `<div class="cn-body">
        <div class="cn-head"><h3 id="cn-title">${ic("notifications")}Cài đặt thông báo</h3>
            <button type="button" class="cn-x" data-cn-close title="Đóng">${ic("close")}<span class="sr-only">Đóng</span></button></div>
        <p class="note" id="cn-desc">Khi có tin nhắn mới, Senpai ơi báo ngay trên trang bạn đang xem. Nội dung tin nhắn
            không bao giờ được đưa vào e-mail hay thông báo, chỉ tên người gửi.</p>
        <label class="cn-switch"><input type="checkbox" role="switch" id="cn-email" data-cn-field="emailEnabled" disabled>
            <span class="cn-label"><b>Thông báo qua e-mail</b>
                <span class="note">Gửi khi tin nhắn chưa được đọc sau khoảng 10 phút; mỗi cuộc trò chuyện nhiều nhất một
                    e-mail trong 6 giờ.</span></span></label>
        <label class="cn-switch"><input type="checkbox" role="switch" id="cn-push" data-cn-field="pushEnabled" disabled>
            <span class="cn-label"><b>Thông báo trên trình duyệt</b>
                <span class="note">Hiện trên điện thoại hoặc máy tính kể cả khi bạn không mở Senpai ơi, trên những trình
                    duyệt bạn đã bật ở dưới.</span></span></label>
        <section class="cn-device" aria-labelledby="cn-device-title">
            <h4 id="cn-device-title">${ic("devices")}Trình duyệt này</h4>
            <p class="cn-device-state" id="cn-device-state">${esc(TEXT.checking)}</p>
            <button type="button" class="btn sm pri" id="cn-device-btn" hidden></button>
        </section>
        <p class="cn-status" id="cn-status" role="status" aria-live="polite"></p>
        <div class="cn-actions"><button type="button" class="btn" data-cn-close>Xong</button></div>
    </div>`;
    document.body.append(dialog);
    els = {
        email: dialog.querySelector("#cn-email"),
        push: dialog.querySelector("#cn-push"),
        state: dialog.querySelector("#cn-device-state"),
        device: dialog.querySelector("#cn-device-btn"),
        status: dialog.querySelector("#cn-status")
    };
    dialog.querySelectorAll("[data-cn-close]").forEach((b) => b.addEventListener("click", close));
    // A click on the dark backdrop (the dialog element itself, outside its body) closes it
    dialog.addEventListener("click", (event) => {
        if (event.target === dialog) close();
    });
    dialog.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && typeof dialog.showModal !== "function") close();
    });
    [els.email, els.push].forEach((input) => input.addEventListener("change", () => save(input)));
    els.device.addEventListener("click", toggleDevice);
}

function close() {
    if (!dialog.open) return;
    if (typeof dialog.close === "function") dialog.close(); else dialog.removeAttribute("open");
}

// ------------------------------------------------------------------------------------------------ member-wide switches

async function load() {
    els.email.disabled = true;
    els.push.disabled = true;
    say(TEXT.loading);
    try {
        settings = await apiClient.chatNotifySettings();
        apply();
        say("");
    } catch (error) {
        settings = null;
        sayError(error);
    }
    renderDevice();
}

function apply() {
    els.email.checked = !!settings?.emailEnabled;
    els.push.checked = !!settings?.pushEnabled;
    els.email.disabled = !settings;
    els.push.disabled = !settings;
}

/** Saves one switch as soon as it changes; on failure it goes back and the reason is shown. */
async function save(input) {
    const field = input.dataset.cnField;
    const value = input.checked;
    input.disabled = true;
    say(TEXT.saving);
    try {
        settings = await apiClient.updateChatNotifySettings({ [field]: value });
        say(TEXT.saved, "ok");
    } catch (error) {
        input.checked = !value;
        sayError(error);
    } finally {
        if (settings) apply();
        renderDevice();
    }
}

// -------------------------------------------------------------------------------------------------------- this browser

function pushModule() {
    pushLoading ??= import("../lib/push.js").catch(() => null);
    return pushLoading;
}

/** Why Web Push cannot work here, or null. The page explains it instead of offering a button that fails. */
function impossibleReason(push) {
    if (!window.isSecureContext) return TEXT.http;
    if (inAppBrowser()) return IN_APP_ADVICE;
    if (appleMobile() && !homeScreenApp()) return TEXT.iphone;
    if (!push) return TEXT.noModule;
    if (!push.pushSupported()) return TEXT.unsupported;
    return null;
}

async function renderDevice() {
    if (deviceBusy) return;
    const push = await pushModule();
    const reason = impossibleReason(push);
    let state = null;
    if (!reason) {
        try {
            state = await push.pushState();
        } catch {
            state = "off";
        }
    }
    const text = reason ?? { unsupported: TEXT.unsupported, denied: TEXT.denied, on: TEXT.on }[state] ?? TEXT.off;
    const hint = state === "on" && settings && !settings.pushEnabled ? ` ${TEXT.memberOff}` : "";
    els.state.textContent = text + hint;
    els.state.classList.toggle("cn-warn", !!reason || state === "denied" || !!hint);
    const offer = state === "on" || state === "off";
    els.device.hidden = !offer;
    els.device.disabled = !offer || !settings;
    els.device.className = state === "on" ? "btn sm" : "btn sm pri";
    els.device.dataset.action = state === "on" ? "disable" : "enable";
    els.device.innerHTML = state === "on"
        ? `${ic("notifications_off")}${esc(TEXT.disable)}`
        : `${ic("notifications_active")}${esc(TEXT.enable)}`;
}

/** "Bật/Tắt trên trình duyệt này": the click itself asks the permission (Safari allows it only from a click). */
async function toggleDevice() {
    const enable = els.device.dataset.action !== "disable";
    const push = await pushModule();
    if (!push) {
        say(TEXT.noModule, "error");
        return;
    }
    deviceBusy = true;
    els.device.disabled = true;
    say(enable ? "Đang bật…" : "Đang tắt…");
    try {
        if (enable) {
            const state = await push.enablePush();      // also turns the member-wide push switch on
            if (state === "on") {
                settings = await apiClient.chatNotifySettings().catch(() => settings);
                if (settings) apply();
                say(TEXT.enabled, "ok");
            } else if (state === "denied") {
                say(TEXT.denied, "error");
            } else if (state === "unsupported") {
                say(TEXT.unsupported, "error");
            } else {
                say(TEXT.notAllowed, "error");
            }
        } else {
            await push.disablePush();
            say(TEXT.disabled, "ok");
        }
    } catch (error) {
        sayError(error, TEXT.enableFailed);
    } finally {
        deviceBusy = false;
        renderDevice();
    }
}

// ------------------------------------------------------------------------------------------------------------ status

function say(text, kind = "") {
    els.status.className = `cn-status ${kind}`;
    els.status.textContent = text;
}

/**
 * Server errors already speak Vietnamese (ApiError); a browser error (push service down, a refused subscription) is
 * in English, so the member gets `fallback` and the details go to the console.
 */
function sayError(error, fallback = "Có lỗi xảy ra, vui lòng thử lại.") {
    if (error?.status === 401) {
        els.status.className = "cn-status error";
        els.status.innerHTML = `${esc(TEXT.signedOut)} <a class="link" href="${esc(loginUrl())}">Đăng nhập</a>`;
        return;
    }
    if (error?.name === "NotAllowedError") {
        say(TEXT.notAllowed, "error");
        return;
    }
    const fromServer = typeof error?.status === "number" && error.message;
    if (!fromServer) console.warn("Chat notifications:", error);
    say(fromServer ? error.message : fallback, "error");
}
