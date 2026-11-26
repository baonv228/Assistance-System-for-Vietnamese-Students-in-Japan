// Tests of the "Dựa trên" interests of "Dành cho bạn" (assets/js/lib/feed-posts.js), shared by the Home and /for-you.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { interestChips, interestNote, reasonLine } from "../../main/resources/static/assets/js/lib/feed-posts.js";

/** The page's chip, reduced to what the test checks. */
const chip = (icon, text, what, extra = "") => `[${icon}|${what}|${text}|${extra}]`;

test("chosen topics first as chosen chips, learnt ones after as learnt chips", () => {
    const feed = { interests: ["Nhà ở", "Y tế", "Ăn uống"], chosenInterests: ["Nhà ở", "Y tế"] };
    assert.equal(interestChips(feed, chip),
        "[interests|Chủ đề bạn chọn|Nhà ở|][interests|Chủ đề bạn chọn|Y tế|][history|Chủ đề bạn hay đọc|Ăn uống|learnt]");
});

test("no interests: no chips; an answer without chosenInterests counts every topic as learnt", () => {
    assert.equal(interestChips({ interests: [], chosenInterests: [] }, chip), "");
    assert.equal(interestChips({}, chip), "");
    assert.equal(interestChips({ interests: ["Đi lại"] }, chip), "[history|Chủ đề bạn hay đọc|Đi lại|learnt]");
});

test("the note says where the interests come from", () => {
    assert.match(interestNote({ interests: [], chosenInterests: [] }), /^Bấm “Điều chỉnh” để chọn chủ đề bạn quan tâm\./);
    assert.match(interestNote({ interests: ["Đi lại"], chosenInterests: [] }),
        /^Chủ đề quan tâm được rút ra từ .*90 ngày qua\. Bấm “Điều chỉnh” để tự chọn chủ đề\.$/);
    assert.equal(interestNote({ interests: ["Nhà ở"], chosenInterests: ["Nhà ở"] }),
        "Bài thuộc chủ đề bạn chọn được ưu tiên trong gợi ý.");
    assert.match(interestNote({ interests: ["Nhà ở", "Đi lại"], chosenInterests: ["Nhà ở"] }),
        /^Bài thuộc chủ đề bạn chọn được ưu tiên trong gợi ý\. Các chủ đề còn lại được rút ra từ /);
});

// ---- reasonLine: the one line above a post of "Dành cho bạn" on the member Home

const AREA = { code: "AREA", label: "Gần khu vực của bạn", detail: "Ga Nippori" };
const STAGE = { code: "STAGE", label: "Hợp giai đoạn của bạn", detail: "Kinh nghiệm" };
const INTEREST = { code: "INTEREST", label: "Chủ đề bạn quan tâm", detail: "Visa và cư trú" };

test("no reason: no line", () => {
    assert.equal(reasonLine({}), "");
    assert.equal(reasonLine({ reasons: [] }), "");
    assert.equal(reasonLine({ reasons: null }), "");
});

test("one reason: its icon, label and detail, nothing counted", () => {
    const html = reasonLine({ reasons: [AREA] });
    assert.match(html, /^<p class="mh-why"><span class="ic [^"]*" aria-hidden="true">location_on<\/span>/);
    assert.match(html, /<span>Gần khu vực của bạn · <b>Ga Nippori<\/b><\/span><\/p>$/);
    assert.doesNotMatch(html, /mh-why-more|sr-only/);
});

test("a reason without detail has no separator; an unknown code gets the generic icon", () => {
    const html = reasonLine({ reasons: [{ code: "NEW_CODE", label: "Mới", detail: null }] });
    assert.match(html, />auto_awesome<\/span><span>Mới<\/span><\/p>$/);
    assert.doesNotMatch(html, / · /);
});

test("each code has its own icon", () => {
    assert.match(reasonLine({ reasons: [{ code: "PREFECTURE", label: "Cùng tỉnh với bạn", detail: "Ga Ueno" }] }), />map</);
    assert.match(reasonLine({ reasons: [STAGE] }), />flag</);
    assert.match(reasonLine({ reasons: [INTEREST] }), />interests</);
});

test("only the first reason is shown; the others are counted, on hover and for screen readers", () => {
    const html = reasonLine({ reasons: [INTEREST, STAGE, AREA] });
    assert.match(html, /<span>Chủ đề bạn quan tâm · <b>Visa và cư trú<\/b><\/span>/);
    assert.match(html, /<span class="mh-why-more" title="Hợp giai đoạn của bạn: Kinh nghiệm; Gần khu vực của bạn: Ga Nippori" aria-hidden="true">\+2<\/span>/);
    assert.match(html, /<span class="sr-only">; Hợp giai đoạn của bạn: Kinh nghiệm; Gần khu vực của bạn: Ga Nippori<\/span><\/p>$/);
    assert.equal((html.match(/Hợp giai đoạn/g) || []).length, 2, "the second reason only in the title and the screen-reader text");
});

test("labels and details are escaped, also inside the title attribute (place names are typed by members)", () => {
    const evil = { code: "AREA", label: "Gần <b>bạn</b>", detail: `Quán "A" <img src=x onerror=alert(1)>` };
    const html = reasonLine({ reasons: [INTEREST, evil] });
    assert.doesNotMatch(html, /<img|<b>bạn/);
    assert.match(html, /title="Gần &lt;b&gt;bạn&lt;\/b&gt;: Quán &quot;A&quot; &lt;img src=x onerror=alert\(1\)&gt;"/);
    const first = reasonLine({ reasons: [evil] });
    assert.match(first, /<span>Gần &lt;b&gt;bạn&lt;\/b&gt; · <b>Quán &quot;A&quot; &lt;img src=x onerror=alert\(1\)&gt;<\/b><\/span>/);
});
