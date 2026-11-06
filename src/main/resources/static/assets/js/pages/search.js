// "Tìm kiếm" (/search?q=, the prototype's "Tìm kiếm chung", opened from the search box of the Home): one text searched
// in every part of the site by GET /api/search (SearchService). Each part shows its first 5 items, where the text
// occurs is marked, and "Xem tất cả" opens that part's own page with the same text (/blog?q=, /qa?q=…), which lists the
// same items. The text is kept in the address; typing asks again after a short pause, Enter at once.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { markHits } from "../lib/search-text.js";
import {
    areaRoman, esc, eventRange, eventStateClass, eventStateLabel, ic, loginUrl, tagChips, timeAgo
} from "../lib/ui.js";

const $ = (selector) => document.querySelector(selector);
const form = $("#search");
const input = $("#q");
const summary = $("#summary");
const jump = $("#jump");
const results = $("#results");
const failure = $("#failure");

/** Typing waits this long before asking (Enter asks at once). */
const SEARCH_DELAY = 300;
/** Ideas under an empty box: everyday things a new student looks for. */
const IDEAS = ["Thẻ cư trú", "Ngân hàng", "Nhà ở", "Làm thêm", "Bệnh viện", "Ga tàu"];

/** `path?q=text` (plus other parameters first). */
const withQuery = (path, q, extra = {}) => `${path}?${new URLSearchParams({ ...extra, q })}`;

/**
 * The parts, in the order shown (that of the prototype, with Hỏi-Đáp next to the posts): what one item looks like and
 * where its "Xem tất cả" leads. `more` gets the text and the part's total.
 */
const GROUPS = [
    { key: "places", title: "Địa điểm", icon: "location_on", item: placeItem,
        more: (q) => ({ href: withQuery("/map", q), label: `Tìm “${q}” trên Bản đồ (có cả địa điểm OpenStreetMap)`, always: true }) },
    { key: "posts", title: "Bài viết", icon: "forum", item: postItem,
        more: (q, n) => ({ href: withQuery("/blog", q), label: `Xem tất cả ${n} bài viết trong Blog` }) },
    { key: "questions", title: "Hỏi-Đáp", icon: "contact_support", item: questionItem,
        more: (q, n) => ({ href: withQuery("/qa", q), label: `Xem tất cả ${n} câu hỏi trong Hỏi-Đáp` }) },
    { key: "guides", title: "Cẩm nang", icon: "menu_book", item: guideItem,
        more: (q, n) => ({ href: withQuery("/guides", q), label: `Xem tất cả ${n} bài cẩm nang` }) },
    { key: "events", title: "Sự kiện", icon: "celebration", item: eventItem,
        more: (q, n) => ({ href: withQuery("/events", q), label: `Xem tất cả ${n} sự kiện` }) },
    { key: "phrases", title: "Mẫu câu và từ vựng", icon: "translate", item: phraseItem,
        more: (q, n) => ({ href: withQuery("/phrases", q), label: `Xem tất cả ${n} mẫu câu và từ vựng trong Nhật ngữ` }) },
    { key: "faqs", title: "Câu hỏi thường gặp", icon: "quiz", item: faqItem,
        more: (q, n) => ({ href: withQuery("/qa", q, { tab: "faq" }), label: `Xem tất cả ${n} câu hỏi thường gặp` }) }
];

let user = null;
let asked = null;   // the text whose answer is on screen (null before the first)
let ticket = 0;     // the request in flight; an answer to an older one is dropped

// ---------------------------------------------------------------- items

/** One result: the whole card opens `href` (the title is the link); `extra` sits above it (a "Nghe" button). */
const card = (icon, href, title, body, extra = "") => `<li class="res-item">${ic(icon)}
    <div class="res-body"><a class="res-link" href="${esc(href)}">${title}</a>${body}</div>${extra}</li>`;

const text = (value, q) => (value ? `<p class="res-text">${markHits(value, q)}</p>` : "");

function placeItem(p, q) {
    const otherName = [p.nameJa, p.nameEn].find((n) => n && n !== p.name);
    const reviews = p.reviewCount
        ? `<span><span class="res-star" aria-hidden="true">★</span> ${p.rating} · ${p.reviewCount} review</span>`
        : "<span>Chưa có review</span>";
    return card(p.categoryIcon || "location_on", `/map?place=${encodeURIComponent(p.id)}`,
        `${markHits(p.name, q)}${otherName ? ` <span class="res-alt" lang="${otherName === p.nameJa ? "ja" : "en"}">${markHits(otherName, q)}</span>` : ""}`,
        `${p.address ? `<p class="res-text">${markHits(p.address, q)}</p>` : ""}
        <span class="res-meta"><span class="badge">${esc(p.categoryName)}</span>
            ${p.areaName ? `<span>${ic("location_on")}${esc(areaRoman(p.areaName))}</span>` : ""}
            ${reviews}${p.closed ? '<span class="badge warn">Đã đóng cửa</span>' : ""}</span>`);
}

function postItem(b, q) {
    return card("article", `/blog/${encodeURIComponent(b.id)}`, markHits(b.title, q),
        `${text(b.excerpt, q)}
        <span class="res-meta">${b.status === "PROCESSING" ? '<span class="badge warn">Đang kiểm duyệt</span>' : ""}
            ${b.topics?.[0] ? `<span class="badge">${esc(b.topics[0].name)}</span>` : ""}
            <span>${esc(b.author.displayName)}</span> ${tagChips(b.author.tags)}
            <span>· ${esc(timeAgo(b.createdAt))}</span>
            <span aria-label="${b.likeCount} lượt thích">${ic("favorite")}${b.likeCount}</span>
            <span aria-label="${b.commentCount} bình luận">${ic("chat_bubble")}${b.commentCount}</span></span>`);
}

function questionItem(x, q) {
    const state = x.solved ? '<span class="badge ok">Đã giải quyết</span>'
        : x.unanswered ? '<span class="badge warn">Chưa ai trả lời</span>' : "";
    return card("help", `/qa/${encodeURIComponent(x.id)}`, markHits(x.title, q),
        `${text(x.excerpt, q)}
        <span class="res-meta">${x.status === "PROCESSING" ? '<span class="badge warn">Đang kiểm duyệt</span>' : ""}${state}
            ${x.topics?.[0] ? `<span class="badge">${esc(x.topics[0].name)}</span>` : ""}
            <span>${esc(x.author.displayName)} · ${esc(timeAgo(x.createdAt))}</span>
            <span aria-label="${x.answerCount} trả lời">${ic("forum")}${x.answerCount}</span></span>`);
}

function guideItem(g, q) {
    // A guide without a topic of its own reports its stage as topic too: named once
    const where = [...new Set([g.stageName, g.topicName].filter(Boolean))].map(esc).join(" · ");
    return card("menu_book", `/guides/${encodeURIComponent(g.id)}`, markHits(g.title, q),
        `${text(g.summary, q)}${where ? `<span class="res-meta">${where}</span>` : ""}`);
}

function eventItem(e, q) {
    return card("celebration", `/events/${encodeURIComponent(e.id)}`, markHits(e.title, q),
        `<span class="res-meta"><span class="badge ${eventStateClass(e)}">${esc(eventStateLabel(e))}</span>
            <span>${esc(eventRange(e))}</span>
            <span>${ic("location_on")}${markHits(e.locationName || "Chưa gắn vị trí", q)}</span></span>`);
}

/** A phrase opens "Nhật ngữ" on that Japanese text; "Nghe" reads it aloud (the browser's own voice). */
function phraseItem(p, q) {
    const listen = "speechSynthesis" in window
        ? `<button class="btn sm" type="button" data-speak="${esc(p.japanese)}" aria-label="Nghe ${esc(p.japanese)}">${ic("volume_up")}Nghe</button>`
        : "";
    return card("translate", withQuery("/phrases", p.japanese),
        `<span class="res-ja" lang="ja">${markHits(p.japanese, q)}</span>`,
        `${p.romaji ? `<span class="res-meta">${markHits(p.romaji, q)}</span>` : ""}
        <p class="res-text res-meaning">${markHits(p.meaningVi, q)}</p>
        <span class="res-meta"><span class="badge">${p.kind === "VOCAB" ? "Từ vựng" : "Mẫu câu"}</span>
            ${p.category ? `<span>${esc(p.category.name)}</span>` : ""}</span>`, listen);
}

function faqItem(f, q) {
    return card("quiz", `/qa?${new URLSearchParams({ tab: "faq", id: f.id })}`, markHits(f.question, q),
        `${text(f.answer, q)}${f.topicName ? `<span class="res-meta">${esc(f.topicName)}</span>` : ""}`);
}

// ---------------------------------------------------------------- page

function render(data) {
    asked = data.q;
    document.title = data.q ? `“${data.q}” – Tìm kiếm – Senpai ơi` : "Tìm kiếm – Senpai ơi";
    history.replaceState(null, "", data.q ? withQuery("/search", data.q) : "/search");
    jump.hidden = true;
    jump.innerHTML = "";
    if (!data.q) {
        summary.textContent = "Gõ để tìm trong địa điểm, bài viết, Hỏi-Đáp, cẩm nang, sự kiện, mẫu câu tiếng Nhật và câu hỏi thường gặp.";
        results.innerHTML = `<div class="res-ideas"><span class="note">Thử tìm:</span>
            ${IDEAS.map((idea) => `<button class="btn sm" type="button" data-idea="${esc(idea)}">${esc(idea)}</button>`).join("")}</div>`;
        return;
    }
    if (data.tooShort) {
        summary.textContent = "Hãy gõ ít nhất 2 chữ hoặc số (một chữ tiếng Nhật cũng được).";
        results.innerHTML = "";
        return;
    }
    const q = data.q;
    const groups = GROUPS.filter((g) => data[g.key].total > 0);
    if (!groups.length) {
        summary.innerHTML = `Không tìm thấy kết quả nào cho “${esc(q)}”.`;
        results.innerHTML = nothingFound(q);
        return;
    }
    summary.innerHTML = `Tìm thấy <b>${data.total}</b> kết quả cho “${esc(q)}”.`;
    if (groups.length > 1) {
        jump.innerHTML = groups.map((g) => `<a href="#res-${g.key}">${ic(g.icon)}${esc(g.title)}
            <span class="count">${data[g.key].total}</span></a>`).join("");
        jump.hidden = false;
    }
    results.innerHTML = groups.map((g) => section(g, data[g.key], q)).join("")
        + (data.places.total ? "" : `<p class="note">Không thấy địa điểm cần tìm?
            <a class="link" href="${esc(withQuery("/map", q))}">Tìm “${esc(q)}” trên Bản đồ</a>: có cả địa điểm của OpenStreetMap.</p>`);
}

function section(group, part, q) {
    const more = group.more(q, part.total);
    const links = [];
    if (more.always || part.total > part.items.length) {
        links.push(`<a class="link" href="${esc(more.href)}">${esc(more.label)}${ic("arrow_forward")}</a>`);
    }
    // A Member can narrow the posts to their own feed, ranked for them
    if (group.key === "posts" && user?.role === "USER") {
        links.push(`<a class="link" href="${esc(withQuery("/for-you", q))}">${ic("auto_awesome")}Chỉ tìm trong bài dành cho bạn</a>`);
    }
    return `<section class="res-group" id="res-${group.key}" aria-labelledby="res-${group.key}-title">
        <h3 id="res-${group.key}-title">${ic(group.icon)}${esc(group.title)} <span class="count">${part.total}</span></h3>
        <ul class="res-list">${part.items.map((x) => group.item(x, q)).join("")}</ul>
        ${links.length ? `<p class="res-more">${links.join("")}</p>` : ""}
    </section>`;
}

function nothingFound(q) {
    const ask = !user ? loginUrl("/qa/ask") : user.role === "USER" ? "/qa/ask" : null;
    return `<div class="res-empty"><b>Gợi ý</b><ul>
        <li>Thử từ khóa ngắn hơn hoặc viết cách khác; có dấu hay không dấu đều được.</li>
        <li><a class="link" href="${esc(withQuery("/map", q))}">Tìm “${esc(q)}” trên Bản đồ</a>: có cả địa điểm của OpenStreetMap.</li>
        ${ask ? `<li>Chưa ai hỏi điều này? <a class="link" href="${esc(ask)}">Đặt câu hỏi cho các senpai</a>.</li>` : ""}
    </ul></div>`;
}

/** Searches the text unless it is the one on screen (`always`: Enter, an idea, a retry after a failure). */
async function run(value, always = false) {
    const q = value.trim();
    if (q === asked && !always) {
        return;
    }
    const mine = ++ticket;
    failure.hidden = true;
    results.setAttribute("aria-busy", "true");
    try {
        const data = q ? await apiClient.search(q) : { q: "" };
        if (mine === ticket) {
            render(data);
        }
    } catch (error) {
        if (mine === ticket) {
            // What is on screen stays; Enter asks again
            failure.textContent = error.message;
            failure.hidden = false;
            if (asked === null) {
                summary.textContent = "";
            }
        }
    } finally {
        if (mine === ticket) {
            results.removeAttribute("aria-busy");
        }
    }
}

let typing;
input.addEventListener("input", () => {
    clearTimeout(typing);
    typing = setTimeout(() => run(input.value), SEARCH_DELAY);
});
form.addEventListener("submit", (event) => {
    event.preventDefault();
    clearTimeout(typing);
    run(input.value, true);
});
results.addEventListener("click", (event) => {
    const idea = event.target.closest("[data-idea]");
    if (idea) {
        input.value = idea.dataset.idea;
        run(input.value, true);
        return;
    }
    const speak = event.target.closest("[data-speak]");
    if (speak) {
        const utterance = new SpeechSynthesisUtterance(speak.dataset.speak);
        utterance.lang = "ja-JP";
        utterance.rate = 0.85;
        speechSynthesis.cancel();
        speechSynthesis.speak(utterance);
    }
});

user = await mountSiteHeader("search").catch(() => null);
const first = (new URLSearchParams(location.search).get("q") || "").slice(0, 100);
input.value = first;
if (!first.trim()) {
    input.focus();
}
await run(first, true);
