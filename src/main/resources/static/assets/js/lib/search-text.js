// Marks what a search found in a title or an excerpt ("Tìm kiếm" /search, "Dành cho bạn" /for-you). The server finds
// the items (accents and case ignored); this only shows where the text occurs, with the same folding as the chat search
// (lib/chat-text.js). Tested in Node: node --test src/test/js/ (search-text.test.mjs).
import { fold, foldWithMap } from "./chat-text.js";
import { esc } from "./ui.js";

/**
 * `text` escaped for innerHTML, every place `query` occurs in it wrapped in <mark> (Vietnamese accents, case and runs
 * of white space ignored: "pho" marks "Phở", "nha o" marks "nhà  ở"). No query or no hit: the text escaped as it is.
 */
export function markHits(text, query) {
    const source = String(text ?? "");
    const q = fold(query);
    if (!q) return esc(source);
    const { folded, map } = foldWithMap(source);
    let html = "";
    let last = 0;
    for (let at = folded.indexOf(q); at >= 0; at = folded.indexOf(q, at + q.length)) {
        const start = map[at];
        const end = map[at + q.length] ?? source.length;
        if (start < last || end <= start) continue;
        html += `${esc(source.slice(last, start))}<mark>${esc(source.slice(start, end))}</mark>`;
        last = end;
    }
    return html + esc(source.slice(last));
}
