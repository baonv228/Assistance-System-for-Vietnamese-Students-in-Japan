// Sample dashboard for staff roles. Each page sets <body data-role="MODERATOR|ACADEMIC_MANAGER|ADMIN">.
// Access is enforced on the server (SecurityConfig): other roles are redirected to their own home page.
import { currentUser } from "../api/client.js";
import { logout } from "../components/site-header.js";
import { esc, ic, LOGO_SVG } from "../lib/ui.js";

/** Modules from the use case specification; each will become a real page later. */
const DESKS = {
    MODERATOR: {
        title: "Trang Moderator",
        intro: "Kiểm duyệt nội dung cộng đồng, địa điểm, sự kiện và cẩm nang.",
        modules: [
            ["verified", "Duyệt tag định danh", "UC-M01, UC-M02"],
            ["flag", "Báo cáo nội dung", "UC-M03"],
            ["gavel", "Khiếu nại", "UC-M04"],
            ["storefront", "Báo cáo đóng cửa", "UC-M05"],
            ["add_location_alt", "Duyệt ghim mới", "UC-M06"],
            ["celebration", "Sự kiện", "UC-M07"],
            ["menu_book", "Cẩm nang", "UC-M08"],
            ["sell", "Chủ đề blog", "UC-M09"],
            ["location_on", "Địa điểm", "UC-M10 – UC-M13"],
            ["merge", "Gộp trùng lặp", "UC-M14"]
        ]
    },
    ACADEMIC_MANAGER: {
        title: "Trang Người quản lý học thuật",
        intro: "Quản lý mẫu câu tiếng Nhật và kiểm tra câu luyện tập của người dùng.",
        modules: [
            ["translate", "Thư viện mẫu câu", "UC-A01"],
            ["category", "Chủ đề học", "UC-A02"],
            ["report", "Báo cáo mẫu câu", "UC-A03"],
            ["rule", "Báo cáo câu luyện tập", "UC-A04"],
            ["spellcheck", "Duyệt câu luyện tập", "UC-A05"]
        ]
    },
    ADMIN: {
        title: "Trang Quản trị viên",
        intro: "Quản lý tài khoản, vai trò, cấu hình hệ thống và theo dõi hoạt động.",
        modules: [
            ["group", "Người dùng và vai trò", "Tối đa 3 Moderator"],
            ["tune", "Cấu hình hệ thống", "Ngưỡng báo cáo, giới hạn tài khoản mới"],
            ["block", "Từ khóa cấm", "Bộ lọc nội dung tự động"],
            ["history", "Nhật ký hệ thống", "Ai làm gì, lúc nào"],
            ["speed", "Hạn mức dịch vụ", "Google Maps, kiểm duyệt ảnh, lưu trữ"]
        ]
    }
};

const role = document.body.dataset.role;
const desk = DESKS[role];
const user = await currentUser();
if (!user) {
    window.location.replace("/pages/login.html");
} else if (user.role !== role) {
    window.location.replace(user.homePath);
} else {
    render();
}

function render() {
    document.title = `${desk.title} – Senpai ơi`;
    document.body.innerHTML = `
        <header class="site-top">
            <a class="logo" href="/">${LOGO_SVG}Senpai ơi</a>
            <span class="badge br">${esc(user.roleLabel)}</span>
            <span class="grow"></span>
            <div class="site-account">
                <a class="btn sm" href="/">${ic("home")}<span class="who">Trang người dùng</span></a>
                <a class="btn sm" href="/pages/profile.html">${ic("account_circle")}<span class="who">${esc(user.displayName)}</span></a>
                <button class="btn sm" type="button" data-logout title="Đăng xuất">${ic("logout")}<span class="sr-only">Đăng xuất</span></button>
            </div>
        </header>
        <div class="desk">
            <nav class="desk-side" aria-label="Chức năng">
                <span class="side-title">Chức năng</span>
                <a href="#" aria-current="page">${ic("space_dashboard")}Tổng quan</a>
                ${desk.modules.map(([icon, name]) => `<a href="#" data-soon>${ic(icon)}${esc(name)}</a>`).join("")}
            </nav>
            <main class="desk-main">
                <div>
                    <h2>${esc(desk.title)}</h2>
                    <p class="note">Xin chào ${esc(user.displayName)}. ${esc(desk.intro)}</p>
                </div>
                <div class="info">${ic("construction")} Đây là trang mẫu: đăng nhập và phân quyền đã hoạt động, các chức năng bên dưới đang được nhóm xây dựng.</div>
                <div class="feat">${desk.modules.map(([icon, name, uc]) => `
                    <div class="card module">${ic(icon, "big")}<h3>${esc(name)}</h3>
                        <span class="note">${esc(uc)}</span><span><span class="badge lan">Đang phát triển</span></span></div>`).join("")}
                </div>
            </main>
        </div>`;
    document.querySelector("[data-logout]").addEventListener("click", logout);
    document.querySelectorAll("[data-soon]").forEach((a) => a.addEventListener("click", (e) => e.preventDefault()));
}
