// Tests of the text of a post, question, comment or answer as HTML (assets/js/lib/rich-text.js), shared by the post
// page and "Xem thêm" of the list cards.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";

const { richText } = await import("../../main/resources/static/assets/js/lib/rich-text.js");
const { markHits } = await import("../../main/resources/static/assets/js/lib/search-text.js");

const LINK = (url, text = url) => `<a href="${url}" target="_blank" rel="nofollow noopener ugc">${text}</a>`;

test("a blank line starts a paragraph, a single line break stays a line break", () => {
    assert.equal(richText("Xin chào"), "<p>Xin chào</p>");
    assert.equal(richText("Dòng 1\nDòng 2"), "<p>Dòng 1<br>Dòng 2</p>");
    assert.equal(richText("Đoạn 1\n\nĐoạn 2\n\n\nĐoạn 3"), "<p>Đoạn 1</p><p>Đoạn 2</p><p>Đoạn 3</p>");
    assert.equal(richText("Windows\r\n\r\nxuống dòng\r\nkiểu cũ"), "<p>Windows</p><p>xuống dòng<br>kiểu cũ</p>");
});

test("empty or missing text: one empty paragraph, never “undefined”", () => {
    assert.equal(richText(""), "<p></p>");
    assert.equal(richText(null), "<p></p>");
    assert.equal(richText(undefined), "<p></p>");
});

test("everything is escaped", () => {
    assert.equal(richText(`<script>alert(1)</script> & "a" 'b'`),
        "<p>&lt;script&gt;alert(1)&lt;/script&gt; &amp; &quot;a&quot; &#39;b&#39;</p>");
});

test("web addresses become links; the sign closing the sentence stays outside", () => {
    assert.equal(richText("Xem https://senpaioi.com/blog/7 nhé."), `<p>Xem ${LINK("https://senpaioi.com/blog/7")} nhé.</p>`);
    assert.equal(richText("(https://a.com/x?y=1&z=2), rồi http://b.vn!"),
        `<p>(${LINK("https://a.com/x?y=1&amp;z=2")}), rồi ${LINK("http://b.vn")}!</p>`);
    assert.equal(richText("cuối https://x.jp/abc."), `<p>cuối ${LINK("https://x.jp/abc")}.</p>`);
    assert.equal(richText("https://ja.wikipedia.org/wiki/東京 ok"), `<p>${LINK("https://ja.wikipedia.org/wiki/東京")} ok</p>`);
    assert.equal(richText("không phải ftp://a.com hay www.a.com"), "<p>không phải ftp://a.com hay www.a.com</p>");
});

test("a link never swallows an escaped sign: <, >, quotes end it", () => {
    assert.equal(richText("<https://example.com>"), `<p>&lt;${LINK("https://example.com")}&gt;</p>`);
    assert.equal(richText(`"https://a.com/x"`), `<p>&quot;${LINK("https://a.com/x")}&quot;</p>`);
    assert.equal(richText("https://a.com/<b>x</b>"), `<p>${LINK("https://a.com/")}&lt;b&gt;x&lt;/b&gt;</p>`);
    assert.doesNotMatch(richText(`https://a.com/"onmouseover="alert(1)`), /href="[^"]*"onmouseover/);
});

test("a marker marks the plain text and the text of links, the address of a link stays as it is", () => {
    const html = richText("Thẻ cư trú\n\nhttps://the-cu-tru.jp/the", (text) => markHits(text, "the"));
    assert.equal(html, `<p><mark>Thẻ</mark> cư trú</p><p>${LINK("https://the-cu-tru.jp/the",
        "https://<mark>the</mark>-cu-tru.jp/<mark>the</mark>")}</p>`);
});

test("the default marker gives the same text as the post page always gave for ordinary posts", () => {
    const old = (text) => text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c])
        .replace(/(https?:\/\/[^\s<]+[^\s<.,;:!?)\]'"])/g, (url) => LINK(url))
        .split(/\n{2,}/).map((para) => `<p>${para.replace(/\n/g, "<br>")}</p>`).join("");
    for (const text of ["", "Xin chào", "a\nb\n\nc", "\n\nđầu", "cuối\n\n", "Link https://a.com/x?y=1&z=2, rồi https://b.vn!",
        "https://a.com/path_(x) và https://b.com", "Mục 1:\n- a\n- b\n\nMục 2: http://x.com:8080/a;b", "  cách  \n  dòng  "]) {
        assert.equal(richText(text), old(text), JSON.stringify(text));
    }
});
