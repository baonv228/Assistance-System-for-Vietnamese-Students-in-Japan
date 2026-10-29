// Service worker of Senpai ơi (scope "/"): shows the Web Push notifications of new chat messages
// (feature/chat/notify). A push carries { title, body, url, tag }, never the message text. Registered by
// assets/js/lib/push.js when a member turns notifications on. Plain script, no imports: it also runs on browsers
// that do not load module service workers.

const FALLBACK_URL = "/messages";

self.addEventListener("install", () => {
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener("push", (event) => {
    event.waitUntil(showPush(event));
});

self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const url = absolute(event.notification.data && event.notification.data.url);
    event.waitUntil(openOrFocus(url));
});

async function showPush(event) {
    let data = {};
    try {
        data = event.data ? event.data.json() : {};
    } catch {
        data = {};
    }
    const url = absolute(data.url);
    // Always shown: Safari (and Chrome's userVisibleOnly) take a push that shows nothing as "silent" and cancel the
    // subscription after a few. The server does not push to a member whose page is reading that conversation
    // (ChatViewers), so this rarely shows twice what is already on screen.
    const tag = typeof data.tag === "string" && data.tag ? data.tag : undefined;
    await self.registration.showNotification(text(data.title, "Senpai ơi"), {
        body: text(data.body, "Bạn có tin nhắn mới."),
        icon: "/assets/img/icon-192.png",               // Android shows no SVG here
        tag,
        // A newer message of the same conversation replaces the notification and still rings
        renotify: Boolean(tag),
        data: { url },
    });
}

/** A tab of this site on that page gets the focus; else any tab of this site goes there; else a new tab opens. */
async function openOrFocus(url) {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const ours = windows.filter((client) => sameOrigin(client.url));
    const exact = ours.find((client) => samePage(client.url, url));
    if (exact) {
        await exact.focus();
        return;
    }
    const tab = ours.find((client) => client.focused) || ours[0];
    if (tab) {
        try {
            const focused = await tab.focus();
            // navigate() only works on a tab this worker controls; otherwise open a new one
            if (await (focused || tab).navigate(url)) {
                return;
            }
        } catch {
            // fall through to a new tab
        }
    }
    await self.clients.openWindow(url);
}

/** Only an address of this site: a path such as "/messages/12"; anything else opens the inbox. */
function absolute(path) {
    if (typeof path !== "string" || !path.startsWith("/") || path.startsWith("//") || path.includes("\\")) {
        return new URL(FALLBACK_URL, self.location.origin).href;
    }
    const url = new URL(path, self.location.origin);
    return url.origin === self.location.origin ? url.href : new URL(FALLBACK_URL, self.location.origin).href;
}

function sameOrigin(address) {
    try {
        return new URL(address).origin === self.location.origin;
    } catch {
        return false;
    }
}

function samePage(address, url) {
    try {
        const a = new URL(address);
        const b = new URL(url);
        return a.origin === b.origin && a.pathname.replace(/\/+$/, "") === b.pathname.replace(/\/+$/, "");
    } catch {
        return false;
    }
}

function text(value, fallback) {
    return typeof value === "string" && value.trim() ? value.slice(0, 200) : fallback;
}
