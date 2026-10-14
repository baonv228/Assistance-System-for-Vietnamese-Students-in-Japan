import { apiClient } from "../api/client.js";
import { hideAlert, markInvalid, showAlert, withBusy } from "./auth-ui.js";

const form = document.querySelector("#complete-form");
const alertBox = document.querySelector("#alert");
const providerNames = { GOOGLE: "Google", FACEBOOK: "Facebook" };

try {
    const pending = await apiClient.pendingSocial();
    document.querySelector("#subtitle").textContent =
        `Bạn đang đăng ký bằng tài khoản ${providerNames[pending.provider] || pending.provider}.`;
    document.querySelector("#email").value = pending.email;
    form.displayName.value = pending.displayName || "";
    form.hidden = false;
    form.displayName.focus();
} catch (error) {
    showAlert(alertBox, error.message);
}

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    hideAlert(alertBox);
    const body = {
        displayName: form.displayName.value.trim(),
        adultConfirmed: form.adultConfirmed.checked,
        acceptTerms: form.acceptTerms.checked
    };
    const problems = {};
    if (body.displayName.length < 2) problems.displayName = "Tên hiển thị dài từ 2 đến 60 ký tự.";
    if (!body.adultConfirmed) problems.adultConfirmed = "Bạn phải từ đủ 18 tuổi để đăng ký.";
    else if (!body.acceptTerms) problems.acceptTerms = "Bạn cần đồng ý Điều khoản, Chính sách bảo mật và Quy tắc cộng đồng.";
    if (Object.keys(problems).length) {
        markInvalid(form, problems);
        showAlert(alertBox, Object.values(problems)[0]);
        return;
    }
    try {
        await withBusy(document.querySelector("#complete"), "Đang tạo tài khoản…", () => apiClient.completeSocial(body));
        window.location.replace("/");
    } catch (error) {
        markInvalid(form, error.fields);
        showAlert(alertBox, error.message);
    }
});
