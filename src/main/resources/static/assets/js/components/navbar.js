export async function mountNavbar() {
    const host = document.querySelector("[data-component='navbar']");
    if (!host) {
        return;
    }
    const response = await fetch("/components/navbar.html");
    host.innerHTML = await response.text();
}
