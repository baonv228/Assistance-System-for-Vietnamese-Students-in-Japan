// What the Hỏi-Đáp page shows, kept in its address so refresh, Back and shared links show the same list:
//   community questions (default)  /qa?q=&topics=1,2&status=UNSOLVED&sort=LIKES&page=2
//   "Câu hỏi thường gặp" (FAQ)      /qa?tab=faq&q=&topic=LIVING&page=2   one entry: /qa?tab=faq&id=12
// Pages are counted from 1 in the address and from 0 in the API. Old /faq links answer 301 to the FAQ tab.
// No DOM here, so node --test can check it (src/test/js/qa-state.test.mjs).

export const STATUSES = ["ALL", "UNANSWERED", "UNSOLVED", "SOLVED"];
export const SORTS = ["NEWEST", "LIKES", "ANSWERS"];
export const TABS = ["community", "faq"];
export const Q_MAX = 100;

const FAQ_TOPIC = /^[A-Z_]{1,30}$/;
const ID = /^[1-9]\d{0,14}$/;

/** The page's state from a query string ("?tab=faq&q=sim" or a URLSearchParams); anything unknown falls back. */
export function readQaState(search) {
    const p = new URLSearchParams(search);
    const oneOf = (value, allowed, fallback) => (allowed.includes(value) ? value : fallback);
    const topic = p.get("topic") || "";
    const id = p.get("id") || "";
    return {
        tab: oneOf(p.get("tab"), TABS, "community"),
        q: (p.get("q") || "").slice(0, Q_MAX),
        topicIds: (p.get("topics") || "").split(",").map(Number).filter((n) => Number.isInteger(n) && n > 0),
        status: oneOf(p.get("status"), STATUSES, "ALL"),
        sort: oneOf(p.get("sort"), SORTS, "NEWEST"),
        page: Math.max(1, Number.parseInt(p.get("page"), 10) || 1),
        faqTopic: FAQ_TOPIC.test(topic) ? topic : "",
        faqId: ID.test(id) ? Number(id) : null
    };
}

/** The query string (without "?") for a state: only what differs from the defaults, only the shown tab's filters. */
export function qaQuery(state) {
    const p = new URLSearchParams();
    const q = (state.q || "").trim();
    if (state.tab === "faq") {
        p.set("tab", "faq");
        if (state.faqId) {
            p.set("id", state.faqId);
            return p.toString();
        }
        if (q) p.set("q", q);
        if (state.faqTopic) p.set("topic", state.faqTopic);
    } else {
        if (q) p.set("q", q);
        if (state.topicIds?.length) p.set("topics", state.topicIds.join(","));
        if (state.status && state.status !== "ALL") p.set("status", state.status);
        if (state.sort && state.sort !== "NEWEST") p.set("sort", state.sort);
    }
    if (state.page > 1) p.set("page", state.page);
    return p.toString();
}

/** Link to the FAQ tab: its list (searching q) or one entry. */
export function faqHref({ q = "", id = null } = {}) {
    return `/qa?${qaQuery({ ...readQaState(""), tab: "faq", q, faqId: id })}`;
}

/** Link to the community questions, searching q. */
export function communityHref(q = "") {
    const query = qaQuery({ ...readQaState(""), q });
    return `/qa${query ? `?${query}` : ""}`;
}
