// "Review mới" ("Tất cả" on the Home card "Review mới quanh …"): every review of a place by other people, newest first,
// in one scope: around the user's area, in their prefecture, or everywhere (guests and members without an area only
// get "Tất cả"). Each review opens its place on the Map. The scope is kept in the address (?scope=).
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { areaRoman, areaShort, esc, ic, loginUrl, stars, tagChips, timeAgo } from "../lib/ui.js";

const summary = document.querySelector("#summary");
const scopesBox = document.querySelector("#scopes");
const list = document.querySelector("#list");
const more = document.querySelector("#more");
const failure = document.querySelector("#failure");

const placeUrl = (placeId) => `/map?place=${encodeURIComponent(placeId)}`;
const SCOPE_NAMES = {
    AREA: (feed) => `Quanh ${areaShort(feed.areaName)}`,
    PREFECTURE: (feed) => `Cả tỉnh ${feed.prefectureName}`,
    ALL: () => "Tất cả"
};

let user = null;
let feed = null;            // the last page received
let ticket = 0;             // the request in flight; an answer to an older one is dropped

/** "quanh Arakawa-ku", escaped for innerHTML. */
function scopeText(code) {
    return esc(code === "AREA" ? `quanh ${areaShort(feed.areaName)}`
        : code === "PREFECTURE" ? `trong tỉnh ${feed.prefectureName}` : "trên Senpai ơi");
}

/** Asks one page; page 0 replaces the list, the next ones are added under it. */
async function load(scope, page) {
    const mine = ++ticket;
    more.disabled = true;
    failure.hidden = true;
    list.setAttribute("aria-busy", "true");
    try {
        const next = await apiClient.homeReviews({ scope, page });
        if (mine !== ticket) {
            return;
        }
        feed = next;
        render(page > 0);
    } catch (error) {
        if (mine !== ticket) {
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
    history.replaceState(null, "", `/reviews?${new URLSearchParams({ scope: feed.scope })}`);
    summary.innerHTML = `${count} review ${scopeText(feed.scope)}, mới nhất trước. Bấm vào một review để xem địa điểm
        trên Bản đồ.${feed.areaName ? "" : ` ${areaHint()}`}`;
    scopesBox.hidden = feed.scopes.length < 2;
    scopesBox.innerHTML = feed.scopes.map((s) => `<button type="button" data-scope="${s.code}"
        aria-pressed="${s.code === feed.scope}">${esc(SCOPE_NAMES[s.code](feed))} · ${s.count}</button>`).join("");
    const items = feed.items.map(reviewCard).join("");
    if (append) {
        list.insertAdjacentHTML("beforeend", items);
    } else {
        list.innerHTML = items || `<p class="note rv-empty">${emptyText()}</p>`;
    }
    more.hidden = !feed.hasMore;
}

/** Guests and members without an area see only "Tất cả": how to get the reviews around them. */
function areaHint() {
    return user
        ? 'Chọn khu vực ở trang <a class="link" href="/profile">Tôi</a> để xem review quanh bạn.'
        : `<a class="link" href="${esc(loginUrl())}">Đăng nhập</a> và chọn khu vực để xem review quanh bạn.`;
}

function emptyText() {
    const first = 'Mở <a class="link" href="/map">Bản đồ</a> để viết review đầu tiên.';
    return feed.scope === "ALL" ? `Chưa có review nào. ${first}` : `Chưa có review nào ${scopeText(feed.scope)}. ${first}`;
}

function reviewCard(r) {
    const where = areaRoman(r.placeArea);
    const near = r.near === "AREA" ? `<span class="badge br">${ic("location_on")}Gần bạn</span>`
        : r.near === "PREFECTURE" ? '<span class="badge">Cùng tỉnh</span>' : "";
    const photo = r.photoUrl
        ? `<a class="rv-photo" href="${placeUrl(r.placeId)}" tabindex="-1" aria-hidden="true"><img src="${esc(r.photoUrl)}"
            alt="" loading="lazy">${r.mediaCount > 1 ? `<span>+${r.mediaCount - 1}</span>` : ""}</a>`
        : r.mediaCount ? `<span class="note">${ic("videocam")}${r.mediaCount} video</span>` : "";
    return `<article class="card rv">
        <a class="rv-place" href="${placeUrl(r.placeId)}">
            <span class="rv-ic">${ic(r.categoryIcon || "place")}</span>
            <span class="rv-place-txt"><b>${esc(r.placeName)}</b>
                <span class="note">${esc(r.categoryName)}${where ? ` · ${esc(where)}` : ""}</span></span>
        </a>
        <div class="row">
            <span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span>${near}
            ${r.placeClosed ? '<span class="badge warn">Đã đóng cửa</span>' : ""}
            ${r.likeCount > 10 ? `<span class="badge lan">${ic("verified")}Hữu ích</span>` : ""}
        </div>
        <p class="rv-text">${esc(r.excerpt)}</p>
        ${photo}
        <div class="row rv-foot">
            <span class="note">${esc(r.authorName)} ${tagChips(r.authorTags)} · ${esc(timeAgo(r.createdAt))} ·
                <span aria-label="${r.likeCount} lượt thích">${ic("favorite")}${r.likeCount}</span></span>
            <span class="grow"></span>
            <a class="link" href="${placeUrl(r.placeId)}">Xem trên bản đồ${ic("arrow_forward")}</a>
        </div>
    </article>`;
}

scopesBox.addEventListener("click", (e) => {
    const button = e.target.closest("[data-scope]");
    if (button && button.dataset.scope !== feed?.scope) {
        scopesBox.querySelectorAll("[data-scope]").forEach((b) => b.setAttribute("aria-pressed", String(b === button)));
        load(button.dataset.scope, 0);
    }
});
more.addEventListener("click", () => load(feed.scope, feed.page + 1));
// A photo that cannot be loaded leaves no broken frame (error events do not bubble, hence the capture)
list.addEventListener("error", (e) => e.target.closest?.(".rv-photo")?.remove(), true);

user = await mountSiteHeader("home");
await load(new URLSearchParams(location.search).get("scope"), 0);
