// Live updates (common/live/LiveEvents on the server): while the page is in front, one EventSource on /api/me/live
// says at once that something changed for the signed-in user ("chat": a message, an edit, a read, typing, a status;
// "notification": a new notification), and the page asks for it with the usual API instead of waiting for its next
// poll. Events carry ids and a kind only. A hidden tab holds no stream (pages ask again when they come back), so a
// member with many tabs open keeps one or two connections, not one per tab (browsers allow 6 per site over HTTP/1.1).
// Without EventSource, or while the stream is down, nothing comes from here and the pages poll as before.
//
//   startLive()               opens the stream (site-header.js, for a signed-in user)
//   onLive(type, handler)     handler(data) for each event of that type → a function that unsubscribes;
//                             type "open": handler() each time the stream (re)opens, to catch up on what was missed
//   liveOpen()                whether the stream is open now (pages then poll less often)

const STREAM = "/api/me/live";
/** After the browser gave up on the stream (an error answer, a dropped tunnel): first wait, doubled up to the most. */
const RETRY_FIRST_MS = 5000;
const RETRY_MAX_MS = 60000;

const handlers = new Map();                     // type → Set of handlers
let source = null;
let open = false;
let started = false;
let stopped = false;
let retryMs = RETRY_FIRST_MS;
let retryTimer = null;

export function startLive() {
    if (started || typeof globalThis.EventSource !== "function") return;
    started = true;
    document.addEventListener("visibilitychange", sync);
    window.addEventListener("pageshow", sync);           // back from the back/forward cache
    window.addEventListener("pagehide", close);
    window.addEventListener("asvsj:signing-out", stop);
    sync();
}

export function onLive(type, handler) {
    if (!handlers.has(type)) handlers.set(type, new Set());
    handlers.get(type).add(handler);
    return () => handlers.get(type)?.delete(handler);
}

export function liveOpen() {
    return open;
}

/** In front: a stream; hidden: none. */
function sync() {
    if (stopped) return;
    if (document.hidden) close();
    else if (!source) connect();
}

function connect() {
    clearTimeout(retryTimer);
    retryTimer = null;
    const es = new globalThis.EventSource(STREAM);
    source = es;
    es.addEventListener("ready", () => {
        if (source !== es) return;
        open = true;
        retryMs = RETRY_FIRST_MS;
        emit("open");
    });
    for (const type of ["chat", "notification"]) {
        es.addEventListener(type, (event) => {
            if (source !== es) return;
            let data = {};
            try {
                data = JSON.parse(event.data) ?? {};
            } catch {
                // a broken line: still worth asking
            }
            emit(type, data);
        });
    }
    es.addEventListener("error", () => {
        if (source !== es) return;
        open = false;
        // CONNECTING: the browser tries again by itself (the server said after how long). CLOSED: it gave up (an
        // error answer such as 401 or a tunnel's 502), so try again later, less and less often
        if (es.readyState === 2) {
            es.close();
            source = null;
            if (!stopped && !document.hidden) {
                retryTimer = setTimeout(sync, retryMs);
                retryMs = Math.min(retryMs * 2, RETRY_MAX_MS);
            }
        }
    });
}

function close() {
    clearTimeout(retryTimer);
    retryTimer = null;
    open = false;
    source?.close();
    source = null;
}

/** "Đăng xuất": no stream any more in this tab. */
function stop() {
    stopped = true;
    close();
}

function emit(type, data) {
    for (const handler of handlers.get(type) ?? []) {
        try {
            handler(data);
        } catch (error) {
            console.error("live", type, error);
        }
    }
}
