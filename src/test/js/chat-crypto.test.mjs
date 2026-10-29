// Tests of the "Tin nhắn" browser encryption (assets/js/lib/chat-crypto.js) in Node's WebCrypto.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { createHash, createHmac } from "node:crypto";
import {
    cryptoSupported, WRAP_ITERATIONS, PASSPHRASE_MIN_LENGTH, MAX_CIPHERTEXT_BYTES,
    createIdentity, unlockIdentity, saveLocalIdentity, loadLocalIdentity, forgetLocalIdentity, setKeyStore,
    conversationKey, encryptMessage, decryptMessage, encryptFile, decryptFile, safetyNumber, frankingTag,
    toBase64, fromBase64,
    WrongPassphraseError, UnreadableMessageError, CryptoUnavailableError, KeyStorageError
} from "../../main/resources/static/assets/js/lib/chat-crypto.js";

const utf8 = (text) => new TextEncoder().encode(text);

// Contract §4 shared vector (the Java test reproduces the same tag).
const VECTOR = {
    fk: "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=",
    plaintext: "eyJ2IjoxLCJ0ZXh0IjoiWGluIGNow6BvIn0=",
    conversationId: 42,
    senderId: 7,
    tag: "78d4be3ee84351509e736a57ae1fd15ac9a7890e456f08197b931a5b2ac980f0"
};

// Two members talking in conversation 5: Lan (user 7, key 11) and Minh (user 9, key 12). PBKDF2 at 600000 rounds is
// slow, so the identities are made once.
const CONV = 5;
const LAN = { userId: 7, keyId: 11, ...(await createIdentity("Mật khẩu của Lan")) };
const MINH = { userId: 9, keyId: 12, ...(await createIdentity("minh-passphrase-2026")) };
const keyLan = await conversationKey(LAN.privateKey, MINH.publicKey, CONV, LAN.keyId, MINH.keyId);
const keyMinh = await conversationKey(MINH.privateKey, LAN.publicKey, CONV, MINH.keyId, LAN.keyId);

const fromLan = (body, key = keyLan) => encryptMessage({ key, conversationId: CONV, senderId: LAN.userId,
    senderKeyId: LAN.keyId, recipientKeyId: MINH.keyId, body });
const readAsMinh = (envelope, extra = {}) => decryptMessage({ key: keyMinh, conversationId: CONV,
    senderId: LAN.userId, envelope, ...extra });

/** Seals any P with the conversation key (bypassing encryptMessage's checks), with a correct franking tag. */
async function sealRaw(plaintext) {
    const fk = crypto.getRandomValues(new Uint8Array(32));
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const sealed = new Uint8Array(fk.length + plaintext.length);
    sealed.set(fk);
    sealed.set(plaintext, fk.length);
    const ciphertext = new Uint8Array(await crypto.subtle.encrypt(
        { name: "AES-GCM", iv, additionalData: utf8(`${CONV}|${LAN.userId}|${LAN.keyId}|${MINH.keyId}`) }, keyLan, sealed));
    return { ciphertext: toBase64(ciphertext), iv: toBase64(iv), senderKeyId: LAN.keyId, recipientKeyId: MINH.keyId,
        frankingTag: await frankingTag(fk, CONV, LAN.userId, plaintext) };
}

/** A copy of base64 data with one bit flipped at `index`. */
function flipped(base64, index = 0) {
    const bytes = fromBase64(base64);
    bytes[index] ^= 0x01;
    return toBase64(bytes);
}

const fileEntry = (over = {}) => ({ id: 31, kind: "IMAGE", mime: "image/jpeg", size: 1234,
    key: toBase64(new Uint8Array(32).fill(3)), iv: toBase64(new Uint8Array(12).fill(4)), w: 1600, h: 1200, ...over });

test("franking tag reproduces the shared contract vector byte for byte", async () => {
    assert.equal(toBase64(utf8(JSON.stringify({ v: 1, text: "Xin chào" }))), VECTOR.plaintext);
    assert.deepEqual([...fromBase64(VECTOR.fk)], Array.from({ length: 32 }, (_, i) => i + 1));
    assert.equal(await frankingTag(VECTOR.fk, 42, 7, fromBase64(VECTOR.plaintext)), VECTOR.tag);
    assert.equal(await frankingTag(fromBase64(VECTOR.fk), "42", "7", VECTOR.plaintext), VECTOR.tag);
    // independent implementation: HMAC-SHA256(fk, "asvsj-frank-v1|42|7|" ‖ P)
    const expected = createHmac("sha256", Buffer.from(VECTOR.fk, "base64"))
        .update(Buffer.concat([Buffer.from("asvsj-frank-v1|42|7|"), Buffer.from(VECTOR.plaintext, "base64")]))
        .digest("hex");
    assert.equal(expected, VECTOR.tag);
});

test("encryptMessage writes P exactly as the vector's body", async () => {
    const sent = await encryptMessage({ key: keyLan, conversationId: 42, senderId: 7, senderKeyId: LAN.keyId,
        recipientKeyId: MINH.keyId, body: { text: "Xin chào" } });
    assert.equal(sent.plaintext, VECTOR.plaintext);
    assert.equal(await frankingTag(sent.frankingKey, 42, 7, sent.plaintext), sent.envelope.frankingTag);
});

test("base64 helpers are standard base64 with padding and strict", () => {
    const bytes = Uint8Array.from({ length: 70000 }, (_, i) => (i * 7) & 0xFF);
    assert.equal(toBase64(bytes), Buffer.from(bytes).toString("base64"));
    assert.deepEqual(fromBase64(toBase64(bytes)), bytes);
    assert.equal(toBase64(new Uint8Array([0xFB, 0xFF])), "+/8=");
    assert.equal(toBase64(new Uint8Array(0)), "");
    for (const bad of ["AQ", "AQ=", "AQI", "-_8=", "AQ ID", "AQ==\n", "====", null, 5]) {
        assert.throws(() => fromBase64(bad), TypeError, String(bad));
    }
});

test("cryptoSupported is true in Node and false outside a secure context", async () => {
    assert.equal(cryptoSupported(), true);
    globalThis.isSecureContext = false;                 // what http://192.168.1.32:8090 looks like
    try {
        assert.equal(cryptoSupported(), false);
        await assert.rejects(createIdentity("long enough phrase"), CryptoUnavailableError);
        await assert.rejects(frankingTag(VECTOR.fk, 42, 7, VECTOR.plaintext), CryptoUnavailableError);
    } finally {
        delete globalThis.isSecureContext;
    }
    // older or odd browsers: crypto without subtle
    const real = Object.getOwnPropertyDescriptor(globalThis, "crypto");
    const webCrypto = globalThis.crypto;
    const random = (array) => webCrypto.getRandomValues(array);
    Object.defineProperty(globalThis, "crypto", { value: { getRandomValues: random }, configurable: true });
    try {
        assert.equal(cryptoSupported(), false);
        await assert.rejects(encryptFile(new Uint8Array(4)), CryptoUnavailableError);
    } finally {
        Object.defineProperty(globalThis, "crypto", real);
    }
    assert.equal(cryptoSupported(), true);
});

test("identity: 65-byte public key starting 0x04, backup within the server limits, non-extractable key", () => {
    for (const member of [LAN, MINH]) {
        const point = fromBase64(member.publicKey);
        assert.equal(point.length, 65);
        assert.equal(point[0], 0x04);
        assert.equal(member.backup.wrapIterations, WRAP_ITERATIONS);
        assert.equal(WRAP_ITERATIONS, 600000);
        assert.equal(fromBase64(member.backup.wrapSalt).length, 16);
        assert.equal(fromBase64(member.backup.wrapIv).length, 12);
        const wrapped = fromBase64(member.backup.wrappedPrivateKey).length;
        assert.ok(wrapped >= 16 && wrapped <= 600, `wrapped ${wrapped}`);
        assert.equal(member.privateKey.type, "private");
        assert.equal(member.privateKey.extractable, false);
    }
    assert.notEqual(LAN.publicKey, MINH.publicKey);
});

test("identity: the right passphrase unlocks the backup, the wrong one throws WrongPassphraseError", async () => {
    const backup = { id: LAN.keyId, publicKey: LAN.publicKey, ...LAN.backup };
    const unlocked = await unlockIdentity(backup, "Mật khẩu của Lan");
    assert.equal(unlocked.extractable, false);
    assert.equal(unlocked.type, "private");
    // the unlocked key is the same key: it derives the same conversation key as the original
    const again = await conversationKey(unlocked, MINH.publicKey, CONV, LAN.keyId, MINH.keyId);
    const sent = await fromLan({ text: "Khóa mở lại" }, again);
    assert.equal((await readAsMinh(sent.envelope)).body.text, "Khóa mở lại");

    await assert.rejects(unlockIdentity(backup, "Mật khẩu của lan"), WrongPassphraseError);
    await assert.rejects(unlockIdentity(backup, ""), WrongPassphraseError);
    // a backup that belongs to another public key, or broken fields, are refused
    await assert.rejects(unlockIdentity({ ...backup, publicKey: MINH.publicKey }, "Mật khẩu của Lan"), TypeError);
    await assert.rejects(unlockIdentity({ ...backup, wrapIv: toBase64(new Uint8Array(8)) }, "x"), TypeError);
    await assert.rejects(unlockIdentity({ ...backup, wrapIterations: 1e9 }, "x"), TypeError);
});

test("identity: the passphrase is NFC-normalised (composed and decomposed typing unlock the same key)", async () => {
    const composed = "Phở bò ngon lắm".normalize("NFC");
    const decomposed = composed.normalize("NFD");
    assert.notEqual(composed, decomposed);
    const made = await createIdentity(decomposed);
    const unlocked = await unlockIdentity(made.backup, composed);
    const key1 = await conversationKey(made.privateKey, MINH.publicKey, 1, 100, 12);
    const key2 = await conversationKey(unlocked, MINH.publicKey, 1, 100, 12);
    const sent = await encryptMessage({ key: key1, conversationId: 1, senderId: 3, senderKeyId: 100,
        recipientKeyId: 12, body: { text: "NFC" } });
    const read = await decryptMessage({ key: key2, conversationId: 1, senderId: 3, envelope: sent.envelope });
    assert.equal(read.body.text, "NFC");
});

test("identity: a passphrase shorter than 8 characters is refused", async () => {
    assert.equal(PASSPHRASE_MIN_LENGTH, 8);
    await assert.rejects(createIdentity("1234567"), RangeError);
    await assert.rejects(createIdentity(12345678), TypeError);
});

test("conversation key: Lan→Minh and Minh→Lan derive the same key, other ids a different one", async () => {
    const toMinh = await fromLan({ text: "Chào Minh" });
    assert.equal((await readAsMinh(toMinh.envelope)).body.text, "Chào Minh");
    const toLan = await encryptMessage({ key: keyMinh, conversationId: CONV, senderId: MINH.userId,
        senderKeyId: MINH.keyId, recipientKeyId: LAN.keyId, body: { text: "Chào Lan" } });
    const read = await decryptMessage({ key: keyLan, conversationId: CONV, senderId: MINH.userId,
        envelope: toLan.envelope });
    assert.equal(read.body.text, "Chào Lan");
    // the same keys in another conversation, or another key pair, give another key
    const otherConv = await conversationKey(MINH.privateKey, LAN.publicKey, CONV + 1, MINH.keyId, LAN.keyId);
    await assert.rejects(readAsMinh(toMinh.envelope, { key: otherConv }), UnreadableMessageError);
    const otherIds = await conversationKey(MINH.privateKey, LAN.publicKey, CONV, MINH.keyId, 99);
    await assert.rejects(readAsMinh(toMinh.envelope, { key: otherIds }), UnreadableMessageError);
    // invalid peer keys
    await assert.rejects(conversationKey(LAN.privateKey, "AAAA", CONV, 1, 2), TypeError);
    const offCurve = fromBase64(MINH.publicKey);
    offCurve[64] ^= 1;
    await assert.rejects(conversationKey(LAN.privateKey, toBase64(offCurve), CONV, 1, 2), TypeError);
});

test("messages: text and a body with files round-trip, envelope as the server expects", async () => {
    const sent = await fromLan({ text: "Ảnh hôm qua 🌸" });
    const { envelope } = sent;
    assert.deepEqual(Object.keys(envelope).sort(), ["ciphertext", "frankingTag", "iv", "recipientKeyId", "senderKeyId"]);
    assert.equal(fromBase64(envelope.iv).length, 12);
    assert.match(envelope.frankingTag, /^[0-9a-f]{64}$/);
    assert.equal(envelope.senderKeyId, LAN.keyId);
    assert.equal(envelope.recipientKeyId, MINH.keyId);
    // ciphertext = AES-GCM(fk ‖ P) incl. the 16-byte tag
    assert.equal(fromBase64(envelope.ciphertext).length, 32 + fromBase64(sent.plaintext).length + 16);
    assert.deepEqual((await readAsMinh(envelope)).body, { text: "Ảnh hôm qua 🌸", files: [] });

    const files = [fileEntry(), fileEntry({ id: 32, mime: "image/png", w: undefined, h: undefined })];
    const withFiles = await fromLan({ text: "", files });
    const read = await readAsMinh(withFiles.envelope);
    assert.equal(read.body.text, null);
    assert.deepEqual(read.body.files, [fileEntry(), { id: 32, kind: "IMAGE", mime: "image/png", size: 1234,
        key: fileEntry().key, iv: fileEntry().iv }]);
    const voice = await fromLan({ files: [fileEntry({ kind: "AUDIO", mime: "audio/webm;codecs=opus", w: undefined,
        h: undefined, dur: 12.5 })] });
    assert.equal((await readAsMinh(voice.envelope)).body.files[0].dur, 12.5);
});

test("messages: malformed bodies are refused when writing", async () => {
    await assert.rejects(fromLan({}), TypeError);
    await assert.rejects(fromLan({ text: "" }), TypeError);
    await assert.rejects(fromLan({ text: 5 }), TypeError);
    await assert.rejects(fromLan({ files: [fileEntry({ mime: "image/svg+xml" })] }), TypeError);
    await assert.rejects(fromLan({ files: [fileEntry({ kind: "VIDEO" })] }), TypeError);        // image mime
    await assert.rejects(fromLan({ files: [fileEntry({ key: "AAAA" })] }), TypeError);
    await assert.rejects(fromLan({ files: [fileEntry(), fileEntry({ kind: "VIDEO", mime: "video/mp4" })] }), TypeError);
    await assert.rejects(fromLan({ files: Array.from({ length: 6 }, (_, i) => fileEntry({ id: i + 1 })) }), TypeError);
    await assert.rejects(fromLan({ text: "x".repeat(MAX_CIPHERTEXT_BYTES) }), RangeError);
    await assert.rejects(encryptMessage({ key: keyLan, conversationId: "abc", senderId: 7, senderKeyId: 1,
        recipientKeyId: 2, body: { text: "x" } }), TypeError);
});

test("messages: any change to ciphertext, iv, AAD inputs or franking tag makes it unreadable", async () => {
    const { envelope } = await fromLan({ text: "Đừng sửa tôi" });
    const tampered = {
        ciphertext: { ...envelope, ciphertext: flipped(envelope.ciphertext, 3) },
        gcmTag: { ...envelope, ciphertext: flipped(envelope.ciphertext, fromBase64(envelope.ciphertext).length - 1) },
        iv: { ...envelope, iv: flipped(envelope.iv) },
        shortIv: { ...envelope, iv: toBase64(new Uint8Array(8)) },
        senderKeyId: { ...envelope, senderKeyId: LAN.keyId + 100 },
        recipientKeyId: { ...envelope, recipientKeyId: MINH.keyId + 100 },
        frankingTag: { ...envelope, frankingTag: envelope.frankingTag.replace(/^./, (c) => (c === "0" ? "1" : "0")) },
        upperTag: { ...envelope, frankingTag: envelope.frankingTag.toUpperCase() },
        noTag: { ...envelope, frankingTag: undefined },
        notBase64: { ...envelope, ciphertext: "***" }
    };
    if (tampered.upperTag.frankingTag === envelope.frankingTag) {
        delete tampered.upperTag;                       // digits only: nothing to upper-case
    }
    for (const [name, bad] of Object.entries(tampered)) {
        await assert.rejects(readAsMinh(bad), UnreadableMessageError, name);
    }
    await assert.rejects(readAsMinh(envelope, { conversationId: CONV + 1 }), UnreadableMessageError);
    await assert.rejects(readAsMinh(envelope, { senderId: MINH.userId }), UnreadableMessageError);
    await assert.rejects(readAsMinh(null), UnreadableMessageError);
    // the original still reads
    assert.equal((await readAsMinh(envelope)).body.text, "Đừng sửa tôi");
});

test("messages: a peer's body that is not a valid v1 body is unreadable", async () => {
    assert.equal((await readAsMinh(await sealRaw(utf8('{"v":1,"text":"ok"}')))).body.text, "ok");
    const bad = ['{"v":2,"text":"later"}', '{"text":"no version"}', "not json", '{"v":1,"text":7}', "[1]",
        JSON.stringify({ v: 1, files: [fileEntry({ mime: "text/html" })] }),
        JSON.stringify({ v: 1, files: [fileEntry({ kind: "constructor" })] }),
        JSON.stringify({ v: 1, files: [fileEntry({ size: -1 })] }),
        JSON.stringify({ v: 1, files: "x" })];
    for (const text of bad) {
        await assert.rejects(readAsMinh(await sealRaw(utf8(text))), UnreadableMessageError, text);
    }
    await assert.rejects(readAsMinh(await sealRaw(new Uint8Array([0x7B, 0xFF, 0x7D]))), UnreadableMessageError);
});

test("messages: plaintext and frankingKey from decrypt reproduce the stored tag (what a report sends)", async () => {
    const sent = await fromLan({ text: "Bán tài khoản ngân hàng, liên hệ…" });
    const read = await readAsMinh(sent.envelope);
    assert.equal(read.plaintext, sent.plaintext);
    assert.equal(read.frankingKey, sent.frankingKey);
    assert.equal(fromBase64(read.frankingKey).length, 32);
    // the server's check: HMAC-SHA256(fk, "asvsj-frank-v1|conv|sender|" ‖ P) == stored tag
    const serverSide = createHmac("sha256", Buffer.from(read.frankingKey, "base64"))
        .update(Buffer.concat([Buffer.from(`asvsj-frank-v1|${CONV}|${LAN.userId}|`),
            Buffer.from(read.plaintext, "base64")]))
        .digest("hex");
    assert.equal(serverSide, sent.envelope.frankingTag);
    assert.equal(await frankingTag(read.frankingKey, CONV, LAN.userId, fromBase64(read.plaintext)),
        sent.envelope.frankingTag);
    assert.equal(JSON.parse(Buffer.from(read.plaintext, "base64").toString("utf8")).text,
        "Bán tài khoản ngân hàng, liên hệ…");
    // a reporter cannot claim another text with the same key
    assert.notEqual(await frankingTag(read.frankingKey, CONV, LAN.userId, utf8('{"v":1,"text":"khác"}')),
        sent.envelope.frankingTag);
});

test("files: round trip, tampering and wrong key/iv are unreadable", async () => {
    const original = Uint8Array.from({ length: 100000 }, (_, i) => (i * 31) & 0xFF);
    const sealed = await encryptFile(original);
    assert.equal(sealed.bytes.length, original.length + 16);
    assert.equal(fromBase64(sealed.key).length, 32);
    assert.equal(fromBase64(sealed.iv).length, 12);
    assert.deepEqual(await decryptFile(sealed.bytes, sealed.key, sealed.iv), original);
    assert.deepEqual(await decryptFile(sealed.bytes.buffer, sealed.key, sealed.iv), original);
    assert.deepEqual(await decryptFile(new Blob([sealed.bytes]), sealed.key, sealed.iv), original);
    const fromBlob = await encryptFile(new Blob([original]));
    assert.deepEqual(await decryptFile(fromBlob.bytes, fromBlob.key, fromBlob.iv), original);

    const changed = sealed.bytes.slice();
    changed[500] ^= 0x80;
    await assert.rejects(decryptFile(changed, sealed.key, sealed.iv), UnreadableMessageError);
    await assert.rejects(decryptFile(sealed.bytes.slice(0, -1), sealed.key, sealed.iv), UnreadableMessageError);
    await assert.rejects(decryptFile(sealed.bytes, flipped(sealed.key), sealed.iv), UnreadableMessageError);
    await assert.rejects(decryptFile(sealed.bytes, sealed.key, flipped(sealed.iv)), UnreadableMessageError);
    await assert.rejects(decryptFile(sealed.bytes, "short", sealed.iv), UnreadableMessageError);
    // the AAD is "asvsj-file-v1": the same key and iv with another AAD do not open it
    const raw = await crypto.subtle.importKey("raw", fromBase64(sealed.key), "AES-GCM", false, ["decrypt"]);
    await assert.rejects(crypto.subtle.decrypt({ name: "AES-GCM", iv: fromBase64(sealed.iv),
        additionalData: utf8("asvsj-file-v2") }, raw, sealed.bytes));
    const opened = await crypto.subtle.decrypt({ name: "AES-GCM", iv: fromBase64(sealed.iv),
        additionalData: utf8("asvsj-file-v1") }, raw, sealed.bytes);
    assert.deepEqual(new Uint8Array(opened), original);
});

test("safety number: the same on both sides, six groups of five digits, as specified", async () => {
    const lanSide = await safetyNumber(LAN.publicKey, MINH.publicKey);
    const minhSide = await safetyNumber(MINH.publicKey, LAN.publicKey);
    assert.equal(lanSide, minhSide);
    assert.match(lanSide, /^\d{5}( \d{5}){5}$/);
    // independent implementation of contract §4
    const [a, b] = [Buffer.from(LAN.publicKey, "base64"), Buffer.from(MINH.publicKey, "base64")];
    const digest = createHash("sha256").update(Buffer.compare(a, b) <= 0 ? Buffer.concat([a, b]) : Buffer.concat([b, a]))
        .digest();
    const groups = [];
    for (let i = 0; i < 30; i += 5) {
        groups.push(String(digest.readUIntBE(i, 5) % 100000).padStart(5, "0"));
    }
    assert.equal(lanSide, groups.join(" "));
    assert.equal(await safetyNumber(fromBase64(LAN.publicKey), MINH.publicKey), lanSide);
    assert.notEqual(await safetyNumber(LAN.publicKey, LAN.publicKey), lanSide);
    await assert.rejects(safetyNumber("AAAA", LAN.publicKey), TypeError);
});

test("local key store: save, load, forget through setKeyStore; no IndexedDB in Node gives KeyStorageError", async () => {
    const rows = new Map();
    setKeyStore({
        get: async (id) => rows.get(id),
        put: async (id, record) => void rows.set(id, record),
        delete: async (id) => void rows.delete(id)
    });
    try {
        assert.equal(await loadLocalIdentity(LAN.userId), null);
        await saveLocalIdentity(LAN.userId, { keyId: LAN.keyId, publicKey: LAN.publicKey, privateKey: LAN.privateKey });
        assert.deepEqual([...rows.keys()], ["7"]);
        const loaded = await loadLocalIdentity("7");
        assert.equal(loaded.keyId, LAN.keyId);
        assert.equal(loaded.publicKey, LAN.publicKey);
        assert.equal(loaded.privateKey, LAN.privateKey);
        await assert.rejects(saveLocalIdentity(LAN.userId, { keyId: 1, publicKey: LAN.publicKey,
            privateKey: keyLan }), TypeError);
        await forgetLocalIdentity(LAN.userId);
        assert.equal(await loadLocalIdentity(LAN.userId), null);
    } finally {
        setKeyStore(null);
    }
    // back on the default store: Node has no IndexedDB, which must reject cleanly (as a private window may)
    assert.equal(typeof globalThis.indexedDB, "undefined");
    await assert.rejects(loadLocalIdentity(LAN.userId), KeyStorageError);
    await assert.rejects(saveLocalIdentity(LAN.userId, { keyId: LAN.keyId, publicKey: LAN.publicKey,
        privateKey: LAN.privateKey }), KeyStorageError);
});
