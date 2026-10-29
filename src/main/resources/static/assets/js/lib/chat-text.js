// Text helpers of the "Tin nhắn" page that touch no DOM: search that ignores accents and case (the server cannot search
// encrypted messages, so the page does), the snippet around a hit, one-line previews and links in a message.
// Tested in Node: node --test src/test/js/ (chat-text.test.mjs).
import { esc } from "./ui.js";

const MARKS = /[̀-ͯ]/g;
const SPACE = /\s/u;

/**
 * One character as search compares it: Vietnamese accents and marks dropped ("ộ" → "o", "ư" → "u"), "đ"/"Đ" → "d",
 * lower case. Marks outside the Latin range stay, so Japanese "が" is not "か".
 */
function foldChar(ch) {
    if (ch === "đ" || ch === "Đ") return "d";
    return ch.normalize("NFD").replace(MARKS, "").normalize("NFC").toLowerCase();
}

/**
 * The text folded for search, and where each folded character came from: `map[i]` is the index in `text` of the
 * character that gave `folded[i]` (one more entry at the end: `text.length`). White space runs count as one space and
 * leading white space is dropped; combining marks typed on their own (text in NFD) vanish.
 */
export function foldWithMap(text) {
    const source = String(text ?? "");
    let folded = "";
    const map = [];
    let index = 0;
    let space = false;
    for (const ch of source) {
        if (SPACE.test(ch)) {
            if (!space && folded) {
                folded += " ";
                map.push(index);
            }
            space = true;
        } else {
            space = false;
            const f = foldChar(ch);
            for (let k = 0; k < f.length; k++) map.push(index);
            folded += f;
        }
        index += ch.length;
    }
    map.push(source.length);
    return { folded, map };
}

/** "Đà  Nẵng " → "da nang": what a search query is compared as. */
export function fold(text) {
    return foldWithMap(text).folded.trim();
}

/**
 * The first place `query` occurs in `text`, accents, case and white space ignored → `{ start, end }` (indices in
 * `text`, end exclusive), or null (no hit, empty query).
 */
export function findMatch(text, query) {
    const q = fold(query);
    if (!q) return null;
    const source = String(text ?? "");
    const { folded, map } = foldWithMap(source);
    const at = folded.indexOf(q);
    if (at < 0) return null;
    return { start: map[at], end: map[at + q.length] ?? source.length };
}

/**
 * The hit of `query` in `text` with a little text around it, on one line: `{ before, match, after }` (plain text, the
 * page escapes it and marks `match`), "…" where the text was cut; null when there is no hit.
 */
export function snippet(text, query, { before = 36, after = 80 } = {}) {
    const source = String(text ?? "");
    const hit = findMatch(source, query);
    if (!hit) return null;
    let from = Math.max(0, hit.start - before);
    let to = Math.min(source.length, hit.end + after);
    if (from > 0) {
        // start at a word when one begins close by, never inside a surrogate pair
        const space = source.slice(from, hit.start).search(SPACE);
        if (space >= 0 && space < 14) from += space + 1;
        else if (isLowSurrogate(source, from)) from--;
    }
    if (to < source.length && isLowSurrogate(source, to)) to++;
    return {
        before: (from > 0 ? "…" : "") + flat(source.slice(from, hit.start)).trimStart(),
        match: flat(source.slice(hit.start, hit.end)),
        after: flat(source.slice(hit.end, to)).trimEnd() + (to < source.length ? "…" : "")
    };
}

/** Text on one line (white space runs → one space), at most `max` characters with "…" (emoji are never split). */
export function oneLine(text, max = 120) {
    const line = flat(String(text ?? "")).trim();
    const chars = [...line];
    return chars.length > max ? `${chars.slice(0, max - 1).join("").trimEnd()}…` : line;
}

/**
 * Escaped text with http(s) addresses turned into links that open in a new tab (any other scheme stays text). Line
 * breaks stay in the text: the bubble keeps them with `white-space: pre-wrap`.
 */
export function linkify(text) {
    return esc(text).replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])/g,
        (url) => `<a href="${url}" target="_blank" rel="nofollow noopener noreferrer ugc">${url}</a>`);
}

function flat(text) {
    return text.replace(/\s+/gu, " ");
}

function isLowSurrogate(text, index) {
    const code = text.charCodeAt(index);
    return code >= 0xdc00 && code <= 0xdfff;
}
