import { apiClient, currentUser } from "../api/client.js";
import { hideAlert, markInvalid, setupPasswordToggles, setupSocialButtons, showAlert, withBusy } from "./auth-ui.js";

const form = document.querySelector("#login-form");
const alertBox = document.querySelector("#alert");
const submit = document.querySelector("#submit");

/** Pages that sign in or sign up: never a place to come back to. */
const AUTH_PAGES = ["/login", "/register", "/complete-signup", "/pages/login.html", "/pages/register.html",
    "/pages/complete-signup.html"];

/**
 * Where to go after signing in. A member goes back to the page that sent them here (?next=/qa/7, see loginUrl in
 * ui.js) when it is a page of this site; otherwise, and always for staff, to the home page of the role. Never another
 * site: "//evil.example", "/\evil.example", "https://…", and paths the browser would turn into one (tabs, line
 * breaks) are refused.
 */
function destination(user) {
    const next = new URLSearchParams(window.location.search).get("next");
    if (user.role !== "USER" || !next || !/^\/(?![/\\])/.test(next) || next.includes("://")
        || /[\u0000-\u001F\u007F\s\\]/.test(next)) {
        return user.homePath;
    }
    let url;
    try {
        url = new URL(next, window.location.origin);
    } catch {
        return user.homePath;
    }
    const path = url.pathname.replace(/\/+$/, "").toLowerCase() || "/";
    if (url.origin !== window.location.origin || AUTH_PAGES.includes(path)) {
        return user.homePath;
    }
    return `${url.pathname}${url.search}${url.hash}`;
}

// The submit handler comes before any await: pressing Enter while the checks below are still running (or after one
// failed) must never send the form itself. The form is method="post" too, so a native submit never puts the email
// and password in the address bar.
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
        window.location.replace(destination(user));
    } catch (error) {
        markInvalid(form, error.fields);
        showAlert(alertBox, error.message);
        form.password.value = "";
        form.password.focus();
    }
});

setupPasswordToggles();

const params = new URLSearchParams(window.location.search);
const notices = {
    deleted: ["Tài khoản của bạn đã được xóa.", "success"],
    registered: ["Đăng ký thành công. Hãy đăng nhập bằng email và mật khẩu vừa tạo.", "success"],
    reset: ["Đã đặt lại mật khẩu. Hãy đăng nhập bằng mật khẩu mới.", "success"],
    social: ["Đăng nhập bằng Google hoặc Facebook không thành công. Vui lòng thử lại.", "error"],
    social_no_email: ["Tài khoản mạng xã hội không chia sẻ email. Hãy cho phép chia sẻ email hoặc đăng ký bằng email.", "error"],
    account_locked: ["Tài khoản này đã bị khóa. Vui lòng liên hệ quản trị viên.", "error"]
};
const notice = params.has("registered") ? notices.registered : params.has("reset") ? notices.reset
    : params.has("deleted") ? notices.deleted : notices[params.get("error")];
// "Quên mật khẩu?" takes the email typed so far to the next page (in this tab's storage, never in the address)
document.querySelector("#forgot").addEventListener("click", () => {
    try {
        sessionStorage.setItem("asvsj.forgotEmail", form.email.value.trim());
    } catch {
        // storage blocked: the email is typed again
    }
});
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

// Already signed in → go where a sign-in would go (the page in ?next=, else the home page of this role). If the check
// fails (network, server error) the form still works.
const signedIn = await currentUser().catch(() => null);
if (signedIn) {
    window.location.replace(destination(signedIn));
}

// Google / Facebook sign-in goes through the provider and the server sends the user to the home page of the role (or
// to complete the sign-up); ?next= is not carried through that round trip
await setupSocialButtons(apiClient);
