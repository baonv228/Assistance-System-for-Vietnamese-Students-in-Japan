// Explicit browser integration runner; excluded from *.test.mjs discovery.
// Start the app on localhost:8080 with a local test database and Edge with CDP on 9231.
import fs from "node:fs/promises";
import assert from "node:assert/strict";

const endpoint = process.env.ADMIN_CDP_URL || "http://127.0.0.1:9231";
const targets = await (await fetch(`${endpoint}/json/list`)).json();
const target = targets.find((t) => t.type === "page");
assert(target, "isolated browser page is required");
const ws = new WebSocket(target.webSocketDebuggerUrl);
let seq = 0;
let policy = null;
const pending = new Map();
const events = [];
const checks = [];
const held = [];
await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
});
const call = (method, params = {}) =>
    new Promise((resolve, reject) => {
        const id = ++seq;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
    });
const fulfill = (id, status, payload) =>
    call("Fetch.fulfillRequest", {
        requestId: id,
        responseCode: status,
        responseHeaders: [{ name: "Content-Type", value: "application/json;charset=UTF-8" }],
        body: Buffer.from(JSON.stringify(payload)).toString("base64")
    });
ws.addEventListener("message", (event) => {
    const m = JSON.parse(event.data);
    if (m.id && pending.has(m.id)) {
        const promise = pending.get(m.id);
        pending.delete(m.id);
        m.error ? promise.reject(new Error(m.error.message)) : promise.resolve(m.result);
    } else if (m.method === "Fetch.requestPaused") {
        const p = m.params;
        const u = new URL(p.request.url);
        const local = ["localhost", "127.0.0.1"].includes(u.hostname) && u.port === "8080";
        const font =
            u.protocol === "https:" &&
            ["fonts.googleapis.com", "fonts.gstatic.com"].includes(u.hostname) &&
            p.request.method === "GET";
        if (!local && !font) {
            call("Fetch.failRequest", { requestId: p.requestId, errorReason: "BlockedByClient" }).catch(() => {});
        } else if (policy?.matches(p.request, u)) {
            held.push(p);
            if (policy.status) fulfill(p.requestId, policy.status, policy.payload).catch(() => {});
        } else call("Fetch.continueRequest", { requestId: p.requestId }).catch(() => {});
    } else if (m.method) events.push(m);
});
const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const evaluate = async (expression) => {
    const r = await call("Runtime.evaluate", { expression, returnByValue: true, awaitPromise: true });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    return r.result?.value;
};
const wait = async (expression, timeout = 15000) => {
    const until = Date.now() + timeout;
    while (Date.now() < until) {
        if (await evaluate(expression)) return;
        await pause(100);
    }
    throw new Error(`Timed out: ${expression}`);
};
const waitHeld = async () => {
    const until = Date.now() + 15000;
    while (!held.length && Date.now() < until) await pause(100);
    assert(held.length, "request reached interception");
};
const check = (name, detail) => {
    checks.push({ name, passed: true, detail });
};
const platformFonts = async (selector) => {
    const doc = await call("DOM.getDocument");
    const node = await call("DOM.querySelector", { nodeId: doc.root.nodeId, selector });
    assert(node.nodeId, `font sample exists: ${selector}`);
    return (await call("CSS.getPlatformFontsForNode", { nodeId: node.nodeId })).fonts;
};
const assertFont = (fonts, family, context) => {
    assert(
        fonts.some(
            (font) =>
                (font.familyName === family || font.familyName.startsWith(family + " ")) &&
                font.isCustomFont &&
                font.glyphCount > 0
        ),
        `${context} renders the original ${family} font`
    );
};
const go = async (path) => {
    await call("Page.navigate", { url: `http://localhost:8080${path}` });
    await wait(`location.pathname===${JSON.stringify(path.split("?")[0])} && document.readyState==='complete'`);
    await wait('!!document.querySelector("#adminMainContent")');
};
const api = (path, method = "GET", body) =>
    evaluate(`(async()=>{
    const headers={'Content-Type':'application/json'};
    if (${JSON.stringify(method)}!=='GET') {
        const c=await (await fetch('/api/auth/csrf')).json(); headers[c.data.headerName]=c.data.token;
    }
    const r=await fetch('/api/admin'+${JSON.stringify(path)},{method:${JSON.stringify(method)},headers,body:${body === undefined ? "undefined" : JSON.stringify(JSON.stringify(body))}});
    const text=await r.text();
    return {status:r.status,...(text?JSON.parse(text):{message:'Empty HTTP response'})};
})()`);
const requestPolicy = (path, method) => {
    held.length = 0;
    policy = { matches: (r, u) => u.pathname === path && r.method === method };
};
async function onceWhilePending(name, path, method, trigger, repeat, disabled) {
    requestPolicy(path, method);
    await evaluate(trigger);
    await waitHeld();
    assert(await evaluate(disabled), `${name}: controls disabled`);
    await evaluate(repeat);
    await pause(450);
    assert.equal(held.length, 1, `${name}: exactly one request`);
    const request = held[0];
    policy = null;
    await call("Fetch.continueRequest", { requestId: request.requestId });
    const until = Date.now() + 15000;
    let completed;
    while (Date.now() < until) {
        completed = events.find(
            (event) =>
                ["Network.loadingFinished", "Network.loadingFailed"].includes(event.method) &&
                event.params.requestId === request.networkId
        );
        if (completed) break;
        await pause(100);
    }
    assert.equal(completed?.method, "Network.loadingFinished", `${name}: request completed`);
    check(name, {
        requests: 1,
        pendingControlsDisabled: true,
        response: "real server"
    });
}

await call("Runtime.enable");
await call("Page.enable");
await call("Network.enable");
await call("DOM.enable");
await call("CSS.enable");
await call("Network.setCacheDisabled", { cacheDisabled: true });
await call("Fetch.enable", { patterns: [{ urlPattern: "http://*/*" }, { urlPattern: "https://*/*" }] });
const result = {
    origin: "http://localhost:8080",
    browser: "Edge headless",
    checks,
    layouts: [],
    observedAt: new Date().toISOString()
};
let keywordId = null;
const marker = `xss${Date.now()}`;
const payload = `${marker} \\" <img src=x onerror=window.__adminXss=1> 日本語`;
await fs.mkdir("target/admin-final-browser", { recursive: true });
try {
    await call("Network.clearBrowserCookies");
    await call("Page.navigate", { url: "http://localhost:8080/login" });
    await wait('document.readyState==="complete" && !!document.querySelector("#submit")');
    const email = process.env.ADMIN_SMOKE_EMAIL;
    const password = process.env.ADMIN_SMOKE_PASSWORD;
    assert(email && password, "supply local README development account through environment");
    await evaluate(
        `(()=>{document.querySelector('#email').value=${JSON.stringify(email)};document.querySelector('#password').value=${JSON.stringify(password)};document.querySelector('#submit').click()})()`
    );
    await wait('location.pathname==="/admin" && !!document.querySelector(".admin-stat-card")');

    // Initial loading and retry behavior: intercept only the read endpoint.
    requestPolicy("/api/admin/dashboard/overview", "GET");
    await go("/admin");
    await waitHeld();
    assert(await evaluate('document.querySelector("#adminMainContent").textContent.includes("Đang tải")'));
    check("ADM-UI-01 loading state visible while overview request waits");
    policy = null;
    await fulfill(held[0].requestId, 500, { success: false, message: "Injected read failure", data: null });
    await wait('!!document.querySelector("#retryBtn")');
    assert(await evaluate('document.querySelector("#adminMainContent").textContent.includes("Không tải được")'));
    await evaluate('document.querySelector("#retryBtn").click()');
    await wait('!!document.querySelector(".admin-stat-card")');
    check("ADM-UI-01 read failure, retry and recovery");
    await evaluate(
        'document.querySelector("[data-preset=custom]").click();document.querySelector("#rangeFrom").value="2000-01-01";document.querySelector("#rangeTo").value="2000-01-01";document.querySelector("#applyRange").click()'
    );
    await wait('document.querySelector("#adminMainContent").textContent.includes("Không có dữ liệu trong khoảng")');
    check("ADM-UI-01 real empty custom-range response renders");

    await go("/admin/system-config");
    await wait('!!document.querySelector("#kwForm")');
    await onceWhilePending(
        "ADM-UI-03 keyword add repeated submit",
        "/api/admin/keywords",
        "POST",
        `document.querySelector('#kwInput').value=${JSON.stringify(payload)};document.querySelector('#kwForm').requestSubmit()`,
        `document.querySelector('#kwForm').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`,
        'document.querySelector("#kwAdd").disabled'
    );
    await wait(
        `Array.from(document.querySelectorAll('.admin-kw-text')).some(e=>e.textContent.includes(${JSON.stringify(marker)}))`
    );
    let list = await api(`/keywords?search=${marker}`);
    assert.equal(list.data.length, 1);
    keywordId = list.data[0].id;
    assert.equal(list.data[0].keyword, payload.toLowerCase());
    assert(
        await evaluate(
            `Array.from(document.querySelectorAll('.admin-kw-text')).find(e=>e.textContent.includes(${JSON.stringify(marker)})).textContent===${JSON.stringify(payload.toLowerCase())} && !document.querySelector('.admin-kw-list img') && !window.__adminXss`
        )
    );
    check("ADM-KWD-08 quotes, slash, Japanese and HTML render as literal keyword text");
    await evaluate(`document.querySelector('[data-edit="${keywordId}"]').click()`);
    assert.equal(await evaluate('document.querySelector("[data-edit-form] input").value'), payload.toLowerCase());
    const edited = payload.toLowerCase() + " edit";
    await onceWhilePending(
        "ADM-UI-03 keyword edit repeated submit",
        `/api/admin/keywords/${keywordId}`,
        "PUT",
        `document.querySelector('[data-edit-form] input').value=${JSON.stringify(edited)};document.querySelector('[data-edit-form]').requestSubmit()`,
        `document.querySelector('[data-edit-form]').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`,
        'document.querySelector("[data-edit-form] button[type=submit]").disabled'
    );
    await wait(`!!document.querySelector('[data-edit="${keywordId}"]')`);
    await onceWhilePending(
        "ADM-UI-03 safety repeated submit",
        "/api/admin/safety-config",
        "PUT",
        `document.querySelector('#param-new_account_days').value='9999';document.querySelector('#safetyForm').requestSubmit()`,
        `document.querySelector('#safetyForm').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}))`,
        'document.querySelector("#safetySave").disabled'
    );
    await wait('document.querySelector("#param-new_account_days").getAttribute("aria-invalid")==="true"');
    await go("/admin/system-config");
    await wait('!!document.querySelector("[data-edit-setting]")');
    await evaluate(
        'document.querySelector("[data-edit-setting]").click();document.querySelector("#settingValueInput").value="999999"'
    );
    const settingKey = await evaluate('document.querySelector("[data-edit-setting]").dataset.editSetting');
    await onceWhilePending(
        "ADM-UI-03 single setting modal repeated confirm",
        `/api/admin/settings/${settingKey}`,
        "PUT",
        'document.querySelector("#modalConfirmBtn").click()',
        'document.querySelector("#modalConfirmBtn").dispatchEvent(new Event("click"));document.querySelector("#modalCancelBtn").dispatchEvent(new Event("click"))',
        'document.querySelector("#modalConfirmBtn").disabled && document.querySelector("#modalCancelBtn").disabled'
    );
    await wait('!document.querySelector("#modalConfirmBtn").disabled');
    await evaluate('document.querySelector("#modalCancelBtn").click()');

    await go("/admin/activity-log");
    await wait('!!document.querySelector("#logsTableContainer table")');
    await evaluate(
        'document.querySelector("#logActionFilter").value="KEYWORD_ADD";document.querySelector("#logActionFilter").dispatchEvent(new Event("change"))'
    );
    await wait(`document.querySelector('#logsTableContainer').textContent.includes(${JSON.stringify(marker)})`);
    assert(await evaluate('!document.querySelector("#logsTableContainer img") && !window.__adminXss'));
    await evaluate(
        `Array.from(document.querySelectorAll('#logsTableContainer tr')).find(e=>e.textContent.includes(${JSON.stringify(marker)})).querySelector('[data-view-diff]').click()`
    );
    await wait('!!document.querySelector("#activeAdminModal pre")');
    assert(
        await evaluate(
            `document.querySelector('#activeAdminModal').textContent.includes(${JSON.stringify(marker)}) && !document.querySelector('#activeAdminModal img') && !window.__adminXss`
        )
    );
    check("ADM-LOG-05 and ADM-UI-04 log row and JSON diff modal escape HTML without execution");
    await evaluate('document.querySelector("#modalCancelBtn").click()');

    await go("/admin/users?role=USER&status=ACTIVE");
    await wait('!!document.querySelector("a.user-cell")');
    const detailPath = await evaluate('document.querySelector("a.user-cell").getAttribute("href")');
    await go(detailPath);
    await wait('!!document.querySelector("#roleBtn")');
    const userId = detailPath.split("?")[0].split("/").pop();
    for (const status of [400, 404, 409]) {
        requestPolicy(`/api/admin/users/${userId}/role`, "PUT");
        policy.status = status;
        policy.payload = { success: false, message: `UI test ${status}: giữ nguyên tài khoản`, data: null };
        await evaluate(
            'document.querySelector("#roleSelect").value="ACADEMIC_MANAGER";document.querySelector("#roleBtn").click()'
        );
        await waitHeld();
        await wait(`document.querySelector('#roleMessage').textContent.includes('UI test ${status}')`);
        policy = null;
        assert(await evaluate(`document.querySelector('#roleMessage').textContent.includes('UI test ${status}')`));
        check(`ADM-UI-02 role action renders ${status} without changing an account`);
    }
    await go("/admin/users/999999999");
    await wait('document.querySelector("#adminMainContent").textContent.includes("Không tìm thấy tài khoản")');
    check("ADM-UI-02 actual missing detail renders 404 with return link");

    // Verify the same downloaded typefaces and Material Symbols used throughout the site.
    for (const [name, path] of [
        ["dashboard", "/admin"],
        ["users", "/admin/users"],
        ["detail", detailPath],
        ["settings", "/admin/system-config"],
        ["logs", "/admin/activity-log"]
    ]) {
        await go(path);
        await wait('!!document.querySelector("#adminMainContent h2")');
        await evaluate("document.fonts.ready.then(() => true)");
        for (const [label, width, height, zoom] of [
            ["desktop", 1440, 900, 1],
            ["tablet", 768, 1024, 1],
            ["mobile", 390, 844, 1],
            ["zoom200", 720, 450, 1]
        ]) {
            // Browser 200% layout zoom halves CSS viewport at the same physical screen size.
            await call("Emulation.setDeviceMetricsOverride", {
                width,
                height,
                deviceScaleFactor: label === "zoom200" ? 2 : 1,
                mobile: label === "mobile"
            });
            await call("Emulation.setPageScaleFactor", { pageScaleFactor: zoom });
            await pause(150);
            const metrics = await evaluate(
                `(()=>{const e=document.querySelector('#adminMainContent h2');const c=getComputedStyle(e);return {width:innerWidth,height:innerHeight,overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth,heading:e.textContent,font:c.fontFamily,color:c.color,bodyFont:getComputedStyle(document.body).fontFamily,background:getComputedStyle(document.body).backgroundColor}})()`
            );
            assert(!metrics.overflow, `${name} ${label} does not overflow`);
            assert(!metrics.heading.includes("\ufffd"), "Vietnamese text decodes correctly");
            assert.equal(metrics.color, "rgb(47, 62, 110)", "heading keeps the shared ink color");
            assert.equal(metrics.background, "rgb(251, 244, 243)", "page keeps the shared background color");
            const fonts = await platformFonts("#adminMainContent h2");
            const bodyFonts = await platformFonts(".desk-side a");
            const iconFonts = await platformFonts(".desk-side a .ic");
            assertFont(fonts, "Bricolage Grotesque", `${name} heading`);
            assertFont(bodyFonts, "Be Vietnam Pro", `${name} navigation`);
            assertFont(iconFonts, "Material Symbols Rounded", `${name} icon`);
            result.layouts.push({ page: name, viewport: label, ...metrics, fonts, bodyFonts, iconFonts });
            const screenshot = await call("Page.captureScreenshot", { format: "png", captureBeyondViewport: false });
            await fs.writeFile(
                `target/admin-final-browser/${name}-${label}.png`,
                Buffer.from(screenshot.data, "base64")
            );
        }
    }
    check("ADM-UI-05 actual fonts/colors/Unicode and 20 viewport renders", { screenshots: 20 });
    await go("/admin/system-config");
    await wait(`!!document.querySelector('[data-del="${keywordId}"]')`);
    await onceWhilePending(
        "ADM-UI-03 keyword delete repeated click",
        `/api/admin/keywords/${keywordId}`,
        "DELETE",
        `document.querySelector('[data-del="${keywordId}"]').click()`,
        `document.querySelector('[data-del="${keywordId}"]').dispatchEvent(new Event('click'))`,
        `document.querySelector('[data-del="${keywordId}"]').disabled`
    );
    list = await api(`/keywords?search=${marker}`);
    assert.equal(list.data.length, 0);
    keywordId = null;
    check("Temporary malicious-looking keyword cleaned up and absent");
    const unexpected = events.filter(
        (e) =>
            e.method === "Runtime.exceptionThrown" ||
            (e.method === "Runtime.consoleAPICalled" && e.params.type === "error")
    );
    assert.equal(unexpected.length, 0, "no JavaScript exceptions");
    check("No JavaScript console errors or runtime exceptions");
} catch (error) {
    result.error = error.stack;
    process.exitCode = 1;
    try {
        result.failurePage = await evaluate("({url:location.href,text:document.body.innerText.slice(0,1500)})");
    } catch {}
    result.runtimeErrors = events.filter((e) => e.method === "Runtime.exceptionThrown");
} finally {
    policy = null;
    for (const p of held) {
        try {
            await call("Fetch.continueRequest", { requestId: p.requestId });
        } catch {}
    }
    try {
        // Also recover a created keyword if the run failed before its ID was recorded.
        const remaining = await api(`/keywords?search=${marker}`);
        assert.equal(remaining.status, 200, "cleanup can read its keyword fixture");
        for (const keyword of remaining.data) {
            const deleted = await api(`/keywords/${keyword.id}`, "DELETE");
            assert([200, 204, 404].includes(deleted.status), "temporary keyword can be deleted");
        }
        result.cleanup = { keywordAbsent: (await api(`/keywords?search=${marker}`)).data?.length === 0 };
        assert(result.cleanup.keywordAbsent, "temporary keyword is absent after cleanup");
    } catch (error) {
        result.cleanup = { error: error.message };
        result.error ||= error.stack;
        process.exitCode = 1;
    }
    await fs.writeFile("target/admin-final-browser/report.json", JSON.stringify(result, null, 2));
    console.log(
        JSON.stringify(
            {
                checks: checks.length,
                passed: checks.filter((c) => c.passed).length,
                error: result.error,
                cleanup: result.cleanup,
                layouts: result.layouts.length
            },
            null,
            2
        )
    );
    ws.close();
}
