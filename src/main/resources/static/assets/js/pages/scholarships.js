import { apiClient } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";

await mountNavbar();

const list = document.querySelector("#scholarship-list");

/** An element whose text is set with textContent: API data never goes in as HTML. */
function el(tag, className, text) {
    const node = document.createElement(tag);
    if (className) {
        node.className = className;
    }
    if (text !== undefined) {
        node.textContent = text ?? "";
    }
    return node;
}

try {
    const scholarships = await apiClient.getScholarships();
    list.replaceChildren(...scholarships.map((scholarship) => {
        const item = el("article", "list-item");
        item.append(el("span", "tag", scholarship.provider), el("h2", null, scholarship.title),
            el("p", null, scholarship.description), el("strong", null, `Deadline: ${scholarship.deadline ?? ""}`));
        return item;
    }));
} catch (error) {
    // Scholarships are not built: the API answers "Tính năng này sắp ra mắt."
    list.replaceChildren(el("p", "error", error.message));
}
