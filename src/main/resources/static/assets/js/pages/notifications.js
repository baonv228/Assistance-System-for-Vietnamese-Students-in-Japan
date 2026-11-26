// "Thông báo" (/notifications, "Xem tất cả" of the bell's panel): the signed-in user's notifications in the same list
// as the panel (components/notif-panel.js): tabs "Tất cả" / "Chưa đọc", "Hôm nay" / "Trước đó", 20 at a time. Opening
// it clears the bell's number; each notification stays unread until it is clicked.
import { createNotifList } from "../components/notif-panel.js";
import { markNotificationsSeen, mountSiteHeader } from "../components/site-header.js";
import { loginUrl } from "../lib/ui.js";

const user = await mountSiteHeader("notifications");
if (!user) {
    location.replace(loginUrl());
} else {
    const list = createNotifList({ mode: "page", onSeen: markNotificationsSeen });
    document.querySelector("#notifications").append(list.el);
    list.open();
    // A new notification while the page is open (the bell heard of it): it comes in at the top
    window.addEventListener("asvsj:notification", () => list.refresh());
}
