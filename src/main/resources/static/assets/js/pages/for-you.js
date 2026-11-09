// "Dành cho bạn" ("Xem tất cả" on the member Home): the whole personal feed, ranked by the server exactly like the
// Home's five posts (a place in the user's area, then the prefecture, a topic the user chose, a topic that suits the
// stage, a topic learnt from what the user did; posts of the last 30 days first; posts already liked, saved or
// commented on last), 10 at a time. A scope keeps only the posts that matched one reason; "Tìm trong bài dành cho bạn" only the posts holding a text (title, content or linked
// place, as the Blog's search), in the same order. Both are kept in the address (?scope=&q=); /for-you#search (the
// magnifier of the Home's "Dành cho bạn") opens with the focus in the search box.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { interestChips, interestNote, postMeta, postUrl, reasonChips } from "../lib/feed-posts.js";
import { markHits } from "../lib/search-text.js";
import { areaShort, esc, ic, loginUrl, STAGES } from "../lib/ui.js";

const searchForm = document.querySelector("#search");
const input = document.querySelector("#q");
const inputCount = document.querySelector("#q-count");
const summary = document.querySelector("#summary");
const basisBox = document.querySelector("#basis");
const scopesBox = document.querySelector("#scopes");
const list = document.querySelector("#list");
const more = document.querySelector("#more");
const failure = document.querySelector("#failure");

const SCOPE_NAMES = { ALL: "Tất cả", NEAR: "Gần bạn", STAGE: "Hợp giai đoạn", INTEREST: "Chủ đề quan tâm" };
/** Typing waits this long before asking (Enter asks at once). */
const SEARCH_DELAY = 350;
/** The scope and text the page was opened with. */
const asked = new URLSearchParams(location.search);

let feed = null;            // the last page received
let query = "";             // the search text asked for (spaces stripped), "" for none
let ticket = 0;             // the request in flight; an answer to an older one is dropped
let shown = new Set();      // ids on screen: if the ranking moved between two pages, a post is never drawn twice
let engagedHeading = false; // drawn once, above the first post the user already liked, saved or commented on
let focusNewPosts = false;  // "Xem thêm" pressed while it had the focus

/** Asks one page; page 0 replaces the list, the next ones are added under it. */
async function load(scope, page) {
    const mine = ++ticket;
    more.disabled = true;
    failure.hidden = true;
    list.setAttribute("aria-busy", "true");
    try {
        const next = await apiClient.forYou({ scope, page, q: query });
        if (mine !== ticket) {
            return;
        }
        feed = next;
        render(page > 0);
    } catch (error) {
        if (mine !== ticket) {
            return;
        }
        if (error.status === 401) {
            location.replace(loginUrl());           // the session ended: sign in, then back to this page
            return;
        }
        // The list on screen stays; "Xem thêm" or a scope can be pressed again
        failure.textContent = error.message;
        failure.hidden = false;
        if (!feed) {
            summary.textContent = "";
        }
    } finally {
        if (mine === ticket) {
            more.disabled = false;
            list.removeAttribute("aria-busy");
        }
    }
}

function render(append) {
    const count = feed.scopes.find((s) => s.code === feed.scope)?.count ?? 0;
    const address = new URLSearchParams();
    if (feed.scope !== "ALL") address.set("scope", feed.scope);
    if (feed.q) address.set("q", feed.q);
    history.replaceState(null, "", address.size ? `/for-you?${address}` : "/for-you");
    summary.innerHTML = summaryText(count);
    basisBox.innerHTML = basis();
    basisBox.hidden = false;

    // Redrawn with the new counts; the scope button that had the focus keeps it
    const focused = document.activeElement?.closest?.("[data-scope]")?.dataset.scope;
    scopesBox.hidden = feed.scopes.length < 2;
    scopesBox.innerHTML = feed.scopes.map((s) => `<button type="button" data-scope="${s.code}"
        aria-pressed="${s.code === feed.scope}">${esc(SCOPE_NAMES[s.code] || s.code)} · ${s.count}</button>`).join("");
    if (focused) {
        scopesBox.querySelector(`[data-scope="${focused}"]`)?.focus();
    }

    if (!append) {
        shown = new Set();
        engagedHeading = false;
    }
    const fresh = feed.items.filter((p) => !shown.has(p.id));
    const html = fresh.map(postCard).join("");
    if (append) {
        list.insertAdjacentHTML("beforeend", html);
        // Keyboard users go on reading where the new posts start (the button lost the focus while it was disabled,
        // and it disappears after the last page)
        if (focusNewPosts && fresh.length) {
            list.querySelector(`[data-post="${fresh[0].id}"] .feedtitle`)?.focus({ preventScroll: true });
        }
        focusNewPosts = false;
    } else {
        list.innerHTML = html || `<p class="fy-empty">${emptyText()}</p>`;
    }
    more.hidden = !feed.hasMore;
}

/** How many posts the scope holds and how they are ordered, escaped for innerHTML. */
function summaryText(count) {
    const posts = `<b>${count}</b> bài viết`;
    if (feed.q) {
        const found = `${posts} có “${esc(feed.q)}”`;
        switch (feed.scope) {
            case "NEAR":
                return `${found}, gắn với địa điểm ở ${esc(areaShort(feed.areaName))} hoặc trong cùng tỉnh.`;
            case "STAGE":
                return `${found}, có chủ đề hợp giai đoạn “${esc(STAGES[feed.stage] || "")}”.`;
            case "INTEREST":
                return `${found}, thuộc chủ đề bạn quan tâm.`;
            default:
                return `${found} trong các bài dành cho bạn, xếp như danh sách gợi ý.`;
        }
    }
    switch (feed.scope) {
        case "NEAR":
            return `${posts} gắn với địa điểm ở ${esc(areaShort(feed.areaName))} hoặc trong cùng tỉnh.`;
        case "STAGE":
            return `${posts} có chủ đề hợp giai đoạn “${esc(STAGES[feed.stage] || "")}”.`;
        case "INTEREST":
            return `${posts} thuộc chủ đề bạn quan tâm.`;
        default:
            return `${posts} của các thành viên khác, xếp theo khu vực, giai đoạn và chủ đề bạn quan tâm. Bài trong 30
                ngày qua đứng trước; bài bạn đã thích, lưu hoặc bình luận xếp cuối.`;
    }
}

/** What the feed is based on, as on Home; area, stage and the chosen topics are changed on "Tôi". */
function basis() {
    const chips = [
        feed.areaName ? chip("location_on", areaShort(feed.areaName), "Khu vực") : chip("location_off", "Chưa chọn khu vực", "", "off"),
        feed.stage ? chip("flag", STAGES[feed.stage] || feed.stage, "Giai đoạn") : chip("flag", "Chưa chọn giai đoạn", "", "off"),
        interestChips(feed, chip)
    ];
    return `<div class="spot-basis"><span class="spot-label">Dựa trên</span>${chips.join("")}
            <a class="btn sm" href="/profile#area" aria-label="Điều chỉnh khu vực, giai đoạn và chủ đề quan tâm ở trang Tôi">${ic("tune")}Điều chỉnh</a></div>
        <p class="note">${interestNote(feed)}</p>`;
}

/** A "Dựa trên" chip; `what` names it for screen readers ("Khu vực: Arakawa-ku"). */
const chip = (icon, text, what, extra = "") =>
    `<span class="spot-chip ${extra}">${ic(icon)}${what ? `<span class="sr-only">${what}: </span>` : ""}${esc(text)}</span>`;

function emptyText() {
    if (feed.q) {
        // The search may still find it elsewhere: the member's own posts, questions, places, guides…
        const where = feed.scope === "ALL" ? "trong các bài dành cho bạn" : "trong mục này";
        return `Không có bài viết nào có “${esc(feed.q)}” ${where}.
            <a class="link" href="/search?${new URLSearchParams({ q: feed.q })}">Tìm “${esc(feed.q)}” trên toàn Senpai ơi</a>
            hoặc <button class="link" type="button" data-clear>xóa từ khóa</button>.`;
    }
    const blog = 'Hãy xem thêm ở <a class="link" href="/blog">Blog</a>.';
    return {
        NEAR: `Chưa có bài viết nào gắn với địa điểm quanh bạn. ${blog}`,
        STAGE: `Chưa có bài viết nào có chủ đề hợp giai đoạn của bạn. ${blog}`,
        INTEREST: `Chưa có bài viết nào thuộc chủ đề bạn quan tâm. ${blog}`
    }[feed.scope] || `Chưa có bài viết phù hợp. ${blog}`;
}

function postCard(p) {
    shown.add(p.id);
    let heading = "";
    if (p.engaged && !engagedHeading) {
        engagedHeading = true;
        heading = `<h3 class="fy-divider">${ic("history")}Bài bạn đã thích, lưu hoặc bình luận</h3>`;
    }
    return `${heading}<article class="fy-item" data-post="${esc(String(p.id))}">${reasonChips(p)}
        <a class="feedtitle" href="${postUrl(p)}">${markHits(p.title, feed.q)}</a>
        ${p.excerpt ? `<p class="spot-excerpt">${markHits(p.excerpt, feed.q)}</p>` : ""}
        ${postMeta(p)}
    </article>`;
}

/**
 * Asks again with the text in the box, from the first page, in the scope on screen (counts follow the text); typing
 * that changes nothing but spaces asks nothing, Enter always asks (again after a failure).
 */
function search(always = false) {
    const text = input.value.trim();
    if (text === query && !always) {
        return;
    }
    query = text;
    load(feed?.scope ?? asked.get("scope"), 0);   // before the first answer: the scope in the address
}

let typing;
input.addEventListener("input", () => {
    clearTimeout(typing);
    typing = setTimeout(search, SEARCH_DELAY);
    inputCount.textContent = input.value.length >= 80 ? `${input.value.length}/100` : "";
});
searchForm.addEventListener("submit", (e) => {
    e.preventDefault();
    clearTimeout(typing);
    search(true);
});
list.addEventListener("click", (e) => {
    if (e.target.closest("[data-clear]")) {
        input.value = "";
        inputCount.textContent = "";
        search();
        input.focus();
    }
});

scopesBox.addEventListener("click", (e) => {
    const button = e.target.closest("[data-scope]");
    if (button && button.dataset.scope !== feed?.scope) {
        scopesBox.querySelectorAll("[data-scope]").forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
        load(button.dataset.scope, 0);
    }
});
more.addEventListener("click", () => {
    focusNewPosts = document.activeElement === more;
    load(feed.scope, feed.page + 1);
});

await mountSiteHeader("home");
query = (asked.get("q") || "").trim().slice(0, 100);
input.value = query;
if (location.hash === "#search") {
    input.focus();
}
await load(asked.get("scope"), 0);
