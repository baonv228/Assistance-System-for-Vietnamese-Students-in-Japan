// Tests of the Hỏi-Đáp address (assets/js/lib/qa-state.js): community questions and the "Câu hỏi thường gặp" tab
// read from and written to the query string. Run: node --test src/test/js/

import { test } from "node:test";
import assert from "node:assert/strict";
import { communityHref, faqHref, qaQuery, Q_MAX, readQaState }
    from "../../main/resources/static/assets/js/lib/qa-state.js";

const DEFAULTS = {
    tab: "community", q: "", topicIds: [], status: "ALL", sort: "NEWEST", page: 1, faqTopic: "", faqId: null
};

test("an empty address is the first page of the community questions", () => {
    assert.deepEqual(readQaState(""), DEFAULTS);
    assert.deepEqual(readQaState("?"), DEFAULTS);
    assert.equal(qaQuery(DEFAULTS), "");
});

test("community filters are read and written back unchanged", () => {
    const search = "q=visa&topics=3%2C7&status=UNSOLVED&sort=LIKES&page=2";
    const state = readQaState(`?${search}`);
    assert.deepEqual(state, { ...DEFAULTS, q: "visa", topicIds: [3, 7], status: "UNSOLVED", sort: "LIKES", page: 2 });
    assert.equal(qaQuery(state), search);
});

test("the FAQ tab keeps its search, topic and page, or one entry", () => {
    const list = readQaState("?tab=faq&q=mua+sim&topic=LIVING&page=3");
    assert.deepEqual(list, { ...DEFAULTS, tab: "faq", q: "mua sim", faqTopic: "LIVING", page: 3 });
    assert.equal(qaQuery(list), "tab=faq&q=mua+sim&topic=LIVING&page=3");

    const one = readQaState("?tab=faq&id=12");
    assert.equal(one.faqId, 12);
    assert.equal(qaQuery(one), "tab=faq&id=12");
    // An entry shown alone drops the list's search, topic and page from the address
    assert.equal(qaQuery({ ...one, q: "sim", faqTopic: "LIVING", page: 4 }), "tab=faq&id=12");
});

test("each tab writes only its own filters", () => {
    const state = { ...DEFAULTS, q: "  rác  ", topicIds: [5], status: "SOLVED", sort: "ANSWERS", faqTopic: "OTHER" };
    assert.equal(qaQuery(state), "q=r%C3%A1c&topics=5&status=SOLVED&sort=ANSWERS");
    assert.equal(qaQuery({ ...state, tab: "faq" }), "tab=faq&q=r%C3%A1c&topic=OTHER");
});

test("unknown or broken values fall back instead of failing", () => {
    const state = readQaState("?tab=FAQ&status=OPEN&sort=old&page=-4&topics=a,0,-2,4.5,9&topic=living;drop&id=0");
    assert.deepEqual(state, { ...DEFAULTS, topicIds: [9] });
    assert.equal(readQaState("?tab=faq&topic=" + "A".repeat(31)).faqTopic, "");
    assert.equal(readQaState("?tab=faq&id=12abc").faqId, null);
    assert.equal(readQaState("?tab=faq&id=" + "9".repeat(16)).faqId, null);
    assert.equal(readQaState("?page=abc").page, 1);
    assert.equal(readQaState(`?q=${"x".repeat(150)}`).q.length, Q_MAX);
});

test("links to the FAQ tab and back to the community questions", () => {
    assert.equal(faqHref(), "/qa?tab=faq");
    assert.equal(faqHref({ q: " đổ rác " }), "/qa?tab=faq&q=%C4%91%E1%BB%95+r%C3%A1c");
    assert.equal(faqHref({ id: 7 }), "/qa?tab=faq&id=7");
    assert.equal(faqHref({ q: "sim", id: 7 }), "/qa?tab=faq&id=7");
    assert.equal(communityHref(), "/qa");
    assert.equal(communityHref("visa"), "/qa?q=visa");
    // What a link writes, the page reads back
    assert.equal(readQaState(new URL(faqHref({ q: "đổ rác" }), "http://x").search).q, "đổ rác");
});
