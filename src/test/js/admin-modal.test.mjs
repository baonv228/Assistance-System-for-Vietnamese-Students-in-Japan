import { test } from "node:test";
import assert from "node:assert/strict";
import { installModalDocument } from "./helpers/admin-dom.mjs";
import { showModal } from "../../main/resources/static/assets/js/components/admin-layout.js";

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("Admin modal submits once while pending and cannot be dismissed before it finishes", async (t) => {
    installModalDocument(t);
    let resolveWrite;
    const write = new Promise((resolve) => {
        resolveWrite = resolve;
    });
    let writes = 0;
    showModal({
        title: "Save",
        bodyHtml: "",
        onConfirm: () => {
            writes++;
            return write;
        }
    });
    const modal = document.getElementById("activeAdminModal");
    const confirm = modal.querySelector("#modalConfirmBtn");
    const cancel = modal.querySelector("#modalCancelBtn");
    const close = modal.querySelector("#modalCloseBtn");

    // Dispatch deliberately bypasses native disabled-button handling, as repeated submit/click events can.
    confirm.click();
    confirm.click();
    cancel.click();
    close.click();
    modal.click();
    assert.equal(writes, 1);
    assert.equal(document.getElementById("activeAdminModal"), modal);
    assert.ok([confirm, cancel, close].every((button) => button.hasAttribute("disabled")));

    resolveWrite(false);
    await settle();
    assert.equal(document.getElementById("activeAdminModal"), null);
});

test("Admin modal restores controls after a failed write and allows one retry", async (t) => {
    installModalDocument(t);
    let rejectWrite;
    const write = new Promise((_, reject) => {
        rejectWrite = reject;
    });
    let writes = 0;
    showModal({ title: "Save", bodyHtml: "", onConfirm: () => (++writes === 1 ? write : false) });
    const modal = document.getElementById("activeAdminModal");
    const confirm = modal.querySelector("#modalConfirmBtn");
    confirm.click();
    rejectWrite(new Error("Server unavailable"));
    await settle();

    assert.equal(document.getElementById("activeAdminModal"), modal);
    assert.ok(
        [confirm, modal.querySelector("#modalCancelBtn"), modal.querySelector("#modalCloseBtn")].every(
            (button) => !button.hasAttribute("disabled")
        )
    );
    confirm.click();
    await settle();
    assert.equal(writes, 2);
    assert.equal(document.getElementById("activeAdminModal"), null);
});
