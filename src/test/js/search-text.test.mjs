// Tests of the search marks (assets/js/lib/search-text.js) used by "Tìm kiếm" (/search) and "Dành cho bạn" (/for-you).
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { markHits } from "../../main/resources/static/assets/js/lib/search-text.js";

test("marks every hit, accents and case ignored, keeping the text as written", () => {
    assert.equal(markHits("Phở ngon ở Ueno, PHO bò", "pho"), "<mark>Phở</mark> ngon ở Ueno, <mark>PHO</mark> bò");
    assert.equal(markHits("Đăng ký ở phường", "dang ky"), "<mark>Đăng ký</mark> ở phường");
    assert.equal(markHits("thủ tục VISA", "Visa"), "thủ tục <mark>VISA</mark>");
});

test("a hit may span a run of white space, and text typed in decomposed form", () => {
    assert.equal(markHits("nhà\n  ở gần ga", "nha o"), "<mark>nhà\n  ở</mark> gần ga");
    assert.equal(markHits("Đà Nẵng".normalize("NFD"), "da nang"), `<mark>${"Đà Nẵng".normalize("NFD")}</mark>`);
});

test("escapes the text and the hit, so markup typed in a post stays text", () => {
    assert.equal(markHits("<b>visa</b> & thẻ", "visa"), "&lt;b&gt;<mark>visa</mark>&lt;/b&gt; &amp; thẻ");
    assert.equal(markHits("a < b", "<"), "a <mark>&lt;</mark> b");
});

test("no query, a blank one or no hit: the text escaped as it is", () => {
    assert.equal(markHits("Ga <Ueno>", ""), "Ga &lt;Ueno&gt;");
    assert.equal(markHits("Ga Ueno", "   "), "Ga Ueno");
    assert.equal(markHits("Ga Ueno", "Shibuya"), "Ga Ueno");
    assert.equal(markHits(null, "a"), "");
});

test("Japanese text is marked as typed; overlapping hits are marked once", () => {
    assert.equal(markHits("日暮里駅の近く", "駅"), "日暮里<mark>駅</mark>の近く");
    assert.equal(markHits("aaaa", "aa"), "<mark>aa</mark><mark>aa</mark>");
    assert.equal(markHits("aaa", "aa"), "<mark>aa</mark>a");
});
