// Shell of the staff pages (Moderator, Academic Manager, Admin sample dashboards): top bar plus the side menu of the
// role's modules. A module with an `href` is built and links to its page; the others are marked "Đang phát triển".
// Usage: const user = await mountStaffDesk("MODERATOR", "events");  → fills <body>, returns the user and the main box.
import { currentUser } from "../api/client.js";
import { logout } from "./site-header.js";
import { esc, ic, LOGO_SVG } from "../lib/ui.js";

/** Modules from the use case specification: [key, icon, name, use cases, href of the built page]. */
export const DESKS = {
    MODERATOR: {
        title: "Trang Moderator",
        home: "/moderator",
        intro: "Kiểm duyệt nội dung cộng đồng, địa điểm, sự kiện và cẩm nang.",
        modules: [
            ["tags", "verified", "Duyệt tag định danh", "UC-M01, UC-M02"],
            ["reports", "flag", "Báo cáo nội dung", "UC-M03"],
            ["appeals", "gavel", "Khiếu nại", "UC-M04"],
            ["closed", "storefront", "Báo cáo đóng cửa", "UC-M05"],
            ["pins", "add_location_alt", "Duyệt ghim mới", "UC-M06"],
            ["events", "celebration", "Sự kiện", "UC-M07", "/moderator/events"],
            ["guide", "menu_book", "Cẩm nang", "UC-M08"],
            ["topics", "sell", "Chủ đề blog", "UC-M09"],
            ["places", "location_on", "Địa điểm", "UC-M10 – UC-M13"],
            ["merge", "merge", "Gộp trùng lặp", "UC-M14"]
        ]
    },
    ACADEMIC_MANAGER: {
        title: "Trang Người quản lý học thuật",
        home: "/academic",
        intro: "Quản lý mẫu câu tiếng Nhật và kiểm tra câu luyện tập của người dùng.",
        modules: [
            ["phrases", "translate", "Thư viện mẫu câu", "UC-A01"],
            ["learn-topics", "category", "Chủ đề học", "UC-A02"],
            ["phrase-reports", "report", "Báo cáo mẫu câu", "UC-A03"],
            ["practice-reports", "rule", "Báo cáo câu luyện tập", "UC-A04"],
            ["practice", "spellcheck", "Duyệt câu luyện tập", "UC-A05"]
        ]
    },
    ADMIN: {
        title: "Trang Quản trị viên",
        home: "/admin",
        intro: "Quản lý tài khoản, vai trò, cấu hình hệ thống và theo dõi hoạt động.",
        modules: [
            ["users", "group", "Người dùng và vai trò", "Tối đa 3 Moderator"],
            ["config", "tune", "Cấu hình hệ thống", "Ngưỡng báo cáo, giới hạn tài khoản mới"],
            ["keywords", "block", "Từ khóa cấm", "Bộ lọc nội dung tự động"],
            ["logs", "history", "Nhật ký hệ thống", "Ai làm gì, lúc nào"],
            ["quota", "speed", "Hạn mức dịch vụ", "Bản đồ OpenStreetMap, kiểm duyệt ảnh, lưu trữ"]
        ]
    }
};

/**
 * Checks the role (the server already did; this sends a wrong role to its own home), draws the shell and returns
 * { user, main } where main is the element for the page's content; null when the page is being left.
 *
 * @param active the module key of this page, or "overview"
 */
export async function mountStaffDesk(role, active = "overview") {
    const desk = DESKS[role];
    const user = await currentUser();
    if (!user) {
        window.location.replace("/login");
        return null;
    }
    if (user.role !== role) {
        window.location.replace(user.homePath);
        return null;
    }
    const link = ([key, icon, name, , href]) => href
        ? `<a href="${href}" ${key === active ? 'aria-current="page"' : ""}>${ic(icon)}${esc(name)}</a>`
        : `<a href="#" data-soon title="Đang phát triển">${ic(icon)}${esc(name)}</a>`;
    document.body.innerHTML = `
        <header class="site-top">
            <a class="logo" href="/">${LOGO_SVG}Senpai ơi</a>
            <span class="badge br">${esc(user.roleLabel)}</span>
            <span class="grow"></span>
            <div class="site-account">
                <a class="btn sm" href="/">${ic("home")}<span class="who">Trang người dùng</span></a>
                <a class="btn sm" href="/profile">${ic("account_circle")}<span class="who">${esc(user.displayName)}</span></a>
                <button class="btn sm" type="button" data-logout title="Đăng xuất">${ic("logout")}<span class="sr-only">Đăng xuất</span></button>
            </div>
        </header>
        <div class="desk">
            <nav class="desk-side" aria-label="Chức năng">
                <span class="side-title">Chức năng</span>
                <a href="${desk.home}" ${active === "overview" ? 'aria-current="page"' : ""}>${ic("space_dashboard")}Tổng quan</a>
                ${desk.modules.map(link).join("")}
            </nav>
            <main class="desk-main" id="desk-main"></main>
        </div>
        <div class="toast" id="toast" role="status" aria-live="polite" hidden></div>`;
    document.querySelector("[data-logout]").addEventListener("click", logout);
    document.querySelectorAll("[data-soon]").forEach((a) => a.addEventListener("click", (e) => e.preventDefault()));
    return { user, main: document.querySelector("#desk-main") };
}
