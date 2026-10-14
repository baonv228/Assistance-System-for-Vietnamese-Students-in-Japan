// Small helpers shared by the login, sign-up and complete-sign-up pages.

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

/** Shows which password rules are met; mirrors the server rules (8–72 characters, letters and digits). */
export function setupPasswordRules(input, list) {
    const rules = {
        length: (v) => v.length >= 8 && v.length <= 72,
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

/** Configures the Google / Facebook buttons depending on which providers the server has enabled. */
export async function setupSocialButtons(apiClient) {
    const buttons = document.querySelectorAll("[data-provider]");
    let providers = { google: false, facebook: false };
    try {
        providers = await apiClient.providers();
    } catch {
        // keep both disabled
    }
    buttons.forEach((button) => {
        const id = button.dataset.provider;
        if (providers[id]) {
            button.addEventListener("click", () => {
                window.location.href = `/oauth2/authorization/${id}`;
            });
        } else {
            button.disabled = true;
            button.title = "Chưa được cấu hình trên máy chủ";
        }
    });
}
