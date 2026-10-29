// Tests of the "Tin nhắn" text helpers (assets/js/lib/chat-text.js): search in a conversation ignoring accents and
// case, snippets, one-line previews, links. Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { fold, foldWithMap, findMatch, snippet, oneLine, linkify }
    from "../../main/resources/static/assets/js/lib/chat-text.js";

const hitOf = (text, query) => {
    const hit = findMatch(text, query);
    return hit && text.slice(hit.start, hit.end);
};

test("fold drops Vietnamese accents and marks, đ and case", () => {
    assert.equal(fold("Đà Nẵng"), "da nang");
    assert.equal(fold("TIẾNG Việt"), "tieng viet");
    assert.equal(fold("ư ơ ă â ê ô Ư Ơ"), "u o a a e o u o");
    assert.equal(fold("đường ĐI"), "duong di");
    assert.equal(fold("  xin \n\t chào  "), "xin chao");
});

test("fold keeps Japanese voicing marks and other scripts", () => {
    assert.equal(fold("が"), "が");
    assert.notEqual(fold("ガス"), fold("カス"));
    assert.equal(fold("東京"), "東京");
});

test("fold reads text typed in decomposed form (NFD) the same", () => {
    assert.equal(fold("Đà Nẵng".normalize("NFD")), "da nang");
    assert.equal(fold("Thứ Hai".normalize("NFD")), fold("thu hai"));
});

test("foldWithMap points every folded character back to the text", () => {
    const text = "Ơ  kìa";
    const { folded, map } = foldWithMap(text);
    assert.equal(folded, "o kia");
    assert.equal(map.length, folded.length + 1);
    assert.equal(map[0], 0);
    assert.equal(map[2], 3);            // "k" after two spaces
    assert.equal(map[folded.length], text.length);
});

test("findMatch finds a query without accents in accented text and returns the original part", () => {
    assert.equal(hitOf("Hẹn gặp ở Đà Nẵng nhé", "da nang"), "Đà Nẵng");
    assert.equal(hitOf("Hẹn gặp ở Đà Nẵng nhé", "DA NẴNG"), "Đà Nẵng");
    assert.equal(hitOf("Có bác nào biết chỗ làm thẻ không", "THE"), "thẻ");
    assert.equal(hitOf("xin   chào\nbạn", "chao ban"), "chào\nbạn");
});

test("findMatch covers the marks of decomposed text", () => {
    const text = "Đi Đà Nẵng".normalize("NFD");
    assert.equal(hitOf(text, "nang").normalize("NFC"), "Nẵng");
    assert.equal(hitOf(text, "da").normalize("NFC"), "Đà");
});

test("findMatch answers null for no hit or an empty query", () => {
    assert.equal(findMatch("xin chào", "tạm biệt"), null);
    assert.equal(findMatch("xin chào", "   "), null);
    assert.equal(findMatch("", "a"), null);
    assert.equal(findMatch(null, "a"), null);
});

test("snippet cuts around the hit, on one line, with ellipses", () => {
    const text = `${"Mở đầu dài dòng ".repeat(6)}chỗ làm thẻ cư trú ở Shinjuku\nrồi ${"phần sau rất dài ".repeat(8)}`;
    const s = snippet(text, "the cu tru");
    assert.equal(s.match, "thẻ cư trú");
    assert.ok(s.before.startsWith("…"));
    assert.ok(s.after.endsWith("…"));
    assert.ok(!s.after.includes("\n"));
    assert.ok(s.before.length <= 40);
});

test("snippet of a short text has no ellipses", () => {
    assert.deepEqual(snippet("Cảm ơn anh nhiều", "anh"), { before: "Cảm ơn ", match: "anh", after: " nhiều" });
    assert.equal(snippet("Cảm ơn", "xyz"), null);
});

test("snippet never splits an emoji", () => {
    const text = `${"😀".repeat(30)}tìm${"😀".repeat(50)}`;
    const s = snippet(text, "tim", { before: 5, after: 5 });
    assert.equal(s.match, "tìm");
    assert.ok(!/[\ud800-\udfff]/.test(s.before.replace(/(?:[\ud800-\udbff][\udc00-\udfff])/g, "")));
    assert.ok(!/[\ud800-\udfff]/.test(s.after.replace(/(?:[\ud800-\udbff][\udc00-\udfff])/g, "")));
});

test("oneLine collapses white space and cuts with an ellipsis", () => {
    assert.equal(oneLine("  a\n\nb\tc  "), "a b c");
    assert.equal(oneLine("abcdefghij", 5), "abcd…");
    assert.equal(oneLine("😀😀😀😀😀😀", 3), "😀😀…");
    assert.equal(oneLine(null), "");
});

test("linkify escapes the text and links http(s) addresses only", () => {
    assert.equal(linkify("<script>alert(1)</script>"), "&lt;script&gt;alert(1)&lt;/script&gt;");
    const html = linkify("Xem https://example.com/a?b=1&c=2. Hết");
    assert.ok(html.includes('<a href="https://example.com/a?b=1&amp;c=2"'));
    assert.ok(html.endsWith("</a>. Hết"));
    assert.ok(!linkify("javascript:alert(1)").includes("<a"));
    assert.ok(!linkify("ftp://example.com").includes("<a"));
    assert.equal(linkify("dòng 1\ndòng 2"), "dòng 1\ndòng 2");
});

test("linkify keeps quotes inside an address from breaking out of the attribute", () => {
    const html = linkify('http://x.test/"onmouseover="alert(1)');
    assert.ok(!html.includes('"onmouseover="'));
    assert.ok(html.includes("&quot;onmouseover=&quot;"));
});
