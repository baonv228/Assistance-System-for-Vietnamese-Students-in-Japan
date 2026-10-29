// Photos, videos and voice messages of "Tin nhắn" (end-to-end encrypted conversations only), for messages.js.
// Before sending: photos are re-encoded to JPG ≤ 1600 px (EXIF and GPS dropped), a video keeps its pictures but loses
// its metadata (place, phone model: lib/mp4-strip.js), a voice message is recorded here; then each file is encrypted
// with its own key (lib/chat-crypto.js encryptFile) and uploaded. The server only ever sees ciphertext, so the limits
// on kinds, sizes and lengths are checked here first (the server checks the encrypted size and the mix again).
// After receiving: each file is downloaded once per page, decrypted and shown from an object URL.
//
// Importable in Node (tests): nothing here touches the DOM until a function is called.

import { apiClient, ApiError } from "../api/client.js";
import { cryptoSupported, CryptoUnavailableError, decryptFile, encryptFile } from "../lib/chat-crypto.js";
import { appleMobile, IN_APP_ADVICE, inAppBrowser, touchOnly } from "../lib/device.js";
import { readMp4Info, stripMp4Metadata } from "../lib/mp4-strip.js";
import { MB, shrinkToJpeg, unreadableImage } from "../lib/media-files.js";
import { esc, ic } from "../lib/ui.js";

// ---------------------------------------------------------------------------------------------------------- limits

/** Used when the conversation's `limits` lack a value (they come from ChatService.LIMITS). */
const DEFAULT_LIMITS = {
    filesMax: 5,
    imageMaxBytes: 10 * MB,
    videoMaxBytes: 50 * MB,
    audioMaxBytes: 5 * MB,
    videoMaxSeconds: 30,
    audioMaxSeconds: 120
};
/** AES-GCM adds a 16-byte tag, and the server's limits apply to the encrypted upload. */
const TAG_BYTES = 16;
/** A photo larger than this is not even opened (decoding it could freeze a phone); it would be shrunk anyway. */
const RAW_PHOTO_MAX = 50 * MB;
/** Phones often record 30.0x seconds for "30 seconds" (same slack as MediaService on the server). */
const VIDEO_SLACK_SECONDS = 0.5;
/** A shorter recording is a mis-tap, not a message. */
const VOICE_MIN_SECONDS = 1;
/** Voice is clear at 64 kbit/s: 120 s ≈ 1 MB, far below the 5 MB limit. */
const VOICE_BITS_PER_SECOND = 64000;
/** Files downloaded and decrypted at the same time when a conversation with many photos opens. */
const DOWNLOADS_AT_ONCE = 3;

const PHOTO_TYPES = ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"];
const PHOTO_EXT = /\.(jpe?g|png|webp|heic|heif)$/i;
const VIDEO_TYPES = ["video/mp4", "video/quicktime"];
const VIDEO_EXT = /\.(mp4|mov)$/i;

/** `accept` of the photo/video file input. HEIC opens only where the browser can decode it (Safari); see prepareFiles. */
export const ATTACH_ACCEPT = [...PHOTO_TYPES, ".jpg", ".jpeg", ".png", ".webp", ".heic", ".heif",
    ...VIDEO_TYPES, ".mp4", ".mov"].join(",");

/**
 * MediaRecorder types tried in this order; none supported → the browser's own default. MP4 with AAC first: every
 * browser plays it, while a WebM/Opus recording (Firefox records nothing else) does not play on older iPhones.
 */
export const RECORDER_TYPES = ["audio/mp4;codecs=mp4a.40.2", "audio/mp4", "audio/webm;codecs=opus"];

// Same list as chat-crypto.js: a Blob URL of any other type (HTML, SVG) could run script in this site when opened.
const SAFE_TYPES = {
    IMAGE: ["image/jpeg", "image/png", "image/webp", "image/gif"],
    VIDEO: ["video/mp4", "video/quicktime", "video/webm"],
    AUDIO: ["audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/aac", "audio/wav"]
};
const MIME_PARAMS = /^[a-z0-9=.,+" -]*$/i;

/** The conversation's limits with defaults for anything missing. */
export function withDefaults(limits) {
    const out = { ...DEFAULT_LIMITS };
    for (const name of Object.keys(DEFAULT_LIMITS)) {
        const value = limits?.[name];
        if (Number.isFinite(value) && value > 0) {
            out[name] = value;
        }
    }
    return out;
}

/** "10 MB" */
const mb = (bytes) => `${Math.round(bytes / MB)} MB`;

/** A file name short enough for a message. */
function shortName(name) {
    const text = String(name || "tệp");
    return text.length > 40 ? `${text.slice(0, 18)}…${text.slice(-18)}` : text;
}

/** IMAGE, VIDEO or null, from the type the browser gives, else from the extension. */
export function fileKind(file) {
    const type = String(file?.type || "").toLowerCase();
    if (PHOTO_TYPES.includes(type)) return "IMAGE";
    if (VIDEO_TYPES.includes(type)) return "VIDEO";
    if (type && type !== "application/octet-stream") return null;
    const name = String(file?.name || "");
    if (PHOTO_EXT.test(name)) return "IMAGE";
    if (VIDEO_EXT.test(name)) return "VIDEO";
    return null;
}

/**
 * Checks a pick before anything is opened: types, the mix (1–filesMax photos, or one video, or one voice message, also
 * counting the attachments already chosen), the video size and absurdly large photos. `files` are File-like
 * `{ name, type, size }`, `existing` the Attachments already above the message box.
 * → `{ kind: "IMAGE" | "VIDEO" | null, error: string | null }` (error: nothing of this pick is taken).
 */
export function checkSelection(files, limits, existing = []) {
    const l = withDefaults(limits);
    const list = [...(files || [])];
    if (!list.length) {
        return { kind: null, error: null };
    }
    const kinds = [];
    for (const file of list) {
        const kind = fileKind(file);
        if (!kind) {
            return { kind: null, error: `Không gửi được “${shortName(file.name)}”. Chỉ gửi được ảnh JPG, PNG, HEIC hoặc video MP4, MOV.` };
        }
        kinds.push(kind);
    }
    const had = [...(existing || [])].map((a) => a.kind);
    const all = [...had, ...kinds];
    const mix = `Mỗi tin nhắn gửi được tối đa ${l.filesMax} ảnh, hoặc một video, hoặc một tin nhắn thoại.`;
    if (new Set(all).size > 1) {
        return { kind: null, error: mix };
    }
    const kind = kinds[0];
    if (kind === "VIDEO") {
        if (all.length > 1) {
            return { kind: null, error: mix };
        }
        if (list[0].size + TAG_BYTES > l.videoMaxBytes) {
            return { kind: null, error: `Video tối đa ${mb(l.videoMaxBytes)}. Hãy chọn video ngắn hơn hoặc quay ở chất lượng thấp hơn.` };
        }
        return { kind, error: null };
    }
    if (all.length > l.filesMax) {
        return {
            kind: null,
            error: had.length
                ? `Mỗi tin nhắn tối đa ${l.filesMax} ảnh (bạn đã chọn ${had.length} ảnh).`
                : `Mỗi tin nhắn tối đa ${l.filesMax} ảnh.`
        };
    }
    const big = list.find((f) => f.size > RAW_PHOTO_MAX);
    if (big) {
        return { kind: null, error: `Ảnh “${shortName(big.name)}” quá lớn (tối đa ${mb(RAW_PHOTO_MAX)}).` };
    }
    return { kind, error: null };
}

/**
 * The MIME type to keep for a file of this kind, or null when it is not one this app shows (same rule as
 * chat-crypto.js: a known type, harmless parameters). The page builds Blobs only with such a type.
 */
export function allowedMime(kind, mime) {
    if (typeof mime !== "string" || mime.length > 120 || !SAFE_TYPES[kind]) {
        return null;
    }
    const cut = mime.indexOf(";");
    const type = (cut < 0 ? mime : mime.slice(0, cut)).trim().toLowerCase();
    if (!SAFE_TYPES[kind].includes(type)) {
        return null;
    }
    return cut < 0 || MIME_PARAMS.test(mime.slice(cut + 1)) ? mime : type;
}

/** The first of RECORDER_TYPES `isTypeSupported` accepts, or "" (let the browser choose). */
export function chooseRecorderMime(isTypeSupported) {
    for (const type of RECORDER_TYPES) {
        try {
            if (isTypeSupported(type)) return type;
        } catch {
            // an old browser throwing on the question: try the next one
        }
    }
    return "";
}

/**
 * The type a recording is sent as: what the recorder reports when it is a known audio type; a "video/webm" or
 * "video/mp4" of an audio-only recording becomes audio/…; null when nothing usable is known.
 */
export function audioMime(mime) {
    if (typeof mime !== "string" || !mime.trim()) {
        return null;
    }
    const known = allowedMime("AUDIO", mime.trim());
    if (known) {
        return known;
    }
    const type = mime.split(";")[0].trim().toLowerCase();
    return { "video/webm": "audio/webm", "video/mp4": "audio/mp4", "audio/x-m4a": "audio/mp4", "audio/m4a": "audio/mp4" }[type]
        ?? null;
}

/** "0:05", "1:30" (rounded to the second). */
export function formatDuration(seconds) {
    const total = Math.max(0, Math.round(Number(seconds) || 0));
    return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}`;
}

/** The list preview of a message with files: "📷 Ảnh", "📷 3 ảnh", "🎬 Video", "🎤 Tin nhắn thoại". */
export function fileSummary(files) {
    const list = Array.isArray(files) ? files.filter(Boolean) : [];
    if (!list.length) {
        return "";
    }
    const kind = list[0].kind;
    if (kind === "VIDEO") return "🎬 Video";
    if (kind === "AUDIO") return "🎤 Tin nhắn thoại";
    if (kind !== "IMAGE") return "📎 Tệp đính kèm";
    const photos = list.filter((f) => f.kind === "IMAGE").length;
    return photos > 1 ? `📷 ${photos} ảnh` : "📷 Ảnh";
}

// --------------------------------------------------------------------------------------------------- choosing files

/**
 * Prepares picked photos or one video for sending. `existing` (optional) = the Attachments already chosen, so the mix
 * and the photo count are checked over all of them; only the new ones are returned.
 * → `{ items: Attachment[], error }`, Attachment = `{ kind, mime, bytes, size, w?, h?, dur?, previewUrl, name, blob }`.
 * A photo the browser cannot open (HEIC outside Safari, a broken file) is left out with its error; the others are kept.
 */
export async function prepareFiles(files, limits, existing = []) {
    const list = [...(files || [])];
    const { kind, error } = checkSelection(list, limits, existing);
    if (error || !kind) {
        return { items: [], error };
    }
    const l = withDefaults(limits);
    return kind === "VIDEO" ? prepareVideo(list[0], l) : preparePhotos(list, l);
}

async function preparePhotos(list, l) {
    const items = [];
    let error = null;
    for (const file of list) {                          // one at a time: phones run out of memory on 5 big photos
        let blob;
        try {
            blob = await shrinkToJpeg(file);
        } catch {
            error ??= unreadableImage(file);
            continue;
        }
        if (blob.size + TAG_BYTES > l.imageMaxBytes) {
            error ??= `Ảnh “${shortName(file.name)}” vẫn lớn hơn ${mb(l.imageMaxBytes)} sau khi thu nhỏ. Hãy chọn ảnh khác.`;
            continue;
        }
        const bytes = new Uint8Array(await blob.arrayBuffer());
        const size = await imageSize(blob);
        items.push({ kind: "IMAGE", mime: "image/jpeg", bytes, size: bytes.length, ...size,
            previewUrl: URL.createObjectURL(blob), name: file.name, blob });
    }
    return { items, error };
}

async function prepareVideo(file, l) {
    const unreadable = `Không đọc được video “${shortName(file.name)}”. Hãy chọn video MP4 hoặc MOV khác (quay bằng điện thoại hoặc máy ảnh).`;
    let original;
    try {
        original = new Uint8Array(await file.arrayBuffer());
    } catch {
        return { items: [], error: unreadable };
    }
    const info = readMp4Info(original);
    if (!info) {
        return { items: [], error: unreadable };
    }
    if (info.seconds > l.videoMaxSeconds + VIDEO_SLACK_SECONDS) {
        return { items: [], error: `Video dài ${Math.round(info.seconds)} giây. Video tối đa ${l.videoMaxSeconds} giây.` };
    }
    const bytes = stripMp4Metadata(original);          // where it was filmed, the phone: the server cannot remove it
    if (!bytes) {
        return { items: [], error: unreadable };
    }
    // MOV is labelled video/mp4: browsers play H.264 MOV files as MP4, and Firefox refuses a "video/quicktime" Blob
    const mime = "video/mp4";
    const blob = new Blob([bytes], { type: mime });
    const previewUrl = URL.createObjectURL(blob);
    const size = await videoSize(previewUrl);
    return {
        items: [{ kind: "VIDEO", mime, bytes, size: bytes.length, ...size, dur: Math.round(info.seconds * 10) / 10,
            previewUrl, name: file.name, blob }],
        error: null
    };
}

/** { w, h } of a JPEG made by shrinkToJpeg (no EXIF rotation left), or {} when the browser cannot tell. */
async function imageSize(blob) {
    try {
        if (typeof createImageBitmap === "function") {
            const bitmap = await createImageBitmap(blob);
            const size = { w: bitmap.width, h: bitmap.height };
            bitmap.close?.();
            return size;
        }
    } catch {
        // fall through
    }
    return {};
}

/** { w, h } of a video this browser can open (rotation applied), else {} (HEVC on many PCs). */
function videoSize(url) {
    if (typeof document === "undefined") {
        return Promise.resolve({});
    }
    return new Promise((resolve) => {
        const video = document.createElement("video");
        const done = (value) => {
            clearTimeout(timer);
            video.removeAttribute("src");
            video.load();
            resolve(value);
        };
        const timer = setTimeout(() => done({}), 4000);
        video.preload = "metadata";
        video.muted = true;
        video.onloadedmetadata = () => done(video.videoWidth > 0 && video.videoHeight > 0
            ? { w: video.videoWidth, h: video.videoHeight } : {});
        video.onerror = () => done({});
        video.src = url;
    });
}

// ----------------------------------------------------------------------------------------------- voice messages

const insecure = () => globalThis.isSecureContext === false;

/** Why a voice message cannot be recorded here, in Vietnamese. */
function recorderUnavailable() {
    if (insecure()) return "Ghi âm chỉ dùng được khi mở Senpai ơi qua địa chỉ https.";
    if (inAppBrowser()) return IN_APP_ADVICE;
    return "Trình duyệt này không ghi âm được. Hãy dùng Chrome, Edge, Firefox hoặc Safari mới.";
}

/** The Vietnamese message for a refused or failed getUserMedia. */
export function microphoneError(error) {
    switch (error?.name) {
        case "NotAllowedError":
        case "SecurityError":
            if (inAppBrowser()) return IN_APP_ADVICE;
            return appleMobile()
                ? "Bạn chưa cho phép Senpai ơi dùng micro. Trên iPhone: bấm “aA” cạnh địa chỉ → Cài đặt trang web → Micro → Cho phép (hoặc Cài đặt → Safari → Micro), rồi thử lại."
                : "Bạn chưa cho phép Senpai ơi dùng micro. Hãy cho phép micro trong cài đặt trang của trình duyệt (biểu tượng ổ khóa cạnh địa chỉ) rồi thử lại.";
        case "NotFoundError":
        case "OverconstrainedError":
            return "Không tìm thấy micro trên thiết bị này.";
        case "NotReadableError":
        case "AbortError":
            return "Micro đang được ứng dụng khác dùng. Hãy tắt ứng dụng đó rồi thử lại.";
        default:
            return "Không bật được micro. Hãy thử lại.";
    }
}

/**
 * Records one voice message. `new VoiceRecorder({ maxSeconds, onTick(seconds), onStop?(attachment | null, error?),
 * maxBytes? })`; `start()` asks for the microphone (rejects with a Vietnamese Error when refused); `stop()` →
 * Attachment (kind AUDIO) or null when nothing usable was recorded (shorter than 1 s, or cancelled); `cancel()` drops
 * it. At `maxSeconds` it stops by itself (and when the microphone goes away): `onStop` then gets the result, and a
 * later `stop()` gives the same one. The microphone is released as soon as the recording ends. A recording is never
 * passed through the MP4 metadata stripper: MediaRecorder writes no location or phone model.
 */
export class VoiceRecorder {
    static supported() {
        return !insecure() && typeof globalThis.MediaRecorder === "function"
            && typeof globalThis.navigator?.mediaDevices?.getUserMedia === "function";
    }

    /** Why `supported()` is false, in Vietnamese (to show instead of the microphone button). */
    static unavailableReason() {
        return recorderUnavailable();
    }

    #maxSeconds;
    #maxBytes;
    #onTick;
    #onStop;
    #state = "idle";                                    // idle → starting → recording → stopping → done | cancelled
    #stream = null;
    #recorder = null;
    #chunks = [];
    #chosenType = "";
    #startedAt = 0;
    #elapsedMs = 0;
    #timer = null;
    #lastTick = -1;
    #result = null;
    #settle = null;
    #auto = false;
    #session = 0;
    #onPageHide = () => this.cancel();

    constructor({ maxSeconds = DEFAULT_LIMITS.audioMaxSeconds, onTick, onStop, maxBytes = DEFAULT_LIMITS.audioMaxBytes } = {}) {
        this.#maxSeconds = Number.isFinite(maxSeconds) && maxSeconds > 0 ? maxSeconds : DEFAULT_LIMITS.audioMaxSeconds;
        this.#maxBytes = Number.isFinite(maxBytes) && maxBytes > 0 ? maxBytes : DEFAULT_LIMITS.audioMaxBytes;
        this.#onTick = typeof onTick === "function" ? onTick : null;
        this.#onStop = typeof onStop === "function" ? onStop : null;
    }

    /** True from start() until the recording ends. */
    get recording() {
        return this.#state === "starting" || this.#state === "recording";
    }

    /** Whole seconds recorded so far. */
    get seconds() {
        return Math.floor(this.#elapsed() / 1000);
    }

    async start() {
        if (this.#state === "starting" || this.#state === "recording" || this.#state === "stopping") {
            throw new Error("Đang ghi âm.");
        }
        if (!VoiceRecorder.supported()) {
            throw new Error(recorderUnavailable());
        }
        // a recorder can be used again after stop() or cancel()
        this.#elapsedMs = 0;
        this.#lastTick = -1;
        this.#auto = false;
        this.#result = null;
        this.#state = "starting";
        // Events of an earlier recording (a late "stop" after cancel()) must not touch this one
        const session = ++this.#session;
        const current = () => session === this.#session;
        let stream;
        try {
            stream = await navigator.mediaDevices.getUserMedia({
                audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
            });
        } catch (error) {
            if (current()) this.#state = "idle";
            throw new Error(microphoneError(error), { cause: error });
        }
        if (!current() || this.#state !== "starting") { // cancelled while the browser was asking
            stream.getTracks().forEach((t) => t.stop());
            return;
        }
        this.#stream = stream;
        this.#chosenType = chooseRecorderMime((type) => MediaRecorder.isTypeSupported(type));
        let recorder;
        try {
            recorder = new MediaRecorder(stream, {
                ...(this.#chosenType ? { mimeType: this.#chosenType } : {}),
                audioBitsPerSecond: VOICE_BITS_PER_SECOND
            });
        } catch {
            try {
                recorder = new MediaRecorder(stream);
            } catch (error) {
                this.#release();
                this.#state = "idle";
                throw new Error(recorderUnavailable(), { cause: error });
            }
        }
        this.#recorder = recorder;
        this.#chunks = [];
        this.#result = new Promise((resolve, reject) => {
            this.#settle = { resolve, reject };
        });
        this.#result.catch(() => {});                   // handled by whoever calls stop(); never "unhandled"
        recorder.ondataavailable = (event) => {
            if (current() && event.data && event.data.size) this.#chunks.push(event.data);
        };
        recorder.onstop = () => {
            if (!current()) return;
            if (this.#state === "recording") {          // the browser stopped it by itself: keep what was recorded
                this.#elapsedMs = Math.min(this.#elapsed(), this.#maxSeconds * 1000);
                this.#state = "stopping";
                this.#result.then((attachment) => this.#onStop?.(attachment), (error) => this.#onStop?.(null, error));
            }
            this.#finish();
        };
        recorder.onerror = () => {
            if (current()) this.#endBySelf();
        };
        // The microphone unplugged, or the permission taken back
        stream.getAudioTracks().forEach((track) => track.addEventListener("ended", () => {
            if (current()) this.#endBySelf();
        }));
        try {
            recorder.start();
        } catch (error) {
            this.#release();
            this.#state = "idle";
            throw new Error(recorderUnavailable(), { cause: error });
        }
        this.#state = "recording";
        this.#startedAt = performance.now();
        this.#timer = setInterval(() => this.#tick(), 200);
        globalThis.addEventListener?.("pagehide", this.#onPageHide);
        this.#tick();
    }

    /** Ends the recording → the Attachment, or null when it was too short or cancelled. */
    stop() {
        if (this.#state === "starting") {
            this.cancel();
            return Promise.resolve(null);
        }
        if (this.#state === "recording") {
            this.#halt();
        }
        return this.#result ?? Promise.resolve(null);
    }

    /** Drops the recording and releases the microphone; a pending stop() gives null. */
    cancel() {
        const was = this.#state;
        this.#session++;                                // later events of this recording are ignored
        if (was === "starting" || was === "recording" || was === "stopping") {
            this.#state = "cancelled";
            this.#stopTimer();
            if (was === "recording") {
                try {
                    this.#recorder.stop();
                } catch {
                    // already inactive
                }
            }
            this.#chunks = [];
            this.#settle?.resolve(null);
            this.#settle = null;
        }
        this.#release();
    }

    #elapsed() {
        if (this.#state === "recording") {
            return performance.now() - this.#startedAt;
        }
        return this.#elapsedMs;
    }

    #tick() {
        const ms = this.#elapsed();
        if (ms >= this.#maxSeconds * 1000) {
            this.#endBySelf();
            return;
        }
        const seconds = Math.floor(ms / 1000);
        if (seconds !== this.#lastTick) {
            this.#lastTick = seconds;
            this.#onTick?.(seconds);
        }
    }

    /** Reached maxSeconds, or the microphone went away: stop and hand the result to onStop. */
    #endBySelf() {
        if (this.#state !== "recording") return;
        this.#auto = true;
        this.#halt();
        this.#result.then((attachment) => this.#onStop?.(attachment), (error) => this.#onStop?.(null, error));
    }

    #halt() {
        this.#elapsedMs = Math.min(this.#elapsed(), this.#maxSeconds * 1000);
        this.#state = "stopping";
        this.#stopTimer();
        if (this.#auto) this.#onTick?.(Math.floor(this.#elapsedMs / 1000));
        try {
            this.#recorder.stop();                      // the last data, then onstop → #finish
        } catch {
            this.#finish();
        }
    }

    async #finish() {
        this.#stopTimer();
        this.#release();
        const settle = this.#settle;
        if (!settle) return;
        this.#settle = null;
        this.#state = "done";
        const session = this.#session;
        try {
            const attachment = await this.#attachment();
            if (session !== this.#session) {            // cancel() while the clip was being read
                releasePending(attachment);
                settle.resolve(null);
                return;
            }
            settle.resolve(attachment);
        } catch (error) {
            settle.reject(error);
        }
    }

    async #attachment() {
        const seconds = this.#elapsedMs / 1000;
        const parts = this.#chunks;
        this.#chunks = [];
        if (seconds < VOICE_MIN_SECONDS || !parts.length) {
            return null;
        }
        const mime = audioMime(this.#recorder?.mimeType) ?? audioMime(parts[0].type) ?? audioMime(this.#chosenType);
        if (!mime) {
            throw new Error("Trình duyệt này ghi âm theo định dạng Senpai ơi chưa gửi được. Hãy thử Chrome hoặc Safari.");
        }
        const blob = new Blob(parts, { type: mime });
        if (!blob.size) {
            return null;
        }
        if (blob.size + TAG_BYTES > this.#maxBytes) {
            throw new Error(`Tin nhắn thoại quá lớn (tối đa ${mb(this.#maxBytes)}).`);
        }
        const bytes = new Uint8Array(await blob.arrayBuffer());
        return { kind: "AUDIO", mime, bytes, size: bytes.length, dur: Math.round(seconds * 10) / 10,
            previewUrl: URL.createObjectURL(blob), name: "Tin nhắn thoại", blob };
    }

    #stopTimer() {
        clearInterval(this.#timer);
        this.#timer = null;
    }

    /** Turns the microphone off (the browser's recording light goes out). */
    #release() {
        this.#stream?.getTracks().forEach((track) => track.stop());
        this.#stream = null;
        globalThis.removeEventListener?.("pagehide", this.#onPageHide);
    }
}

/**
 * The recorder bar shown instead of the message box while recording: timer, progress to the limit, "Hủy"
 * (`data-recorder-cancel`) and "Xong" (`data-recorder-stop`). Optional for messages.js; updateRecorder() refreshes it.
 */
export function recorderHtml(seconds, maxSeconds) {
    const max = maxSeconds || DEFAULT_LIMITS.audioMaxSeconds;
    const percent = Math.min(100, Math.max(0, (seconds / max) * 100));
    return `<div class="cm-rec" role="group" aria-label="Đang ghi âm"><span class="cm-rec-dot" aria-hidden="true"></span>`
        + `<span class="cm-rec-label">Đang ghi âm</span>`
        + `<span class="cm-rec-time" data-recorder-time>${formatDuration(seconds)}</span>`
        + `<span class="cm-rec-max">/ ${formatDuration(max)}</span>`
        + `<span class="cm-rec-bar" aria-hidden="true"><span data-recorder-bar style="width:${percent.toFixed(1)}%"></span></span>`
        + `<button type="button" class="btn sm cm-rec-cancel" data-recorder-cancel>${ic("delete")}<span>Hủy</span></button>`
        + `<button type="button" class="btn sm pri cm-rec-stop" data-recorder-stop>${ic("stop_circle")}<span>Xong</span></button></div>`;
}

/** Updates the timer of a bar made by recorderHtml without redrawing it (the focus stays on its buttons). */
export function updateRecorder(root, seconds, maxSeconds) {
    const max = maxSeconds || DEFAULT_LIMITS.audioMaxSeconds;
    const time = root?.querySelector("[data-recorder-time]");
    const bar = root?.querySelector("[data-recorder-bar]");
    if (time) time.textContent = formatDuration(seconds);
    if (bar) bar.style.width = `${Math.min(100, (seconds / max) * 100).toFixed(1)}%`;
}

// --------------------------------------------------------------------------------------------- chosen, not sent yet

/** Markup of the chosen attachments above the message box; each remove button has `data-remove-attachment="<index>"`. */
export function pendingHtml(items) {
    const list = [...(items || [])];
    if (!list.length) {
        return "";
    }
    const photos = list.filter((item) => item.kind === "IMAGE").length;
    const cells = list.map((item, i) => {
        const url = esc(item.previewUrl || "");
        if (item.kind === "IMAGE") {
            const label = photos > 1 ? `ảnh ${i + 1}` : "ảnh";
            return `<li class="cm-pend cm-pend-photo"><img src="${url}" alt="Ảnh ${i + 1} sẽ gửi">${removeButton(i, label)}</li>`;
        }
        if (item.kind === "VIDEO") {
            // "#t=0.1": Safari shows the first picture instead of a black box
            return `<li class="cm-pend cm-pend-video"><video src="${url}#t=0.1" muted playsinline preload="metadata" aria-label="Video sẽ gửi"></video>`
                + `<span class="cm-pend-dur">${ic("videocam")}${formatDuration(item.dur)}</span>${removeButton(i, "video")}</li>`;
        }
        return `<li class="cm-pend cm-pend-audio">${ic("mic")}<audio src="${url}" controls preload="metadata" aria-label="Nghe lại tin nhắn thoại"></audio>`
            + `<span class="cm-pend-dur">${formatDuration(item.dur)}</span>${removeButton(i, "tin nhắn thoại")}</li>`;
    });
    return `<ul class="cm-pending" aria-label="Tệp sẽ gửi kèm">${cells.join("")}</ul>`;
}

const removeButton = (index, label) => `<button type="button" class="cm-pend-x" data-remove-attachment="${index}" `
    + `title="Bỏ ${label}" aria-label="Bỏ ${label}">${ic("close")}</button>`;

/** Frees the preview of an attachment removed or sent (its bytes go with the item). */
export function releasePending(item) {
    if (item?.previewUrl?.startsWith("blob:")) {
        URL.revokeObjectURL(item.previewUrl);
    }
    if (item) item.previewUrl = null;
}

// -------------------------------------------------------------------------------------------------------- sending

/**
 * Encrypts and uploads the attachments one by one → `{ fileIds, files }`; `files` are exactly the `files` entries of
 * the message body (`{ id, kind, mime, size, key, iv, w?, h?, dur? }`). `onProgress(done, total)` after each file (and
 * (0, total) first). Fails with an ApiError (the server's status kept: 401 signed out, 429 day limit) whose message
 * names the file. Files uploaded before a failure stay unsent; the server removes them after a day.
 */
export async function uploadAttachments(conversationId, items, onProgress) {
    const list = [...(items || [])];
    const fileIds = [];
    const files = [];
    onProgress?.(0, list.length);
    for (const [index, item] of list.entries()) {
        try {
            if (!cryptoSupported()) {
                throw new CryptoUnavailableError();
            }
            const sealed = await encryptFile(item.bytes);
            const form = new FormData();
            form.append("kind", item.kind);
            form.append("file", new Blob([sealed.bytes], { type: "application/octet-stream" }), "chat.bin");
            const answer = await apiClient.uploadChatFile(conversationId, form);
            const id = Number(answer?.fileId);
            if (!Number.isSafeInteger(id) || id <= 0) {
                throw new Error("Máy chủ không trả về mã tệp.");
            }
            const ref = { id, kind: item.kind, mime: item.mime, size: item.size, key: sealed.key, iv: sealed.iv };
            for (const name of ["w", "h", "dur"]) {
                if (item[name] != null) ref[name] = item[name];
            }
            fileIds.push(id);
            files.push(ref);
            keepSentCopy(conversationId, ref, item);
        } catch (error) {
            throw new ApiError(`Không gửi được ${fileLabel(item, index, list)}: ${error?.message || "lỗi không rõ."}`,
                Number.isInteger(error?.status) ? error.status : null, error?.fields || {});
        }
        onProgress?.(index + 1, list.length);
    }
    return { fileIds, files };
}

/** "ảnh thứ 2 (IMG_0012.jpg)", "video", "tin nhắn thoại". */
function fileLabel(item, index, list) {
    if (item?.kind === "VIDEO") return "video";
    if (item?.kind === "AUDIO") return "tin nhắn thoại";
    const name = item?.name ? ` (${shortName(item.name)})` : "";
    return list.length > 1 ? `ảnh thứ ${index + 1}${name}` : `ảnh${name}`;
}

/** The sender's own bubble shows the sent file from memory instead of downloading and decrypting it again. */
function keepSentCopy(conversationId, ref, item) {
    const mime = allowedMime(ref.kind, ref.mime);
    if (!mime || typeof URL?.createObjectURL !== "function") return;
    const blob = item.blob ?? new Blob([item.bytes], { type: mime });
    const url = URL.createObjectURL(blob);
    madeUrls.add(url);
    cache.set(cacheKey(conversationId, ref), Promise.resolve(url));
}

// ------------------------------------------------------------------------------------------------------- showing

const cache = new Map();                                // "<conversation>:<file>:<key>" → Promise<object URL>
const madeUrls = new Set();                             // every object URL made here, revoked by releaseAttachments
let generation = 0;                                     // bumped by releaseAttachments: late downloads are dropped
let aborter = new AbortController();                    // aborted by releaseAttachments: downloads of the old one stop
let running = 0;
const queue = [];
const observers = new Map();                            // scroll container (or null = window) → IntersectionObserver
const watched = new Map();                              // placeholder → its job, until it scrolls into view
let viewer = null;

const cacheKey = (conversationId, file) => `${conversationId}:${file.id}:${file.key}`;

/** Width / height kept between 3:4 and 2:1, so one odd photo neither towers over the thread nor becomes a strip. */
function ratio(file, low, high) {
    if (!(file.w > 0 && file.h > 0)) return null;
    return Math.min(high, Math.max(low, file.w / file.h)).toFixed(4);
}

/**
 * Placeholders for a message's files (from its decrypted body): a photo grid, a video or a voice message, each with
 * `data-chat-file="<id>"`. hydrateAttachments() fills them in. No whitespace between tags: bubbles use pre-wrap.
 */
export function attachmentsHtml(files) {
    const list = (Array.isArray(files) ? files : []).filter((f) => f && Number.isSafeInteger(f.id) && SAFE_TYPES[f.kind]);
    if (!list.length) {
        return "";
    }
    const kind = list[0].kind;
    if (kind === "IMAGE") {
        const photos = list.filter((f) => f.kind === "IMAGE").slice(0, 5);
        const cells = photos.map((f, i) => {
            const r = photos.length === 1 ? ratio(f, 0.75, 2) : null;
            const style = r ? ` style="aspect-ratio:${r}"` : "";
            const label = photos.length > 1 ? `Ảnh ${i + 1}/${photos.length}` : "Ảnh";
            return `<div class="cm-photo" data-chat-file="${esc(f.id)}" data-kind="IMAGE" data-label="${esc(label)}"${style}>${waiting("ảnh")}</div>`;
        });
        return `<div class="cm-files cm-photos cm-n${photos.length}">${cells.join("")}</div>`;
    }
    const f = list[0];
    if (kind === "VIDEO") {
        const r = ratio(f, 0.5625, 2);
        const tall = r !== null && Number(r) < 1 ? " cm-tall" : "";
        const style = r ? ` style="aspect-ratio:${r}"` : "";
        return `<div class="cm-files"><div class="cm-video${tall}" data-chat-file="${esc(f.id)}" data-kind="VIDEO"${style}>${waiting("video")}</div></div>`;
    }
    return `<div class="cm-files"><div class="cm-audio" data-chat-file="${esc(f.id)}" data-kind="AUDIO">${ic("mic")}`
        + `<span class="cm-audio-slot">${waiting("tin nhắn thoại")}</span>`
        + `<span class="cm-audio-dur">${formatDuration(f.dur)}</span></div></div>`;
}

const waiting = (what) => `<span class="cm-wait"><span class="sr-only">Đang tải ${what}…</span></span>`;

/**
 * Downloads, decrypts and shows the files of the placeholders inside `root` (attachmentsHtml) whose ids are in
 * `files`. Each file is downloaded once per page: a redraw takes the cached object URL. Files load when they scroll
 * near the view; a photo click opens the viewer. Failures show "Không tải được tệp" (with "Thử lại" unless it is gone).
 */
export function hydrateAttachments(root, conversationId, files) {
    if (!root || typeof root.querySelectorAll !== "function") return;
    sweep();
    const byId = new Map();
    for (const f of Array.isArray(files) ? files : []) {
        if (f && Number.isSafeInteger(f.id)) byId.set(String(f.id), f);
    }
    const nodes = [...(root.matches?.("[data-chat-file]") ? [root] : []), ...root.querySelectorAll("[data-chat-file]")];
    let observer;                                       // found once per call: every placeholder shares the thread
    for (const node of nodes) {
        if (node.dataset.cmState) continue;              // already being loaded or shown
        const file = byId.get(node.dataset.chatFile);
        if (!file || file.kind !== node.dataset.kind) continue;
        const job = { conversationId, file };
        node.dataset.cmState = "waiting";
        const cached = cache.has(cacheKey(conversationId, file));
        if (!cached && observer === undefined) observer = observerFor(node);
        if (!cached && observer) {
            watched.set(node, job);
            observer.observe(node);
        } else {
            show(node, job);
        }
    }
}

/** Revokes every object URL of this module (messages.js calls it when leaving a conversation). */
export function releaseAttachments() {
    generation++;
    // Downloads still running (a 50 MB video) would keep the slots of the next conversation's photos busy
    aborter.abort();
    aborter = new AbortController();
    observers.forEach((observer) => observer.disconnect());
    observers.clear();
    watched.clear();
    queue.length = 0;
    cache.clear();
    if (viewer?.open) viewer.close();
    madeUrls.forEach((url) => URL.revokeObjectURL(url));
    madeUrls.clear();
}

/** Stops watching placeholders a redraw has removed. */
function sweep() {
    for (const [node] of watched) {
        if (!node.isConnected) {
            watched.delete(node);
            observers.forEach((observer) => observer.unobserve(node));
        }
    }
}

/** One IntersectionObserver per scrolling container (the thread), so files load shortly before they show. */
function observerFor(node) {
    if (typeof IntersectionObserver !== "function") return null;
    const container = scrollParent(node);
    let observer = observers.get(container);
    if (!observer) {
        observer = new IntersectionObserver((entries) => {
            for (const entry of entries) {
                if (!entry.isIntersecting) continue;
                observer.unobserve(entry.target);
                const job = watched.get(entry.target);
                watched.delete(entry.target);
                if (job && entry.target.isConnected) show(entry.target, job);
            }
        }, { root: container, rootMargin: "400px 0px" });
        observers.set(container, observer);
    }
    return observer;
}

function scrollParent(node) {
    for (let el = node.parentElement; el && el !== document.body; el = el.parentElement) {
        const overflow = getComputedStyle(el).overflowY;
        if (overflow === "auto" || overflow === "scroll") return el;
    }
    return null;
}

async function show(node, job) {
    node.dataset.cmState = "loading";
    node.classList.remove("cm-failed");
    try {
        const url = await fileUrl(job.conversationId, job.file);
        if (!node.isConnected) return;                  // redrawn meanwhile: the new placeholder takes the cached URL
        fill(node, job.file, url);
        node.dataset.cmState = "ready";
    } catch (error) {
        if (!node.isConnected || error?.released) return;
        failed(node, job, error);
    }
}

function fileUrl(conversationId, file) {
    const key = cacheKey(conversationId, file);
    let promise = cache.get(key);
    if (!promise) {
        const gen = generation;
        promise = limited(() => download(conversationId, file, gen));
        cache.set(key, promise);
        promise.catch(() => {
            if (cache.get(key) === promise) cache.delete(key);   // "Thử lại" downloads again
        });
    }
    return promise;
}

/** At most DOWNLOADS_AT_ONCE downloads at a time; the others wait their turn. */
function limited(task) {
    return new Promise((resolve, reject) => {
        const run = () => {
            running++;
            task().then(resolve, reject).finally(() => {
                running--;
                queue.shift()?.();
            });
        };
        if (running < DOWNLOADS_AT_ONCE) run(); else queue.push(run);
    });
}

async function download(conversationId, file, gen) {
    if (gen !== generation) throw released();
    const mime = allowedMime(file.kind, file.mime);
    if (!mime) {
        throw new Error("Loại tệp không hợp lệ.");
    }
    const signal = aborter.signal;
    let response;
    let bytes;
    try {
        response = await fetch(apiClient.chatFileUrl(conversationId, file.id), { credentials: "same-origin", signal });
        if (response.ok) bytes = new Uint8Array(await response.arrayBuffer());
    } catch {
        if (signal.aborted) throw released();
        throw new ApiError("Không kết nối được máy chủ.", 0);
    }
    if (!response.ok) {
        throw new ApiError("Không tải được tệp", response.status);
    }
    const plain = await decryptFile(bytes, file.key, file.iv);
    if (gen !== generation) throw released();
    const url = URL.createObjectURL(new Blob([plain], { type: mime }));   // only the checked type, never sniffed
    madeUrls.add(url);
    return url;
}

const released = () => Object.assign(new Error("released"), { released: true });

/** Puts the decrypted file into its placeholder. */
function fill(node, file, url) {
    if (file.kind === "IMAGE") {
        const open = document.createElement("button");
        open.type = "button";
        open.className = "cm-open";
        open.setAttribute("aria-label", `${node.dataset.label || "Ảnh"}: xem cỡ lớn`);
        const img = document.createElement("img");
        img.alt = "";
        img.decoding = "async";
        img.src = url;
        open.append(img);
        open.addEventListener("click", () => openViewer(node));
        node.replaceChildren(open);
        return;
    }
    if (file.kind === "VIDEO") {
        const video = document.createElement("video");
        video.controls = true;
        video.playsInline = true;
        video.preload = "metadata";
        video.setAttribute("playsinline", "");
        video.setAttribute("aria-label", "Video");
        sayWhenUnplayable(video);
        video.src = url;
        node.replaceChildren(video);
        return;
    }
    const audio = document.createElement("audio");
    audio.controls = true;
    audio.preload = "metadata";
    audio.setAttribute("aria-label", `Tin nhắn thoại, ${formatDuration(file.dur)}`);
    fixEndlessDuration(audio);
    sayWhenUnplayable(audio);
    audio.src = url;
    node.querySelector(".cm-audio-slot")?.replaceChildren(audio);
}

/**
 * A format this device cannot play (e.g. a WebM voice message recorded in Firefox, on an older iPhone) leaves a dead
 * player: say so under it instead.
 */
function sayWhenUnplayable(media) {
    media.addEventListener("error", () => {
        const code = media.error?.code;
        if (code !== 3 && code !== 4) return;           // MEDIA_ERR_DECODE, MEDIA_ERR_SRC_NOT_SUPPORTED
        const note = document.createElement("span");
        note.className = "cm-unplayable";
        note.setAttribute("role", "status");
        note.textContent = "Thiết bị này không phát được tệp này. Hãy thử mở trên trình duyệt hoặc máy khác.";
        media.after(note);
    }, { once: true });
}

/**
 * Chrome writes WebM recordings without a length, so the player shows no length and cannot seek until it has played
 * once: jumping far ahead makes it read the length, then it goes back to the start.
 */
function fixEndlessDuration(media) {
    media.addEventListener("loadedmetadata", () => {
        if (media.duration !== Infinity) return;
        media.addEventListener("timeupdate", () => {
            media.currentTime = 0;
        }, { once: true });
        media.currentTime = 1e101;
    }, { once: true });
}

function failed(node, job, error) {
    node.dataset.cmState = "failed";
    node.classList.add("cm-failed");
    const box = document.createElement("span");
    box.className = "cm-fail";
    box.innerHTML = `${ic(job.file.kind === "IMAGE" ? "broken_image" : "error")}<span>Không tải được tệp</span>`;
    // 404: recalled or deleted, trying again cannot help
    if (error?.status !== 404) {
        const retry = document.createElement("button");
        retry.type = "button";
        retry.className = "cm-retry";
        retry.textContent = "Thử lại";
        retry.addEventListener("click", () => {
            node.replaceChildren(...placeholderOf(job.file));
            show(node, job);
        });
        box.append(retry);
    }
    const slot = job.file.kind === "AUDIO" ? node.querySelector(".cm-audio-slot") : node;
    (slot || node).replaceChildren(box);
}

/** The loading state again, for "Thử lại". */
function placeholderOf(file) {
    const template = document.createElement("template");
    template.innerHTML = attachmentsHtml([file]);
    const fresh = template.content.querySelector("[data-chat-file]");
    return fresh ? [...fresh.childNodes] : [];
}

// --------------------------------------------------------------------------------------------------------- viewer

/** Full-size photo over the page; Esc, a click or "Đóng" closes it, arrows go through the photos of that message. */
function openViewer(cell) {
    const group = cell.closest(".cm-photos");
    const cells = group ? [...group.querySelectorAll('.cm-photo[data-cm-state="ready"]')] : [cell];
    const urls = cells.map((c) => c.querySelector("img")?.src).filter(Boolean);
    let index = Math.max(0, cells.indexOf(cell));
    if (!urls.length) return;
    const dialog = viewerDialog();
    const img = dialog.querySelector("img");
    const count = dialog.querySelector(".cm-viewer-count");
    const save = dialog.querySelector(".cm-viewer-save");
    const showAt = (i) => {
        index = (i + urls.length) % urls.length;
        img.src = urls[index];
        save.href = urls[index];
        prepareShare(save, urls[index]);
        count.textContent = urls.length > 1 ? `${index + 1} / ${urls.length}` : "";
    };
    dialog.querySelectorAll(".cm-viewer-nav").forEach((b) => { b.hidden = urls.length < 2; });
    dialog.cmStep = (step) => showAt(index + step);
    showAt(index);
    if (!dialog.open) {
        if (typeof dialog.showModal === "function") dialog.showModal(); else dialog.setAttribute("open", "");
    }
    dialog.querySelector(".cm-viewer-close").focus();
}

/**
 * On phones "Lưu ảnh" opens the share sheet ("Lưu hình ảnh" puts it in Photos; a download would only reach Files on an
 * iPhone). share() must run straight in the click, so the photo is made into a File as soon as it is shown; until
 * then, and where sharing files is not possible, the link downloads it.
 */
function prepareShare(save, url) {
    save.cmFile = null;
    save.cmUrl = url;
    if (!touchOnly() || typeof navigator.canShare !== "function") return;
    fetch(url).then((response) => response.blob()).then((blob) => {
        const ext = { "image/png": "png", "image/webp": "webp", "image/gif": "gif" }[blob.type] ?? "jpg";
        const file = new File([blob], `senpai-oi-anh.${ext}`, { type: blob.type || "image/jpeg" });
        if (save.cmUrl === url && navigator.canShare({ files: [file] })) save.cmFile = file;
    }).catch(() => {
        // the photo was released meanwhile: the link stays a download
    });
}

function viewerDialog() {
    if (viewer) return viewer;
    viewer = document.createElement("dialog");
    viewer.className = "cm-viewer";
    viewer.setAttribute("aria-label", "Xem ảnh");
    viewer.innerHTML = `<img alt="Ảnh cỡ lớn">`
        + `<button type="button" class="cm-viewer-nav prev" aria-label="Ảnh trước">${ic("chevron_left")}</button>`
        + `<button type="button" class="cm-viewer-nav next" aria-label="Ảnh sau">${ic("chevron_right")}</button>`
        + `<div class="cm-viewer-bar"><span class="cm-viewer-count"></span>`
        + `<a class="cm-viewer-save" download="senpai-oi-anh.jpg">${ic("download")}<span>Lưu ảnh</span></a>`
        + `<button type="button" class="cm-viewer-close">${ic("close")}<span>Đóng</span></button></div>`;
    const close = () => {
        if (!viewer.open) return;
        if (typeof viewer.close === "function") viewer.close(); else viewer.removeAttribute("open");
    };
    viewer.addEventListener("click", (event) => {
        const nav = event.target.closest(".cm-viewer-nav");
        if (nav) {
            viewer.cmStep?.(nav.classList.contains("prev") ? -1 : 1);
            return;
        }
        const save = event.target.closest(".cm-viewer-save");
        if (save) {                                                 // saving keeps the photo open
            if (save.cmFile) {
                event.preventDefault();
                navigator.share({ files: [save.cmFile] }).catch(() => {
                    // closed without choosing: nothing to do
                });
            }
            return;
        }
        close();
    });
    viewer.addEventListener("close", () => {
        viewer.querySelector(".cm-viewer-save").cmFile = null;
    });
    viewer.addEventListener("keydown", (event) => {
        if (event.key === "ArrowLeft") viewer.cmStep?.(-1);
        else if (event.key === "ArrowRight") viewer.cmStep?.(1);
        // a modal <dialog> closes on Esc by itself; the fallback without showModal() does not
        else if (event.key === "Escape" && typeof viewer.showModal !== "function") close();
    });
    document.body.append(viewer);
    return viewer;
}
