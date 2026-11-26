// Tests of the Facebook-like cards of the Blog and Hỏi-Đáp lists (assets/js/lib/feed-card.js) and their photo grid
// (assets/js/lib/post-media.js): what each card shows, and who gets which "Thích" button.
// Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";

// loginUrl() of a guest's "Thích" reads the page address
globalThis.location = { pathname: "/blog", search: "?topics=3", origin: "https://senpaioi.test" };

const { mediaGrid, soloRatio, GRID_MAX } = await import("../../main/resources/static/assets/js/lib/post-media.js");
const { feedCard, feedSkeleton } = await import("../../main/resources/static/assets/js/lib/feed-card.js");
const { forYouCard } = await import("../../main/resources/static/assets/js/lib/feed-posts.js");
const { markHits } = await import("../../main/resources/static/assets/js/lib/search-text.js");

const photo = (id, width = 1200, height = 800) =>
    ({ id, type: "IMAGE", url: `/media/blog/2026/11/p${id}.jpg`, mimeType: "image/jpeg", width, height });
const video = (id) => ({ id, type: "VIDEO", url: `/media/blog/2026/11/v${id}.mp4`, mimeType: "video/mp4", width: null, height: null });

const count = (html, pattern) => (html.match(pattern) || []).length;

function post(extra = {}) {
    return {
        id: 7, type: "POST", status: "PUBLISHED", pinned: false, title: "Mở tài khoản <ngân hàng>",
        excerpt: "Mình đi Yucho ở Arakawa…", author: { id: 3, displayName: "Lan", avatarUrl: null, tags: [] },
        topics: [{ id: 2, name: "Thủ tục" }], places: [], likeCount: 0, commentCount: 0,
        createdAt: new Date().toISOString(), media: [], liked: false, ...extra
    };
}

const member = { id: 9, role: "USER" };

// ---------------------------------------------------------------- photo grid

test("no media: no grid", () => {
    assert.equal(mediaGrid([]), "");
    assert.equal(mediaGrid(), "");
});

test("one to five photos: one tile each, the grid named by the count", () => {
    for (let n = 1; n <= GRID_MAX; n++) {
        const html = mediaGrid(Array.from({ length: n }, (_, i) => photo(i + 1)));
        assert.match(html, new RegExp(`class="pm-grid pm-n${n}"`));
        assert.equal(count(html, /class="pm-tile/g), n);
        assert.doesNotMatch(html, /pm-more/);
    }
});

test("more than five: five tiles, the last says how many more and opens at its own place", () => {
    const html = mediaGrid([photo(1), photo(2), photo(3), photo(4), photo(5), video(6)]);
    assert.match(html, /class="pm-grid pm-n5"/);
    assert.equal(count(html, /class="pm-tile/g), 5);
    assert.match(html, /<span class="pm-more" aria-hidden="true">\+1<\/span>/);
    assert.match(html, /data-media-index="4"\s+aria-label="Xem ảnh 5 trên 6, còn 1 ảnh hoặc video nữa"/);
});

test("a lone video plays in place; a video among photos is a tile with a play mark", () => {
    const solo = mediaGrid([video(1)]);
    assert.match(solo, /pm-solo-video/);
    assert.match(solo, /<video controls playsinline preload="metadata" src="\/media\/blog\/2026\/11\/v1.mp4#t=0.1">/);
    assert.doesNotMatch(solo, /pm-tile/);

    const mixed = mediaGrid([video(1), photo(2)]);
    assert.match(mixed, /class="pm-tile pm-video"/);
    assert.match(mixed, /src="\/media\/blog\/2026\/11\/v1.mp4#t=0.1"/);
    assert.match(mixed, /class="pm-play"/);
    assert.match(mixed, /aria-label="Xem video 1 trên 2"/);
});

test("a lone photo keeps its shape between 4:5 and 1.91:1", () => {
    assert.equal(soloRatio(photo(1, 1200, 800)), 1.5);
    assert.equal(soloRatio(photo(1, 600, 2000)), 0.8);
    assert.equal(soloRatio(photo(1, 4000, 1000)), 1.91);
    assert.equal(soloRatio({ type: "IMAGE" }), 4 / 3);
    assert.match(mediaGrid([photo(1, 1200, 800)]), /style="--pm-ar:1.500"/);
    assert.match(mediaGrid([photo(1, 1200, 800)]), /aria-label="Xem ảnh lớn hơn"/);
});

test("media addresses are escaped", () => {
    const html = mediaGrid([{ id: 1, type: "IMAGE", url: '/media/x"onerror="alert(1).jpg', width: 1, height: 1 }]);
    assert.doesNotMatch(html, /"onerror="/);
    assert.match(html, /&quot;onerror=&quot;/);
});

// ---------------------------------------------------------------- card

test("a card shows author, time, title, excerpt with “Xem thêm”, topics and the grid; text is escaped", () => {
    const html = feedCard(post({ media: [photo(1), photo(2)] }), "POST", null);
    assert.match(html, /data-post="7"/);
    assert.match(html, /<a href="\/users\/3">Lan<\/a>/);
    assert.match(html, /Mở tài khoản &lt;ngân hàng&gt;/);
    assert.match(html, /<a class="fbp-more" href="\/blog\/7">Xem thêm<\/a>/);
    assert.match(html, /data-topic="2"/);
    assert.match(html, /class="pm-grid pm-n2"/);
    assert.match(html, /Công khai/);
});

test("an excerpt that is not cut has no “Xem thêm”; no topics and no media: no rows for them", () => {
    const html = feedCard(post({ excerpt: "Ngắn gọn.", topics: [] }), "POST", null);
    assert.doesNotMatch(html, /Xem thêm/);
    assert.doesNotMatch(html, /fbp-tags/);
    assert.doesNotMatch(html, /pm-grid/);
});

test("counts: hidden at 0 and 0, else likes and comments (answers for a question)", () => {
    assert.match(feedCard(post(), "POST", null), /<div class="fbp-counts" hidden>/);
    const blog = feedCard(post({ likeCount: 1234, commentCount: 5 }), "POST", null);
    assert.match(blog, /1\.234<span class="sr-only"> lượt thích<\/span>/);
    assert.match(blog, /href="\/blog\/7#comments">5 bình luận<\/a>/);
    const question = feedCard(post({ type: "QA", answerCount: 3, solved: true }), "QA", null);
    assert.match(question, /class="fbp-replies solved" href="\/qa\/7#comments">3 câu trả lời<\/a>/);
});

test("guest: “Thích” sends to sign in and back to this list", () => {
    const html = feedCard(post(), "POST", null);
    assert.match(html, /<a class="fbp-act" href="\/login\?next=%2Fblog%3Ftopics%3D3" title="Đăng nhập để thích">/);
    assert.match(html, /href="\/blog\/7#reply"/);
    assert.match(html, /data-fbp="share"/);
});

test("member: a toggle that shows the like; own post: no “Thích” at all; staff: disabled", () => {
    assert.match(feedCard(post(), "POST", member), /data-fbp="like" aria-pressed="false"/);
    assert.match(feedCard(post({ liked: true }), "POST", member), /class="fbp-act on" type="button" data-fbp="like" aria-pressed="true"/);

    const own = feedCard(post(), "POST", { id: 3, role: "USER" });
    assert.doesNotMatch(own, /Thích/);
    assert.match(own, /class="fbp-actions two"/);

    const staff = feedCard(post(), "POST", { id: 50, role: "MODERATOR" });
    assert.match(staff, /<button class="fbp-act" type="button" disabled title="Tài khoản của bạn không thực hiện được thao tác này.">/);
    assert.doesNotMatch(staff, /data-fbp="like"/);
});

test("a post still being scanned: flag, “only you”, no action bar", () => {
    const html = feedCard(post({ status: "PROCESSING" }), "POST", { id: 3, role: "USER" });
    assert.match(html, /class="card fbp processing"/);
    assert.match(html, /Đang quét ảnh\/video/);
    assert.match(html, /Chỉ mình bạn/);
    assert.doesNotMatch(html, /fbp-actions/);
});

test("question flags: solved, or nobody answered yet; “Trả lời” instead of “Bình luận”", () => {
    const solved = feedCard(post({ type: "QA", solved: true, unanswered: false, answerCount: 2 }), "QA", member);
    assert.match(solved, /Đã giải quyết/);
    assert.doesNotMatch(solved, /Chưa ai trả lời/);
    assert.match(solved, /href="\/qa\/7#reply"/);
    assert.match(solved, /<span>Trả lời<\/span>/);

    const open = feedCard(post({ type: "QA", solved: false, unanswered: true, answerCount: 0, pinned: true }), "QA", member);
    assert.match(open, /Chưa ai trả lời/);
    assert.match(open, /Đã ghim/);
});

test("skeleton: n placeholder cards, hidden from screen readers", () => {
    const html = feedSkeleton(3);
    assert.equal(count(html, /class="card fbp-skel" aria-hidden="true"/g), 3);
    assert.match(html, /Đang tải…/);
});

// ---------------------------------------------------------------- "Dành cho bạn" (Home and /for-you)

/** A post of /api/me/home forYou or /api/me/for-you items: no status or pinned flag (always published). */
function forYou(extra = {}) {
    const { status, pinned, ...rest } = post();
    return {
        ...rest, authorName: "Lan", topicName: "Thủ tục", engaged: false,
        reason: "Gần khu vực của bạn",
        reasons: [{ code: "AREA", label: "Gần khu vực của bạn", detail: "Ga Nippori" },
            { code: "INTEREST", label: "Chủ đề bạn quan tâm", detail: "Thủ tục" }],
        ...extra
    };
}

test("“Dành cho bạn”: the Blog card with why it was chosen above the author", () => {
    const html = forYouCard(forYou({ media: [photo(1), photo(2), photo(3)] }), member);
    const top = html.indexOf('class="fbp-top"');
    assert.ok(top > 0 && top < html.indexOf('class="fbp-head"'), "reasons before the author");
    assert.match(html, /Gần khu vực của bạn · <b>Ga Nippori<\/b>/);
    assert.match(html, /class="why interest"/);
    assert.match(html, /class="pm-grid pm-n3"/);
    assert.match(html, /<a href="\/users\/3">Lan<\/a>/);
    assert.match(html, /href="\/blog\/7#reply"/);
});

test("“Dành cho bạn”: published, so a member can like it; topics open the Blog filtered", () => {
    const html = forYouCard(forYou({ liked: true }), member);
    assert.match(html, /class="fbp-act on" type="button" data-fbp="like" aria-pressed="true"/);
    assert.match(html, /data-fbp="share"/);
    assert.doesNotMatch(html, /Đang quét|Đã ghim/);
    assert.match(html, /<a class="badge topic" href="\/blog\?topics=2" title="Xem các bài thuộc chủ đề này">Thủ tục<\/a>/);
    assert.doesNotMatch(html, /data-topic=/);
});

test("“Dành cho bạn”: no reasons, no strip", () => {
    assert.doesNotMatch(forYouCard(forYou({ reasons: [], reason: null }), member), /fbp-top/);
});

test("/for-you search: the text is marked in the title and excerpt, still escaped", () => {
    const html = forYouCard(forYou(), member, { mark: (text) => markHits(text, "ngan hang") });
    assert.match(html, /Mở tài khoản &lt;<mark>ngân hàng<\/mark>&gt;/);
    assert.doesNotMatch(html, /<ngân hàng>/);
    const plain = forYouCard(forYou(), member);
    assert.match(plain, /Mở tài khoản &lt;ngân hàng&gt;/);
});

// ---------------------------------------------------------------- an answer without the card's parts

/**
 * A post of "Dành cho bạn" exactly as a server answers it before its update (the 25/11 bug: new page, server not
 * restarted): no author, topics, places, media nor liked.
 */
const oldFeedPost = () => ({
    id: 41, title: "Kinh nghiệm thuê nhà", excerpt: "Mình thuê nhà ở Arakawa…", authorName: "Hoa",
    topicName: "Nhà ở", likeCount: 4, commentCount: 1, createdAt: new Date().toISOString(),
    reason: "Gần khu vực của bạn", reasons: [{ code: "AREA", label: "Gần khu vực của bạn", detail: "Ga Nippori" }],
    engaged: false
});

test("an old answer still draws: name and topic without links, no photos, “Thích” for a member", () => {
    let html;
    assert.doesNotThrow(() => { html = forYouCard(oldFeedPost(), member); });
    assert.match(html, /<b>Hoa<\/b>/);
    assert.doesNotMatch(html, /href="\/users\//);
    assert.match(html, /<span class="fbp-avatar" aria-hidden="true">/);
    assert.match(html, /<span class="badge topic">Nhà ở<\/span>/);
    assert.doesNotMatch(html, /pm-grid/);
    assert.match(html, /Gần khu vực của bạn · <b>Ga Nippori<\/b>/);
    assert.match(html, /4<span class="sr-only"> lượt thích<\/span>/);
    assert.match(html, /data-fbp="like" aria-pressed="false"/);
});

test("missing counts or parts never throw, for every kind and viewer", () => {
    const bare = { id: 5, status: "PUBLISHED", title: "T", createdAt: new Date().toISOString() };
    for (const user of [null, member, { id: 1, role: "MODERATOR" }]) {
        for (const kind of ["POST", "QA"]) {
            assert.doesNotThrow(() => feedCard(bare, kind, user), `${kind} ${user?.role ?? "guest"}`);
        }
    }
    assert.match(feedCard(bare, "POST", member), /<div class="fbp-counts" hidden>/);
});
