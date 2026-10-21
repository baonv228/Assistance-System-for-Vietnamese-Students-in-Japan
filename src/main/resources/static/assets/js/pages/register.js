import { apiClient, currentUser } from "../api/client.js";
import {
    hideAlert, markInvalid, setupPasswordRules, setupPasswordToggles, setupSocialButtons, showAlert, withBusy
} from "./auth-ui.js";

const alertBox = document.querySelector("#alert");
const emailForm = document.querySelector("#email-form");
const codeForm = document.querySelector("#code-form");
const passwordForm = document.querySelector("#password-form");
const resendButton = document.querySelector("#resend");

// passwordMinLength: Admin setting password_min_length, sent with the verified code (8 until then)
const state = { email: "", ticket: "", resendTimer: null, passwordMinLength: 8 };

// Everything the forms need is set up before any await (the checks are at the end of this file): pressing Enter
// early must never send a form itself. The forms are method="post" too, so a native submit never puts the
// email, code or password in the address bar.
setupPasswordToggles();
const passwordOk = setupPasswordRules(passwordForm.password, document.querySelector("#password-rules"),
    () => state.passwordMinLength);

function goTo(step) {
    document.querySelectorAll(".step").forEach((section) => {
        section.hidden = section.dataset.step !== String(step);
    });
    document.querySelectorAll("[data-step-label]").forEach((item) => {
        const n = Number(item.dataset.stepLabel);
        item.classList.toggle("done", n < step);
        item.classList.toggle("current", n === step);
        item.toggleAttribute("aria-current", n === step);
    });
    const firstInput = document.querySelector(`.step[data-step="${step}"] input`);
    firstInput?.focus();
}

function startResendCountdown(seconds) {
    clearInterval(state.resendTimer);
    let left = seconds;
    resendButton.disabled = true;
    resendButton.textContent = `Gửi lại sau ${left}s`;
    state.resendTimer = setInterval(() => {
        left -= 1;
        if (left <= 0) {
            clearInterval(state.resendTimer);
            resendButton.disabled = false;
            resendButton.textContent = "Gửi lại mã";
        } else {
            resendButton.textContent = `Gửi lại sau ${left}s`;
        }
    }, 1000);
}

async function sendCode(button) {
    const result = await withBusy(button, "Đang gửi…", () => apiClient.sendRegisterCode(state.email));
    state.email = result.email;
    document.querySelector("#email-shown").textContent = result.email;
    document.querySelector("#code-hint").textContent =
        `Mã có hiệu lực trong ${result.expiresInMinutes} phút. Nhớ kiểm tra cả hộp thư Spam.`;
    startResendCountdown(result.resendAfterSeconds);
    showAlert(alertBox, `Đã gửi mã xác thực tới ${result.email}.`, "success");
}

// Step 1 → 2
emailForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    state.email = emailForm.email.value.trim();
    if (!emailForm.email.checkValidity() || !state.email) {
        markInvalid(emailForm, { email: true });
        showAlert(alertBox, "Vui lòng nhập email hợp lệ.");
        return;
    }
    try {
        await sendCode(document.querySelector("#send-code"));
        markInvalid(emailForm);
        codeForm.code.value = "";
        goTo(2);
    } catch (error) {
        markInvalid(emailForm, error.fields?.email ? error.fields : { email: true });
        showAlert(alertBox, error.message);
    }
});

resendButton.addEventListener("click", async () => {
    hideAlert(alertBox);
    try {
        await sendCode(resendButton);
        codeForm.code.value = "";
        codeForm.code.focus();
    } catch (error) {
        showAlert(alertBox, error.message);
        resendButton.disabled = false;
    }
});

document.querySelector("#change-email").addEventListener("click", () => {
    hideAlert(alertBox);
    clearInterval(state.resendTimer);
    goTo(1);
});

// Only digits in the code box; submit automatically when 6 digits are typed or pasted
codeForm.code.addEventListener("input", () => {
    codeForm.code.value = codeForm.code.value.replace(/\D/g, "").slice(0, 6);
    if (codeForm.code.value.length === 6) {
        codeForm.requestSubmit();
    }
});

// Step 2 → 3
codeForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const code = codeForm.code.value;
    if (!/^\d{6}$/.test(code)) {
        markInvalid(codeForm, { code: true });
        showAlert(alertBox, "Mã xác thực gồm 6 chữ số.");
        return;
    }
    try {
        const result = await withBusy(document.querySelector("#verify"), "Đang kiểm tra…",
            () => apiClient.verifyRegisterCode(state.email, code));
        state.ticket = result.ticket;
        if (Number.isInteger(result.passwordMinLength) && result.passwordMinLength > 0) {
            state.passwordMinLength = result.passwordMinLength;
        }
        document.querySelector('#password-rules [data-rule="length"]').textContent =
            `Từ ${state.passwordMinLength} ký tự`;
        passwordForm.password.dispatchEvent(new Event("input"));
        clearInterval(state.resendTimer);
        markInvalid(codeForm);
        showAlert(alertBox, "Email đã được xác thực. Hãy tạo mật khẩu.", "success");
        goTo(3);
    } catch (error) {
        markInvalid(codeForm, { code: true });
        showAlert(alertBox, error.message);
        codeForm.code.select();
    }
});

// Step 3 → back to login
passwordForm.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const body = {
        ticket: state.ticket,
        displayName: passwordForm.displayName.value.trim(),
        password: passwordForm.password.value,
        adultConfirmed: passwordForm.adultConfirmed.checked,
        acceptTerms: passwordForm.acceptTerms.checked
    };
    const problems = {};
    if (body.displayName.length < 2) problems.displayName = "Tên hiển thị dài từ 2 đến 60 ký tự.";
    if (!passwordOk()) {
        problems.password = `Mật khẩu chưa đạt yêu cầu: từ ${state.passwordMinLength} ký tự, có cả chữ và số.`;
    } else if (passwordForm.confirm.value !== body.password) {
        problems.confirm = "Mật khẩu nhập lại không khớp.";
    }
    if (!body.adultConfirmed) problems.adultConfirmed = "Bạn phải từ đủ 18 tuổi để đăng ký.";
    else if (!body.acceptTerms) problems.acceptTerms = "Bạn cần đồng ý Điều khoản, Chính sách bảo mật và Quy tắc cộng đồng.";
    if (Object.keys(problems).length) {
        markInvalid(passwordForm, problems);
        showAlert(alertBox, Object.values(problems)[0]);
        return;
    }
    try {
        await withBusy(document.querySelector("#complete"), "Đang tạo tài khoản…",
            () => apiClient.completeRegistration(body));
        // Pass the email to the login page without putting it in the URL
        try {
            sessionStorage.setItem("asvsj.registeredEmail", state.email);
        } catch {
            // storage blocked: the user just types the email again
        }
        window.location.replace("/login?registered=1");
    } catch (error) {
        markInvalid(passwordForm, error.fields);
        showAlert(alertBox, error.message);
        if (error.status === 400 && /hết hạn/.test(error.message)) {
            goTo(1);
        }
    }
});

// Already signed in → go to the home page of this role. If the check fails (network, server error) the forms still work.
const signedIn = await currentUser().catch(() => null);
if (signedIn) {
    window.location.replace(signedIn.homePath);
}

await setupSocialButtons(apiClient);
