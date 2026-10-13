import { apiClient } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";

await mountNavbar();

const list = document.querySelector("#scholarship-list");

try {
    const scholarships = await apiClient.getScholarships();
    list.innerHTML = scholarships.map((scholarship) => `
        <article class="list-item">
            <span class="tag">${scholarship.provider}</span>
            <h2>${scholarship.title}</h2>
            <p>${scholarship.description}</p>
            <strong>Deadline: ${scholarship.deadline}</strong>
        </article>
    `).join("");
} catch (error) {
    list.innerHTML = `<p class="error">${error.message}</p>`;
}
