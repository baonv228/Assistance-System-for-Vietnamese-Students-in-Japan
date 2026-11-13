// Small helpers shared by the login, sign-up and complete-sign-up pages.

import { android, googleSignInBlocked } from "../lib/device.js";

export function showAlert(element, message, type = "error") {
    element.textContent = message;
    element.className = `alert ${type} show`;
    element.setAttribute("role", type === "error" ? "alert" : "status");
}

export function hideAlert(element) {
    element.className = "alert";
    element.textContent = "";
}

/** Marks inputs named in `fields` (from ApiError.fields) as invalid and clears the others. */
export function markInvalid(form, fields = {}) {
    form.querySelectorAll("input").forEach((input) => {
        if (fields[input.name]) {
            input.setAttribute("aria-invalid", "true");
        } else {
            input.removeAttribute("aria-invalid");
        }
    });
}

/** Disables a button while the promise runs and swaps its label. */
export async function withBusy(button, busyLabel, action) {
    const label = button.textContent;
    button.disabled = true;
    button.textContent = busyLabel;
    try {
        return await action();
    } finally {
        button.disabled = false;
        button.textContent = label;
    }
}

/** "Hiện" / "Ẩn" buttons next to password inputs. */
export function setupPasswordToggles(root = document) {
    root.querySelectorAll("[data-toggle-password]").forEach((button) => {
        const input = root.querySelector(`#${button.dataset.togglePassword}`);
        button.addEventListener("click", () => {
            const show = input.type === "password";
            input.type = show ? "text" : "password";
            button.textContent = show ? "Ẩn" : "Hiện";
            button.setAttribute("aria-pressed", String(show));
        });
    });
}

/**
 * Shows which password rules are met; mirrors the server rules (minLength()–72 characters, letters and digits).
 * minLength is a function: the minimum is an Admin setting the page learns when the code is verified.
 */
export function setupPasswordRules(input, list, minLength = () => 8) {
    const rules = {
        length: (v) => v.length >= minLength() && v.length <= 72,
        letter: (v) => /\p{L}/u.test(v),
        digit: (v) => /\d/.test(v)
    };
    const update = () => {
        list.querySelectorAll("[data-rule]").forEach((item) => {
            item.classList.toggle("ok", rules[item.dataset.rule](input.value));
        });
    };
    input.addEventListener("input", update);
    update();
    return () => Object.values(rules).every((rule) => rule(input.value));
}

/**
 * Configures the Google / Facebook buttons depending on which providers the server has enabled. Inside an app's own
 * browser (Zalo, Facebook, Messenger…, opened from a shared link) Google refuses every sign-in with its English page
 * "403: disallowed_useragent", so there the Google button points to a note on how to open the page in Chrome or Safari.
 */
export async function setupSocialButtons(apiClient) {
    const buttons = document.querySelectorAll("[data-provider]");
    let providers = { google: false, facebook: false };
    try {
        providers = await apiClient.providers();
    } catch {
        // keep both disabled
    }
    let note = null;
    buttons.forEach((button) => {
        const id = button.dataset.provider;
        if (!providers[id]) {
            button.disabled = true;
            button.title = "Chưa được cấu hình trên máy chủ";
            return;
        }
        if (id === "google" && googleSignInBlocked()) {
            note = note || inAppNote(button.closest(".social-row"));
            button.addEventListener("click", () => {
                note.classList.remove("flash");
                void note.offsetWidth;   // restart the animation on every press
                note.classList.add("flash");
                note.scrollIntoView({ block: "nearest", behavior: "smooth" });
                note.focus({ preventScroll: true });
            });
            return;
        }
        button.addEventListener("click", () => {
            window.location.href = `/oauth2/authorization/${id}`;
        });
    });
}

/** The note under the social buttons in an in-app browser: why Google does not work here and how to leave. */
function inAppNote(row) {
    const link = `${window.location.origin}${window.location.pathname}`;
    const note = document.createElement("div");
    note.className = "alert info show in-app-note";
    note.id = "in-app-note";
    note.tabIndex = -1;
    note.setAttribute("role", "note");

    const text = document.createElement("p");
    text.textContent = "Google không cho đăng nhập trong trình duyệt của Zalo, Facebook, Messenger… Hãy mở trang này "
        + "bằng Chrome hoặc Safari (bấm ⋯ hoặc biểu tượng chia sẻ rồi chọn “Mở bằng trình duyệt”), hoặc đăng nhập "
        + "bằng email.";
    const address = document.createElement("span");
    address.className = "note-link";
    address.textContent = link;

    const actions = document.createElement("div");
    actions.className = "note-actions";
    if (android()) {
        // Android hands an intent:// link to the app named in it: the same page opens in Chrome
        const chrome = document.createElement("a");
        chrome.className = "btn btn-social";
        chrome.href = `intent://${window.location.host}${window.location.pathname}${window.location.search}`
            + "#Intent;scheme=https;package=com.android.chrome;end";
        chrome.textContent = "Mở bằng Chrome";
        actions.append(chrome);
    }
    const copy = document.createElement("button");
    copy.type = "button";
    copy.className = "btn btn-social";
    copy.textContent = "Sao chép liên kết";
    copy.addEventListener("click", async () => {
        try {
            await navigator.clipboard.writeText(link);
            copy.textContent = "Đã sao chép liên kết";
        } catch {
            // some app browsers block the clipboard: select the address so a long press copies it
            const range = document.createRange();
            range.selectNodeContents(address);
            window.getSelection()?.removeAllRanges();
            window.getSelection()?.addRange(range);
            copy.textContent = "Giữ ngón tay lên liên kết để sao chép";
        }
    });
    actions.append(copy);

    note.append(text, address, actions);
    row.after(note);
    return note;
}
