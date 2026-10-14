import { apiClient, currentUser } from "../api/client.js";

export async function mountNavbar() {
    const host = document.querySelector("[data-component='navbar']");
    if (!host) {
        return;
    }
    const response = await fetch("/components/navbar.html");
    host.innerHTML = await response.text();
    await showAccount(host);
}

/** Replaces the "Đăng nhập" link with the user's name and a logout button when signed in. */
async function showAccount(host) {
    const slot = host.querySelector("[data-account]");
    if (!slot) {
        return;
    }
    const user = await currentUser().catch(() => null);
    if (!user) {
        return;
    }
    slot.replaceChildren();
    const name = document.createElement("span");
    name.textContent = user.displayName;
    const logout = document.createElement("button");
    logout.type = "button";
    logout.className = "nav-logout";
    logout.textContent = "Đăng xuất";
    logout.addEventListener("click", async () => {
        await apiClient.logout();
        window.location.replace("/pages/login.html");
    });
    slot.append(name, logout);
}
