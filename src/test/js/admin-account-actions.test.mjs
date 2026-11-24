import { test } from "node:test";
import assert from "node:assert/strict";
import { installModalDocument } from "./helpers/admin-dom.mjs";
import { adminClient } from "../../main/resources/static/assets/js/api/admin-client.js";
import { runRoleChange, runStatusFlow } from "../../main/resources/static/assets/js/lib/admin-account-actions.js";

const user = { id: 42, displayName: "Người dùng", role: "USER", status: "ACTIVE" };
const settle = () => new Promise((resolve) => setImmediate(resolve));
const failure = (status) => Object.assign(new Error(`Tài khoản đã thay đổi (${status}).`), { status });

// Both account pages replace the message element when fresh account data is rendered.
function accountPage() {
    const events = [];
    let message = null;
    return {
        events,
        get message() {
            return message;
        },
        callbacks: {
            async onChanged() {
                events.push("refresh:start");
                await settle();
                message = null;
                events.push("refresh:rendered");
            },
            onMessage(text, isError) {
                message = { text, isError };
                events.push("message");
            }
        }
    };
}

for (const status of [404, 409]) {
    test(`role change ${status} keeps its error visible after the account refresh`, async (t) => {
        const error = failure(status);
        const update = t.mock.method(adminClient, "updateUserRole", async (...args) => {
            assert.deepEqual(args, [user.id, "MODERATOR", user.role]);
            throw error;
        });
        const page = accountPage();

        assert.equal(await runRoleChange(user, "MODERATOR", page.callbacks), false);

        assert.equal(update.mock.callCount(), 1);
        assert.deepEqual(page.events, ["refresh:start", "refresh:rendered", "message"]);
        assert.deepEqual(page.message, { text: error.message, isError: true });
    });
}

test("missing account at status precheck refreshes before displaying the error and opens no modal", async (t) => {
    installModalDocument(t, { lockReason: "  Vi phạm quy tắc  " });
    const error = failure(404);
    const check = t.mock.method(adminClient, "checkStatusChange", async (...args) => {
        assert.deepEqual(args, [user.id, "LOCKED"]);
        throw error;
    });
    const update = t.mock.method(adminClient, "updateUserStatus", async () => {
        assert.fail("a failed precheck must not submit a status mutation");
    });
    const page = accountPage();

    await runStatusFlow(user, page.callbacks);

    assert.equal(check.mock.callCount(), 1);
    assert.equal(update.mock.callCount(), 0);
    assert.equal(document.getElementById("activeAdminModal"), null);
    assert.deepEqual(page.events, ["refresh:start", "refresh:rendered", "message"]);
    assert.deepEqual(page.message, { text: error.message, isError: true });
});

for (const status of [404, 409]) {
    test(`confirmed status change ${status} closes the modal and retains the error after refresh`, async (t) => {
        installModalDocument(t, { lockReason: "  Vi phạm quy tắc  " });
        const error = failure(status);
        t.mock.method(adminClient, "checkStatusChange", async () => ({}));
        const update = t.mock.method(adminClient, "updateUserStatus", async (...args) => {
            assert.deepEqual(args, [user.id, "LOCKED", "Vi phạm quy tắc", user.status]);
            throw error;
        });
        const page = accountPage();

        await runStatusFlow(user, page.callbacks);
        const modal = document.getElementById("activeAdminModal");
        assert.ok(modal);
        assert.equal(update.mock.callCount(), 0, "opening the dialog does not change the account");
        modal.querySelector("#modalConfirmBtn").click();
        await settle();
        await settle();

        assert.equal(update.mock.callCount(), 1);
        assert.equal(document.getElementById("activeAdminModal"), null);
        assert.deepEqual(page.events, ["refresh:start", "refresh:rendered", "message"]);
        assert.deepEqual(page.message, { text: error.message, isError: true });
    });
}
