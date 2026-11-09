// Tests of the "Dựa trên" interests of "Dành cho bạn" (assets/js/lib/feed-posts.js), shared by the Home and /for-you.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { interestChips, interestNote } from "../../main/resources/static/assets/js/lib/feed-posts.js";

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
