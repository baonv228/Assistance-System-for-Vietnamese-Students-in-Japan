// Tests of how a notification is drawn in the bell's panel and on /notifications (assets/js/lib/notif-view.js).
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import {
    actorOf, badgeOf, groupByDay, initialOf, mergeFresh, messageHtml, newestId, pictureHtml, shortAgo, toneOf
} from "../../main/resources/static/assets/js/lib/notif-view.js";

const comment = (content, link = "/blog/3") => ({ type: "NEW_COMMENT", content, link });

test("the member of a comment is the name before ' đã '; other types have none", () => {
    assert.equal(actorOf(comment("Lan Anh đã bình luận bài “Visa” của bạn.")), "Lan Anh");
    assert.equal(actorOf(comment("Minh đã trả lời câu hỏi “A” của bạn.", "/qa/4")), "Minh");
    assert.equal(actorOf(comment("đã bình luận")), null);
    assert.equal(actorOf(comment(`${"a".repeat(61)} đã bình luận`)), null);
    assert.equal(actorOf(comment("Bài “x đã y” có bình luận")), null);
    assert.equal(actorOf({ type: "CONTENT_PUBLISHED", content: "Lan đã …" }), null);
    assert.equal(actorOf(null), null);
});

test("names and “quoted” titles in bold, the rest escaped", () => {
    assert.equal(messageHtml(comment("Lan đã bình luận bài “Thuê nhà” của bạn.")),
        "<b>Lan</b> đã bình luận bài “<b>Thuê nhà</b>” của bạn.");
    assert.equal(messageHtml({ type: "CONTENT_REJECTED", content: "Review “<b>Ga</b>” chưa được đăng vì có link." }),
        "Review “<b>&lt;b&gt;Ga&lt;/b&gt;</b>” chưa được đăng vì có link.");
    assert.equal(messageHtml(comment("<img src=x> đã bình luận “a & b”")),
        "<b>&lt;img src=x&gt;</b> đã bình luận “<b>a &amp; b</b>”");
    assert.equal(messageHtml({ type: "PIN_MERGED", content: "Ghim “A” trùng với “B” đã có." }),
        "Ghim “<b>A</b>” trùng với “<b>B</b>” đã có.");
    assert.equal(messageHtml({ type: "TAG_GRANTED", content: "Bạn đã được cấp tag Du học sinh." }),
        "Bạn đã được cấp tag Du học sinh.");
    assert.equal(messageHtml({ type: "X", content: "Mở “ không đóng" }), "Mở “ không đóng");
    assert.equal(messageHtml({}), "");
});

test("each type has its badge; an answer to a question gets the Q&A bubble; unknown types the bell", () => {
    assert.deepEqual(badgeOf(comment("A đã bình luận")), ["chat_bubble", "br"]);
    assert.deepEqual(badgeOf(comment("A đã trả lời câu hỏi", "/qa/9")), ["question_answer", "br"]);
    assert.deepEqual(badgeOf({ type: "CONTENT_PUBLISHED" }), ["check", "ok"]);
    assert.deepEqual(badgeOf({ type: "CONTENT_REJECTED" }), ["block", "crit"]);
    assert.deepEqual(badgeOf({ type: "QUEST_STREAK" }), ["local_fire_department", "lan"]);
    assert.deepEqual(badgeOf({ type: "SOMETHING_NEW" }), ["notifications", "br"]);
});

test("the picture: a comment shows the member's letter, the rest the flower of Senpai ơi", () => {
    const member = pictureHtml(comment("Nguyễn Văn Ánh đã bình luận"));
    assert.match(member, /np-face np-tone-\d">Á</);
    assert.match(member, /np-badge np-br/);
    const site = pictureHtml({ type: "CONTENT_REJECTED", content: "Bài viết “A” chưa được đăng" });
    assert.match(site, /np-logo"><svg/);
    assert.match(site, /np-badge np-crit/);
    assert.equal(initialOf("  trần   thị  bích "), "B");
    assert.equal(initialOf(""), "?");
    assert.equal(toneOf("Lan"), toneOf("Lan"));
    assert.ok(toneOf("Lan") >= 0 && toneOf("Lan") < 6);
});

test("short times as on Facebook", () => {
    const now = Date.parse("2026-11-25T12:00:00Z");
    const ago = (ms) => new Date(now - ms).toISOString();
    assert.equal(shortAgo(ago(20 * 1000), now), "Vừa xong");
    assert.equal(shortAgo(ago(5 * 60 * 1000), now), "5 phút");
    assert.equal(shortAgo(ago(20 * 3600 * 1000), now), "20 giờ");
    assert.equal(shortAgo(ago(2 * 86400 * 1000), now), "2 ngày");
    assert.equal(shortAgo(ago(15 * 86400 * 1000), now), "2 tuần");
    assert.equal(shortAgo(ago(400 * 86400 * 1000), now), "1 năm");
    assert.equal(shortAgo(new Date(now + 60000).toISOString(), now), "Vừa xong");   // clock a little ahead
    assert.equal(shortAgo("not a date", now), "");
});

test("'Hôm nay' by this device's date, then 'Trước đó'; empty groups left out", () => {
    const now = new Date(2026, 10, 25, 9, 0);
    const at = (d, h) => ({ id: d * 100 + h, createdAt: new Date(2026, 10, d, h, 0).toISOString() });
    const groups = groupByDay([at(25, 8), at(25, 0), at(24, 23), at(20, 10)], now);
    assert.deepEqual(groups.map((g) => [g.key, g.title, g.items.map((n) => n.id)]),
        [["today", "Hôm nay", [2508, 2500]], ["earlier", "Trước đó", [2423, 2010]]]);
    assert.deepEqual(groupByDay([at(24, 1)], now).map((g) => g.title), ["Trước đó"]);
    assert.deepEqual(groupByDay([at(25, 1)], now).map((g) => g.title), ["Hôm nay"]);
    assert.deepEqual(groupByDay([], now), []);
});

test("fresh notifications go on top, the shown ones take the fresh copy, none twice", () => {
    const n = (id, minute, read = false) => ({ id, read, createdAt: `2026-11-25T10:${String(minute).padStart(2, "0")}:00Z` });
    const shown = [n(5, 50), n(4, 40), n(3, 30)];
    const merged = mergeFresh(shown, [n(7, 59), n(6, 55), n(5, 50, true)]);
    assert.deepEqual(merged.map((x) => x.id), [7, 6, 5, 4, 3]);
    assert.equal(merged.find((x) => x.id === 5).read, true);
    // same second: the higher id first, as the server orders them
    assert.deepEqual(mergeFresh([n(8, 1)], [n(9, 1)]).map((x) => x.id), [9, 8]);
    assert.equal(newestId(merged), 7);
    assert.equal(newestId([]), 0);
});
