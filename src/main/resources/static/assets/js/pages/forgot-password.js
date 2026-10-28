// UC-U05 step 1–3 "Quên mật khẩu": the email goes to the server, which always answers the same message (it never says
// whether the email has an account). The submit handler is attached before any await, like the login page.
import { apiClient } from "../api/client.js";
import { hideAlert, markInvalid, showAlert, withBusy } from "./auth-ui.js";

const form = document.querySelector("#forgot-form");
const alertBox = document.querySelector("#alert");
const submit = document.querySelector("#submit");

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const email = form.email.value.trim();
    if (!email) {
        markInvalid(form, { email: true });
        showAlert(alertBox, "Vui lòng nhập email");
        return;
    }
    try {
        const message = await withBusy(submit, "Đang gửi…", () => apiClient.forgotPassword(email));
        markInvalid(form, {});
        showAlert(alertBox, message, "success");
    } catch (error) {
        markInvalid(form, error.fields);
        showAlert(alertBox, error.message);
    }
});

// From the login page with the email already typed
let typed = null;
try {
    typed = sessionStorage.getItem("asvsj.forgotEmail");
    sessionStorage.removeItem("asvsj.forgotEmail");
} catch {
    // storage blocked
}
if (typed) {
    form.email.value = typed;
}
