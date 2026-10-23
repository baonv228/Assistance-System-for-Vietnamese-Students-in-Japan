// Lễ hội và sự kiện: every event, grouped as in the prototype (đang diễn ra, 30 ngày tới, sau này, đã diễn ra).
// The search text is kept in the address (?q=) so the list can be shared and survives a reload.
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { coverHtml } from "../lib/fest-art.js";
import { esc, eventRange, eventStateClass, eventStateLabel, ic, loginUrl, stars } from "../lib/ui.js";

const SEARCH_DELAY = 250;
const list = document.querySelector("#list");
const input = document.querySelector("#q");

let user = null;
let ticket = 0;
let timer = null;

/** The review form, or sign in first and come back to it. */
const reviewHref = (e) => (user ? `/events/${e.id}/review` : loginUrl(`/events/${e.id}/review`));

function card(e) {
    const rating = e.reviewCount
        ? `<span class="stars" aria-label="${e.avgRating} trên 5 sao">${stars(Math.round(e.avgRating))}</span>
           <span class="note">${e.avgRating.toFixed(1)} · ${e.reviewCount} review</span>`
        : `<span class="note">Chưa có review</span>`;
    // Staff accounts read the events but do not write reviews
    const canWrite = e.canReview && (!user || user.role === "USER");
    return `<article class="card festcard">
        <div class="festcover">${coverHtml(e)}<span class="badge ${eventStateClass(e)}">${esc(eventStateLabel(e))}</span></div>
        <h3><a href="/events/${e.id}">${esc(e.title)}</a></h3>
        <div class="note">${ic("event")}${esc(eventRange(e))} · ${ic("location_on")}${esc(e.locationName)} · ${esc(e.costNote || "")}</div>
        <div class="row">${rating}</div>
        <div class="row actions-row">
            <a class="btn sm" href="/events/${e.id}">${ic(e.lat != null ? "map" : "info")}Xem chi tiết</a>
            ${canWrite ? `<a class="btn sm" href="${esc(reviewHref(e))}">${ic("rate_review")}Viết review</a>` : ""}
        </div>
    </article>`;
}

function section(title, events) {
    if (!events.length) return "";
    return `<section class="events-section"><h2>${esc(title)}</h2><div class="fest-grid">${events.map(card).join("")}</div></section>`;
}

function render(events) {
    const upcoming = events.filter((e) => e.state === "UPCOMING");
    const html = section("Đang diễn ra", events.filter((e) => e.state === "NOW"))
        + section("Trong 30 ngày tới", upcoming.filter((e) => e.daysUntil <= 30))
        + section("Sau này", upcoming.filter((e) => e.daysUntil > 30))
        // newest first: the event that just ended is the one people want to review
        + section("Đã diễn ra (vẫn viết review được)", events.filter((e) => e.state === "PAST").reverse());
    list.innerHTML = html || `<p class="note">${input.value.trim() ? "Không có sự kiện nào khớp." : "Chưa có sự kiện nào."}</p>`;
}

async function load() {
    const q = input.value.trim();
    const mine = ++ticket;
    try {
        const events = await apiClient.events(q);
        if (mine !== ticket) return;                     // an answer for an older text
        render(events);
    } catch (error) {
        if (mine !== ticket) return;
        list.innerHTML = `<div class="state error" role="alert">${ic("cloud_off", "big")}
            <p>${esc(error.message || "Không tải được danh sách sự kiện.")}</p>
            <button class="btn sm" type="button" data-retry>${ic("refresh")}Thử lại</button></div>`;
    }
    const params = new URLSearchParams(location.search);
    if (q) params.set("q", q); else params.delete("q");
    history.replaceState(null, "", `${location.pathname}${params.size ? `?${params}` : ""}`);
}

input.addEventListener("input", () => {
    clearTimeout(timer);
    timer = setTimeout(load, SEARCH_DELAY);
});
document.querySelector("#search").addEventListener("submit", (event) => {
    event.preventDefault();
    clearTimeout(timer);
    load();
});
list.addEventListener("click", (event) => {
    if (event.target.closest("[data-retry]")) {
        list.innerHTML = `<p class="note">Đang tải…</p>`;
        load();
    }
});

input.value = new URLSearchParams(location.search).get("q") || "";
user = await mountSiteHeader("fest");
await load();
