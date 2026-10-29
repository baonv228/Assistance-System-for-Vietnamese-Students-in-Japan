// Tests of the live update stream of the pages (assets/js/lib/live.js) with a fake EventSource, document and window:
// one stream while the tab is in front and none while it is hidden, events handed to the pages, catching up after a
// reconnect, a new try after the browser gave up, nothing after "Đăng xuất". Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";

const MODULE = new URL("../../main/resources/static/assets/js/lib/live.js", import.meta.url).href;
let fresh = 0;

/** A browser around a fresh copy of live.js (the module keeps its state, so every test loads its own). */
async function browser() {
    const sources = [];
    const listeners = { document: {}, window: {} };
    const on = (where) => (type, fn) => { (listeners[where][type] ??= []).push(fn); };
    const timers = [];
    class FakeEventSource {
        constructor(url) {
            this.url = url;
            this.readyState = 0;
            this.handlers = {};
            this.closed = false;
            sources.push(this);
        }
        addEventListener(type, fn) { (this.handlers[type] ??= []).push(fn); }
        close() { this.closed = true; this.readyState = 2; }
        fire(type, data) { (this.handlers[type] ?? []).forEach((fn) => fn({ data })); }
    }
    globalThis.EventSource = FakeEventSource;
    globalThis.document = { hidden: false, addEventListener: on("document") };
    globalThis.window = { addEventListener: on("window") };
    const live = await import(`${MODULE}?t=${++fresh}`);
    const fireOn = (where, type) => (listeners[where][type] ?? []).forEach((fn) => fn());
    return { live, sources, timers, fireOn };
}

/** The module's timers are recorded, not run, while `body` runs. */
async function withFakeTimers(timers, body) {
    const real = [globalThis.setTimeout, globalThis.clearTimeout];
    globalThis.setTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
    globalThis.clearTimeout = () => {};
    try {
        await body();
    } finally {
        [globalThis.setTimeout, globalThis.clearTimeout] = real;
    }
}

test("one stream on /api/me/live while in front; events reach the pages, the stream opening says catch up", async () => {
    const { live, sources } = await browser();
    const chats = [];
    let opens = 0;
    live.onLive("chat", (d) => chats.push(d));
    live.onLive("open", () => opens++);
    live.startLive();
    live.startLive();                                       // twice: still one stream
    assert.equal(sources.length, 1);
    assert.equal(sources[0].url, "/api/me/live");
    assert.equal(live.liveOpen(), false);

    sources[0].fire("ready", "{}");
    assert.equal(live.liveOpen(), true);
    assert.equal(opens, 1);
    sources[0].fire("chat", '{"kind":"message","conversationId":5,"messageId":9}');
    sources[0].fire("chat", "not json");                    // still handed on, as an empty event
    assert.deepEqual(chats, [{ kind: "message", conversationId: 5, messageId: 9 }, {}]);

    // the browser reconnects by itself (readyState CONNECTING): the next "ready" means catch up again
    sources[0].readyState = 0;
    sources[0].fire("error");
    assert.equal(live.liveOpen(), false);
    assert.equal(sources.length, 1);
    sources[0].fire("ready", "{}");
    assert.equal(opens, 2);
});

test("hidden tabs hold no stream; coming back opens a new one; after the browser gave up it is tried again", async () => {
    const { live, sources, timers, fireOn } = await browser();
    await withFakeTimers(timers, () => {
        live.startLive();
        document.hidden = true;
        fireOn("document", "visibilitychange");
        assert.equal(sources[0].closed, true);
        assert.equal(live.liveOpen(), false);
        document.hidden = false;
        fireOn("document", "visibilitychange");
        assert.equal(sources.length, 2);

        // CLOSED (an error answer, a dropped tunnel): a new try later, waiting longer each time
        sources[1].readyState = 2;
        sources[1].fire("error");
        assert.equal(sources.length, 2);
        const first = timers.at(-1);
        assert.ok(first.ms >= 5000);
        first.fn();
        assert.equal(sources.length, 3);
        sources[2].readyState = 2;
        sources[2].fire("error");
        assert.ok(timers.at(-1).ms > first.ms);
        // a "ready" again resets the wait
        timers.at(-1).fn();
        sources[3].fire("ready", "{}");
        sources[3].readyState = 2;
        sources[3].fire("error");
        assert.equal(timers.at(-1).ms, first.ms);
    });
});

test("after 'Đăng xuất' the stream closes and never opens again; a handler that throws does not stop the others", async (t) => {
    const { live, sources, fireOn } = await browser();
    const logged = t.mock.method(globalThis.console, "error", () => {});
    const seen = [];
    live.onLive("notification", () => { throw new Error("page bug"); });
    const off = live.onLive("notification", () => seen.push("a"));
    live.onLive("notification", () => seen.push("b"));
    live.startLive();
    sources[0].fire("notification", "{}");
    assert.deepEqual(seen, ["a", "b"]);
    assert.equal(logged.mock.callCount(), 1);
    off();
    sources[0].fire("notification", "{}");
    assert.deepEqual(seen, ["a", "b", "b"]);

    fireOn("window", "asvsj:signing-out");
    assert.equal(sources[0].closed, true);
    fireOn("document", "visibilitychange");
    fireOn("window", "pageshow");
    assert.equal(sources.length, 1);
});
