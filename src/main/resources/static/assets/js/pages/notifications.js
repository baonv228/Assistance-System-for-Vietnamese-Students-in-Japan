// "Thông báo": the signed-in user's notifications, newest first, 20 a page. Opening the page marks the ones shown as
// read (as in the prototype); unread ones keep their mark on this visit so the user sees which are new.
import { apiClient } from "../api/client.js";
import { mountSiteHeader, setNotificationCount } from "../components/site-header.js";
import { esc, fullDate, ic, loginUrl, timeAgo } from "../lib/ui.js";

/** Icon and colour of each kind of notification. */
const KINDS = {
    CONTENT_PUBLISHED: ["check_circle", "ok"],
    CONTENT_REJECTED: ["block", "crit"],
    NEW_COMMENT: ["chat_bubble", "br"],
    BEST_ANSWER: ["workspace_premium", "lan"]
};

const summary = document.querySelector("#summary");
const list = document.querySelector("#list");
const more = document.querySelector("#more");

let items = [];
let page = 0;

await mountSiteHeader("me");
await load(0);
more.addEventListener("click", () => load(page + 1));
// A new notification while the page is open (the bell heard of it): show it at the top
window.addEventListener("asvsj:notification", () => load(0));

async function load(nextPage) {
    more.disabled = true;
    try {
        const data = await apiClient.notifications(nextPage);
        items = nextPage === 0 ? data.items : items.concat(data.items.filter((n) => !items.some((m) => m.id === n.id)));
        page = nextPage;
        more.hidden = !data.hasMore;
        render(data.unread);
        await markRead();
    } catch (error) {
        if (error.status === 401) {
            location.replace(loginUrl());
            return;
        }
        summary.textContent = "";
        list.innerHTML = `<li class="alert error">${esc(error.message)}</li>`;
    } finally {
        more.disabled = false;
    }
}

function render(unread) {
    list.innerHTML = items.map(itemHtml).join("");
    summary.textContent = !items.length ? "Chưa có thông báo."
        : unread ? `${unread} thông báo chưa đọc.` : "Bạn đã xem hết thông báo.";
}

function itemHtml(n) {
    const [icon, kind] = KINDS[n.type] || ["notifications", ""];
    const body = `
        <span class="notif-ic ${kind}">${ic(icon)}</span>
        <span class="notif-txt">
            <span>${esc(n.content)}</span>
            <time class="note" datetime="${esc(n.createdAt)}" title="${esc(fullDate(n.createdAt))}">${timeAgo(n.createdAt)}</time>
        </span>
        ${n.read ? "" : `<span class="notif-dot" title="Chưa đọc"><span class="sr-only">Chưa đọc</span></span>`}`;
    const cls = `notif${n.read ? "" : " unread"}`;
    return `<li>${n.link ? `<a class="${cls}" href="${esc(n.link)}">${body}</a>` : `<div class="${cls}">${body}</div>`}</li>`;
}

/** The ones shown are read now; the bell shows what is left (notifications that came after the page was loaded). */
async function markRead() {
    const newest = items.reduce((max, n) => Math.max(max, n.id), 0);
    if (!items.some((n) => !n.read)) {
        return;
    }
    try {
        const left = await apiClient.readNotifications(newest);
        setNotificationCount(left.unread);
    } catch {
        // stays unread; marked on the next visit
    }
}
