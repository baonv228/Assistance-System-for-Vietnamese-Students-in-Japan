// End-to-end encryption of "Tin nhắn" in the browser, with WebCrypto only (no library, no build step).
// The byte formats are fixed with the server (it checks public keys and franking tags) in the phase-2 contract §4:
// change nothing here without changing the server too. Works on https or localhost pages and in Node ≥ 20 (tests:
// node --test src/test/js/). On a plain-http address (the LAN IP) browsers hide crypto.subtle: cryptoSupported() is
// then false, every function rejects with CryptoUnavailableError, and importing this module still works.
//
// Known limits (documented, accepted): one static key per member, no ratchet, so no forward secrecy; the server sees
// who writes to whom and when, and the sizes.

/** PBKDF2-SHA256 rounds that protect the private key backup with the member's passphrase. */
export const WRAP_ITERATIONS = 600000;
/** Shortest passphrase accepted when a key is created (characters after NFC). */
export const PASSPHRASE_MIN_LENGTH = 8;
/** Largest decoded ciphertext the server accepts for one message. */
export const MAX_CIPHERTEXT_BYTES = 32768;

const ECDH = { name: "ECDH", namedCurve: "P-256" };
const HKDF_SALT = "asvsj-chat-v1";
const FILE_AAD = "asvsj-file-v1";
const FRANK_PREFIX = "asvsj-frank-v1";
const FK_BYTES = 32;
const IV_BYTES = 12;
// Server limits on a backup; also stops a broken backup from asking for billions of PBKDF2 rounds.
const MIN_ITERATIONS = 100000;
const MAX_ITERATIONS = 5000000;
const FILES_MAX = 5;
// Only these types may describe a file: the page turns them into Blob URLs, and an "image" that is really HTML or SVG
// would run script in this site's origin when opened. Parameters (";codecs=opus") are allowed after the type.
const FILE_TYPES = new Map([
    ["IMAGE", ["image/jpeg", "image/png", "image/webp", "image/gif"]],
    ["VIDEO", ["video/mp4", "video/quicktime", "video/webm"]],
    ["AUDIO", ["audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/aac", "audio/wav"]]
]);
const MIME_PARAMS = /^[a-z0-9=.,+" -]*$/i;

const encoder = new TextEncoder();

// ---------------------------------------------------------------------------------------------------------- errors

/** The browser cannot encrypt here: no WebCrypto (plain-http address) or a very old browser. */
export class CryptoUnavailableError extends Error {
    constructor(message = "Trình duyệt này không mã hóa được tin nhắn. Hãy mở Senpai ơi qua địa chỉ https.", options) {
        super(message, options);
        this.name = "CryptoUnavailableError";
    }
}

/** The passphrase does not open the key backup. */
export class WrongPassphraseError extends Error {
    constructor(message = "Mật khẩu mã hóa không đúng.", options) {
        super(message, options);
        this.name = "WrongPassphraseError";
    }
}

/** A message or file that cannot be decrypted, was changed, or whose franking tag does not match. */
export class UnreadableMessageError extends Error {
    constructor(message = "Không đọc được tin nhắn đã mã hóa này.", options) {
        super(message, options);
        this.name = "UnreadableMessageError";
    }
}

/**
 * This browser cannot keep the key on the device (IndexedDB missing or refused, e.g. some private windows). Encryption
 * still works: the page keeps the unlocked key in memory and asks for the passphrase on the next visit.
 */
export class KeyStorageError extends Error {
    constructor(message = "Trình duyệt này không lưu được khóa mã hóa trên máy (có thể do chế độ ẩn danh).", options) {
        super(message, options);
        this.name = "KeyStorageError";
    }
}

// --------------------------------------------------------------------------------------------------------- support

/** True when this page can use end-to-end encryption (WebCrypto present in a secure context). */
export function cryptoSupported() {
    const c = globalThis.crypto;
    return globalThis.isSecureContext !== false
        && !!c && typeof c.getRandomValues === "function"
        && !!c.subtle && typeof c.subtle.deriveBits === "function";
}

/** crypto.subtle, or a CryptoUnavailableError (inside async functions this becomes a rejection). */
function subtle() {
    if (!cryptoSupported()) {
        throw new CryptoUnavailableError();
    }
    return globalThis.crypto.subtle;
}

// --------------------------------------------------------------------------------------------------------- base64

/** Standard base64 with padding, as the server's java.util.Base64 writes it. */
export function toBase64(bytes) {
    const data = asBytes(bytes);
    let binary = "";
    for (let i = 0; i < data.length; i += 0x8000) {
        binary += String.fromCharCode.apply(null, data.subarray(i, i + 0x8000));
    }
    return btoa(binary);
}

const BASE64 = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/;

/** Decodes standard base64 strictly (atob alone would also accept spaces and missing padding). */
export function fromBase64(text) {
    if (typeof text !== "string" || !BASE64.test(text)) {
        throw new TypeError("Not standard base64");
    }
    const binary = atob(text);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
        out[i] = binary.charCodeAt(i);
    }
    return out;
}

// -------------------------------------------------------------------------------------------------------- identity

/**
 * A new identity key pair for this member. `publicKey` (base64 of the 65-byte raw point) and `backup` go to the server
 * (createChatKey); the private key leaves the browser only inside the backup, encrypted with the passphrase.
 * `privateKey` is non-extractable: page scripts can use it but never read it out.
 */
export async function createIdentity(passphrase) {
    const s = subtle();
    const phrase = normalisePassphrase(passphrase);
    if ([...phrase].length < PASSPHRASE_MIN_LENGTH) {
        throw new RangeError(`Mật khẩu mã hóa cần ít nhất ${PASSPHRASE_MIN_LENGTH} ký tự.`);
    }
    const pair = await s.generateKey(ECDH, true, ["deriveBits"]);
    const publicKey = toBase64(new Uint8Array(await s.exportKey("raw", pair.publicKey)));
    const pkcs8 = new Uint8Array(await s.exportKey("pkcs8", pair.privateKey));
    try {
        const salt = randomBytes(16);
        const iv = randomBytes(IV_BYTES);
        const wrapKey = await passphraseKey(phrase, salt, WRAP_ITERATIONS);
        const wrapped = new Uint8Array(await s.encrypt({ name: "AES-GCM", iv }, wrapKey, pkcs8));
        const privateKey = await s.importKey("pkcs8", pkcs8, ECDH, false, ["deriveBits"]);
        return {
            publicKey,
            backup: { wrappedPrivateKey: toBase64(wrapped), wrapSalt: toBase64(salt), wrapIv: toBase64(iv),
                wrapIterations: WRAP_ITERATIONS },
            privateKey
        };
    } finally {
        pkcs8.fill(0);                                  // the clear private key lives no longer than needed
    }
}

/**
 * Opens a key backup from the server (chatMyKey().key) with the passphrase → the non-extractable private key.
 * Throws WrongPassphraseError when the passphrase does not fit, TypeError when the backup is malformed or does not
 * belong to `backup.publicKey` (checked when the backup carries it).
 */
export async function unlockIdentity(backup, passphrase) {
    const s = subtle();
    const { wrapped, salt, iv, iterations } = readBackup(backup);
    const phrase = normalisePassphrase(passphrase);
    if (!phrase) {
        throw new WrongPassphraseError();
    }
    const wrapKey = await passphraseKey(phrase, salt, iterations);
    let pkcs8;
    try {
        pkcs8 = new Uint8Array(await s.decrypt({ name: "AES-GCM", iv }, wrapKey, wrapped));
    } catch (error) {
        throw new WrongPassphraseError(undefined, { cause: error });
    }
    try {
        if (typeof backup.publicKey === "string") {
            await checkKeyPair(pkcs8, backup.publicKey);
        }
        return await s.importKey("pkcs8", pkcs8, ECDH, false, ["deriveBits"]);
    } finally {
        pkcs8.fill(0);
    }
}

function normalisePassphrase(passphrase) {
    if (typeof passphrase !== "string") {
        throw new TypeError("passphrase must be a string");
    }
    return passphrase.normalize("NFC");                 // "ở" typed composed or decomposed is the same passphrase
}

/** AES-GCM-256 key from PBKDF2-SHA256 over the UTF-8 of the (already NFC) passphrase. */
async function passphraseKey(phrase, salt, iterations) {
    const s = subtle();
    const base = await s.importKey("raw", encoder.encode(phrase), "PBKDF2", false, ["deriveKey"]);
    return s.deriveKey({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, base,
        { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
}

/** The backup fields decoded and checked against the server limits. */
function readBackup(backup) {
    try {
        const wrapped = fromBase64(backup.wrappedPrivateKey);
        const salt = fromBase64(backup.wrapSalt);
        const iv = fromBase64(backup.wrapIv);
        const iterations = backup.wrapIterations;
        if (wrapped.length >= 16 && wrapped.length <= 600 && salt.length >= 16 && salt.length <= 64
            && iv.length === IV_BYTES && Number.isSafeInteger(iterations)
            && iterations >= MIN_ITERATIONS && iterations <= MAX_ITERATIONS) {
            return { wrapped, salt, iv, iterations };
        }
    } catch {
        // reported below
    }
    throw new TypeError("Bản sao lưu khóa mã hóa không hợp lệ.");
}

/** Refuses a backup whose private key is not the one of `publicKey` (messages to it would be unreadable). */
async function checkKeyPair(pkcs8, publicKey) {
    const s = subtle();
    const point = publicKeyBytes(publicKey);
    const jwk = await s.exportKey("jwk", await s.importKey("pkcs8", pkcs8, ECDH, true, ["deriveBits"]));
    const fromPrivate = concat(Uint8Array.of(4), fromBase64Url(jwk.x), fromBase64Url(jwk.y));
    if (!sameBytes(fromPrivate, point)) {
        throw new TypeError("Bản sao lưu khóa mã hóa không khớp với khóa công khai.");
    }
}

// ------------------------------------------------------------------------------------------------ key on the device

const DB_NAME = "asvsj-chat";
const DB_STORE = "identities";
const DB_OPEN_MS = 5000;

let keyStore = null;                                    // a store set by setKeyStore (tests), else IndexedDB
let dbOpening = null;                                   // the IndexedDB connection (no secret in it)

/**
 * Replaces where unlocked keys are kept: `{ get(id), put(id, record), delete(id) }`, each returning a promise (ids are
 * user ids as strings). Tests pass an in-memory store; null goes back to IndexedDB.
 */
export function setKeyStore(store) {
    keyStore = store ?? null;
}

/**
 * Keeps the unlocked key on this device, so the passphrase is asked once per browser. The CryptoKey is stored as a
 * non-extractable key: even scripts of this site cannot read its bytes. Rejects with KeyStorageError when the browser
 * refuses (the page then keeps the key in memory for this visit).
 */
export async function saveLocalIdentity(userId, { keyId, publicKey, privateKey }) {
    const id = String(idNumber(userId, "userId"));
    if (!privateKey || privateKey.type !== "private") {
        throw new TypeError("privateKey must be the private CryptoKey");
    }
    await currentStore().put(id, { userId: id, keyId: idNumber(keyId, "keyId"), publicKey, privateKey,
        savedAt: Date.now() });
}

/** The key kept on this device for this member, `{ keyId, publicKey, privateKey }`, or null. */
export async function loadLocalIdentity(userId) {
    const record = await currentStore().get(String(idNumber(userId, "userId")));
    if (!record || !record.privateKey || record.privateKey.type !== "private") {
        return null;
    }
    return { keyId: record.keyId, publicKey: record.publicKey, privateKey: record.privateKey };
}

/** Removes this member's key from this device (sign-out on a shared computer, or a new key). */
export async function forgetLocalIdentity(userId) {
    await currentStore().delete(String(idNumber(userId, "userId")));
}

function currentStore() {
    return keyStore ?? indexedDbStore;
}

const indexedDbStore = {
    get: (id) => inTransaction("readonly", (store) => store.get(id)),
    put: (id, record) => inTransaction("readwrite", (store) => store.put(record, id)),
    delete: (id) => inTransaction("readwrite", (store) => store.delete(id))
};

/** Opens "asvsj-chat" the first time a key is read or written (never at import), and again after a failure. */
function openDb() {
    if (!dbOpening) {
        dbOpening = new Promise((resolve, reject) => {
            const idb = globalThis.indexedDB;
            if (!idb) {
                reject(new KeyStorageError());
                return;
            }
            // Some Safari versions never answer open(): give up instead of leaving the page waiting.
            const timer = setTimeout(() => reject(new KeyStorageError()), DB_OPEN_MS);
            let request;
            try {
                request = idb.open(DB_NAME, 1);
            } catch (error) {
                clearTimeout(timer);
                reject(new KeyStorageError(undefined, { cause: error }));
                return;
            }
            request.onupgradeneeded = () => request.result.createObjectStore(DB_STORE);
            request.onsuccess = () => {
                clearTimeout(timer);
                const db = request.result;
                db.onversionchange = () => {
                    db.close();
                    dbOpening = null;
                };
                db.onclose = () => {
                    dbOpening = null;
                };
                resolve(db);
            };
            request.onerror = () => {
                clearTimeout(timer);
                reject(new KeyStorageError(undefined, { cause: request.error }));
            };
        }).catch((error) => {
            dbOpening = null;                           // the next call tries again
            throw error;
        });
    }
    return dbOpening;
}

/** Runs one request in its own transaction and resolves with its result once the transaction is committed. */
async function inTransaction(mode, action) {
    const db = await openDb();
    return new Promise((resolve, reject) => {
        let result;
        let tx;
        try {
            tx = db.transaction(DB_STORE, mode);
            const request = action(tx.objectStore(DB_STORE));
            request.onsuccess = () => {
                result = request.result;
            };
        } catch (error) {
            // e.g. DataCloneError: this browser cannot store a CryptoKey here
            reject(new KeyStorageError(undefined, { cause: error }));
            return;
        }
        tx.oncomplete = () => resolve(result);
        tx.onerror = () => reject(new KeyStorageError(undefined, { cause: tx.error }));
        tx.onabort = () => reject(new KeyStorageError(undefined, { cause: tx.error }));
    });
}

// ------------------------------------------------------------------------------------------------ conversation key

/**
 * The AES-GCM key of the messages between the key ids keyIdA and keyIdB in this conversation (order does not matter):
 * ECDH(own private key, peer's public key) → HKDF-SHA256(salt "asvsj-chat-v1", info "conv:<id>:<min>:<max>").
 * Both members get the same key. For an older message, pass the key ids of its envelope and the peer's public key of
 * that id (chatKey). Non-extractable; the caller may keep it in memory per (conversation, key pair).
 */
export async function conversationKey(privateKey, peerPublicKey, conversationId, keyIdA, keyIdB) {
    const s = subtle();
    const conversation = idNumber(conversationId, "conversationId");
    const a = idNumber(keyIdA, "keyIdA");
    const b = idNumber(keyIdB, "keyIdB");
    const peer = await importPublicKey(peerPublicKey);
    const shared = new Uint8Array(await s.deriveBits({ name: "ECDH", public: peer }, privateKey, 256));
    try {
        const base = await s.importKey("raw", shared, "HKDF", false, ["deriveKey"]);
        const info = `conv:${conversation}:${Math.min(a, b)}:${Math.max(a, b)}`;
        return await s.deriveKey(
            { name: "HKDF", hash: "SHA-256", salt: encoder.encode(HKDF_SALT), info: encoder.encode(info) },
            base, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]);
    } finally {
        shared.fill(0);
    }
}

/** A peer's public key (base64 of the raw point, its bytes, or a CryptoKey). WebCrypto checks the point is on P-256. */
async function importPublicKey(value) {
    if (value && typeof value === "object" && value.type === "public") {
        return value;
    }
    const point = publicKeyBytes(value);
    try {
        return await subtle().importKey("raw", point, ECDH, true, []);
    } catch (error) {
        throw new TypeError("Khóa công khai không hợp lệ.", { cause: error });
    }
}

/** The 65 bytes of an uncompressed P-256 point (0x04 ‖ x ‖ y), or a TypeError. */
function publicKeyBytes(value) {
    let point;
    try {
        point = typeof value === "string" ? fromBase64(value) : asBytes(value);
    } catch {
        point = null;
    }
    if (!point || point.length !== 65 || point[0] !== 4) {
        throw new TypeError("Khóa công khai không hợp lệ.");
    }
    return point;
}

// --------------------------------------------------------------------------------------------------------- messages

/**
 * Encrypts one message. body = `{ text?, files? }` (files as in the contract: `{ id, kind, mime, size, key, iv, w?, h?,
 * dur? }`). Returns `envelope` (send as `encrypted`), and `frankingKey` + `plaintext` (base64; the sender may keep them
 * like the recipient does, e.g. to quote or edit). Throws TypeError for an empty or malformed body, RangeError when the
 * message would be larger than the server accepts.
 */
export async function encryptMessage({ key, conversationId, senderId, senderKeyId, recipientKeyId, body }) {
    const s = subtle();
    const aad = messageAad(conversationId, senderId, senderKeyId, recipientKeyId);
    const plaintext = encodeBody(body);
    if (FK_BYTES + plaintext.length + 16 > MAX_CIPHERTEXT_BYTES) {
        throw new RangeError("Tin nhắn quá dài.");
    }
    const frankingKey = randomBytes(FK_BYTES);
    const iv = randomBytes(IV_BYTES);
    const ciphertext = new Uint8Array(await s.encrypt({ name: "AES-GCM", iv, additionalData: aad }, key,
        concat(frankingKey, plaintext)));
    return {
        envelope: {
            ciphertext: toBase64(ciphertext),
            iv: toBase64(iv),
            senderKeyId: idNumber(senderKeyId, "senderKeyId"),
            recipientKeyId: idNumber(recipientKeyId, "recipientKeyId"),
            frankingTag: await frankingTag(frankingKey, conversationId, senderId, plaintext)
        },
        frankingKey: toBase64(frankingKey),
        plaintext: toBase64(plaintext)
    };
}

/**
 * Decrypts a message envelope from the server. senderId = the writer (own id when `mine`). Returns `body`
 * (`{ text: string|null, files: [] }`) and `frankingKey` + `plaintext` (base64), which a report sends as they are
 * (reportChatMessage body fields of the same names). Throws UnreadableMessageError when the key is wrong, anything was
 * changed (ciphertext, iv, ids), the franking tag does not match or the body is not a valid v1 body.
 */
export async function decryptMessage({ key, conversationId, senderId, envelope }) {
    const s = subtle();
    idNumber(conversationId, "conversationId");         // caller mistakes stay TypeErrors, not "unreadable"
    idNumber(senderId, "senderId");
    let sealed;
    try {
        const iv = fromBase64(envelope.iv);
        if (iv.length !== IV_BYTES) {
            throw new TypeError("iv");
        }
        const aad = messageAad(conversationId, senderId, envelope.senderKeyId, envelope.recipientKeyId);
        sealed = new Uint8Array(await s.decrypt({ name: "AES-GCM", iv, additionalData: aad }, key,
            fromBase64(envelope.ciphertext)));
    } catch (error) {
        throw new UnreadableMessageError(undefined, { cause: error });
    }
    if (sealed.length <= FK_BYTES) {
        throw new UnreadableMessageError();
    }
    const frankingKey = sealed.slice(0, FK_BYTES);
    const plaintext = sealed.slice(FK_BYTES);
    const expected = await frankingTag(frankingKey, conversationId, senderId, plaintext);
    // A sender who puts one text inside and a tag of another would make reports of this message fail.
    if (typeof envelope.frankingTag !== "string" || !sameText(expected, envelope.frankingTag)) {
        throw new UnreadableMessageError();
    }
    let body;
    try {
        body = decodeBody(plaintext);
    } catch (error) {
        throw error instanceof UnreadableMessageError ? error : new UnreadableMessageError(undefined, { cause: error });
    }
    return { body, frankingKey: toBase64(frankingKey), plaintext: toBase64(plaintext) };
}

/**
 * Lowercase hex HMAC-SHA256(fk, "asvsj-frank-v1|<conversationId>|<senderId>|" ‖ P): binds the message to its text,
 * so a reported text can be checked by the server. fk and P as bytes or base64.
 */
export async function frankingTag(frankingKey, conversationId, senderId, plaintextBytes) {
    const s = subtle();
    const fk = bytesOf(frankingKey);
    if (fk.length !== FK_BYTES) {
        throw new TypeError("frankingKey must be 32 bytes");
    }
    const prefix = `${FRANK_PREFIX}|${idNumber(conversationId, "conversationId")}|${idNumber(senderId, "senderId")}|`;
    const hmac = await s.importKey("raw", fk, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
    const mac = new Uint8Array(await s.sign("HMAC", hmac, concat(encoder.encode(prefix), bytesOf(plaintextBytes))));
    return Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** UTF-8 of "<conversationId>|<senderId>|<senderKeyId>|<recipientKeyId>": a message cannot be moved to another place. */
function messageAad(conversationId, senderId, senderKeyId, recipientKeyId) {
    return encoder.encode([idNumber(conversationId, "conversationId"), idNumber(senderId, "senderId"),
        idNumber(senderKeyId, "senderKeyId"), idNumber(recipientKeyId, "recipientKeyId")].join("|"));
}

/** P = UTF-8 of {"v":1,"text"?,"files"?}; empty text is left out, at least text or one file is needed. */
function encodeBody(body) {
    if (!body || typeof body !== "object") {
        throw new TypeError("body must be { text?, files? }");
    }
    const out = { v: 1 };
    if (body.text != null && body.text !== "") {
        if (typeof body.text !== "string") {
            throw new TypeError("body.text must be a string");
        }
        out.text = body.text;
    }
    if (body.files != null && body.files.length) {
        const files = checkFiles(body.files);
        if (!files) {
            throw new TypeError("body.files is not a valid list of files");
        }
        out.files = files;
    }
    if (out.text == null && !out.files) {
        throw new TypeError("body is empty");
    }
    return encoder.encode(JSON.stringify(out));
}

/** The body inside P, checked as strictly as it is written: the peer is the only one who wrote it. */
function decodeBody(plaintext) {
    let parsed;
    try {
        parsed = JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(plaintext));
    } catch (error) {
        throw new UnreadableMessageError(undefined, { cause: error });
    }
    if (!parsed || typeof parsed !== "object" || parsed.v !== 1
        || (parsed.text != null && typeof parsed.text !== "string")) {
        throw new UnreadableMessageError();
    }
    const files = parsed.files == null ? [] : checkFiles(parsed.files);
    if (!files) {
        throw new UnreadableMessageError();
    }
    return { text: parsed.text ?? null, files };
}

/** The file entries with only the known fields, or null when one is malformed or the mix is not allowed. */
function checkFiles(list) {
    if (!Array.isArray(list) || list.length > FILES_MAX) {
        return null;
    }
    const files = [];
    for (const f of list) {
        const entry = checkFile(f);
        if (!entry) {
            return null;
        }
        files.push(entry);
    }
    // Same mix as the server allows: 1–5 photos, or one video, or one voice message.
    const kinds = new Set(files.map((f) => f.kind));
    if (kinds.size > 1 || (files.length > 1 && !kinds.has("IMAGE"))) {
        return null;
    }
    return files;
}

function checkFile(f) {
    if (!f || typeof f !== "object" || !FILE_TYPES.has(f.kind) || !allowedMime(f.kind, f.mime)
        || !Number.isSafeInteger(f.id) || f.id < 0 || !Number.isSafeInteger(f.size) || f.size < 0) {
        return null;
    }
    try {
        if (fromBase64(f.key).length !== 32 || fromBase64(f.iv).length !== IV_BYTES) {
            return null;
        }
    } catch {
        return null;
    }
    const entry = { id: f.id, kind: f.kind, mime: f.mime, size: f.size, key: f.key, iv: f.iv };
    for (const name of ["w", "h"]) {
        if (f[name] != null) {
            if (!Number.isSafeInteger(f[name]) || f[name] <= 0 || f[name] > 100000) {
                return null;
            }
            entry[name] = f[name];
        }
    }
    if (f.dur != null) {
        if (typeof f.dur !== "number" || !Number.isFinite(f.dur) || f.dur < 0 || f.dur > 3600) {
            return null;
        }
        entry.dur = f.dur;
    }
    return entry;
}

function allowedMime(kind, mime) {
    if (typeof mime !== "string" || mime.length > 120) {
        return false;
    }
    const cut = mime.indexOf(";");
    const type = (cut < 0 ? mime : mime.slice(0, cut)).trim().toLowerCase();
    return FILE_TYPES.get(kind).includes(type) && (cut < 0 || MIME_PARAMS.test(mime.slice(cut + 1)));
}

// ------------------------------------------------------------------------------------------------------------ files

/**
 * Encrypts a photo, video or voice message (bytes, ArrayBuffer or Blob) with its own random key: → `{ bytes }` to
 * upload (ciphertext ‖ tag) and `key`, `iv` (base64) for the message body. Re-encode photos and strip videos first
 * (shrinkToJpeg, stripMp4Metadata): the server cannot see inside.
 */
export async function encryptFile(input) {
    const s = subtle();
    const data = await readInput(input);
    const raw = randomBytes(32);
    const iv = randomBytes(IV_BYTES);
    try {
        const key = await s.importKey("raw", raw, "AES-GCM", false, ["encrypt"]);
        const bytes = new Uint8Array(await s.encrypt({ name: "AES-GCM", iv, additionalData: encoder.encode(FILE_AAD) },
            key, data));
        return { bytes, key: toBase64(raw), iv: toBase64(iv) };
    } finally {
        raw.fill(0);
    }
}

/** Decrypts downloaded file bytes with the key and iv of its body entry → Uint8Array, or UnreadableMessageError. */
export async function decryptFile(input, key, iv) {
    const s = subtle();
    try {
        const data = await readInput(input);
        const raw = fromBase64(key);
        const nonce = fromBase64(iv);
        if (raw.length !== 32 || nonce.length !== IV_BYTES) {
            throw new TypeError("key or iv");
        }
        const aesKey = await s.importKey("raw", raw, "AES-GCM", false, ["decrypt"]);
        return new Uint8Array(await s.decrypt({ name: "AES-GCM", iv: nonce, additionalData: encoder.encode(FILE_AAD) },
            aesKey, data));
    } catch (error) {
        throw new UnreadableMessageError("Không đọc được tệp đã mã hóa này.", { cause: error });
    }
}

// --------------------------------------------------------------------------------------------------- safety number

/**
 * "Mã an toàn": six groups of five digits, the same on both phones when both see the right keys. SHA-256 over the two
 * raw public keys, smaller first; bytes 0–29 in six 5-byte big-endian numbers, each mod 100000.
 */
export async function safetyNumber(publicKeyA, publicKeyB) {
    const a = publicKeyBytes(publicKeyA);
    const b = publicKeyBytes(publicKeyB);
    const [first, second] = compareBytes(a, b) <= 0 ? [a, b] : [b, a];
    const digest = new Uint8Array(await subtle().digest("SHA-256", concat(first, second)));
    const groups = [];
    for (let i = 0; i < 30; i += 5) {
        let value = 0;
        for (let j = i; j < i + 5; j++) {
            value = value * 256 + digest[j];            // ≤ 2^40, exact in a double
        }
        groups.push(String(value % 100000).padStart(5, "0"));
    }
    return groups.join(" ");
}

// ---------------------------------------------------------------------------------------------------------- helpers

/** An id as a number (JSON numbers or digit strings); ids end up in AAD and HKDF text, so they must print the same. */
function idNumber(value, name) {
    const n = typeof value === "string" && /^\d{1,16}$/.test(value) ? Number(value) : value;
    if (!Number.isSafeInteger(n) || n < 0) {
        throw new TypeError(`${name} must be a whole number ≥ 0`);
    }
    return n;
}

function randomBytes(n) {
    return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

function asBytes(value) {
    if (value instanceof Uint8Array) {
        return value;
    }
    if (value instanceof ArrayBuffer) {
        return new Uint8Array(value);
    }
    if (ArrayBuffer.isView(value)) {
        return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
    }
    throw new TypeError("expected bytes");
}

/** Bytes given as bytes or as base64. */
function bytesOf(value) {
    return typeof value === "string" ? fromBase64(value) : asBytes(value);
}

async function readInput(input) {
    if (typeof Blob !== "undefined" && input instanceof Blob) {
        return new Uint8Array(await input.arrayBuffer());
    }
    return asBytes(input);
}

function concat(...parts) {
    const out = new Uint8Array(parts.reduce((sum, p) => sum + p.length, 0));
    let at = 0;
    for (const p of parts) {
        out.set(p, at);
        at += p.length;
    }
    return out;
}

function compareBytes(a, b) {
    for (let i = 0; i < Math.min(a.length, b.length); i++) {
        if (a[i] !== b[i]) {
            return a[i] - b[i];
        }
    }
    return a.length - b.length;
}

function sameBytes(a, b) {
    return a.length === b.length && compareBytes(a, b) === 0;
}

/** Compares two strings in time that does not depend on where they differ. */
function sameText(a, b) {
    if (a.length !== b.length) {
        return false;
    }
    let diff = 0;
    for (let i = 0; i < a.length; i++) {
        diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
    }
    return diff === 0;
}

/** base64url without padding (JWK coordinates). */
function fromBase64Url(text) {
    const b64 = text.replace(/-/g, "+").replace(/_/g, "/");
    return fromBase64(b64 + "=".repeat((4 - (b64.length % 4)) % 4));
}
