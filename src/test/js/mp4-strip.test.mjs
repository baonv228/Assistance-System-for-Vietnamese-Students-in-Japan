// Tests of the MP4/MOV metadata stripper used before a chat video is encrypted (assets/js/lib/mp4-strip.js), on
// synthetic files built here box by box. Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { stripMp4Metadata, readMp4Info } from "../../main/resources/static/assets/js/lib/mp4-strip.js";

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

/** A box whose 32-bit size field is `size` (normally header + body; 0, 1 or a wrong value for crafted files). */
function rawBox(size, type, body = zeros(0)) {
    const out = new Uint8Array(8 + body.length);
    new DataView(out.buffer).setUint32(0, size);
    out.set(ascii(type), 4);
    out.set(body, 8);
    return out;
}

const box = (type, ...parts) => {
    const body = concat(...parts);
    return rawBox(8 + body.length, type, body);
};

/** A box with size 1 and a 64-bit size (`large`, default the real size). */
function largeBox(type, body, large = BigInt(16 + body.length)) {
    const out = new Uint8Array(16 + body.length);
    const view = new DataView(out.buffer);
    view.setUint32(0, 1);
    out.set(ascii(type), 4);
    view.setBigUint64(8, large);
    out.set(body, 16);
    return out;
}

/** A metadata box, or (stripped) what the stripper must turn it into: a zero-filled "free" box of the same size. */
const metaBox = (stripped, type, ...parts) => (stripped ? box("free", zeros(concat(...parts).length)) : box(type, ...parts));
const largeMetaBox = (stripped, type, body) => (stripped ? largeBox("free", zeros(body.length)) : largeBox(type, body));

const ftyp = (brand = "isom") => box("ftyp", ascii(brand), zeros(4), ascii(brand), ascii("mp42"));

/** mvhd version 0: timescale and 32-bit duration, then the usual rest of the header. */
function mvhd(timescale, duration) {
    const body = zeros(100);
    const view = new DataView(body.buffer);
    view.setUint32(12, timescale);
    view.setUint32(16, duration);
    return box("mvhd", body);
}

/** mvhd version 1: 64-bit times and duration. */
function mvhd1(timescale, duration) {
    const body = zeros(112);
    const view = new DataView(body.buffer);
    body[0] = 1;
    view.setUint32(20, timescale);
    view.setBigUint64(24, BigInt(duration));
    return box("mvhd", body);
}

const SECRETS = ["+35.6895+139.6917/", "com.apple.quicktime.location.ISO6709", "Pixel 9 Pro", "XMP-secret",
    "iPhone 15", "top-level-meta", "trailing-udta"];

/**
 * A MOV like an iPhone writes, with metadata at the three levels the stripper handles (file, moov, trak), 64-bit sizes
 * (moov and a uuid in a track) and a size-0 box at the end; `stripped` builds what the result must be.
 */
function sampleFile(stripped) {
    const trak1 = box("trak",
        box("tkhd", zeros(84)),
        metaBox(stripped, "udta", box("©mod", ascii("Pixel 9 Pro"))),
        metaBox(stripped, "meta", zeros(4), box("hdlr", zeros(24))),
        box("mdia", box("mdhd", zeros(24)), box("udta", ascii("deep-kept"))));     // deeper levels: left as on the server
    const trak2 = box("trak",
        box("tkhd", zeros(84)),
        largeMetaBox(stripped, "uuid", concat(zeros(16), ascii("XMP-secret"))),
        box("mdia", box("mdhd", zeros(24))));
    const moovBody = concat(
        mvhd(600, 3000),
        metaBox(stripped, "udta", box("©xyz", ascii("+35.6895+139.6917/"))),
        metaBox(stripped, "meta", box("keys", ascii("com.apple.quicktime.location.ISO6709")), box("ilst", ascii("iPhone 15"))),
        trak1,
        trak2,
        box("free", ascii("kept-free")));
    const tail = ascii("trailing-udta");
    return concat(
        ftyp("qt  "),
        metaBox(stripped, "uuid", zeros(16), ascii("XMP")),
        largeBox("moov", moovBody),
        metaBox(stripped, "meta", ascii("top-level-meta")),
        box("mdat", ascii("VIDEO-FRAMES-".repeat(50))),
        stripped ? rawBox(0, "free", zeros(tail.length)) : rawBox(0, "udta", tail));
}

const contains = (bytes, text) => Buffer.from(bytes).includes(Buffer.from(text, "latin1"));

test("metadata boxes of the file, moov and each track become zero-filled free boxes; other bytes unchanged", () => {
    const input = sampleFile(false);
    const before = input.slice();
    const out = stripMp4Metadata(input);
    assert.ok(out instanceof Uint8Array);
    assert.equal(out.length, input.length);
    assert.notEqual(out.buffer, input.buffer);
    assert.deepEqual(out, sampleFile(true));            // byte for byte: only the metadata boxes changed
    assert.deepEqual(input, before);                    // the input is left untouched
    for (const secret of SECRETS) {
        assert.ok(contains(input, secret), secret);
        assert.ok(!contains(out, secret), secret);
    }
    assert.ok(contains(out, "deep-kept") && contains(out, "kept-free") && contains(out, "VIDEO-FRAMES-"));
    assert.deepEqual(readMp4Info(out), { seconds: 5, quickTime: true });
});

test("accepts ArrayBuffer, offset views and DataView; stripping twice changes nothing more", () => {
    const input = sampleFile(false);
    const expected = sampleFile(true);
    assert.deepEqual(stripMp4Metadata(input.buffer), expected);
    const padded = concat(ascii("pad!"), input, ascii("pad!"));
    assert.deepEqual(stripMp4Metadata(padded.subarray(4, 4 + input.length)), expected);
    assert.deepEqual(stripMp4Metadata(new DataView(padded.buffer, 4, input.length)), expected);
    assert.deepEqual(stripMp4Metadata(expected), expected);
});

test("a file without metadata comes back identical", () => {
    const plain = concat(ftyp(), box("moov", mvhd(1000, 12000), box("trak", box("tkhd", zeros(84)))),
        box("mdat", ascii("frames")));
    assert.deepEqual(stripMp4Metadata(plain), plain);
    assert.deepEqual(readMp4Info(plain), { seconds: 12, quickTime: false });
});

test("readMp4Info reads version 1 headers and refuses what the server refuses", () => {
    const v1 = concat(ftyp(), box("moov", mvhd1(1000, 12345)), box("mdat", zeros(8)));
    assert.deepEqual(readMp4Info(v1), { seconds: 12.345, quickTime: false });
    const refused = {
        noDuration: concat(ftyp(), box("moov", mvhd(600, 0))),
        unknownDuration: concat(ftyp(), box("moov", mvhd(600, 0xFFFFFFFF))),
        unknownDuration64: concat(ftyp(), box("moov", mvhd1(600, -1n & 0xFFFFFFFFFFFFFFFFn))),
        negativeDuration64: concat(ftyp(), box("moov", mvhd1(600, 0x8000000000000000n))),
        noTimescale: concat(ftyp(), box("moov", mvhd(0, 600))),
        noMvhd: concat(ftyp(), box("moov", box("trak", zeros(8)))),
        shortMvhd: concat(ftyp(), box("moov", box("mvhd", zeros(10)))),
        noMoov: concat(ftyp(), box("mdat", zeros(64))),
        fragmented: concat(ftyp(), box("moov", mvhd(600, 3000)), box("moof", zeros(16)), box("mdat", zeros(16)))
    };
    for (const [name, file] of Object.entries(refused)) {
        assert.equal(readMp4Info(file), null, name);
        assert.equal(stripMp4Metadata(file), null, name);
    }
});

test("sizes past the end of the file are read as 'up to the end' (32-bit, 64-bit and 0)", () => {
    const head = concat(ftyp(), box("moov", mvhd(600, 3000), box("udta", ascii("+35.6+139.7/"))));
    const headStripped = concat(ftyp(), box("moov", mvhd(600, 3000), box("free", zeros(12))));
    const frames = ascii("frames-cut-short");
    for (const [name, tail] of [
        ["32-bit", rawBox(0xFFFFFFF0, "mdat", frames)],
        ["64-bit", largeBox("mdat", frames, 1n << 40n)],
        ["64-bit max", largeBox("mdat", frames, 0x7FFFFFFFFFFFFFFFn)],
        ["size 0", rawBox(0, "mdat", frames)]
    ]) {
        assert.deepEqual(stripMp4Metadata(concat(head, tail)), concat(headStripped, tail), name);
    }
    // moov itself last and cut short: its children are read up to the end of the file
    const cutMoov = concat(ftyp(), rawBox(0xFFFFFF00, "moov", concat(mvhd(600, 3000), box("meta", ascii("where")))));
    const out = stripMp4Metadata(cutMoov);
    assert.ok(out && !contains(out, "where"));
    // fewer than 8 bytes after the last box are ignored, as on the server
    const trailing = concat(head, ascii("12345"));
    assert.deepEqual(stripMp4Metadata(trailing), concat(headStripped, ascii("12345")));
});

test("broken or crafted sizes give null, never an exception or an endless loop", () => {
    const moov = box("moov", mvhd(600, 3000));
    const crafted = {
        sizeBelowHeader: concat(ftyp(), moov, rawBox(3, "mdat", zeros(8))),
        sizeSeven: concat(ftyp(), rawBox(7, "free", zeros(8)), moov),
        largeSizeWithoutRoom: concat(ftyp(), moov, rawBox(1, "mdat", zeros(4))),
        largeSizeBelowHeader: concat(ftyp(), moov, largeBox("mdat", zeros(8), 12n)),
        largeSizeNegative: concat(ftyp(), moov, largeBox("mdat", zeros(8), 0x8000000000000000n)),
        largeSizeAllOnes: concat(ftyp(), moov, largeBox("mdat", zeros(8), 0xFFFFFFFFFFFFFFFFn)),
        brokenInMoovAfterMvhd: concat(ftyp(), box("moov", mvhd(600, 3000), rawBox(4, "udta", zeros(8))), box("mdat", zeros(8))),
        brokenInTrak: concat(ftyp(), box("moov", mvhd(600, 3000), box("trak", box("tkhd", zeros(8)), rawBox(2, "meta", zeros(8))))),
        notABoxType: concat(ftyp(), moov, rawBox(16, "\u0000\u0001ab", zeros(8))),
        tooShort: ftyp().subarray(0, 15),
        text: ascii("This is not a video, just some text that is long enough."),
        empty: zeros(0),
        zeros: zeros(4096)
    };
    for (const [name, file] of Object.entries(crafted)) {
        assert.equal(stripMp4Metadata(file), null, name);
    }
    for (const value of [null, undefined, "video", 42, {}, [1, 2, 3]]) {
        assert.equal(stripMp4Metadata(value), null, String(value));
        assert.equal(readMp4Info(value), null, String(value));
    }
});

test("many tiny boxes and huge sizes finish quickly", () => {
    const repeat = (bytes, n) => {
        const out = new Uint8Array(bytes.length * n);
        for (let i = 0; i < n; i++) {
            out.set(bytes, i * bytes.length);
        }
        return out;
    };
    const many = concat(ftyp(), box("moov", mvhd(600, 3000), repeat(box("udta"), 20000)),
        repeat(rawBox(8, "free"), 200000));
    const started = Date.now();
    const out = stripMp4Metadata(many);
    assert.ok(out && out.length === many.length);
    assert.equal(contains(out, "udta"), false);
    const huge = concat(ftyp(), box("moov", mvhd(600, 3000)), largeBox("mdat", zeros(8), 0x7FFFFFFFFFFFFFF0n),
        box("udta", ascii("after-the-huge-box")));
    const hugeOut = stripMp4Metadata(huge);
    // the huge mdat runs to the end, so the udta after it is part of mdat's bytes and stays (as on the server)
    assert.deepEqual(hugeOut, huge);
    assert.ok(Date.now() - started < 3000, `took ${Date.now() - started} ms`);
});
