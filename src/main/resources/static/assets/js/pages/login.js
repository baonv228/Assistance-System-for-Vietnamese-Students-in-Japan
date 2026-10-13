import { apiClient } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";

await mountNavbar();

const form = document.querySelector("#login-form");
const result = document.querySelector("#login-result");

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const formData = new FormData(form);
    try {
        const user = await apiClient.login({
            username: formData.get("username"),
            password: formData.get("password")
        });
        result.textContent = `Xin chao ${user.fullName} (${user.username}). Token: ${user.token}`;
        result.className = "form-message success";
    } catch (error) {
        result.textContent = error.message;
        result.className = "form-message error";
    }
});
