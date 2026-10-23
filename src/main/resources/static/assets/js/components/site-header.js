// Top navigation (desktop) and bottom tab bar (phone), as in the prototype.
// Usage: const user = await mountSiteHeader("home");  → returns the signed-in user or null.
import { apiClient, currentUser } from "../api/client.js";
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
    ["guide", "Cẩm nang", "menu_book", soon("guide")],
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
