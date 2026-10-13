import { apiClient } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";

await mountNavbar();

const list = document.querySelector("#guide-list");

try {
    const guides = await apiClient.getGuides();
    list.innerHTML = guides.map((guide) => `
        <article class="list-item">
            <span class="tag">${guide.category}</span>
            <h2>${guide.title}</h2>
            <p>${guide.summary}</p>
        </article>
    `).join("");
} catch (error) {
    list.innerHTML = `<p class="error">${error.message}</p>`;
}
