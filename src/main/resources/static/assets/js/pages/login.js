import { apiClient, currentUser } from "../api/client.js";
import { hideAlert, markInvalid, setupPasswordToggles, setupSocialButtons, showAlert, withBusy } from "./auth-ui.js";

const form = document.querySelector("#login-form");
const alertBox = document.querySelector("#alert");
const submit = document.querySelector("#submit");

// Already signed in → go to the home page of this role
const signedIn = await currentUser();
if (signedIn) {
    window.location.replace(signedIn.homePath);
}

setupPasswordToggles();
await setupSocialButtons(apiClient);

const params = new URLSearchParams(window.location.search);
const notices = {
    deleted: ["Tài khoản của bạn đã được xóa.", "success"],
    registered: ["Đăng ký thành công. Hãy đăng nhập bằng email và mật khẩu vừa tạo.", "success"],
    social: ["Đăng nhập bằng Google hoặc Facebook không thành công. Vui lòng thử lại.", "error"],
    social_no_email: ["Tài khoản mạng xã hội không chia sẻ email. Hãy cho phép chia sẻ email hoặc đăng ký bằng email.", "error"],
    account_locked: ["Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.", "error"]
};
const notice = params.has("registered") ? notices.registered
    : params.has("deleted") ? notices.deleted : notices[params.get("error")];
if (notice) {
    showAlert(alertBox, notice[0], notice[1]);
}
let registeredEmail = null;
try {
    registeredEmail = sessionStorage.getItem("asvsj.registeredEmail");
    sessionStorage.removeItem("asvsj.registeredEmail");
} catch {
    // storage blocked
}
if (registeredEmail) {
    form.email.value = registeredEmail;
    form.password.focus();
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const email = form.email.value.trim();
    const password = form.password.value;
    if (!email || !password) {
        markInvalid(form, { email: !email, password: !password });
        showAlert(alertBox, "Vui lòng nhập email và mật khẩu.");
        return;
    }
    try {
        const user = await withBusy(submit, "Đang đăng nhập…", () => apiClient.login({ email, password }));
        window.location.replace(user.homePath);
    } catch (error) {
        markInvalid(form, error.fields);
        showAlert(alertBox, error.message);
        form.password.value = "";
        form.password.focus();
    }
});
