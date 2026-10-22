// Sample dashboard for staff roles. Each page sets <body data-role="MODERATOR|ACADEMIC_MANAGER|ADMIN">.
// Access is enforced on the server (SecurityConfig): other roles are redirected to their own home page.
import { DESKS, mountStaffDesk } from "../components/staff-desk.js";
import { esc, ic } from "../lib/ui.js";

const role = document.body.dataset.role;
const desk = DESKS[role];
const shell = await mountStaffDesk(role, "overview");
if (shell) {
    document.title = `${desk.title} – Senpai ơi`;
    shell.main.innerHTML = `
        <div>
            <h2>${esc(desk.title)}</h2>
            <p class="note">Xin chào ${esc(shell.user.displayName)}. ${esc(desk.intro)}</p>
        </div>
        <div class="info">${ic("construction")} Đây là trang mẫu: đăng nhập và phân quyền đã hoạt động, các chức năng có nhãn “Đang phát triển” đang được nhóm xây dựng.</div>
        <div class="feat">${desk.modules.map(([, icon, name, uc, href]) => href
            ? `<a class="card module" href="${href}">${ic(icon, "big")}<h3>${esc(name)}</h3>
                <span class="note">${esc(uc)}</span><span><span class="badge ok">Đã có</span></span></a>`
            : `<div class="card module">${ic(icon, "big")}<h3>${esc(name)}</h3>
                <span class="note">${esc(uc)}</span><span><span class="badge lan">Đang phát triển</span></span></div>`).join("")}
        </div>`;
}
