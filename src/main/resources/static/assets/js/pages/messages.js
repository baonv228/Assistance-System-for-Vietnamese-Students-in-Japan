// "Tin nhắn": the member's conversations (inbox and "Lời mời") and the one open on the right, as in a messenger app.
// /messages/{id} opens a conversation, /messages?to={userId} the conversation with a member ("Nhắn tin" next to their
// name; the first message makes it). While the tab is in front the open conversation asks for new and changed messages
// every few seconds and the list every ~16 s; nothing is asked while it is hidden. What is typed stays per
// conversation, and across a trip to the sign-in page when the session has ended.
//
// End-to-end encryption (lib/chat-crypto.js): once both members accepted the conversation and both turned encryption
// on, the server takes only encrypted messages. This page holds the member's key (unlocked with their "mật khẩu khôi
// phục", kept in the browser), encrypts what is sent and decrypts what is shown, also for the list previews, quotes and
// search (the server cannot search what it cannot read). Requests stay plain text, filtered by the server as before.
// Photos, video and voice messages (pages/chat-media.js) exist only in encrypted conversations.
//
// Messages are drawn one element per message id and only new or changed parts are redrawn, so a video keeps playing
// and photos are not fetched again when news arrive.
import { apiClient } from "../api/client.js";
import { mountSiteHeader, setChatCount } from "../components/site-header.js";
import { avatar, esc, fullDate, ic, loginUrl, tagChips, timeAgo } from "../lib/ui.js";
import {
    conversationKey, createIdentity, cryptoSupported, decryptMessage, encryptMessage, KeyStorageError,
    loadLocalIdentity, PASSPHRASE_MIN_LENGTH, safetyNumber, saveLocalIdentity, unlockIdentity, UnreadableMessageError,
    WrongPassphraseError
} from "../lib/chat-crypto.js";
import { linkify, oneLine, snippet } from "../lib/chat-text.js";
import { appleMobile, homeScreenApp, IN_APP_ADVICE, inAppBrowser, touchOnly } from "../lib/device.js";
import { liveOpen, onLive } from "../lib/live.js";

const ROOM_MS = 4000;
/** While the live stream is open it announces every change at once; asking is only a safety net then. */
const ROOM_LIVE_MS = 15000;
const LIST_EVERY = 4;                       // the list is asked every 4th time the conversation is
const LIST_SOON_MS = 300;                   // announcements arriving together refresh the list once
const TYPING_SHOW_MS = 6000;                // an announced "đang soạn tin" shows this long (as the server keeps it)
const TEXT_MAX_HEIGHT = 160;
const COUNTER_FROM = 1800;
/** Notices shown in red: the member cannot be written to at all. */
const CRIT = ["UNAVAILABLE", "DELETED", "BLOCKED_BY_ME"];
const DRAFT_STORE = "asvsj.chatDrafts";
const KEY_PROMPT_STORE = "asvsj.chat.keyPrompt.";        // + user id: the key dialog was shown in this tab session
const MSG40 = "Hãy chọn lý do báo cáo.";
const TYPING_MS = 3000;                     // "đang soạn tin" is sent at most this often (the server keeps it 6 s)
const LONG_PRESS_MS = 450;
const FIRST_DECRYPT_MS = 800;               // a conversation waits this long for its messages to decrypt before drawing
const RETRY_MS = 15000;                     // a decryption or quote that failed on the network is tried again after
const CHANGED_LOOKBACK_MS = 60000;          // the first "changed since" of a conversation: a minute before it opened
const OLDER_BATCH = 200;                    // the server's largest page of older messages
const SEARCH_CAP = 3000;                    // search reads at most this many messages of a conversation
const SEARCH_PAGES = 40;
const JUMP_PAGES = 10;                      // a quote or search hit further back than this many pages is not loaded
const SEARCH_SHOWN = 50;
const RECALLED_MINE = "Bạn đã thu hồi một tin nhắn";
const RECALLED_THEIRS = "Tin nhắn đã được thu hồi";
const UNREADABLE = "Không đọc được tin nhắn này";
const DECRYPTING = "Đang giải mã…";
const NEED_HTTPS = "Mã hóa đầu cuối chỉ chạy khi mở Senpai ơi qua địa chỉ https.";
const FILES_NEED_E2EE = "Chỉ gửi được ảnh, video, ghi âm trong cuộc trò chuyện đã mã hóa đầu cuối.";

const $ = (selector) => document.querySelector(selector);
const els = {
    list: $("#list"), note: $("#list-note"), more: $("#more"), requests: $("#count-REQUESTS"),
    blocksToggle: $("#blocks-toggle"), blocks: $("#blocks"), keyBanner: $("#key-banner"),
    empty: $("#empty"), room: $("#room"), head: $("#room-head"), thread: $("#thread"), older: $("#older"),
    msgs: $("#msgs"), notice: $("#notice"), typing: $("#typing"), compose: $("#compose"), text: $("#text"),
    send: $("#send"), counter: $("#counter"), sendError: $("#send-error"), progress: $("#progress"),
    composeBar: $("#compose-bar"), recordBar: $("#record-bar"), pending: $("#pending"),
    attachTools: $("#attach-tools"), attach: $("#attach"), attachInput: $("#attach-input"), record: $("#record"),
    search: $("#search"), searchInput: $("#search-input"), searchNote: $("#search-note"),
    searchResults: $("#search-results"), toast: $("#toast")
};
const reportDlg = $("#report-dlg");
const reportForm = $("#report-form");
const reportError = $("#report-error");
const confirmDlg = $("#confirm-dlg");
const setupDlg = $("#key-setup-dlg");
const unlockDlg = $("#key-unlock-dlg");
const safetyDlg = $("#safety-dlg");
const SETUP_INTRO = $("#key-setup-intro").textContent;
// The bubble of a message: quote, attachments and text, then reactions and the line with time and tools. Each part is
// redrawn on its own, so the attachments of a message stay in place while its reactions change.
const MSG_SLOTS = `<div class="msg-body"><div data-slot="quote"></div><div class="msg-attach" data-slot="files"></div>`
    + `<div class="msg-text" data-slot="text"></div></div><div class="msg-reacts" data-slot="reacts"></div>`
    + `<div class="msg-meta" data-slot="meta"></div>`;

let box = "INBOX";
let counts = { unread: 0, requests: 0 };
let items = [];             // rows of the list shown
let listPage = 0;
let listLoading = true;     // the first page of the tab is being asked ("Đang tải…")
let room = null;            // the open conversation: API data + toUserId (before the first message), changedSince, pool
let openTicket = 0;
let markedUpTo = 0;         // newest message of the other member already marked read
let sending = false;
let timer = null;
let ticks = 0;
let reportTarget = null;    // { messageId, room, evidence }
let toastTimer;
const drafts = new Map();   // "c{id}" or "u{userId}" → text being typed

let media = null;           // pages/chat-media.js, null when it could not be loaded (text still works)
// Encryption: "unsupported" (no WebCrypto: plain-http address), "none" (encryption not turned on), "locked" (on, but
// this browser has no key yet), "ready", "error" (the key could not be read)
let keyState = "unknown";
let keyError = "";
let keyLoading = null;
let identity = null;        // { keyId, publicKey, privateKey } of the member's current key
let legacy = null;          // a key this browser had before a newer one was made elsewhere (its old messages)
let serverKey = null;       // the member's current key backup on the server (chatMyKey)
let keyMemoryOnly = false;  // the browser refused to keep the key: asked again on the next visit
let setupReplace = false;
const publicKeys = new Map();   // key id → Promise<base64 public key>
const convKeys = new Map();     // "conversation:myKeyId:peerKeyId" → Promise<CryptoKey>
const decrypted = new Map();    // "messageId:iv" → { state: pending|ok|unreadable|failed, body, frankingKey, plaintext }
const quotes = new Map();       // message id → { message } | { gone } | { failed } of a quoted message not loaded

let pending = [];           // attachments chosen for the next message (chat-media.js Attachment)
let pendingUpload = null;   // { items, result }: those already uploaded, when sending failed after the upload
let recorder = null;
let recordSeconds = 0;
let recordStopping = false;
let replyTo = null;         // { id } of the message being answered
let editing = null;         // { id, stash (the draft put aside), files, original }
let menu = null;            // { id, picking } of the message whose tools are open (long press, emoji button)
let press = null;           // a touch held on a message
let lastTyping = 0;
let stick = true;           // the thread is scrolled to the newest message (and follows new ones)
let redrawTimer = null;
let searchTimer = null;
let typingShown = "";
let signingOut = false;     // "Đăng xuất" was pressed: nothing typed is kept in this tab any more
let polling = false;        // a tick() is asking the server now
let pollAgain = false;      // something was announced meanwhile: ask once more right after
let listTimer = null;
let typingTimer = null;
let outboxSeq = 0;          // keys of the messages shown as "Đang gửi…" (room.outbox)
let sendQueued = false;     // "Gửi" pressed while a message was on its way: send what is typed right after it
let sendingFiles = false;   // the message on its way has photos, video or voice (the button waits for it)
let pollAfterSend = false;  // a poll was held back while an outbox message was on its way
// Every module-level declaration stays above the start below: code after its "await" runs later (TDZ)

// ---------------------------------------------------------------- start

const user = await mountSiteHeader("messages");
fitToWindow();
window.addEventListener("resize", fitToWindow);
window.visualViewport?.addEventListener("resize", fitToWindow);
window.visualViewport?.addEventListener("scroll", fitToWindow);
document.addEventListener("focusin", fitToWindow);
document.addEventListener("focusout", () => setTimeout(fitToWindow, 50));   // focus may only be moving to the next field
document.fonts?.ready.then(fitToWindow);           // the header's height changes once its fonts are in
if (user && user.role === "USER") {
    restoreDrafts();
    wire();
    await Promise.all([loadModules(), loadIdentity()]);
    renderKeyBanner();
    await loadList(0);
    await route();
    window.addEventListener("popstate", route);
    document.addEventListener("visibilitychange", () => {
        if (!document.hidden) {
            forgetKeptDrafts();
            clearTimeout(timer);
            tick();
        } else {
            keepDrafts();                           // a phone may drop the tab while it is in the background
        }
    });
    window.addEventListener("pagehide", keepDrafts);
    window.addEventListener("asvsj:signing-out", () => {
        signingOut = true;
        forgetKeptDrafts();
    });
    onLive("chat", onChatEvent);
    onLive("open", () => tick(true));               // (re)connected: what came while it was down
    schedule();
    promptForKey();
} else {
    // Staff accounts do not chat (the API answers MSG18); guests are sent to sign in by the server
    els.note.textContent = "Tin nhắn chỉ dành cho thành viên.";
    document.querySelector(".chat-tabs").hidden = true;
    els.blocksToggle.hidden = true;
}

/**
 * The page fills the window between the header and, on phones, the tab bar. While the member types on a phone the tab
 * bar is hidden (it would sit on or above the keyboard). iPhone Safari keeps the page as tall as the screen behind the
 * keyboard and scrolls it: there the page takes the height that stays visible (visualViewport) and is held at the top,
 * so the header, the newest messages and the message box all stay in sight.
 */
function fitToWindow() {
    const field = document.activeElement;
    const typing = touchOnly() && Boolean(field?.closest?.(".chatpage")) && field.matches("textarea, input");
    const view = window.visualViewport;
    const covered = typing && Boolean(view) && view.height < window.innerHeight - 100;
    const root = document.documentElement.style;
    document.body.classList.toggle("chat-keyboard", typing);
    if (covered) {
        root.setProperty("--chat-view", `${Math.round(view.height)}px`);
        if (window.scrollY > 0) window.scrollTo(0, 0);
    } else {
        root.removeProperty("--chat-view");
    }
    const top = document.querySelector(".site-top")?.offsetHeight ?? 61;
    const bar = document.querySelector(".tabbar");
    const bottom = bar && getComputedStyle(bar).display !== "none" ? bar.offsetHeight : 0;
    root.setProperty("--chat-top", `${top}px`);
    root.setProperty("--chat-bottom", `${bottom}px`);
}

/**
 * Photos/videos/voice messages come from their own module; when it cannot be loaded the page still sends and reads
 * text, and says what is missing.
 */
async function loadModules() {
    try {
        media = await import("./chat-media.js");
    } catch (error) {
        console.error("chat-media.js", error);
        toast("Không tải được phần ảnh, video, ghi âm của trang. Tin nhắn chữ vẫn dùng được.", "error");
    }
}

/** Sets the tab title; the header puts the unread count in front of it again. */
function setTitle(text) {
    document.title = text;
    setChatCount(counts.unread);
}

// ---------------------------------------------------------------- address

/** Opens what the address says: a conversation, the one with a member, or none. */
async function route() {
    const path = /^\/messages\/(\d+)$/.exec(location.pathname);
    const to = new URLSearchParams(location.search).get("to");
    if (path) {
        await openConversation(() => apiClient.chat(Number(path[1])));
    } else if (to && /^\d{1,18}$/.test(to)) {
        await openConversation(() => apiClient.chatWith(Number(to)), Number(to));
    } else {
        closeRoom();
    }
}

function go(path) {
    if (location.pathname + location.search !== path) history.pushState(null, "", path);
    route();
}

// ---------------------------------------------------------------- encryption key

/**
 * Reads the key kept in this browser and the account's current key on the server, and decides what the page can do
 * with encrypted messages (keyState).
 */
function loadIdentity() {
    if (!cryptoSupported()) {
        keyState = "unsupported";
        return Promise.resolve();
    }
    if (!keyLoading) {
        keyLoading = (async () => {
            try {
                const [local, mine] = await Promise.all([
                    loadLocalIdentity(user.id).catch((error) => {
                        if (!(error instanceof KeyStorageError)) console.warn("chat key", error);
                        return null;
                    }),
                    apiClient.chatMyKey()
                ]);
                serverKey = mine?.key ?? null;
                keyError = "";
                if (identity && serverKey && identity.keyId === serverKey.id) {
                    keyState = "ready";                 // unlocked in this visit (maybe not kept by the browser)
                } else if (local && serverKey && local.keyId === serverKey.id) {
                    setIdentity(local);
                    keyState = "ready";
                } else {
                    // A key of this browser older than the account's current one still opens the messages written with it
                    const old = identity ?? local;
                    if (old && old.keyId !== serverKey?.id) legacy = old;
                    identity = null;
                    keyState = serverKey ? "locked" : "none";
                }
            } catch (error) {
                if (signedOut(error)) return;
                keyState = "error";
                keyError = error.message;
            } finally {
                keyLoading = null;
            }
        })();
    }
    return keyLoading;
}

function setIdentity(next) {
    identity = next;
    // messages that could not be opened before may open now
    for (const [key, entry] of decrypted) {
        if (entry.state !== "ok" && entry.state !== "pending") decrypted.delete(key);
    }
}

/** After the key changed: banner, list previews, the open conversation (its status says whether it is E2EE now). */
async function afterKeyChange(reload) {
    renderKeyBanner();
    renderList();
    if (room) {
        renderHead();
        renderNotice();
        renderMessages("keep");
    }
    if (reload) {
        await refreshList();
        if (room) await reloadRoom();
    }
}

/** The server says the member's key is another one than this browser's (made on another device): ask again. */
async function refreshMyKey() {
    if (keyLoading) return;
    await loadIdentity();
    await afterKeyChange(false);
    if (keyState === "locked") openUnlock();
}

/** The dialog to turn encryption on or to unlock the key, once per tab session (the banner offers it later). */
function promptForKey() {
    if (keyState !== "none" && keyState !== "locked") return;
    const flag = KEY_PROMPT_STORE + user.id;
    try {
        if (sessionStorage.getItem(flag)) return;
        sessionStorage.setItem(flag, "1");
    } catch {
        // storage blocked: the dialog is shown on every visit
    }
    if (keyState === "none") openSetup(false);
    else openUnlock();
}

function renderKeyBanner() {
    let html = "";
    if (keyState === "unsupported") {
        const href = httpsHref();
        html = `${ic("lock")}<p>${NEED_HTTPS} Ở địa chỉ này bạn không đọc được tin nhắn đã mã hóa; lời mời và tin nhắn chưa mã
            hóa vẫn dùng bình thường.</p>
            ${href ? `<a class="btn sm pri" href="${esc(href)}">Mở bằng https</a>` : ""}`;
    } else if (keyState === "none") {
        html = `${ic("lock")}<p>Bật mã hóa đầu cuối để chỉ bạn và người nhận đọc được tin nhắn, và để gửi được ảnh, video, ghi âm.</p>
            <button class="btn sm pri" type="button" data-key="setup">Bật mã hóa</button>`;
    } else if (keyState === "locked") {
        html = `${ic("key")}<p>Nhập mật khẩu khôi phục để đọc và gửi tin nhắn đã mã hóa trên trình duyệt này.</p>
            <button class="btn sm pri" type="button" data-key="unlock">Nhập mật khẩu khôi phục</button>`;
    } else if (keyState === "error") {
        html = `${ic("error")}<p>Chưa tải được khóa mã hóa: ${esc(keyError)}</p>
            <button class="btn sm" type="button" data-key="retry">Thử lại</button>`;
    } else if (keyState === "ready" && keyMemoryOnly) {
        html = `${ic("info")}<p>Trình duyệt này không lưu được khóa mã hóa trên máy (có thể do chế độ ẩn danh). Lần sau bạn sẽ
            cần nhập lại mật khẩu khôi phục.</p>`;
    }
    if (inAppBrowser()) {
        html += `${ic("open_in_browser")}<p>${esc(IN_APP_ADVICE)}</p>`;
    }
    els.keyBanner.className = `chat-key-banner ${keyState}`;
    els.keyBanner.innerHTML = html;
    els.keyBanner.hidden = !html;
}

function onKeyAction(event) {
    const button = event.target.closest("[data-key]");
    if (!button || button.disabled) return;
    const act = button.dataset.key;
    if (act === "setup") openSetup(false);
    else if (act === "unlock") openUnlock();
    else if (act === "retry") {
        busy(button, async () => {
            await loadIdentity();
            await afterKeyChange(true);
            if (keyState === "error") toast(keyError, "error");
        }).catch((error) => toast(error.message, "error"));
    }
}

function openSetup(replace) {
    if (keyState === "unsupported") {
        toast(NEED_HTTPS, "error");
        return;
    }
    setupReplace = replace;
    $("#key-setup-title").textContent = replace ? "Tạo khóa mã hóa mới" : "Bật mã hóa đầu cuối";
    $("#key-setup-intro").textContent = replace
        ? "Khóa mới thay cho khóa cũ trên mọi máy của bạn. Tin nhắn mã hóa trước đây sẽ không đọc được nữa; tin nhắn từ nay được mã hóa bằng khóa mới."
        : SETUP_INTRO;
    const submit = $("#key-setup-submit");
    submit.dataset.label = replace ? "Tạo khóa mới" : "Bật mã hóa";
    submit.textContent = submit.dataset.label;
    $("#key-new").value = "";
    $("#key-again").value = "";
    dialogError("setup", null);
    prepareKeyForm(setupDlg);
    if (!setupDlg.open) setupDlg.showModal();
    $("#key-new").focus();
}

function openUnlock() {
    if (keyState === "unsupported") {
        toast(NEED_HTTPS, "error");
        return;
    }
    $("#key-pass").value = "";
    dialogError("unlock", null);
    prepareKeyForm(unlockDlg);
    if (!unlockDlg.open) unlockDlg.showModal();
    $("#key-pass").focus();
}

/**
 * The hidden "username" of a recovery-password form, so a password manager (iCloud Keychain, Google) keeps the
 * recovery password under its own name and never saves it over the sign-in password of the site. On an iPhone, a
 * reminder that Safari may clear the stored key (its 7-day rule; not for the home-screen app).
 */
function prepareKeyForm(dialog) {
    dialog.querySelectorAll(".key-user").forEach((input) => {
        input.value = `${user.email} (mật khẩu khôi phục tin nhắn)`;
    });
    const note = dialog.querySelector(".key-apple-note");
    if (note) {
        note.textContent = "Trên iPhone, Safari có thể tự xóa khóa đã lưu nếu bạn dùng Safari 7 ngày mà không mở Senpai ơi; "
            + "khi đó chỉ cần nhập lại mật khẩu khôi phục. Mở Senpai ơi từ Màn hình chính thì khóa không bị xóa như vậy.";
        note.hidden = !appleMobile() || homeScreenApp();
    }
}

async function submitSetup(event) {
    event.preventDefault();
    if (setupDlg.classList.contains("busy")) return;
    const pass = $("#key-new").value;
    if ([...pass.normalize("NFC")].length < PASSPHRASE_MIN_LENGTH) {
        dialogError("setup", `Mật khẩu khôi phục cần ít nhất ${PASSPHRASE_MIN_LENGTH} ký tự.`);
        return;
    }
    if (pass !== $("#key-again").value) {
        dialogError("setup", "Hai lần nhập mật khẩu khôi phục chưa giống nhau.");
        return;
    }
    dialogBusy("setup", true);
    try {
        if (!setupReplace) {
            // This tab may be older than a key made on the member's other device meanwhile: never replace it silently
            const current = (await apiClient.chatMyKey())?.key ?? null;
            if (current) {
                serverKey = current;
                keyState = "locked";
                dialogBusy("setup", false);
                setupDlg.close();
                renderKeyBanner();
                openUnlock();
                dialogError("unlock", "Bạn đã bật mã hóa trên một thiết bị khác. Hãy nhập mật khẩu khôi phục đã đặt khi đó.");
                return;
            }
        }
        // PBKDF2 with 600,000 rounds: about a second
        const made = await createIdentity(pass);
        const saved = await apiClient.createChatKey({ publicKey: made.publicKey, ...made.backup });
        if (identity && identity.keyId !== saved.id) legacy = identity;
        serverKey = saved;
        const next = { keyId: saved.id, publicKey: saved.publicKey, privateKey: made.privateKey };
        setIdentity(next);
        keyState = "ready";
        await keepOnDevice(next);
        dialogBusy("setup", false);
        setupDlg.close();
        toast(setupReplace ? "Đã tạo khóa mã hóa mới." : "Đã bật mã hóa đầu cuối.");
        await afterKeyChange(true);
    } catch (error) {
        if (signedOut(error)) return;
        dialogBusy("setup", false);
        dialogError("setup", error.message);
    }
}

async function submitUnlock(event) {
    event.preventDefault();
    if (unlockDlg.classList.contains("busy")) return;
    const pass = $("#key-pass").value;
    if (!pass) {
        dialogError("unlock", "Hãy nhập mật khẩu khôi phục.");
        return;
    }
    dialogBusy("unlock", true);
    try {
        const backup = (await apiClient.chatMyKey()).key;          // the newest backup, in case it changed meanwhile
        if (!backup) {
            serverKey = null;
            keyState = "none";
            dialogBusy("unlock", false);
            unlockDlg.close();
            renderKeyBanner();
            openSetup(false);
            return;
        }
        serverKey = backup;
        const privateKey = await unlockIdentity(backup, pass);
        const next = { keyId: backup.id, publicKey: backup.publicKey, privateKey };
        setIdentity(next);
        keyState = "ready";
        await keepOnDevice(next);
        dialogBusy("unlock", false);
        unlockDlg.close();
        toast("Đã mở khóa. Bạn đọc và gửi được tin nhắn mã hóa trên trình duyệt này.");
        await afterKeyChange(true);
    } catch (error) {
        if (signedOut(error)) return;
        dialogBusy("unlock", false);
        dialogError("unlock", error instanceof WrongPassphraseError
            ? "Mật khẩu khôi phục không đúng. Hãy thử lại." : error.message);
        $("#key-pass").select();
    }
}

/** "Quên mật khẩu? Tạo khóa mới": only after a clear warning, since the old encrypted messages are lost. */
async function forgotPassphrase() {
    unlockDlg.close();
    const ok = await confirmDialog("Tạo khóa mã hóa mới? Các tin nhắn mã hóa trước đây sẽ không đọc được nữa, trên mọi máy "
        + "của bạn. Những người bạn nhắn tin sẽ thấy mã an toàn của bạn thay đổi.", "Tạo khóa mới");
    if (ok) openSetup(true);
    else openUnlock();
}

/** Keeps the unlocked key in this browser; when it refuses (private window), the key lives for this visit only. */
async function keepOnDevice(next) {
    try {
        await saveLocalIdentity(user.id, next);
        keyMemoryOnly = false;
    } catch (error) {
        if (!(error instanceof KeyStorageError)) console.warn("chat key", error);
        keyMemoryOnly = true;
    }
}

function dialogBusy(which, on) {
    const dlg = which === "setup" ? setupDlg : unlockDlg;
    dlg.classList.toggle("busy", on);
    dlg.querySelector("form").setAttribute("aria-busy", String(on));
    dlg.querySelectorAll("input, button").forEach((el) => {
        el.disabled = on;
    });
    $(`#key-${which}-busy`).hidden = !on;
    const submit = $(`#key-${which}-submit`);
    submit.dataset.label ||= submit.textContent;
    submit.textContent = on ? (which === "setup" ? "Đang tạo khóa…" : "Đang mở khóa…") : submit.dataset.label;
}

function dialogError(which, message) {
    const el = $(`#key-${which}-error`);
    el.textContent = message || "";
    el.hidden = !message;
}

// ---------------------------------------------------------------- decrypting

function privateKeyFor(keyId) {
    if (identity?.keyId === keyId) return identity.privateKey;
    if (legacy?.keyId === keyId) return legacy.privateKey;
    return null;
}

/** A public key by id: the member's own from memory, others' from the server once per page. */
function publicKeyOf(keyId) {
    if (identity?.keyId === keyId) return Promise.resolve(identity.publicKey);
    if (serverKey?.id === keyId) return Promise.resolve(serverKey.publicKey);
    if (!publicKeys.has(keyId)) {
        publicKeys.set(keyId, apiClient.chatKey(keyId).then((k) => k.publicKey).catch((error) => {
            publicKeys.delete(keyId);
            throw error;
        }));
    }
    return publicKeys.get(keyId);
}

/** The AES key of one key pair in one conversation (ECDH + HKDF), derived once per page. */
function convKeyFor(conversationId, myKeyId, peerKeyId, peerPublicKey) {
    const id = `${conversationId}:${myKeyId}:${peerKeyId}`;
    if (!convKeys.has(id)) {
        const privateKey = privateKeyFor(myKeyId);
        if (!privateKey) return Promise.reject(new UnreadableMessageError());
        convKeys.set(id, (async () => conversationKey(privateKey, peerPublicKey || await publicKeyOf(peerKeyId),
            conversationId, myKeyId, peerKeyId))().catch((error) => {
            convKeys.delete(id);
            throw error;
        }));
    }
    return convKeys.get(id);
}

function cacheKeyOf(m) {
    return `${m.id}:${m.encrypted.iv}`;
}

/**
 * The decryption of an encrypted message, started the first time it is asked for. A message edited later has a new
 * iv and is decrypted again. States: pending, ok, unreadable (wrong key, changed, franking tag wrong), failed (the
 * network: tried again later), and without caching locked / nohttps while this browser has no key.
 */
function decryption(conversationId, m, peerId) {
    const env = m.encrypted;
    const id = cacheKeyOf(m);
    const known = decrypted.get(id);
    if (known && !(known.state === "failed" && Date.now() - known.at > RETRY_MS)) return known;
    const myKeyId = m.mine ? env.senderKeyId : env.recipientKeyId;
    const peerKeyId = m.mine ? env.recipientKeyId : env.senderKeyId;
    if (!privateKeyFor(myKeyId)) {
        if (keyState === "ready") return { state: "unreadable" };          // written for a key this browser never had
        return { state: keyState === "unsupported" ? "nohttps" : "locked" };
    }
    const entry = { state: "pending" };
    decrypted.set(id, entry);
    entry.promise = (async () => {
        try {
            const key = await convKeyFor(conversationId, myKeyId, peerKeyId);
            const out = await decryptMessage({ key, conversationId, senderId: m.mine ? user.id : peerId, envelope: env });
            Object.assign(entry, { state: "ok", body: out.body, frankingKey: out.frankingKey, plaintext: out.plaintext });
        } catch (error) {
            if (error instanceof UnreadableMessageError || error instanceof TypeError || error?.status === 404) {
                entry.state = "unreadable";
            } else {
                if (signedOut(error)) return;
                console.warn("chat decrypt", error);
                entry.state = "failed";
                entry.at = Date.now();
            }
        }
        redrawSoon();
    })();
    return entry;
}

/**
 * What an encrypted message shows: `{ kind: "ok", text, files, filesSig }` or `{ kind, label }`. Only files the server
 * says are attached to it are shown.
 */
function encryptedView(conversationId, m, peerId, short = false) {
    const entry = decryption(conversationId, m, peerId);
    if (entry.state === "ok") {
        const attached = new Set(m.fileIds ?? []);
        const files = (entry.body.files ?? []).filter((f) => attached.has(f.id));
        return { kind: "ok", text: entry.body.text, files, filesSig: files.map((f) => f.id).join(",") };
    }
    const labels = {
        pending: DECRYPTING,
        failed: short ? DECRYPTING : "Chưa giải mã được, đang thử lại…",
        unreadable: UNREADABLE,
        locked: short ? "Tin nhắn đã mã hóa" : "Tin nhắn đã mã hóa. Nhập mật khẩu khôi phục để đọc.",
        nohttps: short ? "Tin nhắn đã mã hóa" : "Tin nhắn đã mã hóa. Mở Senpai ơi qua địa chỉ https để đọc."
    };
    return { kind: entry.state, label: labels[entry.state] ?? UNREADABLE };
}

function messageView(m) {
    if (m.recalled) return { kind: "recalled" };
    if (m.encrypted) return encryptedView(room.id, m, room.peer.id);
    return { kind: "ok", text: m.content, files: [], filesSig: "" };
}

function fileSummaryOf(files) {
    return media?.fileSummary?.(files) || "📎 Tệp đính kèm";
}

/** Waits for these promises, at most `ms`. */
function settle(promises, ms) {
    return Promise.race([Promise.allSettled(promises), new Promise((resolve) => setTimeout(resolve, ms))]);
}

/** One redraw for many decryptions or quotes finishing together. */
function redrawSoon() {
    if (redrawTimer) return;
    redrawTimer = setTimeout(() => {
        redrawTimer = null;
        if (room) {
            renderMessages("keep");
            renderComposeBar();
        }
        renderList();
        if (!els.search.hidden) runSearch();
    }, 30);
}

// ---------------------------------------------------------------- list

async function loadList(page) {
    const asked = box;
    try {
        const data = await apiClient.chats(asked, page);
        if (asked !== box) return;
        items = page === 0 ? data.items : items.concat(data.items.filter((n) => !items.some((m) => m.id === n.id)));
        listPage = page;
        listLoading = false;
        els.more.hidden = !data.hasMore;
        applyCounts(data.counts);
        renderList();
    } catch (error) {
        if (signedOut(error) || asked !== box) return;
        listLoading = false;
        els.note.textContent = error.message;
    }
}

/** The first page again (a member who loaded more pages keeps them until they switch tabs). */
function refreshList() {
    return listPage === 0 ? loadList(0) : apiClient.chatCounts().then(applyCounts).catch(signedOut);
}

function renderList() {
    document.querySelectorAll(".chat-tabs [role=tab]").forEach((tab) => {
        tab.setAttribute("aria-selected", String(tab.dataset.box === box));
    });
    els.list.setAttribute("aria-labelledby", `tab-${box}`);
    els.list.innerHTML = items.map(itemHtml).join("");
    els.note.textContent = items.length ? "" : listLoading ? "Đang tải…" : emptyText();
}

function emptyText() {
    if (box === "REQUESTS") {
        return "Không có lời mời nhắn tin nào. Tin nhắn đầu tiên của người chưa từng trò chuyện với bạn sẽ nằm ở đây.";
    }
    return counts.requests
        ? `Chưa có cuộc trò chuyện nào. Bạn có ${counts.requests} lời mời nhắn tin đang chờ ở mục “Lời mời”.`
        : "Chưa có cuộc trò chuyện nào. Muốn hỏi thêm ai đó, bấm “Nhắn tin” cạnh tên họ ở Blog hoặc Hỏi-Đáp.";
}

function itemHtml(c) {
    const last = c.lastMessage;
    const current = room?.id === c.id;
    return `<li><a class="chat-item${c.unread ? " unread" : ""}" href="/messages/${c.id}" data-id="${c.id}"
            ${current ? 'aria-current="true"' : ""}>
        ${avatar(c.peer)}
        <span class="chat-item-txt">
            <span class="chat-item-top"><b>${esc(c.peer.displayName)}</b>
                ${c.blockedByMe ? `<span class="badge crit">Đã chặn</span>` : ""}
                ${last ? `<time class="note" datetime="${esc(last.createdAt)}" title="${esc(fullDate(last.createdAt))}">${timeAgo(last.createdAt)}</time>` : ""}</span>
            <span class="chat-preview">${last ? previewHtml(c, last) : ""}</span>
        </span>
        ${c.unread ? `<span class="chat-unread" title="${c.unread} tin chưa đọc">${c.unread > 99 ? "99+" : c.unread}</span>` : ""}
    </a></li>`;
}

/** The last message on one line: decrypted here when it is encrypted, a summary for files, a word when recalled. */
function previewHtml(c, last) {
    const who = last.mine ? "Bạn: " : "";
    if (last.recalled) return esc(last.mine ? RECALLED_MINE : RECALLED_THEIRS);
    if (!last.encrypted) return esc(who + (last.content ?? ""));
    const view = encryptedView(c.id, last, c.peer.id, true);
    if (view.kind !== "ok") return `${ic("lock")}${esc(who + view.label)}`;
    return `${ic("lock")}${esc(who + (view.text ? oneLine(view.text) : fileSummaryOf(view.files)))}`;
}

/** The header button counts conversations with unread messages; the "Lời mời" tab counts requests. */
function applyCounts(next) {
    counts = next;
    setChatCount(next.unread);
    els.requests.textContent = next.requests > 99 ? "99+" : String(next.requests);
    els.requests.hidden = !next.requests;
    if (!items.length && box === "INBOX" && !listLoading) els.note.textContent = emptyText();
}

async function switchBox(next) {
    if (next === box) return;
    box = next;
    items = [];
    listLoading = true;
    els.more.hidden = true;
    renderList();
    await loadList(0);
}

// ---------------------------------------------------------------- the open conversation

async function openConversation(load, toUserId = null) {
    saveDraft();
    const ticket = ++openTicket;
    try {
        const data = await load();
        if (ticket !== openTicket) return;
        const next = {
            ...data, toUserId: data.id ? null : toUserId ?? data.peer.id, pool: null,
            // The server's clock, not this device's: a phone running ahead would miss the first edits and reactions
            changedSince: data.serverTime
                ?? new Date(Date.now() - CHANGED_LOOKBACK_MS).toISOString(),
            // Newest message the server has given this page; polls ask after it, never after one we just sent
            // (the other member's message saved a moment before ours would be skipped)
            polledUpTo: newestId(data.messages),
            lastPeerKeyId: data.status.encryption?.peerKeyId ?? null
        };
        const same = room && draftKey(room) === draftKey(next);
        if (same) {
            next.pool = room.pool;                       // what search loaded stays
            next.lastPeerKeyId = room.lastPeerKeyId ?? next.lastPeerKeyId;
        } else {
            leaveRoom();
        }
        room = next;
        markedUpTo = 0;
        if (data.id && location.pathname !== `/messages/${data.id}`) {
            history.replaceState(null, "", `/messages/${data.id}`);
        }
        if (data.status.state === "REQUEST" && box !== "REQUESTS") {
            await switchBox("REQUESTS");
        }
        // Most messages are decrypted before the first drawing: no flicker, and the scroll ends at the bottom
        await settle(room.messages.filter((m) => m.encrypted && !m.recalled)
            .map((m) => decryption(room.id, m, room.peer.id).promise).filter(Boolean), FIRST_DECRYPT_MS);
        if (ticket !== openTicket) return;
        showRoom();
        renderHead();
        renderNotice();
        renderTyping();
        renderComposeBar();
        renderRecordBar();
        renderPending();
        renderMessages(same ? "keep" : "bottom");
        if (!editing) els.text.value = drafts.get(draftKey(room)) || "";
        hideError();
        grow();
        if (!composeBlock() && matchMedia("(hover: hover)").matches) els.text.focus();
        renderList();
        markRead();
    } catch (error) {
        if (ticket !== openTicket || signedOut(error)) return;
        leaveRoom();
        room = null;
        showRoom();
        els.head.innerHTML = `<button class="btn sm ghost chat-back" type="button" data-act="back" title="Về danh sách">${ic("arrow_back")}<span class="sr-only">Về danh sách</span></button>`;
        els.msgs.innerHTML = `<li class="chat-day">${esc(error.message)}</li>`;
        els.older.hidden = true;
        els.notice.hidden = true;
        els.typing.hidden = true;
        els.compose.hidden = true;
    }
}

function showRoom() {
    document.body.classList.add("chat-open");
    els.empty.hidden = true;
    els.room.hidden = false;
    els.compose.hidden = false;
    setTitle(room ? `${room.peer.displayName} – Tin nhắn – Senpai ơi` : "Tin nhắn – Senpai ơi");
}

function closeRoom() {
    saveDraft();
    openTicket++;
    leaveRoom();
    room = null;
    document.body.classList.remove("chat-open");
    els.room.hidden = true;
    els.empty.hidden = false;
    setTitle("Tin nhắn – Senpai ơi");
    renderList();
}

/** Before another conversation is shown: what belonged to this one goes (recording, attachments, their object URLs). */
function leaveRoom() {
    cancelRecording();
    dropPending();
    editing = null;
    replyTo = null;
    menu = null;
    quotes.clear();
    closeSearch(false);
    media?.releaseAttachments?.();
    els.msgs.replaceChildren();
    stick = true;
    typingShown = "";
    els.typing.hidden = true;
    els.typing.innerHTML = "";
}

function reloadRoom() {
    if (!room) return Promise.resolve();
    const peerId = room.peer.id;
    return room.id ? openConversation(() => apiClient.chat(room.id))
        : openConversation(() => apiClient.chatWith(peerId), peerId);
}

function encOf(r) {
    return r?.status?.encryption ?? null;
}

function isE2ee(r) {
    return encOf(r)?.mode === "E2EE";
}

function renderHead() {
    const p = room.peer;
    const s = room.status;
    const code = s.notice?.code;
    els.head.innerHTML = `
        <button class="btn sm ghost chat-back" type="button" data-act="back" title="Về danh sách">${ic("arrow_back")}<span class="sr-only">Về danh sách</span></button>
        ${avatar(p)}
        <div class="chat-peer"><b>${esc(p.displayName)}</b>
            ${isE2ee(room) ? `<button class="chat-lock" type="button" data-act="safety" title="Xem mã an toàn">${ic("lock")}Đã mã hóa đầu cuối</button>` : ""}
            ${p.tags.length ? `<div class="row">${tagChips(p.tags)}</div>` : ""}</div>
        ${room.id ? `<button class="btn sm ghost" type="button" data-act="search" title="Tìm trong cuộc trò chuyện" aria-controls="search">${ic("search")}<span class="sr-only">Tìm trong cuộc trò chuyện</span></button>` : ""}
        ${code === "DELETED" ? "" : s.blockedByMe
            ? `<button class="btn sm ghost" type="button" data-act="unblock" title="Bỏ chặn ${esc(p.displayName)}">${ic("lock_open")}<span class="lbl">Bỏ chặn</span></button>`
            : `<button class="btn sm ghost" type="button" data-act="block" title="Chặn ${esc(p.displayName)}">${ic("block")}<span class="lbl">Chặn</span></button>`}
        ${room.id ? `<button class="btn sm ghost dan" type="button" data-act="clear" title="Xóa cuộc trò chuyện">${ic("delete")}<span class="sr-only">Xóa cuộc trò chuyện</span></button>` : ""}`;
}

/**
 * What the member should know above the message box: the conversation's notice with the answers to a request, then
 * the encryption notice (the server's, or this browser's when it cannot open the key). The box follows canSend.
 */
function renderNotice() {
    const s = room.status;
    const n = s.notice;
    const parts = [];
    if (n) {
        parts.push(`<div class="chat-notice${CRIT.includes(n.code) ? " crit" : ""}" role="status"><p>${esc(n.text)}</p>
            ${n.code === "REQUEST" ? `<div class="row">
                <button class="btn sm pri" type="button" data-act="accept">${ic("check")}Chấp nhận</button>
                <button class="btn sm" type="button" data-act="decline">Từ chối</button>
                <button class="btn sm dan" type="button" data-act="block">${ic("block")}Chặn</button></div>` : ""}
            ${n.code === "BLOCKED_BY_ME" ? `<div class="row">
                <button class="btn sm" type="button" data-act="unblock">${ic("lock_open")}Bỏ chặn</button></div>` : ""}
        </div>`);
    }
    const local = keyNotice();
    if (local) {
        parts.push(local);
    } else if (encOf(room)?.notice) {
        const en = encOf(room).notice;
        const canTurnOn = en.code === "NO_KEY_ME";
        parts.push(`<div class="chat-notice info" role="status"><p>${ic(canTurnOn ? "lock_open" : "info")}${esc(en.text)}
            ${canTurnOn && keyState === "unsupported" ? esc(` ${NEED_HTTPS}`) : ""}</p>
            ${canTurnOn && keyState === "none" ? `<div class="row">
                <button class="btn sm pri" type="button" data-key="setup">${ic("lock")}Bật mã hóa</button></div>` : ""}</div>`);
    }
    els.notice.innerHTML = parts.join("");
    els.notice.hidden = !parts.length;
    renderComposer();
}

/**
 * This page at https, when the http address looks like one a tunnel or the server's proxy also serves over https (a
 * name on the default port, e.g. the "http://…" link a tunnel prints first); null for localhost, an IP or a port.
 */
function httpsHref() {
    if (location.protocol !== "http:" || location.port) return null;
    const host = location.hostname;
    if (host === "localhost" || /^[\d.]+$/.test(host) || host.includes(":")) return null;
    return `https://${location.host}${location.pathname}${location.search}`;
}

/** "Mở bằng https" when this page has an https address, else what to do instead. */
function httpsAction() {
    const href = httpsHref();
    return href
        ? `<div class="row"><a class="btn sm pri" href="${esc(href)}">${ic("lock")}Mở bằng https</a></div>`
        : `<p class="note">Hãy mở Senpai ơi bằng địa chỉ bắt đầu bằng https:// (trên máy đang chạy server thì dùng
            http://localhost).</p>`;
}

/** In an encrypted conversation, why this browser cannot read or write it now. */
function keyNotice() {
    if (!isE2ee(room) || keyState === "ready") return "";
    if (keyState === "unsupported") {
        return `<div class="chat-notice crit" role="status"><p>${ic("lock")}Cuộc trò chuyện này đã được mã hóa đầu cuối.
            ${NEED_HTTPS} Ở địa chỉ này bạn chưa đọc và gửi được tin nhắn mã hóa.</p>${httpsAction()}</div>`;
    }
    if (keyState === "error") {
        return `<div class="chat-notice crit" role="status"><p>Chưa tải được khóa mã hóa của bạn: ${esc(keyError)}</p>
            <div class="row"><button class="btn sm" type="button" data-key="retry">Thử lại</button></div></div>`;
    }
    return `<div class="chat-notice" role="status"><p>${ic("key")}Cuộc trò chuyện này đã được mã hóa đầu cuối. Nhập mật khẩu khôi
        phục để đọc và gửi tin nhắn trên trình duyệt này.</p>
        <div class="row"><button class="btn sm pri" type="button" data-key="unlock">Nhập mật khẩu khôi phục</button></div></div>`;
}

/** Why the message box is closed now (placeholder text), or null. Editing works while sending does not. */
function composeBlock() {
    if (!room) return "Bạn chưa gửi được tin nhắn";
    if (!room.status.canSend && !editing) return "Bạn chưa gửi được tin nhắn";
    if (isE2ee(room) && keyState !== "ready" && !(editing && !editing.encrypted)) {
        return keyState === "unsupported" ? "Cần mở trang qua https để gửi tin nhắn mã hóa"
            : "Nhập mật khẩu khôi phục để gửi tin nhắn mã hóa";
    }
    return null;
}

function renderComposer() {
    if (!room) return;
    const block = composeBlock();
    els.text.disabled = Boolean(block);
    els.text.placeholder = block || (editing ? "Sửa tin nhắn…" : "Nhập tin nhắn…");
    els.send.disabled = Boolean(block) || sendingFiles;
    els.send.title = (editing ? "Lưu" : "Gửi") + (touchOnly() ? "" : " (Enter)");
    const attach = !block && Boolean(media) && room.status.canAttach && isE2ee(room) && keyState === "ready" && !editing;
    els.attachTools.hidden = !attach;
    els.attach.disabled = sending || Boolean(recorder);
    const canRecord = Boolean(media?.VoiceRecorder?.supported?.());
    els.record.hidden = !canRecord;
    els.record.disabled = sending || Boolean(recorder) || pending.length > 0;
    els.record.title = pending.length ? "Bỏ tệp đã chọn để ghi âm" : "Ghi âm";
}

function renderTyping() {
    const text = room?.status.peerTyping ? `${room.peer.displayName} đang soạn tin…` : "";
    if (text === typingShown) return;
    typingShown = text;
    els.typing.hidden = !text;
    els.typing.innerHTML = text ? `<span class="chat-typing-dots" aria-hidden="true"><i></i><i></i><i></i></span>${esc(text)}` : "";
}

/**
 * A new status from the server. Says when the other member made a new key ("Mã an toàn" changed) and notices when the
 * member's own key changed elsewhere. Returns whether anything but "đang soạn tin" changed.
 */
function applyStatus(next) {
    const before = statusKey(room.status);
    room.status = next;
    const enc = next.encryption;
    if (enc?.peerKeyId) {
        if (room.lastPeerKeyId && enc.peerKeyId !== room.lastPeerKeyId) {
            toast(`Mã an toàn với ${room.peer.displayName} vừa thay đổi vì ${room.peer.displayName} đã tạo khóa mã hóa mới.`);
        }
        room.lastPeerKeyId = enc.peerKeyId;
    }
    // A key made on another device meanwhile: this tab must unlock it, not offer to make another one
    if (enc?.myKeyId && (identity ? enc.myKeyId !== identity.keyId : keyState === "none")) refreshMyKey();
    return statusKey(next) !== before;
}

function statusKey(status) {
    const { peerTyping, ...rest } = status ?? {};
    return JSON.stringify(rest);
}

// ---------------------------------------------------------------- drawing the messages

/**
 * Draws the messages. "bottom": scroll to the newest; "keep": stay where the reader is, or follow new messages when
 * they were at the bottom; "older": older messages were put on top, the ones being read stay in place.
 */
function renderMessages(mode = "keep") {
    if (!room) return;
    const t = els.thread;
    const height = t.scrollHeight;
    const top = t.scrollTop;
    reconcile(messageNodes());
    els.older.hidden = !room.hasOlder;
    if (mode === "bottom") stick = true;
    if (mode === "bottom" || (mode === "keep" && stick)) {
        t.scrollTop = t.scrollHeight;
    } else if (mode === "older") {
        t.scrollTop = top + (t.scrollHeight - height);
    }
}

/** What the list should hold: day separators and messages, each with a key and a signature of what it shows. */
function messageNodes() {
    const nodes = [];
    const messages = room.messages;
    const lastMine = messages.findLast((m) => m.mine);
    const lastTheirs = messages.findLast((m) => !m.mine);
    // "Đã xem" / "Đã gửi" under the member's own newest message, when nothing came back after it
    const showState = room.id && lastMine && (!lastTheirs || lastMine.id > lastTheirs.id);
    let day = null;
    for (const m of messages) {
        const date = new Date(m.createdAt).toDateString();
        if (date !== day) {
            day = date;
            const label = dayLabel(m.createdAt);
            nodes.push({ key: `d${date}`, day: label, sig: label });
        }
        const state = showState && m === lastMine ? (m.id <= room.status.peerReadUpTo ? "seen" : "sent") : "";
        nodes.push(messageNode(m, state));
    }
    const outbox = room.outbox ?? [];
    if (outbox.length) {
        const now = new Date();
        if (now.toDateString() !== day) {
            const label = dayLabel(now.toISOString());
            nodes.push({ key: `d${now.toDateString()}`, day: label, sig: label });
        }
        outbox.forEach((o) => nodes.push(outboxNode(o)));
    }
    return nodes;
}

/** A message on its way ("Đang gửi…"): its text and quote, no tools until the server has it. */
function outboxNode(o) {
    const quote = o.replyToId ? quoteView(o.replyToId) : null;
    const parts = {
        quote: { sig: quote ? JSON.stringify(quote) : "", html: () => (quote ? quoteHtml(quote) : "") },
        files: { sig: "", files: [] },
        text: { sig: o.text, html: () => `<div class="bubble">${linkify(o.text)}</div>` },
        reacts: { sig: "", html: () => "" },
        meta: { sig: "sending", html: () => `<span class="msg-sending">${ic("schedule")}Đang gửi…</span>` }
    };
    const cls = "msg mine sending";
    return { key: o.key, id: "", cls, parts, sig: cls + Object.values(parts).map((p) => p.sig).join("\u0001") };
}

function messageNode(m, state) {
    const view = messageView(m);
    const quote = m.replyToId && !m.recalled ? quoteView(m.replyToId) : null;
    const tools = toolFlags(m, view);
    const mineEmoji = m.reactions?.find((r) => r.mine)?.emoji ?? null;
    const canReact = Boolean(room.status.canReact);
    const parts = {
        quote: { sig: quote ? JSON.stringify(quote) : "", html: () => (quote ? quoteHtml(quote) : "") },
        files: { sig: view.kind === "ok" ? view.filesSig : "", files: view.kind === "ok" ? view.files : [] },
        text: { sig: JSON.stringify([view.kind, view.text ?? view.label ?? "", view.files?.length ?? 0]), html: () => textHtml(m, view) },
        reacts: { sig: m.recalled ? "" : JSON.stringify([m.reactions, canReact]), html: () => reactsHtml(m, canReact) },
        meta: { sig: JSON.stringify([m.createdAt, m.edited, m.recalled, state, tools, mineEmoji]), html: () => metaHtml(m, state, tools, mineEmoji) }
    };
    const cls = `msg ${m.mine ? "mine" : "theirs"}${m.recalled ? " recalled" : ""}`;
    return { key: `m${m.id}`, id: m.id, cls, parts, sig: cls + Object.values(parts).map((p) => p.sig).join("\u0001") };
}

/** Puts the nodes into the list: existing elements are kept (and patched when their signature changed), in order. */
function reconcile(nodes) {
    const list = els.msgs;
    const existing = new Map();
    for (const el of list.children) existing.set(el.dataset.key, el);
    let cursor = list.firstElementChild;
    for (const node of nodes) {
        let el = existing.get(node.key);
        const fresh = !el;
        if (fresh) {
            el = document.createElement("li");
            el.dataset.key = node.key;
        } else {
            existing.delete(node.key);
        }
        if (el === cursor) cursor = cursor.nextElementSibling;
        else list.insertBefore(el, cursor);
        // patched once in the page: the attachments find their scrolling thread when they are filled in
        if (fresh || el.dataset.sig !== node.sig) patch(el, node);
    }
    existing.forEach((el) => el.remove());
}

function patch(el, node) {
    el.dataset.sig = node.sig;
    if (node.day) {
        el.className = "chat-day";
        el.textContent = node.day;
        return;
    }
    if (!el.querySelector("[data-slot]")) el.innerHTML = MSG_SLOTS;
    el.className = node.cls + menuClass(node.id);
    el.dataset.id = node.id;
    for (const [name, part] of Object.entries(node.parts)) {
        const slot = el.querySelector(`[data-slot="${name}"]`);
        if (slot.dataset.sig === part.sig) continue;
        slot.dataset.sig = part.sig;
        if (name === "files") {
            slot.innerHTML = part.files.length ? filesHtml(part.files) : "";
            if (part.files.length) hydrate(slot, part.files);
        } else {
            slot.innerHTML = part.html();
        }
    }
}

function filesHtml(files) {
    if (!media) return `<p class="bubble muted">${ic("attach_file")}${esc(fileSummaryOf(files))} (trang chưa hiển thị được tệp)</p>`;
    return media.attachmentsHtml(files);
}

function hydrate(slot, files) {
    if (!media) return;
    try {
        Promise.resolve(media.hydrateAttachments(slot, room.id, files)).catch((error) => console.warn("chat files", error));
    } catch (error) {
        console.warn("chat files", error);
    }
}

function textHtml(m, view) {
    if (m.recalled) return `<div class="bubble recalled">${ic("undo")}${m.mine ? RECALLED_MINE : RECALLED_THEIRS}</div>`;
    if (view.kind !== "ok") {
        const icon = { pending: "progress_activity", failed: "sync_problem", unreadable: "error" }[view.kind] ?? "lock";
        return `<div class="bubble muted${view.kind === "pending" ? " pending" : ""}">${ic(icon)}${esc(view.label)}</div>`;
    }
    if (view.text) return `<div class="bubble">${linkify(view.text)}</div>`;
    if (!view.files.length) return `<div class="bubble muted">${ic("hide_image")}Tệp đính kèm không còn nữa</div>`;
    return "";
}

function quoteHtml(q) {
    return `<button class="msg-quote" type="button" data-act="jump" data-target="${q.id}" ${q.missing ? "disabled" : ""}
        title="Xem tin nhắn được trả lời"><b>${esc(q.who || "Tin nhắn")}</b><span>${esc(q.text)}</span></button>`;
}

function reactsHtml(m, canReact) {
    if (m.recalled || !m.reactions?.length) return "";
    return m.reactions.map((r) => `<button class="react-chip${r.mine ? " mine" : ""}" type="button" data-act="react-chip"
        data-id="${m.id}" data-emoji="${esc(r.emoji)}" aria-pressed="${r.mine}" ${canReact ? "" : "disabled"}
        title="${r.mine ? "Bỏ cảm xúc của bạn" : `Thả ${esc(r.emoji)}`}">${esc(r.emoji)}<span>${r.count}</span></button>`).join("");
}

function metaHtml(m, state, tools, mineEmoji) {
    const stateHtml = state === "seen" ? `<span class="msg-seen">· Đã xem</span>` : state === "sent" ? `<span>· Đã gửi</span>` : "";
    const tool = (act, icon, label, extra = "") => `<button class="msg-tool${extra}" type="button" data-act="${act}"
        data-id="${m.id}" title="${label}">${ic(icon)}<span class="sr-only">${label}</span></button>`;
    const emojis = (room.limits?.reactions ?? []).map((e) => `<button class="msg-emoji${e === mineEmoji ? " on" : ""}"
        type="button" data-act="react-pick" data-id="${m.id}" data-emoji="${esc(e)}" aria-pressed="${e === mineEmoji}"
        title="${esc(e)}">${esc(e)}</button>`).join("");
    const toolsHtml = [
        tools.react && emojis ? `<span class="msg-react-wrap">${tool("react-open", "add_reaction", "Thả cảm xúc")}<span class="msg-picker" role="group" aria-label="Chọn cảm xúc">${emojis}</span></span>` : "",
        tools.reply ? tool("reply", "reply", "Trả lời") : "",
        tools.copy ? tool("copy", "content_copy", "Sao chép") : "",
        tools.edit ? tool("edit", "edit", "Sửa") : "",
        tools.recall ? tool("recall", "undo", "Thu hồi", " dan") : ""
    ].join("");
    return `<time datetime="${esc(m.createdAt)}" title="${esc(fullDate(m.createdAt))}">${esc(clock(m.createdAt))}</time>`
        + `${m.edited && !m.recalled ? `<span>· đã sửa</span>` : ""}${stateHtml}`
        + `${toolsHtml ? `<span class="msg-tools">${toolsHtml}</span>` : ""}`
        + `${tools.report ? `<button class="msg-report" type="button" data-act="report" data-id="${m.id}" title="Báo cáo tin nhắn này">${ic("flag")}<span>Báo cáo</span></button>` : ""}`;
}

/** What can be done with a message now; the server checks the same again. */
function toolFlags(m, view) {
    const s = room.status;
    const live = Boolean(room.id) && !m.recalled;
    const readable = view.kind === "ok";
    const cutOff = CRIT.includes(s.notice?.code);
    const inTime = Date.now() - Date.parse(m.createdAt) < (room.limits?.editMinutes ?? 15) * 60000;
    return {
        react: live && Boolean(s.canReact),
        reply: live && Boolean(s.canReact),
        copy: !m.recalled && readable && Boolean(view.text) && Boolean(navigator.clipboard?.writeText),
        edit: live && m.mine && !cutOff && inTime
            && (!m.encrypted || (readable && keyState === "ready" && isE2ee(room))),
        recall: live && m.mine,
        report: live && !m.mine
    };
}

/** A quoted message: from the loaded ones, else asked once from the server. */
function quoteView(id) {
    let m = room.messages.find((x) => x.id === id) ?? room.pool?.map.get(id);
    if (!m) {
        const cached = quotes.get(id);
        if (cached?.message) {
            m = cached.message;
        } else {
            if (!cached || (cached.failed && Date.now() - cached.failed > RETRY_MS)) loadQuote(id);
            return cached?.gone ? { id, who: "", text: "Tin nhắn không còn nữa", missing: true }
                : { id, who: "", text: "Đang tải…" };
        }
    }
    const who = m.mine ? "Bạn" : room.peer.displayName;
    if (m.recalled) return { id, who, text: RECALLED_THEIRS };
    if (!m.encrypted) return { id, who, text: oneLine(m.content, 90) };
    const view = encryptedView(room.id, m, room.peer.id, true);
    if (view.kind !== "ok") return { id, who, text: view.label };
    return { id, who, text: view.text ? oneLine(view.text, 90) : fileSummaryOf(view.files) };
}

function loadQuote(id) {
    const target = room;
    const entry = {};
    quotes.set(id, entry);
    apiClient.chatMessage(target.id, id).then((message) => {
        entry.message = message;
    }).catch((error) => {
        if (signedOut(error)) return;
        if (error.status === 404) entry.gone = true;
        else entry.failed = Date.now();
    }).finally(() => {
        if (room === target) redrawSoon();
    });
}

function menuClass(id) {
    return menu?.id === id ? ` tools-open${menu.picking ? " picking" : ""}` : "";
}

function openMenu(id, picking) {
    menu = { id, picking };
    applyMenu();
}

function closeMenu() {
    if (!menu) return;
    menu = null;
    applyMenu();
}

function applyMenu() {
    els.msgs.querySelectorAll(".msg.tools-open").forEach((el) => el.classList.remove("tools-open", "picking"));
    if (!menu) return;
    const el = els.msgs.querySelector(`.msg[data-id="${menu.id}"]`);
    el?.classList.add("tools-open");
    if (menu.picking) el?.classList.add("picking");
}

function clock(iso) {
    return new Date(iso).toLocaleTimeString("vi-VN", { hour: "2-digit", minute: "2-digit" });
}

function dayLabel(iso) {
    const date = new Date(iso);
    const today = new Date();
    const yesterday = new Date(today.getFullYear(), today.getMonth(), today.getDate() - 1);
    if (date.toDateString() === today.toDateString()) return "Hôm nay";
    if (date.toDateString() === yesterday.toDateString()) return "Hôm qua";
    return date.toLocaleDateString("vi-VN", { day: "2-digit", month: "2-digit", year: "numeric" });
}

/** The other member's newest message is read once the conversation is shown in a visible tab. */
async function markRead() {
    if (!room?.id || document.hidden) return;
    const newest = room.messages.reduce((max, m) => (m.mine ? max : Math.max(max, m.id)), 0);
    if (newest <= markedUpTo) return;
    markedUpTo = newest;
    const id = room.id;
    try {
        applyCounts(await apiClient.readChat(id, newest));
        const item = items.find((i) => i.id === id);
        if (item?.unread) {
            item.unread = 0;
            renderList();
        }
    } catch (error) {
        signedOut(error);
    }
}

async function loadOlder() {
    if (!room?.id || !room.messages.length) return;
    const asked = room;
    els.older.disabled = true;
    try {
        const data = await apiClient.chatOlder(asked.id, asked.messages[0].id);
        if (room !== asked) return;
        mergeOlder(data.messages);
        room.hasOlder = data.hasOlder;
        renderMessages("older");
    } catch (error) {
        if (!signedOut(error)) toast(error.message, "error");
    } finally {
        els.older.disabled = false;
    }
}

/** The highest message id in a list (0 for none). */
function newestId(messages) {
    return (messages ?? []).reduce((max, m) => Math.max(max, m.id), 0);
}

/** Older messages (oldest first) put in front of the loaded ones. */
function mergeOlder(older) {
    const known = new Set(room.messages.map((m) => m.id));
    room.messages = older.filter((m) => !known.has(m.id)).concat(room.messages).sort((a, b) => a.id - b.id);
}

/** Puts a message as the server answered it (edit, recall, reaction, a "changed" one) wherever the page keeps it. */
function storeMessage(m) {
    if (!room || !m) return;
    const i = room.messages.findIndex((x) => x.id === m.id);
    if (i >= 0) room.messages[i] = m;
    if (room.pool?.map.has(m.id)) room.pool.map.set(m.id, m);
    const quoted = quotes.get(m.id);
    if (quoted?.message) quoted.message = m;
    if (m.recalled) {
        if (editing?.id === m.id) cancelEdit();
        if (replyTo?.id === m.id) cancelReply();
    }
}

// ---------------------------------------------------------------- asking for news

function schedule() {
    clearTimeout(timer);
    timer = document.hidden ? null : setTimeout(tick, liveOpen() ? ROOM_LIVE_MS : ROOM_MS);
}

/**
 * Asks for the open conversation's news (and every LIST_EVERY-th time, or with `listToo`, the list). One at a time: a
 * call while one is out makes it ask once more right after, so nothing announced meanwhile is missed.
 */
async function tick(listToo = false) {
    clearTimeout(timer);
    timer = null;
    if (polling) {
        pollAgain = true;
        return;
    }
    if (room?.outbox?.length) {
        // a message is on its way: asked once it is in (send() calls back), so it never shows twice
        pollAfterSend = true;
        return;
    }
    polling = true;
    ticks++;
    try {
        if (room?.id) await pollRoom();
        if (listToo || ticks % LIST_EVERY === 0) await refreshList();
    } finally {
        polling = false;
        if (pollAgain) {
            pollAgain = false;
            tick(true);
        } else {
            schedule();
        }
    }
}

/**
 * The live stream announced a change of a conversation (lib/live.js; ids only): the open one is asked at once, any
 * other one shows in the list. "đang soạn tin" shows straight away, without asking.
 */
function onChatEvent(event) {
    const id = Number(event?.conversationId) || null;
    if (event?.kind === "typing") {
        if (room?.id && room.id === id) showPeerTyping();
        return;
    }
    if (room?.id && (id === null || id === room.id)) {
        // a read or a status change may change the counts and the list as well
        tick(event?.kind !== "message" && event?.kind !== "changed");
    } else {
        clearTimeout(listTimer);
        listTimer = setTimeout(refreshList, LIST_SOON_MS);
    }
}

function showPeerTyping() {
    room.status.peerTyping = true;
    renderTyping();
    clearTimeout(typingTimer);
    const shown = room;
    typingTimer = setTimeout(() => {
        if (room === shown && room.status.peerTyping) {
            room.status.peerTyping = false;
            renderTyping();
        }
    }, TYPING_SHOW_MS);
}

/** New messages, messages changed since the last answer (edited, recalled, reacted to) and the status. */
async function pollRoom() {
    const asked = room;
    const after = asked.polledUpTo ?? 0;
    let data;
    try {
        data = await apiClient.chatUpdates(asked.id, after, asked.changedSince);
    } catch (error) {
        signedOut(error);
        return;
    }
    if (room !== asked) return;
    if (data.serverTime) room.changedSince = data.serverTime;
    room.polledUpTo = Math.max(room.polledUpTo ?? 0, newestId(data.messages));
    const known = new Set(room.messages.map((m) => m.id));
    const fresh = data.messages.filter((m) => !known.has(m.id));
    room.messages.push(...fresh);
    room.messages.sort((a, b) => a.id - b.id);          // a message sent meanwhile may already be in the list
    (data.changed ?? []).forEach(storeMessage);
    const changed = applyStatus(data.status);
    renderMessages("keep");
    renderTyping();
    if (changed) {
        renderHead();
        renderNotice();
    }
    if (!els.search.hidden && (fresh.length || data.changed?.length)) scheduleSearch();
    // Every time, not only for fresh messages: one that came while the tab was hidden is read once it is shown again
    // (markRead asks the server only when there is something newer to mark)
    await markRead();
    if (fresh.some((m) => !m.mine)) await refreshList();
}

// ---------------------------------------------------------------- sending, editing

/**
 * Sends what is typed and chosen: plain text in a request or a PLAIN conversation, an envelope (with the files
 * uploaded first) in an E2EE one. Text alone shows at once as "Đang gửi…" (room.outbox) and the box is free for the next
 * message, which goes as soon as this one is through; when sending fails the text comes back into the box. Photos,
 * video and voice keep the box until they are uploaded (their progress shows under it). `again`: the outbox entry sent
 * once more because the conversation turned E2EE meanwhile.
 */
async function send(again = null) {
    if (editing) {
        await saveEdit();
        return;
    }
    if (!room || composeBlock()) return;
    if (sending) {
        if (!again && (els.text.value.trim() || pending.length)) sendQueued = true;
        return;
    }
    if (recorder) {
        showError("Hãy bấm “Dừng” để kết thúc ghi âm trước khi gửi.");
        return;
    }
    const files = again ? [] : pending;
    const content = again ? again.text : els.text.value.trim();
    if (!content && !files.length) return;
    const target = room;
    const e2ee = isE2ee(target);
    if (files.length && !e2ee) {
        showError(FILES_NEED_E2EE);
        return;
    }
    const answeringRequest = target.status.state === "REQUEST";
    const replyToId = again ? again.replyToId : replyTo && target.id ? replyTo.id : null;
    const entry = again ?? (files.length ? null : { key: `o${++outboxSeq}`, text: content, replyToId });
    if (entry && !again) {
        target.outbox = [...(target.outbox ?? []), entry];
        els.text.value = "";
        replyTo = null;
        saveDraft();                                // its draft goes too: it must not come back after being sent
        grow();
        renderComposeBar();
        renderMessages("bottom");
    }
    sending = true;
    sendingFiles = files.length > 0;
    renderComposer();
    hideError();
    let resend = false;
    try {
        let sent;
        if (e2ee) {
            const uploaded = files.length ? await upload(target, files) : { fileIds: [], files: [] };
            const body = { text: content || null, files: uploaded.files };
            const { result, sealed } = await sealAndSend(target, body, (encrypted) =>
                apiClient.sendChat(target.id, { encrypted, fileIds: uploaded.fileIds, replyToId }));
            sent = result;
            remember(sent.message, sealed, body);
        } else if (target.id) {
            sent = await apiClient.sendChat(target.id, replyToId ? { content, replyToId } : content);
        } else {
            sent = await apiClient.sendChatTo(target.toUserId, content);
        }
        if (entry) target.outbox = target.outbox.filter((o) => o !== entry);
        else drafts.delete(draftKey(target));      // text sent with files (with an outbox entry the box was emptied)
        if (room === target) {
            if (!room.id) {
                room.id = sent.conversationId;
                room.toUserId = null;
                history.replaceState(null, "", `/messages/${room.id}`);
            }
            if (!room.messages.some((m) => m.id === sent.message.id)) {
                room.messages.push(sent.message);
                room.messages.sort((a, b) => a.id - b.id);
            }
            applyStatus(sent.status);
            if (!entry) {
                els.text.value = "";
                replyTo = null;
                if (files === pending) dropPending();
                grow();
            }
            renderHead();
            renderNotice();
            renderComposeBar();
            renderMessages("bottom");
        }
        // Answering a request accepts it: it is in the inbox now
        if (answeringRequest && box === "REQUESTS") await switchBox("INBOX");
        else await refreshList();
    } catch (error) {
        if (error?.status === 401) {
            if (entry) putBack(target, entry);      // first, so the text is kept for after signing in
            signedOut(error);
            return;
        }
        if (room === target) {
            // Blocked, waiting or keys changed since the page was drawn: show why
            if ([400, 403, 409].includes(error.status)) await pollStatus();
            // The other member turned encryption on a moment ago: the same text goes again, encrypted
            resend = !again && Boolean(entry) && !e2ee && error.status === 400 && room === target && isE2ee(target)
                && keyState === "ready";
        }
        if (!resend) {
            if (entry) putBack(target, entry);
            if (room === target) showError(error.message);
        }
    } finally {
        sending = false;
        sendingFiles = false;
        hideProgress();
        if (room) renderComposer();
        if (!resend) {
            if (pollAfterSend) {
                pollAfterSend = false;
                tick();
            }
            if (sendQueued) {
                sendQueued = false;
                setTimeout(() => send(), 0);
            }
        }
    }
    if (resend) await send(entry);
}

/** A message that could not be sent: off the thread, and its text back in front of what is typed now. */
function putBack(target, entry) {
    target.outbox = (target.outbox ?? []).filter((o) => o !== entry);
    const join = (rest) => (rest.trim() ? `${entry.text}\n${rest}` : entry.text);
    if (room === target) {
        els.text.value = join(els.text.value);
        if (!replyTo && entry.replyToId) replyTo = { id: entry.replyToId };
        grow();
        renderComposeBar();
        renderMessages("keep");
    } else {
        drafts.set(draftKey(target), join(drafts.get(draftKey(target)) ?? ""));
    }
}

/** Encrypts and uploads the chosen files once; a retry after a failed send reuses the uploaded ones. */
async function upload(target, files) {
    if (pendingUpload?.items === files) return pendingUpload.result;
    if (!media) throw new Error("Không tải được phần gửi ảnh, video, ghi âm. Hãy tải lại trang.");
    showProgress("Đang mã hóa và tải tệp lên…");
    const result = await media.uploadAttachments(target.id, files, (done, total) => {
        if (Number.isFinite(done) && Number.isFinite(total) && total > 0) {
            showProgress(`Đang mã hóa và tải tệp lên (${Math.min(done, total)}/${total})…`);
        }
    });
    pendingUpload = { items: files, result };
    return result;
}

/**
 * Encrypts `body` for the conversation's current keys and hands the envelope to `call` (send or edit). When the
 * server answers 409 (a key changed meanwhile), the status is read again and it is encrypted and sent once more.
 */
async function sealAndSend(target, body, call) {
    for (let attempt = 0; ; attempt++) {
        const enc = encOf(target);
        if (enc?.mode !== "E2EE" || !identity) {
            throw new Error("Cuộc trò chuyện này chưa sẵn sàng để gửi tin nhắn mã hóa. Hãy tải lại trang.");
        }
        if (enc.myKeyId !== identity.keyId) {
            refreshMyKey();
            throw new Error("Khóa mã hóa của bạn vừa được đổi trên một máy khác. Hãy nhập mật khẩu khôi phục rồi gửi lại.");
        }
        const key = await convKeyFor(target.id, enc.myKeyId, enc.peerKeyId, enc.peerPublicKey);
        const sealed = await encryptMessage({ key, conversationId: target.id, senderId: user.id,
            senderKeyId: enc.myKeyId, recipientKeyId: enc.peerKeyId, body });
        try {
            return { result: await call(sealed.envelope), sealed };
        } catch (error) {
            if (error?.status !== 409 || attempt > 0) throw error;
            const fresh = await apiClient.chat(target.id);
            if (room === target) {
                applyStatus(fresh.status);
                renderHead();
                renderNotice();
            } else {
                target.status = fresh.status;
            }
            if (!isE2ee(target)) throw error;
        }
    }
}

/** The member's own encrypted message is known without decrypting it. */
function remember(message, sealed, body) {
    if (!message?.encrypted) return;
    decrypted.set(cacheKeyOf(message), { state: "ok", body: { text: body.text ?? null, files: body.files ?? [] },
        frankingKey: sealed.frankingKey, plaintext: sealed.plaintext });
}

function startReply(id) {
    if (!room?.messages.some((m) => m.id === id)) return;
    if (editing) cancelEdit();
    replyTo = { id };
    closeMenu();
    renderComposeBar();
    els.text.focus();
}

function cancelReply() {
    replyTo = null;
    renderComposeBar();
}

function startEdit(id) {
    const m = room?.messages.find((x) => x.id === id);
    if (!m) return;
    const view = messageView(m);
    if (view.kind !== "ok") {
        toast("Chưa đọc được tin nhắn này nên chưa sửa được.", "error");
        return;
    }
    if (!editing) cancelReply();
    editing = { id, stash: editing ? editing.stash : els.text.value, files: view.files, original: view.text ?? "",
        encrypted: Boolean(m.encrypted) };
    closeMenu();
    els.text.value = view.text ?? "";
    hideError();
    grow();
    renderComposeBar();
    renderComposer();
    els.text.focus();
    els.text.setSelectionRange(els.text.value.length, els.text.value.length);
}

function cancelEdit() {
    if (!editing) return;
    els.text.value = editing.stash;
    editing = null;
    hideError();
    grow();
    renderComposeBar();
    renderComposer();
}

/** "Sửa": plain messages get their new text; encrypted ones a new envelope with the same files. */
async function saveEdit() {
    if (!room || sending || !editing) return;
    const target = room;
    const ed = editing;
    const m = target.messages.find((x) => x.id === ed.id);
    if (!m || m.recalled) {
        cancelEdit();
        return;
    }
    const text = els.text.value.trim();
    if (!text && !ed.files.length) {
        showError("Hãy nhập tin nhắn.");
        return;
    }
    if (text === ed.original.trim()) {
        cancelEdit();
        return;
    }
    sending = true;
    renderComposer();
    hideError();
    try {
        let message;
        if (m.encrypted) {
            const body = { text: text || null, files: ed.files };
            const { result, sealed } = await sealAndSend(target, body, (encrypted) =>
                apiClient.editChatMessage(target.id, m.id, { encrypted }));
            message = result;
            remember(message, sealed, body);
        } else {
            message = await apiClient.editChatMessage(target.id, m.id, { content: text });
        }
        if (room === target) {
            if (editing === ed) {
                editing = null;
                els.text.value = ed.stash;
                grow();
                renderComposeBar();
            }
            storeMessage(message);
            renderMessages("keep");
            refreshList();
        }
    } catch (error) {
        if (signedOut(error)) return;
        if (room === target) showError(error.message);
    } finally {
        sending = false;
        if (room) renderComposer();
    }
}

/** "Thu hồi": both members then see that a message was recalled; its files are deleted. */
async function recall(id) {
    if (!await confirmDialog("Thu hồi tin nhắn này? Cả hai bạn sẽ thấy “Tin nhắn đã được thu hồi”; ảnh, video, ghi âm kèm "
        + "theo cũng bị xóa.", "Thu hồi")) return;
    const target = room;
    try {
        const message = await apiClient.recallChatMessage(target.id, id);
        if (room !== target) return;
        storeMessage(message);
        renderMessages("keep");
        toast("Đã thu hồi tin nhắn.");
        refreshList();
    } catch (error) {
        if (!signedOut(error)) toast(error.message, "error");
    }
}

/** A reaction from the picker: the same emoji again takes it back. */
async function react(id, emoji) {
    const target = room;
    const m = target?.messages.find((x) => x.id === id);
    if (!m) return;
    const current = m.reactions?.find((r) => r.mine)?.emoji ?? null;
    closeMenu();
    try {
        const message = await apiClient.reactChatMessage(target.id, id, emoji === current ? null : emoji);
        if (room !== target) return;
        storeMessage(message);
        renderMessages("keep");
    } catch (error) {
        if (!signedOut(error)) toast(error.message, "error");
    }
}

async function copyText(id) {
    const m = room?.messages.find((x) => x.id === id);
    const view = m && messageView(m);
    closeMenu();
    if (!view?.text) return;
    try {
        await navigator.clipboard.writeText(view.text);
        toast("Đã sao chép tin nhắn.");
    } catch {
        toast("Trình duyệt không cho sao chép.", "error");
    }
}

/** "đang soạn tin" while typing, at most every few seconds, only where the other member may see it. */
function pingTyping() {
    if (!room?.id || !room.status.canReact || editing || !els.text.value.trim()) return;
    const now = Date.now();
    if (now - lastTyping < TYPING_MS) return;
    lastTyping = now;
    apiClient.chatTyping(room.id).catch(signedOut);
}

async function pollStatus() {
    if (room?.id) {
        await pollRoom();
    }
}

function renderComposeBar() {
    let html = "";
    if (editing) {
        html = `${ic("edit")}<span class="chat-bar-txt"><b>Đang sửa tin nhắn</b>${touchOnly() ? "" : " · Enter để lưu, Esc để hủy"}</span>
            <button class="btn sm ghost" type="button" data-act="edit-cancel">Hủy</button>`;
    } else if (replyTo && room) {
        const q = quoteView(replyTo.id);
        html = `${ic("reply")}<span class="chat-bar-txt">Trả lời <b>${esc(q.who)}</b>: ${esc(q.text)}</span>
            <button class="btn sm ghost" type="button" data-act="reply-cancel" title="Không trả lời tin này nữa">${ic("close")}<span class="sr-only">Không trả lời tin này nữa</span></button>`;
    }
    if (els.composeBar.dataset.html === html) return;
    els.composeBar.dataset.html = html;
    els.composeBar.innerHTML = html;
    els.composeBar.hidden = !html;
}

function draftKey(r) {
    return r.id ? `c${r.id}` : `u${r.toUserId}`;
}

function saveDraft() {
    if (!room) return;
    const text = editing ? editing.stash : els.text.value;
    if (text.trim()) drafts.set(draftKey(room), text);
    else drafts.delete(draftKey(room));
}

function grow() {
    els.text.style.height = "auto";
    els.text.style.height = `${Math.min(els.text.scrollHeight + 2, TEXT_MAX_HEIGHT)}px`;
    const n = els.text.value.length;
    els.counter.textContent = n >= COUNTER_FROM ? `${n}/${room?.limits?.messageMax ?? 2000} ·` : "";
}

function showError(message) {
    els.sendError.textContent = message;
    els.sendError.hidden = false;
}

function hideError() {
    els.sendError.hidden = true;
}

function showProgress(text) {
    els.progress.textContent = text;
    els.progress.hidden = false;
}

function hideProgress() {
    els.progress.hidden = true;
}

/** Session ended (or signed out in another tab): keep what was typed and go to sign in, then come back here. */
function signedOut(error) {
    if (error?.status !== 401) return false;
    keepDrafts();
    location.replace(loginUrl());
    return true;
}

/**
 * Keeps what is typed in this tab's sessionStorage, with the member's id: on the way to the sign-in page, and when the
 * tab goes to the background or away (a phone may reload a tab it put aside). Never after "Đăng xuất".
 */
function keepDrafts() {
    if (signingOut) return;
    saveDraft();
    try {
        if (drafts.size) sessionStorage.setItem(DRAFT_STORE, JSON.stringify({ userId: user.id, drafts: [...drafts] }));
        else sessionStorage.removeItem(DRAFT_STORE);
    } catch {
        // storage blocked: the text is lost with the page
    }
}

/** Back in front: the page's own copy counts again (a message sent now must not come back after a reload). */
function forgetKeptDrafts() {
    try {
        sessionStorage.removeItem(DRAFT_STORE);
    } catch {
        // storage blocked: nothing was kept
    }
}

/** What this member typed before the page was reloaded or sent to sign in; another member's text is dropped. */
function restoreDrafts() {
    try {
        const saved = JSON.parse(sessionStorage.getItem(DRAFT_STORE) || "null");
        sessionStorage.removeItem(DRAFT_STORE);
        if (saved?.userId === user.id && Array.isArray(saved.drafts)) {
            saved.drafts.forEach((entry) => {
                if (Array.isArray(entry) && typeof entry[0] === "string" && typeof entry[1] === "string") {
                    drafts.set(entry[0], entry[1]);
                }
            });
        }
    } catch {
        // nothing kept
    }
}

// ---------------------------------------------------------------- attachments, voice messages

/**
 * Photos or a video picked (or pasted): prepared by chat-media.js (which checks the mix with the ones already chosen:
 * up to filesMax photos, or one video, or one voice message) and shown above the message box.
 */
async function addFiles(files) {
    if (!files.length || !room || !media || els.attachTools.hidden) return;
    const target = room;
    const before = pending;
    els.attach.disabled = true;
    showProgress("Đang chuẩn bị tệp…");
    hideError();
    try {
        const { items: ready, error } = await media.prepareFiles(files, target.limits, before);
        if (room !== target || pending !== before) {
            ready.forEach((item) => media.releasePending(item));
            return;
        }
        if (error) showError(error);
        if (ready.length) setPending([...before, ...ready]);
    } catch (error) {
        showError(error.message);
    } finally {
        hideProgress();
        renderComposer();
    }
}

function setPending(next) {
    pending.filter((item) => !next.includes(item)).forEach((item) => media?.releasePending?.(item));
    pending = next;
    pendingUpload = null;
    renderPending();
    renderComposer();
}

function dropPending() {
    pending.forEach((item) => media?.releasePending?.(item));
    pending = [];
    pendingUpload = null;
    renderPending();
}

function removePending(index) {
    if (!pending[index] || sending) return;
    setPending(pending.filter((_, i) => i !== index));
}

function renderPending() {
    els.pending.innerHTML = pending.length && media ? media.pendingHtml(pending) : "";
    els.pending.hidden = !pending.length;
}

/** "Ghi âm": a voice message is the only attachment of its message, so it starts only when none is chosen. */
async function startRecording() {
    if (!media?.VoiceRecorder || recorder || !room || sending) return;
    if (pending.length) {
        showError("Mỗi tin nhắn gửi được ảnh, hoặc một video, hoặc một tin nhắn thoại. Hãy bỏ tệp đã chọn trước khi ghi âm.");
        return;
    }
    const target = room;
    const max = target.limits.audioMaxSeconds ?? 120;
    const rec = new media.VoiceRecorder({
        maxSeconds: max,
        maxBytes: target.limits.audioMaxBytes,
        onTick: (seconds) => {
            if (recorder !== rec) return;
            recordSeconds = seconds;
            renderRecordBar(true);
        },
        // the time is up, or the microphone went away: what was recorded is kept
        onStop: (item, error) => finishRecording(rec, Promise.resolve(item), error)
    });
    recorder = rec;
    recordSeconds = 0;
    recordStopping = false;
    hideError();
    renderRecordBar();
    renderComposer();
    try {
        await rec.start();
    } catch (error) {
        if (recorder === rec) recorder = null;
        renderRecordBar();
        renderComposer();
        showError(error.message);
    }
}

/** "Dừng" (or the recorder stopped by itself): the clip becomes the attachment of the next message. */
async function finishRecording(rec, result, failure) {
    if (!rec || rec !== recorder || recordStopping) return;
    recordStopping = true;
    const target = room;
    try {
        if (failure) throw failure;
        const item = await result;
        const kept = recorder === rec;                  // not cancelled meanwhile
        if (kept) recorder = null;
        if (item && kept && room === target) setPending([item]);
        else if (item) media.releasePending(item);
        else if (kept) showError("Bản ghi âm quá ngắn. Hãy nói ít nhất 1 giây.");
    } catch (error) {
        showError(error.message);
    } finally {
        if (recorder === rec) recorder = null;
        recordStopping = false;
        renderRecordBar();
        renderComposer();
    }
}

function stopRecording() {
    if (recorder) finishRecording(recorder, recorder.stop(), null);
}

function cancelRecording() {
    const rec = recorder;
    recorder = null;
    recordStopping = false;
    if (rec) {
        try {
            rec.cancel();
        } catch (error) {
            console.warn("chat recorder", error);
        }
    }
    renderRecordBar();
    if (room) renderComposer();
}

/** The bar while recording: chat-media.js's own (timer and progress, "Hủy", "Xong"), or a plain one. */
function renderRecordBar(tickOnly = false) {
    if (!recorder) {
        els.recordBar.hidden = true;
        els.recordBar.innerHTML = "";
        return;
    }
    const max = room?.limits?.audioMaxSeconds ?? 120;
    if (media?.recorderHtml) {
        // only the numbers change on a tick: the focus stays on "Xong"
        if (tickOnly && !els.recordBar.hidden && media.updateRecorder) {
            media.updateRecorder(els.recordBar, recordSeconds, max);
        } else {
            els.recordBar.className = "chat-record";
            els.recordBar.innerHTML = media.recorderHtml(recordSeconds, max);
        }
    } else {
        els.recordBar.className = "chat-compose-bar rec";
        els.recordBar.innerHTML = `<span class="rec-dot" aria-hidden="true"></span>
            <span class="chat-bar-txt">Đang ghi âm <b>${mmss(recordSeconds)}</b> / ${mmss(max)}</span>
            <button class="btn sm ghost" type="button" data-recorder-cancel>Hủy</button>
            <button class="btn sm pri" type="button" data-recorder-stop>Xong</button>`;
    }
    els.recordBar.hidden = false;
}

function mmss(seconds) {
    const s = Math.max(0, Math.floor(seconds || 0));
    return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

// ---------------------------------------------------------------- search in the conversation

function openSearch() {
    if (!room?.id) return;
    els.search.hidden = false;
    els.searchInput.focus();
    els.searchInput.select();
    ensurePool();
    runSearch();
}

function closeSearch(focusText) {
    els.search.hidden = true;
    els.searchResults.innerHTML = "";
    els.searchNote.textContent = "";
    clearTimeout(searchTimer);
    if (focusText && room && !els.text.disabled) els.text.focus();
}

/**
 * The whole conversation for search: older pages (200 at a time) until the beginning or SEARCH_CAP messages, all
 * decrypted here. Kept apart from the messages drawn, so a long history is not drawn at once.
 */
function ensurePool() {
    const target = room;
    if (target.pool) return target.pool.ready;
    const pool = { map: new Map(target.messages.map((m) => [m.id, m])), hasOlder: target.hasOlder, loading: true };
    target.pool = pool;
    pool.ready = (async () => {
        let before = target.messages[0]?.id;
        for (let page = 0; pool.hasOlder && before && pool.map.size < SEARCH_CAP && page < SEARCH_PAGES; page++) {
            const data = await apiClient.chatOlder(target.id, before, OLDER_BATCH);
            if (room !== target) return;
            data.messages.forEach((m) => {
                if (!pool.map.has(m.id)) pool.map.set(m.id, m);
            });
            pool.hasOlder = data.hasOlder;
            before = data.messages[0]?.id;
            if (!data.messages.length) break;
            scheduleSearch();
        }
        await Promise.allSettled([...pool.map.values()].filter((m) => m.encrypted && !m.recalled)
            .map((m) => decryption(target.id, m, target.peer.id).promise).filter(Boolean));
    })().catch((error) => {
        if (signedOut(error)) return;
        pool.error = error.message;
    }).finally(() => {
        pool.loading = false;
        if (room === target) runSearch();
    });
    return pool.ready;
}

function scheduleSearch() {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(runSearch, 200);
}

/** Matches without accents or case ("da nang" finds "Đà Nẵng"), newest first. */
function runSearch() {
    if (!room || els.search.hidden) return;
    const query = els.searchInput.value;
    const pool = room.pool;
    const all = new Map(pool ? pool.map : []);
    room.messages.forEach((m) => all.set(m.id, m));
    const hits = [];
    let locked = 0;
    if (query.trim()) {
        for (const m of [...all.values()].sort((a, b) => b.id - a.id)) {
            if (m.recalled) continue;
            const view = messageView(m);
            if (view.kind !== "ok") {
                if (m.encrypted && view.kind !== "pending" && view.kind !== "failed") locked++;
                continue;
            }
            const s = view.text ? snippet(view.text, query) : null;
            if (s) hits.push({ m, s });
        }
    }
    const notes = [];
    if (pool?.loading) notes.push(`Đang tải và giải mã tin nhắn cũ (${all.size} tin)…`);
    if (pool?.error) notes.push(`Chưa tải được hết tin nhắn: ${pool.error}`);
    if (!query.trim()) {
        notes.push("Nhập từ cần tìm. Không cần gõ dấu.");
    } else if (!pool?.loading) {
        notes.push(hits.length ? `${hits.length} tin nhắn có “${query.trim()}”${hits.length > SEARCH_SHOWN ? `, hiện ${SEARCH_SHOWN} tin mới nhất` : ""}.`
            : `Không tìm thấy tin nhắn nào có “${query.trim()}”.`);
    }
    if (pool && !pool.loading && pool.hasOlder) notes.push(`Chỉ tìm trong ${all.size} tin nhắn gần nhất.`);
    if (locked) notes.push(`${locked} tin nhắn mã hóa chưa đọc được nên không tìm trong đó.`);
    els.searchNote.textContent = notes.join(" ");
    els.searchResults.innerHTML = hits.slice(0, SEARCH_SHOWN).map(({ m, s }) => `<li><button class="chat-hit" type="button"
        data-act="jump" data-target="${m.id}" data-from="search">
        <span class="chat-hit-top"><b>${esc(m.mine ? "Bạn" : room.peer.displayName)}</b>
            <time datetime="${esc(m.createdAt)}">${esc(fullDate(m.createdAt))}</time></span>
        <span class="chat-hit-txt">${esc(s.before)}<mark>${esc(s.match)}</mark>${esc(s.after)}</span></button></li>`).join("");
}

/** Scrolls to a message (a quote, a search hit), loading the older ones up to it first, and highlights it. */
async function jumpTo(id, fromSearch) {
    const target = room;
    if (!target?.id || !id) return;
    if (!target.messages.some((m) => m.id === id)) {
        try {
            const first = target.messages[0]?.id;
            if (target.pool?.map.has(id) && first) {
                const pool = target.pool;
                mergeOlder([...pool.map.values()].filter((m) => m.id >= id && m.id < first));
                target.hasOlder = pool.hasOlder || [...pool.map.keys()].some((k) => k < id);
            } else {
                for (let page = 0; target.hasOlder && page < JUMP_PAGES
                    && !target.messages.some((m) => m.id === id); page++) {
                    const data = await apiClient.chatOlder(target.id, target.messages[0].id, OLDER_BATCH);
                    if (room !== target) return;
                    mergeOlder(data.messages);
                    target.hasOlder = data.hasOlder;
                }
            }
        } catch (error) {
            if (!signedOut(error)) toast(error.message, "error");
            return;
        }
        if (room !== target) return;
        renderMessages("older");
    }
    const el = els.msgs.querySelector(`.msg[data-id="${id}"]`);
    if (!el) {
        toast("Không tìm thấy tin nhắn này trong cuộc trò chuyện.", "error");
        return;
    }
    if (fromSearch && matchMedia("(max-width: 860px)").matches) closeSearch(false);
    el.scrollIntoView({ block: "center", behavior: matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth" });
    el.classList.remove("flash");
    void el.offsetWidth;                            // restarts the animation
    el.classList.add("flash");
    setTimeout(() => el.classList.remove("flash"), 1700);
}

// ---------------------------------------------------------------- "Mã an toàn"

async function openSafety() {
    const enc = encOf(room);
    if (enc?.mode !== "E2EE") return;
    const name = room.peer.displayName;
    const body = $("#safety-body");
    body.innerHTML = `<p class="note">Đang tính mã an toàn…</p>`;
    if (!safetyDlg.open) safetyDlg.showModal();
    if (!cryptoSupported()) {
        body.innerHTML = `<p>${esc(NEED_HTTPS)} Mở trang qua https để xem mã an toàn.</p>`;
        return;
    }
    try {
        const number = await safetyNumber(await publicKeyOf(enc.myKeyId), enc.peerPublicKey || await publicKeyOf(enc.peerKeyId));
        body.innerHTML = `<p>Tin nhắn giữa bạn và <b>${esc(name)}</b> được mã hóa đầu cuối: chỉ hai bạn đọc được.</p>
            <p class="chat-safety-number" aria-label="Mã an toàn: ${esc(number)}">${number.split(" ").map((g) => `<span>${esc(g)}</span>`).join("")}</p>
            <p class="note">Muốn chắc không ai xen vào giữa, hãy so dãy số này với ${esc(name)} khi gặp mặt hoặc gọi điện: hai máy
                phải hiện cùng một dãy. Mã đổi khi một trong hai bạn tạo khóa mã hóa mới.</p>`;
    } catch (error) {
        if (signedOut(error)) return;
        body.innerHTML = `<p class="field-error">${esc(error.message)}</p>`;
    }
}

// ---------------------------------------------------------------- actions

function wire() {
    document.querySelectorAll(".chat-tabs [role=tab]").forEach((tab) => {
        tab.addEventListener("click", () => switchBox(tab.dataset.box));
    });
    els.more.addEventListener("click", () => loadList(listPage + 1));
    els.list.addEventListener("click", (event) => {
        const link = event.target.closest("a.chat-item");
        if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        event.preventDefault();
        go(`/messages/${link.dataset.id}`);
    });
    els.older.addEventListener("click", loadOlder);
    els.compose.addEventListener("submit", (event) => {
        event.preventDefault();
        // only the send button sends: another button inside the form (e.g. removing an attachment) must not
        if (event.submitter && event.submitter !== els.send) return;
        send();
    });
    els.text.addEventListener("keydown", (event) => {
        // Enter sends; Shift+Enter is a new line; Enter that ends an IME composition (Japanese, Vietnamese) does not send.
        // On a phone (no Shift to hold) Enter is a new line and the button sends, as in the phone's own chat apps.
        if (event.key === "Enter" && !event.shiftKey && !event.isComposing && event.keyCode !== 229 && !touchOnly()) {
            event.preventDefault();
            send();
        } else if (event.key === "Escape" && (editing || replyTo)) {
            event.preventDefault();
            if (editing) cancelEdit();
            else cancelReply();
        }
    });
    els.text.addEventListener("input", () => {
        grow();
        hideError();
        pingTyping();
    });
    els.text.addEventListener("paste", (event) => {
        const files = [...(event.clipboardData?.files ?? [])];
        if (files.length && !els.attachTools.hidden) {
            event.preventDefault();
            addFiles(files);
        }
    });
    els.attach.addEventListener("click", () => {
        if (!media) return;
        els.attachInput.accept = media.ATTACH_ACCEPT ?? "";
        els.attachInput.value = "";
        els.attachInput.click();
    });
    els.attachInput.addEventListener("change", () => addFiles([...els.attachInput.files]));
    els.record.addEventListener("click", startRecording);
    els.room.addEventListener("click", onRoomAction);
    els.thread.addEventListener("scroll", () => {
        const t = els.thread;
        stick = t.scrollHeight - t.scrollTop - t.clientHeight < 80;
    }, { passive: true });
    // Photos and videos finish loading after the drawing: a reader at the bottom stays at the bottom
    if (typeof ResizeObserver === "function") {
        const follow = new ResizeObserver(() => {
            if (stick && room) els.thread.scrollTop = els.thread.scrollHeight;
        });
        follow.observe(els.msgs);
        follow.observe(els.thread);
    }
    wireLongPress();
    document.addEventListener("click", (event) => {
        if (menu && !event.target.closest(".msg.tools-open")) closeMenu();
        onKeyAction(event);
    });
    document.addEventListener("keydown", (event) => {
        if (event.key === "Escape" && menu) closeMenu();
    });
    els.searchInput.addEventListener("input", scheduleSearch);
    els.searchInput.addEventListener("keydown", (event) => {
        if (event.key === "Escape") {
            event.preventDefault();
            closeSearch(true);
        } else if (event.key === "Enter") {
            event.preventDefault();
            runSearch();
        }
    });
    els.blocksToggle.addEventListener("click", toggleBlocks);
    els.blocks.addEventListener("click", onUnblockFromList);
    wireDialogs();
}

/** On touch screens a long press on a message opens its tools and the reactions (there is no hover there). */
function wireLongPress() {
    document.body.classList.toggle("chat-can-copy", Boolean(navigator.clipboard?.writeText));
    const fire = (p) => {
        if (p.fired) return;
        p.fired = true;
        clearTimeout(p.timer);
        const li = p.li;
        if (!li.isConnected || !li.querySelector(".msg-tools")) return;
        openMenu(Number(li.dataset.id), Boolean(li.querySelector(".msg-react-wrap")));
        navigator.vibrate?.(10);
    };
    els.msgs.addEventListener("pointerdown", (event) => {
        if (event.pointerType === "mouse") return;
        const li = event.target.closest(".msg");
        // a photo (chat-media.js's .cm-open) counts as the message, so photo-only messages have their tools too
        if (!li || event.target.closest("a, video, audio, input, button:not(.cm-open)")) return;
        if (press) clearTimeout(press.timer);
        const p = { li, x: event.clientX, y: event.clientY, fired: false };
        p.timer = setTimeout(() => fire(p), LONG_PRESS_MS);
        press = p;
    });
    els.msgs.addEventListener("pointermove", (event) => {
        if (press && !press.fired && Math.hypot(event.clientX - press.x, event.clientY - press.y) > 10) {
            clearTimeout(press.timer);
            press = null;
        }
    });
    const end = () => {
        if (press) clearTimeout(press.timer);
        setTimeout(() => {
            press = null;
        }, 0);
    };
    els.msgs.addEventListener("pointerup", end);
    els.msgs.addEventListener("pointercancel", end);
    // Android fires the context menu for a long press: it opens the tools instead of the browser's menu
    els.msgs.addEventListener("contextmenu", (event) => {
        if (!press) return;
        event.preventDefault();
        fire(press);
    });
}

async function onRoomAction(event) {
    // buttons made by chat-media.js (attachments chosen, the recorder bar)
    const own = event.target.closest("[data-remove-attachment], [data-recorder-stop], [data-recorder-cancel]");
    if (own) {
        event.preventDefault();
        if (own.hasAttribute("data-remove-attachment")) removePending(Number(own.dataset.removeAttachment));
        else if (own.hasAttribute("data-recorder-stop")) stopRecording();
        else cancelRecording();
        return;
    }
    const button = event.target.closest("[data-act]");
    if (!button || button.disabled) return;
    const act = button.dataset.act;
    const id = Number(button.dataset.id);
    switch (act) {
        case "back": go("/messages"); return;
        case "report": openReport(id); return;
        case "search": if (els.search.hidden) openSearch(); else closeSearch(true); return;
        case "search-close": closeSearch(true); return;
        case "safety": openSafety(); return;
        case "react-open":
            if (menu?.id === id && menu.picking) closeMenu();
            else openMenu(id, true);
            return;
        case "react-pick": react(id, button.dataset.emoji); return;
        case "react-chip": react(id, button.dataset.emoji); return;
        case "reply": startReply(id); return;
        case "copy": copyText(id); return;
        case "edit": startEdit(id); return;
        case "recall": closeMenu(); recall(id); return;
        case "jump": jumpTo(Number(button.dataset.target), button.dataset.from === "search"); return;
        case "reply-cancel": cancelReply(); els.text.focus(); return;
        case "edit-cancel": cancelEdit(); els.text.focus(); return;
        default: break;
    }
    if (!room) return;
    const name = room.peer.displayName;
    try {
        if (act === "accept") {
            applyStatus(await busy(button, () => apiClient.acceptChat(room.id)));
            renderHead();
            renderNotice();
            renderMessages("keep");
            toast(`Đã chấp nhận. Giờ bạn và ${name} nhắn tin thoải mái.`);
            await switchBox("INBOX");
            markedUpTo = 0;
            markRead();
        } else if (act === "decline") {
            if (!await confirmDialog(`Từ chối lời mời nhắn tin của ${name}? Lời mời sẽ rời khỏi danh sách của bạn và `
                + `${name} không gửi thêm được, nhưng không được báo là bị từ chối.`, "Từ chối")) return;
            applyCounts(await busy(button, () => apiClient.declineChat(room.id)));
            toast("Đã từ chối lời mời nhắn tin.");
            await refreshList();
            go("/messages");
        } else if (act === "block") {
            if (!await confirmDialog(`Chặn ${name}? Hai bạn sẽ không nhắn tin được cho nhau cho đến khi bạn bỏ chặn. `
                + `${name} không được báo.`, "Chặn")) return;
            const wasRequest = room.status.state === "REQUEST";
            await busy(button, () => apiClient.blockMember(room.peer.id, true));
            toast(`Đã chặn ${name}.`);
            await afterBlockChange();
            if (wasRequest) go("/messages");
            else await reloadRoom();
        } else if (act === "unblock") {
            await busy(button, () => apiClient.blockMember(room.peer.id, false));
            toast(`Đã bỏ chặn ${name}.`);
            await afterBlockChange();
            await reloadRoom();
        } else if (act === "clear") {
            if (!await confirmDialog(`Xóa cuộc trò chuyện với ${name}? Các tin nhắn hiện có sẽ không còn hiện với bạn; `
                + `${name} vẫn giữ tin nhắn của họ. Nếu có tin nhắn mới, cuộc trò chuyện sẽ hiện lại.`, "Xóa")) return;
            applyCounts(await busy(button, () => apiClient.clearChat(room.id)));
            drafts.delete(draftKey(room));
            toast("Đã xóa cuộc trò chuyện.");
            await refreshList();
            go("/messages");
        }
    } catch (error) {
        if (!signedOut(error)) toast(error.message, "error");
    }
}

async function afterBlockChange() {
    await refreshList();
    if (els.blocksToggle.getAttribute("aria-expanded") === "true") await loadBlocks();
}

async function toggleBlocks() {
    const open = els.blocksToggle.getAttribute("aria-expanded") !== "true";
    els.blocksToggle.setAttribute("aria-expanded", String(open));
    els.blocks.hidden = !open;
    if (open) await loadBlocks();
}

async function loadBlocks() {
    try {
        const rows = await apiClient.chatBlocks();
        els.blocks.innerHTML = rows.length ? rows.map((r) => `<li>${avatar(r.peer)}
                <span class="name">${esc(r.peer.displayName)}</span>
                <button class="btn sm" type="button" data-unblock="${r.peer.id}">Bỏ chặn</button></li>`).join("")
            : `<li class="note">Bạn chưa chặn ai.</li>`;
    } catch (error) {
        if (!signedOut(error)) els.blocks.innerHTML = `<li class="note">${esc(error.message)}</li>`;
    }
}

async function onUnblockFromList(event) {
    const button = event.target.closest("[data-unblock]");
    if (!button) return;
    const id = Number(button.dataset.unblock);
    try {
        await busy(button, () => apiClient.blockMember(id, false));
        toast("Đã bỏ chặn.");
        await afterBlockChange();
        if (room?.peer.id === id) await reloadRoom();
    } catch (error) {
        if (!signedOut(error)) toast(error.message, "error");
    }
}

/** Disables the button while the call runs. */
async function busy(button, call) {
    button.disabled = true;
    try {
        return await call();
    } finally {
        if (button.isConnected) button.disabled = false;
    }
}

// ---------------------------------------------------------------- report dialog, confirm dialog, toast

/**
 * Report a received message. For an encrypted one the decrypted body and its franking key go with the report: the
 * server checks them against the tag stored with the message and keeps the text for the Moderator.
 */
function openReport(messageId) {
    if (!room) return;
    const m = room.messages.find((x) => x.id === messageId);
    let evidence = null;
    if (m?.encrypted) {
        const entry = decrypted.get(cacheKeyOf(m));
        if (entry?.state !== "ok") {
            toast("Chưa đọc được tin nhắn này nên chưa báo cáo được. Bạn vẫn có thể chặn người này.", "error");
            return;
        }
        evidence = { plaintext: entry.plaintext, frankingKey: entry.frankingKey };
    }
    reportTarget = { messageId, room, evidence };
    $("#report-e2ee").hidden = !evidence;
    $("#report-reasons").innerHTML = `<legend class="sr-only">Lý do</legend>`
        + room.reportReasons.map((r) => `<label class="check"><input type="radio" name="reason" value="${r.id}"><span>${esc(r.label)}</span></label>`).join("");
    $("#report-note").value = "";
    $("#report-block").checked = !room.status.blockedByMe;
    $("#report-block").closest("label").hidden = room.status.blockedByMe;
    reportError.hidden = true;
    reportDlg.showModal();
}

function wireDialogs() {
    reportForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const reason = reportForm.querySelector('input[name="reason"]:checked');
        if (!reason) {
            reportError.textContent = MSG40;
            reportError.hidden = false;
            return;
        }
        const block = $("#report-block").checked && !reportTarget.room.status.blockedByMe;
        const body = { reasonId: Number(reason.value), note: $("#report-note").value.trim() || null, block,
            ...(reportTarget.evidence ?? {}) };
        try {
            await busy($("#report-submit"), () => apiClient.reportChatMessage(reportTarget.messageId, body));
            reportDlg.close();
            toast("Cảm ơn bạn. Báo cáo đã được gửi.");                       // MSG38
            if (block && room === reportTarget.room) {
                const wasRequest = room.status.state === "REQUEST";
                await afterBlockChange();
                if (wasRequest) go("/messages");
                else await reloadRoom();
            }
        } catch (error) {
            if (signedOut(error)) return;
            reportError.textContent = error.message;                     // e.g. MSG39 already reported
            reportError.hidden = false;
        }
    });
    $("#key-setup-form").addEventListener("submit", submitSetup);
    $("#key-unlock-form").addEventListener("submit", submitUnlock);
    $("#key-setup-later").addEventListener("click", () => setupDlg.close());
    $("#key-unlock-later").addEventListener("click", () => unlockDlg.close());
    $("#key-forgot").addEventListener("click", forgotPassphrase);
    [setupDlg, unlockDlg].forEach((d) => d.addEventListener("cancel", (event) => {
        if (d.classList.contains("busy")) event.preventDefault();        // Esc while the key is being made
    }));
    document.querySelectorAll("dialog [data-close]").forEach((b) => b.addEventListener("click", () => b.closest("dialog").close()));
    // A click on the backdrop closes the small dialogs; the key dialogs stay (a typed password would be lost)
    [reportDlg, confirmDlg, safetyDlg].forEach((d) => d.addEventListener("click", (event) => {
        if (event.target === d) d.close();
    }));
}

function confirmDialog(text, okLabel) {
    $("#confirm-text").textContent = text;
    const ok = $("#confirm-ok");
    ok.textContent = okLabel;
    confirmDlg.showModal();
    return new Promise((resolve) => {
        const done = (value) => {
            ok.removeEventListener("click", onOk);
            confirmDlg.removeEventListener("close", onClose);
            if (confirmDlg.open) confirmDlg.close();
            resolve(value);
        };
        const onOk = () => done(true);
        const onClose = () => done(false);
        ok.addEventListener("click", onOk);
        confirmDlg.addEventListener("close", onClose);
    });
}

function toast(message, kind = "ok") {
    els.toast.textContent = message;
    els.toast.className = `toast ${kind}`;
    els.toast.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { els.toast.hidden = true; }, 3500);
}
