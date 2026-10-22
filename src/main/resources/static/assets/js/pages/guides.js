import { apiClient } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";

await mountNavbar();

const list = document.querySelector("#guide-list");

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
    const guides = await apiClient.getGuides();
    list.replaceChildren(...guides.map((guide) => {
        const item = el("article", "list-item");
        item.append(el("span", "tag", guide.category), el("h2", null, guide.title), el("p", null, guide.summary));
        return item;
    }));
} catch (error) {
    // The guide is not built yet: the API answers "Tính năng này sắp ra mắt."
    list.replaceChildren(el("p", "error", error.message));
}
