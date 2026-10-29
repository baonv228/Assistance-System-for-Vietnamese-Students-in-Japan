// Top navigation (desktop) and bottom tab bar (phone), as in the prototype.
// Usage: const user = await mountSiteHeader("home");  → returns the signed-in user or null.
import { apiClient, currentUser } from "../api/client.js";
import { onLive, startLive } from "../lib/live.js";
import { esc, ic, LOGO_SVG, loginUrl } from "../lib/ui.js";

/** Features other members are building link to a placeholder page for now. */
const soon = (feature) => `/coming-soon?f=${feature}`;

const TABS = [
    ["home", "Trang chủ", "home", "/"],
    ["map", "Bản đồ", "map", "/map"],
    ["learn", "Học", "school", soon("learn")],
    ["fest", "Sự kiện", "celebration", "/events"],
    ["blog", "Blog", "forum", "/blog"],
    ["qa", "Hỏi-Đáp", "contact_support", "/qa"],
    ["guide", "Cẩm nang", "menu_book", "/guides"],
    ["me", "Tôi", "person", "/profile"]
];

const link = ([key, label, icon, href], active) =>
    `<a href="${href}" ${key === active ? 'aria-current="page"' : ""}>${ic(icon)}<span>${label}</span></a>`;

export async function mountSiteHeader(active) {
    const user = await currentUser().catch(() => null);

    const header = document.createElement("header");
    header.className = "site-top";
    header.innerHTML = `
        <a class="logo" href="/">${LOGO_SVG}Senpai ơi</a>
        <nav class="site-nav" aria-label="Chính">${TABS.filter((t) => t[0] !== "me").map((t) => link(t, active)).join("")}</nav>
        <span class="grow"></span>
        <div class="site-account">${accountHtml(user, active)}</div>`;
    document.body.prepend(header);

    const tabbar = document.createElement("nav");
    tabbar.className = "tabbar";
    tabbar.setAttribute("aria-label", "Điều hướng");
    tabbar.innerHTML = TABS.map((t) => link(t, active)).join("");
    document.body.append(tabbar);
    document.body.classList.add("has-tabbar");

    header.querySelector("[data-logout]")?.addEventListener("click", logout);
    // Back to this page after signing in. Read again when pressed: list pages keep their filters in the address and
    // change it after the header is drawn.
    const login = header.querySelector("[data-login]");
    const refresh = () => { login.href = loginUrl(); };
    login?.addEventListener("pointerdown", refresh);
    login?.addEventListener("click", refresh);
    if (user) {
        watchNotifications(header, user);
        startLive();
    }
    return user;
}

function accountHtml(user, active) {
    if (!user) {
        return `<a class="btn pri sm" href="${esc(loginUrl())}" data-login>${ic("login")}Đăng nhập</a>`;
    }
    const staff = user.role !== "USER"
        ? `<a class="btn sm" href="${esc(user.homePath)}">${ic("space_dashboard")}<span class="who">Trang ${esc(user.roleLabel)}</span></a>`
        : "";
    return `${staff}
        <a class="btn sm bell" href="/notifications" data-bell title="Thông báo">${ic("notifications")}<span class="bell-count" data-bell-count hidden></span><span class="sr-only" data-bell-label>Thông báo</span></a>
        <a class="btn sm ${active === "me" ? "on" : ""}" href="/profile">${ic("account_circle")}<span class="who">${esc(user.displayName)}</span></a>
        <button class="btn sm" type="button" data-logout title="Đăng xuất">${ic("logout")}<span class="sr-only">Đăng xuất</span></button>`;
}

export async function logout() {
    try {
        await apiClient.logout();
    } finally {
        window.location.replace("/login");
    }
}

// ---------------------------------------------------------------- notifications
// The bell shows the unread count. While one of the user's own posts, questions or reviews waits for its photo/video
// scan, the count is asked again every few seconds; the result (CONTENT_PUBLISHED: published, CONTENT_REJECTED: refused
// with the reason) then pops up on whatever page the user is on, and the page hears of every new notification through
// an "asvsj:notification" event (the post, event or place it is about reloads what it shows). A new notification is
// asked for at once when the live stream announces it (lib/live.js); otherwise the bell asks once a minute, and at once
// when the tab comes back to the front; never while the tab is hidden.

const FAST_MS = 5000;
const SLOW_MS = 60000;
const NOTICE_MS = 12000;
const NOTICES_MAX = 3;
/** Pop up as a notice: the result of a photo/video scan. The others only count on the bell. */
const NOTICE_TYPES = {
    CONTENT_PUBLISHED: ["check_circle", "ok"],
    CONTENT_REJECTED: ["block", "crit"]
};

let bell = null;
let checkNow = null;

/**
 * Asks the bell at once: the page just sent content whose photos/video are being scanned without leaving the page
 * (a review or pin on the Map), so it starts asking every few seconds and the result pops up as soon as it is known.
 */
export function checkNotificationsSoon() {
    checkNow?.();
}

/**
 * Shows this unread count on the bell (e.g. after the "Thông báo" page marked them read).
 */
export function setNotificationCount(unread) {
    if (!bell) return;
    const count = bell.querySelector("[data-bell-count]");
    const label = bell.querySelector("[data-bell-label]");
    count.textContent = unread > 99 ? "99+" : String(unread);
    count.hidden = !unread;
    label.textContent = unread ? `Thông báo (${unread} chưa đọc)` : "Thông báo";
    bell.title = label.textContent;
}

function watchNotifications(header, user) {
    bell = header.querySelector("[data-bell]");
    // The newest notification this tab has dealt with, kept across pages: a scan result that arrives while the user
    // moves to another page still pops up there. Without it (a new tab) older notifications are only counted.
    const store = `asvsj.notifications.seen.${user.id}`;
    let seen = readSeen(store);
    let timer = null;
    let running = false;
    let again = false;                          // asked again while a request was out: ask once more right after

    async function check() {
        if (running) {
            again = true;
            return;
        }
        clearTimeout(timer);
        timer = null;
        if (document.hidden) return;
        running = true;
        let next = SLOW_MS;
        try {
            const summary = await apiClient.notificationSummary(seen ?? undefined);
            setNotificationCount(summary.unread);
            if (seen !== null) {
                // oldest first, so the newest notice ends up on top
                summary.fresh.slice().reverse().forEach(announce);
            }
            seen = summary.latestId;
            writeSeen(store, seen);
            if (summary.processing > 0) next = FAST_MS;
        } catch (error) {
            if (error.status === 401) {
                running = false;
                return;                         // signed out (or locked): stop asking
            }
        }
        running = false;
        if (again) {
            again = false;
            check();
        } else if (!document.hidden) {
            timer = setTimeout(check, next);
        }
    }

    document.addEventListener("visibilitychange", () => {
        if (!document.hidden && !timer) check();
    });
    onLive("notification", check);
    onLive("open", check);                          // (re)connected: what came while it was down
    checkNow = check;
    check();
}

function announce(notification) {
    window.dispatchEvent(new CustomEvent("asvsj:notification", { detail: notification }));
    if (NOTICE_TYPES[notification.type]) showNotice(notification);
}

function noticeBox() {
    let box = document.querySelector(".site-notices");
    if (!box) {
        box = document.createElement("div");
        box.className = "site-notices";
        box.setAttribute("role", "status");
        box.setAttribute("aria-live", "polite");
        document.body.append(box);
    }
    return box;
}

function showNotice(notification) {
    const [icon, kind] = NOTICE_TYPES[notification.type];
    const box = noticeBox();
    const notice = document.createElement("div");
    notice.className = `site-notice ${kind}`;
    notice.innerHTML = `${ic(icon)}
        <div class="site-notice-txt"><p>${esc(notification.content)}</p>
            ${notification.link ? `<a class="link" href="${esc(notification.link)}">Xem</a>` : ""}</div>
        <button class="site-notice-x" type="button" title="Đóng">${ic("close")}<span class="sr-only">Đóng</span></button>`;
    notice.querySelector(".site-notice-x").addEventListener("click", () => notice.remove());
    box.prepend(notice);
    [...box.children].slice(NOTICES_MAX).forEach((old) => old.remove());
    setTimeout(() => notice.remove(), NOTICE_MS);
}

function readSeen(store) {
    try {
        const value = Number.parseInt(sessionStorage.getItem(store) ?? "", 10);
        return Number.isInteger(value) && value >= 0 ? value : null;
    } catch {
        return null;                            // storage blocked: this page only
    }
}

function writeSeen(store, id) {
    try {
        sessionStorage.setItem(store, String(id));
    } catch {
        // storage blocked
    }
}
