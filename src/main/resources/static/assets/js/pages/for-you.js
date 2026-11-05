// "Dành cho bạn" ("Xem tất cả" on the member Home): the whole personal feed, ranked by the server exactly like the
// Home's five posts (a place in the user's area, then the prefecture, a topic that suits the stage, an interest; posts
// of the last 30 days first; posts already liked, saved or commented on last), 10 at a time. A scope keeps only the
// posts that matched one reason; it is kept in the address (?scope=).
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { postMeta, postUrl, reasonChips } from "../lib/feed-posts.js";
import { areaShort, esc, ic, loginUrl, STAGES } from "../lib/ui.js";

const summary = document.querySelector("#summary");
const basisBox = document.querySelector("#basis");
const scopesBox = document.querySelector("#scopes");
const list = document.querySelector("#list");
const more = document.querySelector("#more");
const failure = document.querySelector("#failure");

const SCOPE_NAMES = { ALL: "Tất cả", NEAR: "Gần bạn", STAGE: "Hợp giai đoạn", INTEREST: "Chủ đề quan tâm" };

let feed = null;            // the last page received
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
        const next = await apiClient.forYou({ scope, page });
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
    history.replaceState(null, "", feed.scope === "ALL" ? "/for-you" : `/for-you?${new URLSearchParams({ scope: feed.scope })}`);
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

/** What the feed is based on, as on Home; area and stage are changed on "Tôi". */
function basis() {
    const chips = [
        feed.areaName ? chip("location_on", areaShort(feed.areaName), "Khu vực") : chip("location_off", "Chưa chọn khu vực", "", "off"),
        feed.stage ? chip("flag", STAGES[feed.stage] || feed.stage, "Giai đoạn") : chip("flag", "Chưa chọn giai đoạn", "", "off"),
        ...(feed.interests || []).map((t) => chip("interests", t, "Chủ đề bạn quan tâm"))
    ];
    return `<div class="spot-basis"><span class="spot-label">Dựa trên</span>${chips.join("")}
            <a class="btn sm" href="/profile#area" aria-label="Điều chỉnh khu vực và giai đoạn ở trang Tôi">${ic("tune")}Điều chỉnh</a></div>
        <p class="note">${feed.interests?.length
            ? "Chủ đề quan tâm được rút ra từ những bài bạn đã thích, lưu, bình luận hoặc viết trong 90 ngày qua."
            : "Hãy thích, lưu hoặc bình luận bài viết: Senpai ơi sẽ biết bạn quan tâm chủ đề nào để gợi ý sát hơn."}</p>`;
}

/** A "Dựa trên" chip; `what` names it for screen readers ("Khu vực: Arakawa-ku"). */
const chip = (icon, text, what, extra = "") =>
    `<span class="spot-chip ${extra}">${ic(icon)}${what ? `<span class="sr-only">${what}: </span>` : ""}${esc(text)}</span>`;

function emptyText() {
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
        <a class="feedtitle" href="${postUrl(p)}">${esc(p.title)}</a>
        ${p.excerpt ? `<p class="spot-excerpt">${esc(p.excerpt)}</p>` : ""}
        ${postMeta(p)}
    </article>`;
}

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
await load(new URLSearchParams(location.search).get("scope"), 0);
