// Tests of the photo / video / voice-message helpers of "Tin nhắn" (assets/js/pages/chat-media.js) that run without a
// browser: limits and mixes, MIME rules, the recorder type, summaries, markup, the video path of prepareFiles and the
// encrypted upload (apiClient replaced by a fake). Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { apiClient, ApiError } from "../../main/resources/static/assets/js/api/client.js";
import { decryptFile } from "../../main/resources/static/assets/js/lib/chat-crypto.js";
import {
    allowedMime, ATTACH_ACCEPT, attachmentsHtml, audioMime, checkSelection, chooseRecorderMime, fileKind, fileSummary,
    formatDuration, pendingHtml, prepareFiles, recorderHtml, releasePending, uploadAttachments, VoiceRecorder,
    withDefaults
} from "../../main/resources/static/assets/js/pages/chat-media.js";

const MB = 1024 * 1024;
const LIMITS = { filesMax: 5, imageMaxBytes: 10 * MB, videoMaxBytes: 50 * MB, audioMaxBytes: 5 * MB,
    videoMaxSeconds: 30, audioMaxSeconds: 120 };
const photo = (name = "a.jpg", size = 1000, type = "image/jpeg") => ({ name, size, type });
const video = (name = "v.mp4", size = 1000, type = "video/mp4") => ({ name, size, type });

// ------------------------------------------------------------------------------------------------ minimal MP4 files

const ascii = (text) => Uint8Array.from(text, (c) => c.charCodeAt(0));
const zeros = (n) => new Uint8Array(n);

function concat(...parts) {
    const out = new Uint8Array(parts.reduce((sum, p) => sum + p.length, 0));
    let at = 0;
    for (const p of parts) {
        out.set(p, at);
        at += p.length;
    }
    return out;
}

function box(type, ...parts) {
    const body = concat(...parts);
    const out = new Uint8Array(8 + body.length);
    new DataView(out.buffer).setUint32(0, out.length);
    out.set(ascii(type), 4);
    out.set(body, 8);
    return out;
}

function mvhd(timescale, duration) {
    const body = zeros(100);
    const view = new DataView(body.buffer);
    view.setUint32(12, timescale);
    view.setUint32(16, duration);
    return box("mvhd", body);
}

/** A MOV-like file of `seconds` whose moov carries a location ("©xyz") the browser must remove before encrypting. */
const movie = (seconds) => concat(
    box("ftyp", ascii("qt  "), zeros(4), ascii("qt  ")),
    box("moov", mvhd(600, Math.round(seconds * 600)), box("udta", box("©xyz", ascii("+35.6895+139.6917/")))),
    box("mdat", ascii("FRAMES".repeat(20))));

const contains = (bytes, text) => Buffer.from(bytes).includes(Buffer.from(text, "latin1"));

// ------------------------------------------------------------------------------------------------------------ tests

test("limits: the conversation's values win, missing or broken ones fall back to the server defaults", () => {
    assert.deepEqual(withDefaults(undefined), LIMITS);
    assert.equal(withDefaults({ filesMax: 3 }).filesMax, 3);
    assert.equal(withDefaults({ filesMax: 0, videoMaxSeconds: "x" }).filesMax, 5);
    assert.equal(withDefaults({ videoMaxSeconds: "x" }).videoMaxSeconds, 30);
});

test("file kinds: by the browser's type first, by the extension only when the type is unknown", () => {
    assert.equal(fileKind(photo()), "IMAGE");
    assert.equal(fileKind(photo("IMG_1.HEIC", 1, "")), "IMAGE");
    assert.equal(fileKind(photo("x.heic", 1, "image/heic")), "IMAGE");
    assert.equal(fileKind(photo("shot.webp", 1, "image/webp")), "IMAGE");
    assert.equal(fileKind(video("clip.MOV", 1, "")), "VIDEO");
    assert.equal(fileKind(video("clip.mov", 1, "video/quicktime")), "VIDEO");
    assert.equal(fileKind(video("x.mp4", 1, "application/octet-stream")), "VIDEO");
    assert.equal(fileKind({ name: "a.gif", type: "image/gif", size: 1 }), null);
    assert.equal(fileKind({ name: "evil.jpg", type: "text/html", size: 1 }), null);   // the type decides
    assert.equal(fileKind({ name: "notes.pdf", type: "", size: 1 }), null);
    for (const part of ["image/jpeg", "image/png", "image/heic", "video/mp4", "video/quicktime", ".mov", ".heic"]) {
        assert.ok(ATTACH_ACCEPT.split(",").includes(part), part);
    }
});

test("selection: 1–5 photos, or one video; mixes, extra photos and unsupported files are refused whole", () => {
    assert.deepEqual(checkSelection([], LIMITS), { kind: null, error: null });
    assert.deepEqual(checkSelection([photo(), photo("b.png", 5, "image/png")], LIMITS), { kind: "IMAGE", error: null });
    assert.deepEqual(checkSelection([video()], LIMITS), { kind: "VIDEO", error: null });

    const mix = "Mỗi tin nhắn gửi được tối đa 5 ảnh, hoặc một video, hoặc một tin nhắn thoại.";
    assert.equal(checkSelection([photo(), video()], LIMITS).error, mix);
    assert.equal(checkSelection([video(), video("w.mov", 5, "video/quicktime")], LIMITS).error, mix);
    assert.equal(checkSelection([photo()], LIMITS, [{ kind: "AUDIO" }]).error, mix);
    assert.equal(checkSelection([video()], LIMITS, [{ kind: "IMAGE" }]).error, mix);
    assert.equal(checkSelection([video()], LIMITS, [{ kind: "VIDEO" }]).error, mix);

    const six = Array.from({ length: 6 }, (_, i) => photo(`${i}.jpg`));
    assert.equal(checkSelection(six, LIMITS).error, "Mỗi tin nhắn tối đa 5 ảnh.");
    assert.equal(checkSelection(six.slice(0, 3), LIMITS, [{ kind: "IMAGE" }, { kind: "IMAGE" }, { kind: "IMAGE" }]).error,
        "Mỗi tin nhắn tối đa 5 ảnh (bạn đã chọn 3 ảnh).");
    assert.equal(checkSelection(six.slice(0, 2), LIMITS, [{ kind: "IMAGE" }, { kind: "IMAGE" }, { kind: "IMAGE" }]).error, null);
    assert.equal(checkSelection(six.slice(0, 3), { ...LIMITS, filesMax: 2 }).error, "Mỗi tin nhắn tối đa 2 ảnh.");

    const odd = checkSelection([photo(), { name: "hợp đồng.pdf", type: "application/pdf", size: 1 }], LIMITS);
    assert.equal(odd.kind, null);
    assert.match(odd.error, /^Không gửi được “hợp đồng\.pdf”\. Chỉ gửi được ảnh JPG, PNG, HEIC hoặc video MP4, MOV\.$/);
});

test("selection: the video limit is on the encrypted size (16 bytes of tag more), huge photos are not opened", () => {
    assert.equal(checkSelection([video("v.mp4", 50 * MB - 16)], LIMITS).error, null);
    assert.match(checkSelection([video("v.mp4", 50 * MB - 15)], LIMITS).error, /^Video tối đa 50 MB\./);
    assert.match(checkSelection([video("v.mp4", 20 * MB)], { ...LIMITS, videoMaxBytes: 20 * MB }).error, /^Video tối đa 20 MB/);
    assert.equal(checkSelection([photo("big.png", 30 * MB, "image/png")], LIMITS).error, null);   // shrunk later
    assert.equal(checkSelection([photo("huge.png", 51 * MB, "image/png")], LIMITS).error,
        "Ảnh “huge.png” quá lớn (tối đa 50 MB).");
    const long = "a".repeat(80) + ".jpg";
    assert.ok(checkSelection([photo(long, 51 * MB)], LIMITS).error.includes("…"), "long names are shortened");
});

test("MIME rules: only known types of the kind, never HTML or SVG; odd parameters are dropped", () => {
    assert.equal(allowedMime("IMAGE", "image/jpeg"), "image/jpeg");
    assert.equal(allowedMime("AUDIO", "audio/webm;codecs=opus"), "audio/webm;codecs=opus");
    assert.equal(allowedMime("AUDIO", "audio/ogg; codecs=opus"), "audio/ogg; codecs=opus");
    assert.equal(allowedMime("AUDIO", "audio/mp4;codecs=<x>"), "audio/mp4");
    assert.equal(allowedMime("IMAGE", "image/svg+xml"), null);
    assert.equal(allowedMime("IMAGE", "text/html"), null);
    assert.equal(allowedMime("VIDEO", "image/jpeg"), null);
    assert.equal(allowedMime("FILE", "image/jpeg"), null);
    assert.equal(allowedMime("AUDIO", `audio/webm;${"x".repeat(120)}`), null);
    assert.equal(allowedMime("IMAGE", null), null);
});

test("recorder type: mp4/AAC first (plays on every iPhone), then webm/opus (Firefox), else the browser default; a throwing check is skipped", () => {
    assert.equal(chooseRecorderMime(() => true), "audio/mp4;codecs=mp4a.40.2");
    assert.equal(chooseRecorderMime((t) => t === "audio/mp4"), "audio/mp4");                   // Safari
    assert.equal(chooseRecorderMime((t) => t === "audio/webm;codecs=opus"), "audio/webm;codecs=opus");  // Firefox
    assert.equal(chooseRecorderMime(() => false), "");
    assert.equal(chooseRecorderMime((t) => {
        if (t.includes("mp4")) throw new Error("old browser");
        return true;
    }), "audio/webm;codecs=opus");

    assert.equal(audioMime("audio/webm;codecs=opus"), "audio/webm;codecs=opus");
    assert.equal(audioMime("audio/mp4;codecs=mp4a.40.2"), "audio/mp4;codecs=mp4a.40.2");
    assert.equal(audioMime("audio/ogg; codecs=opus"), "audio/ogg; codecs=opus");   // Firefox's default
    assert.equal(audioMime("video/webm;codecs=opus"), "audio/webm");
    assert.equal(audioMime("audio/x-m4a"), "audio/mp4");
    assert.equal(audioMime(""), null);
    assert.equal(audioMime(undefined), null);
    assert.equal(audioMime("text/html"), null);
});

test("VoiceRecorder needs MediaRecorder, a microphone API and a secure page: none of them in Node", async () => {
    assert.equal(VoiceRecorder.supported(), false);
    assert.match(VoiceRecorder.unavailableReason(), /ghi âm/i);
    const recorder = new VoiceRecorder({ maxSeconds: 120 });
    await assert.rejects(recorder.start(), (error) => error instanceof Error && /ghi âm/i.test(error.message));
    assert.equal(await recorder.stop(), null);
    recorder.cancel();                                  // harmless when nothing is recording
    assert.equal(recorder.recording, false);
});

// ------------------------------------------------------------------------- recorder with a fake microphone

const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A MediaRecorder that hands over one chunk a few ms after stop(), like browsers do; `all` keeps every instance. */
class FakeRecorder {
    static all = [];
    static isTypeSupported = (type) => type === "audio/webm;codecs=opus";

    constructor(stream, options = {}) {
        this.stream = stream;
        this.mimeType = options.mimeType ?? "";
        this.state = "inactive";
        FakeRecorder.all.push(this);
    }

    start() {
        this.state = "recording";
    }

    stop() {
        if (this.state === "inactive") return;
        this.state = "inactive";
        setTimeout(() => {
            this.ondataavailable?.({ data: new Blob([new Uint8Array(200)], { type: this.mimeType }) });
            this.onstop?.();
        }, 20);
    }
}

/** Installs the fakes; `run(mic)` gets the tracks handed out; everything is put back afterwards. */
async function withFakeMicrophone(run) {
    const tracks = [];
    const mediaDevices = {
        getUserMedia: async () => {
            const track = { stopped: false, listeners: [], stop() { this.stopped = true; },
                addEventListener(type, fn) { if (type === "ended") this.listeners.push(fn); } };
            tracks.push(track);
            return { getTracks: () => [track], getAudioTracks: () => [track] };
        }
    };
    const savedRecorder = globalThis.MediaRecorder;
    globalThis.MediaRecorder = FakeRecorder;
    Object.defineProperty(navigator, "mediaDevices", { value: mediaDevices, configurable: true });
    FakeRecorder.all = [];
    try {
        await run(tracks);
    } finally {
        delete navigator.mediaDevices;
        if (savedRecorder === undefined) delete globalThis.MediaRecorder; else globalThis.MediaRecorder = savedRecorder;
    }
}

test("VoiceRecorder: records, ticks, stops with an AUDIO attachment and releases the microphone", async () => {
    await withFakeMicrophone(async (tracks) => {
        assert.equal(VoiceRecorder.supported(), true);
        const ticks = [];
        const recorder = new VoiceRecorder({ maxSeconds: 120, onTick: (s) => ticks.push(s) });
        await recorder.start();
        assert.equal(recorder.recording, true);
        assert.equal(FakeRecorder.all[0].mimeType, "audio/webm;codecs=opus");
        await wait(1150);
        const clip = await recorder.stop();
        assert.equal(clip.kind, "AUDIO");
        assert.equal(clip.mime, "audio/webm;codecs=opus");
        assert.equal(clip.size, 200);
        assert.ok(clip.dur >= 1.1 && clip.dur < 2, String(clip.dur));
        assert.ok(clip.previewUrl.startsWith("blob:"));
        assert.deepEqual(ticks.slice(0, 2), [0, 1]);
        assert.equal(tracks[0].stopped, true, "the microphone is off");
        assert.equal(recorder.recording, false);
        assert.equal(await recorder.stop(), clip, "stop() again gives the same result");
        releasePending(clip);

        // a tap shorter than a second is no message
        await recorder.start();
        assert.equal(await recorder.stop(), null);
        assert.equal(tracks[1].stopped, true);
    });
});

test("VoiceRecorder: stops by itself at maxSeconds and when the microphone goes away", async () => {
    await withFakeMicrophone(async (tracks) => {
        let ended;
        const done = new Promise((resolve) => { ended = resolve; });
        const recorder = new VoiceRecorder({ maxSeconds: 1, onStop: (clip, error) => ended({ clip, error }) });
        await recorder.start();
        const { clip, error } = await done;
        assert.equal(error, undefined);
        assert.equal(clip.kind, "AUDIO");
        assert.ok(clip.dur <= 1, "never longer than the limit");
        assert.equal(tracks[0].stopped, true);
        assert.equal(await recorder.stop(), clip);

        let gone;
        const lost = new Promise((resolve) => { gone = resolve; });
        const second = new VoiceRecorder({ maxSeconds: 120, onStop: (c) => gone(c) });
        await second.start();
        await wait(1050);
        tracks[1].listeners.forEach((fn) => fn());      // unplugged
        const kept = await lost;
        assert.equal(kept.kind, "AUDIO", "what was recorded before is kept");
    });
});

test("VoiceRecorder: cancel gives null, frees the microphone, and a late stop event never ends the next recording", async () => {
    await withFakeMicrophone(async (tracks) => {
        const recorder = new VoiceRecorder({ maxSeconds: 120 });
        await recorder.start();
        const pending = recorder.stop();
        recorder.cancel();                              // before the recorder's own stop event arrives
        assert.equal(await pending, null);
        assert.equal(tracks[0].stopped, true);

        await recorder.start();
        recorder.cancel();
        await recorder.start();                         // at once again: the first one's stop event is still coming
        await wait(60);
        assert.equal(recorder.recording, true, "the late event of the cancelled recording was ignored");
        assert.equal(tracks[2].stopped, false);
        await wait(1000);
        const clip = await recorder.stop();
        assert.equal(clip.kind, "AUDIO");
        assert.equal(tracks[2].stopped, true);
    });
});

test("durations and list previews", () => {
    assert.equal(formatDuration(0), "0:00");
    assert.equal(formatDuration(5.4), "0:05");
    assert.equal(formatDuration(59.6), "1:00");
    assert.equal(formatDuration(120), "2:00");
    assert.equal(formatDuration(undefined), "0:00");

    const image = { id: 1, kind: "IMAGE" };
    assert.equal(fileSummary([image]), "📷 Ảnh");
    assert.equal(fileSummary([image, { ...image, id: 2 }, { ...image, id: 3 }]), "📷 3 ảnh");
    assert.equal(fileSummary([{ id: 4, kind: "VIDEO" }]), "🎬 Video");
    assert.equal(fileSummary([{ id: 5, kind: "AUDIO" }]), "🎤 Tin nhắn thoại");
    assert.equal(fileSummary([]), "");
    assert.equal(fileSummary(null), "");
});

test("message markup: placeholders by id and kind, grid class by count, no whitespace between tags", () => {
    assert.equal(attachmentsHtml([]), "");
    assert.equal(attachmentsHtml(undefined), "");
    const photos = [1, 2, 3].map((id) => ({ id, kind: "IMAGE", mime: "image/jpeg", w: 1600, h: 1200 }));
    const grid = attachmentsHtml(photos);
    assert.match(grid, /^<div class="cm-files cm-photos cm-n3">/);
    assert.deepEqual([...grid.matchAll(/data-chat-file="(\d+)"/g)].map((m) => m[1]), ["1", "2", "3"]);
    assert.ok(!/>\s+</.test(grid), "bubbles use white-space: pre-wrap");
    assert.ok(!grid.includes("aspect-ratio"), "several photos are square cells");

    // one photo keeps its shape, kept between 3:4 and 2:1
    assert.match(attachmentsHtml([{ id: 9, kind: "IMAGE", w: 1600, h: 1200 }]), /aspect-ratio:1\.3333/);
    assert.match(attachmentsHtml([{ id: 9, kind: "IMAGE", w: 100, h: 1000 }]), /aspect-ratio:0\.7500/);
    assert.match(attachmentsHtml([{ id: 9, kind: "IMAGE", w: 5000, h: 100 }]), /aspect-ratio:2\.0000/);

    const clip = attachmentsHtml([{ id: 7, kind: "VIDEO", w: 1080, h: 1920, dur: 12 }]);
    assert.match(clip, /class="cm-video cm-tall" data-chat-file="7" data-kind="VIDEO"/);
    const voice = attachmentsHtml([{ id: 8, kind: "AUDIO", dur: 65 }]);
    assert.match(voice, /data-chat-file="8" data-kind="AUDIO"/);
    assert.ok(voice.includes("1:05"));
    assert.ok(!/>\s+</.test(clip + voice));

    // ids that are not whole numbers and unknown kinds never reach the markup
    assert.equal(attachmentsHtml([{ id: "1\"><img src=x onerror=alert(1)>", kind: "IMAGE" }]), "");
    assert.equal(attachmentsHtml([{ id: 3, kind: "SCRIPT" }]), "");
});

test("pending markup: one remove button per item with its index; values are escaped", () => {
    assert.equal(pendingHtml([]), "");
    const items = [
        { kind: "IMAGE", previewUrl: "blob:x/1" },
        { kind: "IMAGE", previewUrl: 'blob:x/2"><script>' }
    ];
    const html = pendingHtml(items);
    assert.deepEqual([...html.matchAll(/data-remove-attachment="(\d+)"/g)].map((m) => m[1]), ["0", "1"]);
    assert.ok(!html.includes("<script>"));
    assert.ok(html.includes("&quot;&gt;&lt;script&gt;"));
    assert.ok(html.includes('aria-label="Bỏ ảnh 2"'));
    assert.match(pendingHtml([{ kind: "VIDEO", previewUrl: "blob:v", dur: 12 }]), /src="blob:v#t=0\.1"[^>]*>.*0:12/);
    assert.match(pendingHtml([{ kind: "AUDIO", previewUrl: "blob:a", dur: 3 }]), /<audio src="blob:a" controls/);
    assert.match(recorderHtml(12, 120), /data-recorder-cancel.*data-recorder-stop/);
    assert.ok(recorderHtml(12, 120).includes("0:12") && recorderHtml(12, 120).includes("2:00"));
});

test("prepareFiles: a video is checked for length and loses its metadata; unreadable files get a clear message", async () => {
    const original = movie(12);
    const file = new File([original], "IMG_0042.MOV", { type: "video/quicktime" });
    const { items, error } = await prepareFiles([file], LIMITS);
    assert.equal(error, null);
    assert.equal(items.length, 1);
    const [item] = items;
    assert.equal(item.kind, "VIDEO");
    assert.equal(item.mime, "video/mp4");
    assert.equal(item.dur, 12);
    assert.equal(item.size, original.length);
    assert.equal(item.bytes.length, original.length);
    assert.ok(contains(original, "+35.6895+139.6917/"));
    assert.ok(!contains(item.bytes, "+35.6895+139.6917/"), "the place it was filmed is gone");
    assert.ok(contains(item.bytes, "FRAMES"));
    assert.ok(item.previewUrl.startsWith("blob:"));
    releasePending(item);
    assert.equal(item.previewUrl, null);

    const slack = await prepareFiles([new File([movie(30.4)], "a.mp4", { type: "video/mp4" })], LIMITS);
    assert.equal(slack.error, null, "30.0x seconds of a phone's 30 s clip are accepted");
    slack.items.forEach(releasePending);

    const long = await prepareFiles([new File([movie(31)], "a.mp4", { type: "video/mp4" })], LIMITS);
    assert.deepEqual(long, { items: [], error: "Video dài 31 giây. Video tối đa 30 giây." });

    const broken = await prepareFiles([new File([ascii("not a video at all")], "x.mp4", { type: "video/mp4" })], LIMITS);
    assert.equal(broken.items.length, 0);
    assert.match(broken.error, /^Không đọc được video “x\.mp4”/);

    // No canvas here, as in a browser that cannot open HEIC: the photo is left out with media-files' message
    const heic = await prepareFiles([new File([zeros(10)], "IMG_1.HEIC", { type: "image/heic" })], LIMITS);
    assert.equal(heic.items.length, 0);
    assert.match(heic.error, /HEIC/);
});

test("uploadAttachments: each file encrypted with its own key, uploaded as kind + file, body entries returned", async () => {
    const sent = [];
    const saved = apiClient.uploadChatFile;
    apiClient.uploadChatFile = async (conversationId, form) => {
        const file = form.get("file");
        sent.push({ conversationId, kind: form.get("kind"), type: file.type,
            bytes: new Uint8Array(await file.arrayBuffer()) });
        return { fileId: 100 + sent.length };
    };
    try {
        const one = Uint8Array.from([1, 2, 3, 4, 5]);
        const two = Uint8Array.from([9, 8, 7]);
        const items = [
            { kind: "IMAGE", mime: "image/jpeg", bytes: one, size: one.length, w: 1600, h: 900, name: "a.jpg" },
            { kind: "IMAGE", mime: "image/jpeg", bytes: two, size: two.length, name: "b.jpg" }
        ];
        const progress = [];
        const result = await uploadAttachments(42, items, (done, total) => progress.push([done, total]));
        assert.deepEqual(progress, [[0, 2], [1, 2], [2, 2]]);
        assert.deepEqual(result.fileIds, [101, 102]);
        assert.equal(sent.length, 2);
        assert.deepEqual(sent.map((s) => [s.conversationId, s.kind, s.type]),
            [[42, "IMAGE", "application/octet-stream"], [42, "IMAGE", "application/octet-stream"]]);
        assert.equal(sent[0].bytes.length, one.length + 16, "ciphertext ‖ 16-byte tag");
        assert.ok(!Buffer.from(sent[0].bytes).includes(Buffer.from(one)), "never the clear bytes");

        const [a, b] = result.files;
        assert.deepEqual(Object.keys(a).sort(), ["h", "id", "iv", "key", "kind", "mime", "size", "w"]);
        assert.deepEqual(Object.keys(b).sort(), ["id", "iv", "key", "kind", "mime", "size"]);
        assert.equal(a.id, 101);
        assert.equal(a.size, 5);
        assert.notEqual(a.key, b.key);
        assert.deepEqual(await decryptFile(sent[0].bytes, a.key, a.iv), one);
        assert.deepEqual(await decryptFile(sent[1].bytes, b.key, b.iv), two);
    } finally {
        apiClient.uploadChatFile = saved;
    }
});

test("uploadAttachments: a refused file fails with the server's status and names the file", async () => {
    const saved = apiClient.uploadChatFile;
    let calls = 0;
    apiClient.uploadChatFile = async () => {
        calls++;
        if (calls === 2) throw new ApiError("Bạn đã gửi đủ 100 tệp hôm nay. Vui lòng thử lại vào ngày mai.", 429);
        return { fileId: calls };
    };
    try {
        const bytes = Uint8Array.from([1]);
        const items = [1, 2, 3].map((i) => ({ kind: "IMAGE", mime: "image/jpeg", bytes, size: 1, name: `IMG_${i}.jpg` }));
        await assert.rejects(uploadAttachments(7, items), (error) => {
            assert.ok(error instanceof ApiError);
            assert.equal(error.status, 429);
            assert.equal(error.message,
                "Không gửi được ảnh thứ 2 (IMG_2.jpg): Bạn đã gửi đủ 100 tệp hôm nay. Vui lòng thử lại vào ngày mai.");
            return true;
        });
        assert.equal(calls, 2, "stops at the first failure");

        apiClient.uploadChatFile = async () => ({});
        await assert.rejects(uploadAttachments(7, [{ kind: "AUDIO", mime: "audio/webm", bytes, size: 1 }]),
            /^ApiError: Không gửi được tin nhắn thoại: Máy chủ không trả về mã tệp\.$|Không gửi được tin nhắn thoại/);
    } finally {
        apiClient.uploadChatFile = saved;
    }
});
