// The text of a post, a question, a comment or an answer as HTML: escaped, a blank line starts a new paragraph, single
// line breaks are kept and web addresses become links. Shared by the post page (pages/blog-detail.js) and "Xem thêm"
// of the list cards (lib/feed-card.js), so a post reads the same in both. Tested in Node: rich-text.test.mjs.
import { esc } from "./ui.js";

/** A web address; it never ends with a sign that usually closes the sentence around it. */
const WEB_ADDRESS = /https?:\/\/[^\s<>]+[^\s<>.,;:!?)\]'"]/g;

/**
 * `text` for innerHTML. `mark(part)` turns each piece of plain text (and the text of each link) into escaped HTML:
 * `esc` by default, `markHits` of lib/search-text.js to mark a searched text ("Dành cho bạn" /for-you).
 */
export function richText(text, mark = esc) {
    return String(text ?? "").replace(/\r\n?/g, "\n").split(/\n{2,}/)
        .map((paragraph) => `<p>${linked(paragraph, mark)}</p>`).join("");
}

function linked(paragraph, mark) {
    let html = "";
    let last = 0;
    for (const found of paragraph.matchAll(WEB_ADDRESS)) {
        const url = found[0];
        html += lines(paragraph.slice(last, found.index), mark)
            + `<a href="${esc(url)}" target="_blank" rel="nofollow noopener ugc">${mark(url)}</a>`;
        last = found.index + url.length;
    }
    return html + lines(paragraph.slice(last), mark);
}

const lines = (text, mark) => (text ? mark(text).replace(/\n/g, "<br>") : "");
