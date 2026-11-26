// The notification list drawn like Facebook's: "Thông báo" with a "…" menu ("Đánh dấu tất cả là đã đọc", "Mở trang
// Thông báo"), the tabs "Tất cả" / "Chưa đọc", the groups "Hôm nay" / "Trước đó" and "Xem thông báo trước đó".
// createNotifList draws it for the bell's panel (mountNotifPopover, opened from the header without leaving the page)
// and for the page /notifications. A notification stays unread (dot, coloured time) until it is clicked; the number on
// the bell is cleared when the list is shown (site-header.js markNotificationsSeen), as on Facebook.
import { apiClient } from "../api/client.js";
import { esc, fullDate, ic, loginUrl } from "../lib/ui.js";
import { groupByDay, mergeFresh, messageHtml, newestId, pictureHtml, shortAgo } from "../lib/notif-view.js";

const TABS = [["all", "Tất cả"], ["unread", "Chưa đọc"]];
/** A click on an unread notification waits at most this long for "đã đọc" to be saved before the page changes. */
const NAV_WAIT_MS = 1500;
const STATUS_MS = 4000;
let made = 0;

const blank = () => ({ items: [], page: 0, hasMore: false, loaded: false, loading: false, error: null, token: 0 });

/**
 * @param mode       "pop" (the bell's panel) or "page" (/notifications)
 * @param onSeen     (newestId) after the first page of a list is on screen: the bell's number goes
 * @param onNavigate before a click on a notification opens its page (the panel closes)
 * @returns {{el, titleId, open, refresh, closeMenu}} open(): load the tab shown afresh; refresh(): add what came since
 */
export function createNotifList({ mode = "pop", onSeen = () => {}, onNavigate = () => {} } = {}) {
    const uid = `np${++made}`;
    const pop = mode === "pop";
    const heading = pop ? "h2" : "h1";
    const root = document.createElement("section");
    root.className = `np np-${mode}`;
    root.setAttribute("aria-labelledby", `${uid}-title`);
    root.innerHTML = `
        <div class="np-head">
            <${heading} class="np-title" id="${uid}-title">Thông báo</${heading}>
            <div class="np-tools">
                <div class="np-menu-wrap">
                    <button class="np-icon" type="button" data-menu-btn aria-haspopup="menu" aria-expanded="false"
                        aria-controls="${uid}-menu" title="Tùy chọn">${ic("more_horiz")}<span class="sr-only">Tùy chọn</span></button>
                    <div class="np-menu" id="${uid}-menu" role="menu" hidden>
                        <button type="button" role="menuitem" data-read-all>${ic("done_all")}<span>Đánh dấu tất cả là đã đọc</span></button>
                        ${pop ? `<a role="menuitem" href="/notifications">${ic("open_in_new")}<span>Mở trang Thông báo</span></a>` : ""}
                    </div>
                </div>
                ${pop ? `<button class="np-icon np-close" type="button" data-close title="Đóng">${ic("close")}<span class="sr-only">Đóng</span></button>` : ""}
            </div>
        </div>
        <div class="np-tabs" role="tablist" aria-label="Lọc thông báo">${TABS.map(([key, label]) =>
            `<button type="button" role="tab" id="${uid}-tab-${key}" data-tab="${key}" aria-controls="${uid}-body">${label}</button>`).join("")}</div>
        <p class="np-status" role="status" hidden></p>
        <div class="np-body" id="${uid}-body" role="tabpanel"></div>`;

    const body = root.querySelector(".np-body");
    const tabs = [...root.querySelectorAll("[role=tab]")];
    const menuButton = root.querySelector("[data-menu-btn]");
    const menu = root.querySelector(".np-menu");
    const status = root.querySelector(".np-status");
    const lists = { all: blank(), unread: blank() };
    let tab = "all";
    let statusTimer = null;

    // ---------------------------------------------------------------- loading
    /** Page `page` of the tab. page 0: `replace` puts it in place of what is shown, else what is new is added on top. */
    async function load(key, page = 0, replace = true) {
        const list = lists[key];
        const token = ++list.token;
        list.loading = true;
        if (key === tab) render();
        try {
            const data = await apiClient.notifications(page, key === "unread");
            if (token !== list.token) return;
            if (page > 0) {
                list.items = mergeFresh(list.items, data.items);
                list.page = page;
                list.hasMore = data.hasMore;
            } else if (replace || !list.loaded) {
                list.items = data.items;
                list.page = 0;
                list.hasMore = data.hasMore;
            } else {
                list.items = mergeFresh(list.items, data.items);
                if (list.page === 0) list.hasMore = data.hasMore;
            }
            list.loaded = true;
            list.error = null;
            if (page === 0 && key === tab) onSeen(newestId(data.items));
        } catch (error) {
            if (token !== list.token) return;
            list.error = error;
            // signed out meanwhile: nothing here works any more, the list gives way to "Đăng nhập lại"
            if (error.status === 401) list.loaded = false;
            else if (list.loaded) say(error.message, true);
        } finally {
            if (token === list.token) {
                list.loading = false;
                if (key === tab) render();
            }
        }
    }

    // ---------------------------------------------------------------- drawing
    function render() {
        const list = lists[tab];
        tabs.forEach((button) => {
            const on = button.dataset.tab === tab;
            button.setAttribute("aria-selected", String(on));
            button.tabIndex = on ? 0 : -1;
        });
        body.setAttribute("aria-labelledby", `${uid}-tab-${tab}`);
        body.setAttribute("aria-busy", String(list.loading));

        // the element with the focus is drawn again: give the focus back to its new copy
        const focused = body.contains(document.activeElement) ? focusKey(document.activeElement) : null;
        body.innerHTML = listHtml(list);
        if (focused) body.querySelector(focused)?.focus({ preventScroll: true });
    }

    function listHtml(list) {
        if (!list.loaded) {
            if (list.error) return errorHtml(list.error);
            return skeletonHtml();
        }
        if (!list.items.length) {
            return tab === "unread"
                ? emptyHtml("done_all", "Bạn đã đọc hết thông báo.", "")
                : emptyHtml("notifications", "Chưa có thông báo nào.",
                    "Kết quả kiểm duyệt bài viết, bình luận mới và câu trả lời hay nhất sẽ hiện ở đây.");
        }
        const groups = groupByDay(list.items);
        // "Xem tất cả" (the page) next to "Trước đó", as on Facebook; on the only group when there is one
        const seeAll = pop ? (groups.find((g) => g.key === "earlier") || groups[0]).key : null;
        const sections = groups.map((g) => `
            <div class="np-sec">
                <div class="np-sec-head"><h3>${esc(g.title)}</h3>
                    ${g.key === seeAll ? `<a class="np-see" href="/notifications">Xem tất cả</a>` : ""}</div>
                <ul class="np-list">${g.items.map(itemHtml).join("")}</ul>
            </div>`).join("");
        const more = list.hasMore
            ? `<button class="np-older" type="button" data-older ${list.loading ? "disabled" : ""}>${list.loading
                ? "Đang tải…" : "Xem thông báo trước đó"}</button>`
            : "";
        return sections + more;
    }

    function itemHtml(n) {
        const unread = !n.read;
        const inner = `${pictureHtml(n)}
            <span class="np-main">
                <span class="np-msg">${messageHtml(n)}</span>
                <time class="np-time" datetime="${esc(n.createdAt)}" title="${esc(fullDate(n.createdAt))}">${esc(shortAgo(n.createdAt))}</time>
            </span>
            <span class="np-dot" aria-hidden="true"></span><span class="sr-only np-sr-unread">${unread ? ", chưa đọc" : ""}</span>`;
        const cls = `np-item${unread ? " unread" : ""}`;
        const id = esc(n.id);
        // what it was about is gone (a deleted post, a removed review): nothing to open, a click only marks it read
        const tag = n.link
            ? `<a class="${cls}" href="${esc(n.link)}" data-id="${id}">${inner}</a>`
            : `<button class="${cls} np-nolink" type="button" data-id="${id}">${inner}</button>`;
        return `<li>${tag}</li>`;
    }

    const skeletonHtml = () => `<ul class="np-list np-skel" aria-label="Đang tải thông báo">${"<li><span class=\"np-sk-pic\"></span><span class=\"np-sk-lines\"><span></span><span></span></span></li>".repeat(4)}</ul>`;

    const emptyHtml = (icon, title, note) => `<div class="np-empty">${ic(icon)}<p><b>${esc(title)}</b></p>${note ? `<p class="note">${esc(note)}</p>` : ""}</div>`;

    function errorHtml(error) {
        if (error.status === 401) {
            return `<div class="np-empty">${ic("lock")}<p><b>Phiên đăng nhập đã hết.</b></p>
                <a class="btn pri sm" href="${esc(loginUrl())}">Đăng nhập lại</a></div>`;
        }
        return `<div class="np-empty">${ic("cloud_off")}<p><b>Chưa tải được thông báo.</b></p><p class="note">${esc(error.message)}</p>
            <button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button></div>`;
    }

    function focusKey(element) {
        const item = element.closest("[data-id]");
        if (item) return `[data-id="${CSS.escape(item.dataset.id)}"]`;
        if (element.closest("[data-older]")) return "[data-older]";
        if (element.closest("[data-retry]")) return "[data-retry]";
        if (element.closest(".np-see")) return ".np-see";
        return null;
    }

    /** A short line under the tabs ("Đã đánh dấu…", an error), read out by screen readers; goes by itself. */
    function say(text, error = false) {
        clearTimeout(statusTimer);
        status.textContent = text;
        status.classList.toggle("err", error);
        status.hidden = !text;
        statusTimer = setTimeout(() => { status.hidden = true; status.textContent = ""; }, STATUS_MS);
    }

    // ---------------------------------------------------------------- reading
    const find = (id) => lists.all.items.find((n) => String(n.id) === id)
        || lists.unread.items.find((n) => String(n.id) === id);

    /** Marks it (un)read in both tabs and on screen, without drawing the list again (the focus stays). */
    function setRead(id, read) {
        [lists.all, lists.unread].forEach((list) => list.items.forEach((n) => {
            if (String(n.id) === String(id)) n.read = read;
        }));
        body.querySelectorAll(`[data-id="${CSS.escape(String(id))}"]`).forEach((item) => {
            item.classList.toggle("unread", !read);
            const sr = item.querySelector(".np-sr-unread");
            if (sr) sr.textContent = read ? "" : ", chưa đọc";
        });
    }

    function markOne(n) {
        setRead(n.id, true);
        return apiClient.readNotification(n.id).catch((error) => {
            if (error.status !== 404) setRead(n.id, false);    // 404: not there any more, nothing to keep unread
        });
    }

    async function markAll() {
        closeMenu();
        const upTo = Math.max(newestId(lists.all.items), newestId(lists.unread.items));
        try {
            await apiClient.readNotifications(upTo || null);
        } catch (error) {
            say(error.message, true);
            return;
        }
        lists.all.items.forEach((n) => {
            if (!upTo || n.id <= upTo) n.read = true;
        });
        lists.unread = blank();
        say("Đã đánh dấu tất cả là đã đọc.");
        if (tab === "unread") load("unread"); else render();
    }

    body.addEventListener("click", async (event) => {
        if (event.target.closest("[data-older]")) {
            load(tab, lists[tab].page + 1);
            return;
        }
        if (event.target.closest("[data-retry]")) {
            load(tab);
            return;
        }
        if (event.target.closest(".np-see")) {
            onNavigate();
            return;
        }
        const item = event.target.closest(".np-item[data-id]");
        const n = item && find(item.dataset.id);
        if (!n) return;
        const link = item.tagName === "A";
        const plain = !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey;
        if (n.read) {
            if (link && plain) onNavigate();
            return;
        }
        const saving = markOne(n);
        if (!link || !plain) return;            // opened in a new tab or window: this page stays as it is
        event.preventDefault();
        await Promise.race([saving, new Promise((resolve) => setTimeout(resolve, NAV_WAIT_MS))]);
        onNavigate();
        location.assign(item.href);
    });
    // middle click (new tab): marked read too
    body.addEventListener("auxclick", (event) => {
        const item = event.button === 1 && event.target.closest("a.np-item[data-id]");
        const n = item && find(item.dataset.id);
        if (n && !n.read) markOne(n);
    });

    // ---------------------------------------------------------------- tabs and menu
    function choose(key, focus = false) {
        if (key !== tab) {
            tab = key;
            render();
        }
        if (focus) tabs.find((b) => b.dataset.tab === key)?.focus();
        load(key);
    }
    tabs.forEach((button) => button.addEventListener("click", () => choose(button.dataset.tab)));
    root.querySelector(".np-tabs").addEventListener("keydown", (event) => {
        const keys = TABS.map(([key]) => key);
        const at = keys.indexOf(tab);
        const next = { ArrowRight: at + 1, ArrowLeft: at - 1, Home: 0, End: keys.length - 1 }[event.key];
        if (next === undefined) return;
        event.preventDefault();
        choose(keys[(next + keys.length) % keys.length], true);
    });

    function closeMenu(focusButton = false) {
        if (menu.hidden) return;
        menu.hidden = true;
        menuButton.setAttribute("aria-expanded", "false");
        if (focusButton) menuButton.focus();
    }
    menuButton.addEventListener("click", () => {
        const opening = menu.hidden;
        menu.hidden = !opening;
        menuButton.setAttribute("aria-expanded", String(opening));
        if (opening) menu.querySelector("[role=menuitem]")?.focus();
    });
    menu.querySelector("[data-read-all]").addEventListener("click", markAll);
    menu.querySelectorAll("a[role=menuitem]").forEach((a) => a.addEventListener("click", () => onNavigate()));
    menu.addEventListener("keydown", (event) => {
        const items = [...menu.querySelectorAll("[role=menuitem]")];
        const at = items.indexOf(document.activeElement);
        if (event.key === "Escape") {
            event.preventDefault();
            event.stopPropagation();             // the menu closes, not the panel
            closeMenu(true);
        } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
            event.preventDefault();
            const step = event.key === "ArrowDown" ? 1 : -1;
            items[(at + step + items.length) % items.length]?.focus();
        }
    });
    root.querySelector(".np-menu-wrap").addEventListener("focusout", (event) => {
        if (event.relatedTarget && !event.currentTarget.contains(event.relatedTarget)) closeMenu();
    });
    document.addEventListener("click", (event) => {
        if (!menu.hidden && !root.querySelector(".np-menu-wrap").contains(event.target)) closeMenu();
    });

    render();
    return {
        el: root,
        titleId: `${uid}-title`,
        open: () => load(tab),
        refresh: () => load(tab, 0, false),
        closeMenu
    };
}

/**
 * The bell's panel: opens under the header on the page the user is on (on phones it fills the screen between the
 * header and the tab bar) and closes with the bell, "Đóng", Escape, a click outside or the keyboard leaving it.
 * @returns {{isOpen, refresh, close}}
 */
export function mountNotifPopover(bell, { onSeen = () => {} } = {}) {
    let list = null;
    let pop = null;
    let open = false;
    const pageOn = bell.classList.contains("on");      // on /notifications the bell stays marked as the page shown

    function build() {
        list = createNotifList({ mode: "pop", onSeen, onNavigate: () => close(false) });
        pop = document.createElement("div");
        pop.className = "notif-pop";
        pop.id = "notif-pop";
        pop.hidden = true;
        pop.tabIndex = -1;
        pop.setAttribute("role", "dialog");
        pop.setAttribute("aria-labelledby", list.titleId);
        pop.append(list.el);
        document.body.append(pop);

        list.el.querySelector("[data-close]")?.addEventListener("click", () => close(true));
        pop.addEventListener("keydown", (event) => {
            if (event.key === "Escape") {
                event.preventDefault();
                close(true);
            }
        });
        pop.addEventListener("focusout", (event) => {
            const to = event.relatedTarget;
            if (to && !pop.contains(to) && !bell.contains(to)) close(false);
        });
        // pointerdown: closes before a click elsewhere does its work, also on parts of a page that stop the click
        // (the pop-ups of site-header.js next to it do not close it)
        document.addEventListener("pointerdown", (event) => {
            const target = event.target;
            if (!open || pop.contains(target) || bell.contains(target)) return;
            if (target instanceof Element && target.closest(".site-notices")) return;
            close(false);
        }, true);
        // a new width may bring or take away the tab bar: measured again once the page is laid out at that width
        new ResizeObserver(() => { if (open) place(); }).observe(document.documentElement);
        window.addEventListener("resize", () => { if (open) requestAnimationFrame(place); });
    }

    /** Below the header; on phones down to the tab bar. */
    function place() {
        const header = bell.closest(".site-top");
        const top = Math.max(0, Math.round(header ? header.getBoundingClientRect().bottom : 60));
        const tabbar = document.querySelector(".tabbar");
        const barTop = tabbar && getComputedStyle(tabbar).display !== "none"
            ? tabbar.getBoundingClientRect().top : window.innerHeight;
        pop.style.setProperty("--np-top", `${top}px`);
        pop.style.setProperty("--np-bottom", `${Math.max(0, Math.round(window.innerHeight - barTop))}px`);
    }

    function show() {
        if (!pop) build();
        place();
        pop.hidden = false;
        open = true;
        bell.setAttribute("aria-expanded", "true");
        bell.classList.add("on");
        pop.focus({ preventScroll: true });
        list.open();
    }

    function close(focusBell = false) {
        if (!open) return;
        open = false;
        pop.hidden = true;
        list.closeMenu();
        bell.setAttribute("aria-expanded", "false");
        bell.classList.toggle("on", pageOn);
        if (focusBell) bell.focus();
    }

    bell.addEventListener("click", (event) => {
        event.preventDefault();
        if (open) close(false); else show();
    });
    return {
        isOpen: () => open,
        refresh: () => { if (open) list.refresh(); },
        close
    };
}
