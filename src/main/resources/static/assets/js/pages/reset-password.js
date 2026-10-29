// UC-U05 steps 5–9 "Đặt lại mật khẩu": the link from the email (/reset-password?token=…). The token is taken out of
// the address bar at once (it stays only in this page's memory), checked, then the new password is sent with it.
import { apiClient } from "../api/client.js";
import { hideAlert, markInvalid, setupPasswordRules, setupPasswordToggles, showAlert, withBusy } from "./auth-ui.js";

const form = document.querySelector("#reset-form");
const alertBox = document.querySelector("#alert");
const submit = document.querySelector("#submit");
const subtitle = document.querySelector("#subtitle");

const token = new URLSearchParams(location.search).get("token") || "";
history.replaceState(null, "", location.pathname);

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const password = form.password.value;
    const confirmPassword = form.confirmPassword.value;
    if (!passwordOk() || password !== confirmPassword) {
        markInvalid(form, { password: !passwordOk(), confirmPassword: password !== confirmPassword });
        showAlert(alertBox, !passwordOk() ? "Mật khẩu chưa đủ các điều kiện bên dưới." : "Hai mật khẩu không khớp.");
        return;
    }
    try {
        await withBusy(submit, "Đang lưu…", () => apiClient.resetPassword(token, password, confirmPassword));
        window.location.replace("/login?reset=1");
    } catch (error) {
        markInvalid(form, error.fields);
        showAlert(alertBox, error.message);
        if (error.status === 400 && !Object.keys(error.fields || {}).length) {
            showInvalidLink(error.message);           // used or expired meanwhile
        }
    }
});

setupPasswordToggles();
const passwordOk = setupPasswordRules(form.password, document.querySelector("#password-rules"));

function showInvalidLink(message) {
    form.hidden = true;
    subtitle.textContent = "Liên kết không dùng được nữa.";
    showAlert(alertBox, message);
    document.querySelector("#again").hidden = false;
}

try {
    const { email } = await apiClient.checkPasswordLink(token);
    subtitle.textContent = `Đặt mật khẩu mới cho tài khoản ${email}.`;
    form.hidden = false;
    form.password.focus();
} catch (error) {
    showInvalidLink(error.message);
}
