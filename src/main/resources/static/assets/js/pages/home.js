// Homepage: guests see an invitation, signed-in users see what matters to them today (as in the prototype).
import { apiClient, ApiError } from "../api/client.js";
import { mountAreaPicker } from "../components/area-picker.js";
import { mountInterestPicker } from "../components/interest-picker.js";
import { mountSiteHeader, showSiteNotice } from "../components/site-header.js";
import { bindFeedCards, feedCard } from "../lib/feed-card.js";
import { interestChips, interestNote, reasonLine } from "../lib/feed-posts.js";
import {
    areaRoman, areaShort, avatar, esc, eventWhen, formatDay, ic, loginUrl, prefectureShort, STAGES, stars, tagChips, timeAgo
} from "../lib/ui.js";

const main = document.querySelector("#home");
/** Every post of "Dành cho bạn", ranked the same way ("Xem tất cả"). */
const FOR_YOU_PAGE = "/for-you";
/** The posts of "Dành cho bạn" on screen by id: the photo viewer and "Thích" of their cards read and update them. */
const forYouPosts = new Map();

// ---------------------------------------------------------------- guest

function renderGuest(data) {
    const { stats, quotes, phrase } = data;
    main.innerHTML = `
        <section class="hero">
            <div class="hero-in">
                <div class="hero-txt">
                    <span class="badge lan">${ic("local_florist")}Cho du học sinh Việt mới đến Nhật</span>
                    <h1>Ở Nhật, bạn không đi một mình.</h1>
                    <p>Các senpai đã đánh dấu sẵn chỗ ăn rẻ, ga tàu, văn phòng quận. Mở bản đồ là thấy nơi nào đã có
                        người Việt review, kèm câu tiếng Nhật để dùng ngay tại đó.</p>
                    ${searchForm()}
                    <div class="row">
                        <a class="btn pri" href="/map">${ic("map")}Xem bản đồ Nippori</a>
                        <a class="btn" href="/register">${ic("person_add")}Đăng ký miễn phí</a>
                    </div>
                    <p class="note">Xem bản đồ, review và từ vựng không cần tài khoản.</p>
                </div>
                <div class="heromap" aria-hidden="true">${HERO_MAP}</div>
            </div>
        </section>
        <div class="page">
            <div class="tiles">
                <div class="tile"><b>${stats.placesWithReviews}</b>địa điểm đã có review</div>
                <div class="tile"><b>${stats.reviews}</b>review của người Việt</div>
                <div class="tile"><b>${stats.phrases}</b>mẫu câu theo địa điểm</div>
            </div>
            ${quotes.length ? `<h2>Senpai nói gì</h2>
            <div class="quotes">${quotes.map((q) => `
                <figure class="quote">
                    <blockquote>"${esc(q.text)}"</blockquote>
                    <figcaption>${esc(q.authorName)} ${tagChips(q.authorTags)} <span class="note">về ${esc(q.placeName)}</span></figcaption>
                </figure>`).join("")}
            </div>` : ""}
            <div class="feat">
                ${feature("location_on", "Bản đồ có review", "Nơi nào đã có review sẽ nở hoa trên bản đồ.", "/map")}
                ${feature("school", "Học tiếng Nhật theo chủ đề", "Thanh toán, hỏi đường, khám bệnh… học đúng câu mình sắp dùng.", "/phrases")}
                ${feature("contact_support", "Hỏi senpai", "Đặt câu hỏi, Mentor trả lời, bạn chọn câu hay nhất.", "/qa")}
                ${feature("celebration", "Lễ hội gần bạn", "Sự kiện đang và sắp diễn ra hiện ngay trên bản đồ.", "/events")}
            </div>
            ${phraseCard(phrase)}
            <p class="note">
                <a class="link" href="/terms?doc=tos">Điều khoản sử dụng</a> ·
                <a class="link" href="/terms?doc=privacy">Chính sách bảo mật</a> ·
                <a class="link" href="/terms?doc=community">Quy tắc cộng đồng</a>
            </p>
        </div>`;
    bindSpeak();
    bindSearch();
}

const feature = (icon, title, text, href) =>
    `<a class="card" href="${href}">${ic(icon, "big")}<h3>${title}</h3><span class="note">${text}</span></a>`;

// ---------------------------------------------------------------- member

/**
 * The member Home (styles: home.css, class prefix "mh-"). Wide screens: a main column with the greeting, the site
 * search, shortcuts and the personal feed "Dành cho bạn", and a side column with the cards that follow the user's stage
 * and area (guides, the phrase of the day, reviews nearby, events, the featured post). Narrow screens: one column, the
 * side cards after the feed. `notice` is shown in "Dành cho bạn" after a save.
 */
async function renderMember(notice = "") {
    const home = await apiClient.memberHome();
    forYouPosts.clear();
    (Array.isArray(home.forYou) ? home.forYou : []).forEach((p) => p && forYouPosts.set(p.id, p));
    // a side card that cannot be drawn (an answer of another shape) is left out, the rest of the Home still shows
    const side = (draw) => safely(draw, () => "");
    main.innerHTML = `
        <div class="mh">
            <div class="mh-main">
                ${welcomeCard(home)}
                ${safely(() => forYouSection(home, notice), forYouFallback)}
            </div>
            <aside class="mh-side" aria-label="Cẩm nang, tiếng Nhật, review và sự kiện cho bạn">
                ${side(() => guideWidget(home))}
                ${side(() => phraseWidget(home.phrase))}
                ${side(() => reviewWidget(home))}
                ${side(() => eventWidget(home.events))}
                ${side(() => featuredWidget(home.featured))}
                ${sideFooter()}
            </aside>
        </div>`;
    bindSpeak(listenQuest(home.phrase));
    bindSearch();
    bindForYou(home);
}

/** The greeting with the user's stage and points, the site search, then shortcuts to what members do most. */
function welcomeCard(home) {
    const me = { displayName: home.displayName, avatarUrl: user?.avatarUrl };
    return `<section class="mh-card mh-hello" aria-labelledby="mh-greeting">
        <div class="mh-hello-top">
            <a class="mh-me" href="/profile" tabindex="-1" aria-hidden="true">${avatar(me)}</a>
            <div class="mh-hello-txt">
                <h1 id="mh-greeting">${greeting(home)}</h1>
                <p class="mh-facts">
                    ${home.stage ? `<span>${ic("flag")}Giai đoạn: <b>${esc(STAGES[home.stage])}</b></span>` : ""}
                    <a href="/profile#points" title="Xem điểm và hạng của bạn">${ic("military_tech")}${Number(home.points).toLocaleString("vi-VN")} điểm · ${esc(home.rankName ?? "")}</a>
                </p>
            </div>
        </div>
        ${searchForm()}
        <nav class="mh-quick" aria-label="Lối tắt">
            ${shortcut("edit_square", "Viết bài", "/blog/new")}
            ${shortcut("contact_support", "Đặt câu hỏi", "/qa/ask")}
            ${shortcut("rate_review", "Viết review", "/map")}
            ${shortcut("bookmark", "Đã lưu", "/saved")}
        </nav>
    </section>`;
}

const shortcut = (icon, label, href) => `<a class="mh-quick-btn" href="${href}">${ic(icon)}<span>${label}</span></a>`;

/**
 * "Dành cho bạn": what the suggestions are based on (area, stage, the topics the user chose, then those learnt from the
 * user's likes, saves, comments and posts; one row that scrolls sideways on phones) with "Điều chỉnh" to change area,
 * stage and topics in place, then the first 5 posts chosen and ranked by the server, drawn as the Blog's Facebook-like
 * cards with one line saying why each was chosen (phones show the first 3, home.css); "Xem tất cả" opens the whole feed
 * (/for-you), which has its own search. The form is open at once while area or stage is missing, until "Để sau".
 */
function forYouSection(home, notice) {
    const posts = home.forYou || [];
    const open = !(home.areaName && home.stage) && !promptHidden();
    return `<section class="mh-foryou" id="foryou" aria-labelledby="foryou-title">
        <div class="mh-section-head">
            <h2 id="foryou-title">${ic("auto_awesome")}Dành cho bạn</h2>
            ${posts.length ? `<a class="mh-link" href="${FOR_YOU_PAGE}" aria-label="Xem tất cả bài viết dành cho bạn">Xem tất cả${ic("chevron_right")}</a>` : ""}
        </div>
        <div class="mh-basis">
            <span class="mh-basis-label">Dựa trên</span>
            <div class="mh-chips">
                ${home.areaName ? chip("location_on", areaShort(home.areaName), "Khu vực") : chip("location_off", "Chưa chọn khu vực", "", "off")}
                ${home.stage ? chip("flag", STAGES[home.stage], "Giai đoạn") : chip("flag", "Chưa chọn giai đoạn", "", "off")}
                ${interestChips(home, chip)}
            </div>
            <button class="mh-tune" type="button" data-tune aria-expanded="${open}" aria-controls="prompt"
                aria-label="Điều chỉnh khu vực, giai đoạn và chủ đề quan tâm">${ic("tune")}<span>Điều chỉnh</span></button>
        </div>
        ${notice ? `<p class="alert success" role="status">${ic("check_circle")} ${esc(notice)}</p>` : ""}
        ${profileForm(home, open)}
        ${posts.length ? `<div class="mh-feed">${posts.map(postCard).join("")}</div>
        <a class="mh-more" href="${FOR_YOU_PAGE}">Xem thêm bài viết dành cho bạn${ic("arrow_forward")}</a>`
        : `<p class="mh-card mh-empty">${ic("article")}<span>Chưa có bài viết phù hợp. Hãy xem thêm ở <a class="link" href="/blog">Blog</a>.</span></p>`}
    </section>`;
}

/**
 * A post of "Dành cho bạn": the Blog's card (published posts of others only) with one line saying why it was chosen
 * above the author (`reasonLine`); its topics open the Blog filtered.
 */
const postCard = (p) => feedCard({ ...p, status: "PUBLISHED", pinned: false }, "POST", user,
    { top: reasonLine(p), topicHref: (t) => `/blog?topics=${encodeURIComponent(t.id)}` });

/**
 * A part of the Home drawn by `draw`, or `fallback` when drawing it fails (an answer of another shape than this page
 * expects, e.g. a server not restarted after an update): the rest of the Home still shows.
 */
function safely(draw, fallback) {
    try {
        return draw();
    } catch (error) {
        console.error(error);
        return fallback();
    }
}

/** "Dành cho bạn" when its posts cannot be drawn: the whole feed stays one click away. */
const forYouFallback = () => `<section class="mh-foryou" id="foryou" aria-labelledby="foryou-title">
        <div class="mh-section-head"><h2 id="foryou-title">${ic("auto_awesome")}Dành cho bạn</h2></div>
        <p class="mh-card mh-empty">${ic("error")}<span>Chưa hiển thị được các bài gợi ý. Hãy tải lại trang hoặc
            <a class="link" href="${FOR_YOU_PAGE}">xem tất cả bài viết dành cho bạn</a>.</span></p>
    </section>`;

/** A "Dựa trên" chip; `what` names it for screen readers ("Khu vực: Arakawa-ku"). */
const chip = (icon, text, what, extra = "") =>
    `<span class="mh-chip ${extra}">${ic(icon)}${what ? `<span class="sr-only">${what}: </span>` : ""}${esc(text)}</span>`;

/** A card of the side column: icon and title, what goes on the right of the title (a link), then `body`. */
const widget = (icon, title, body, aside = "") => `<section class="mh-card mh-widget">
        <div class="mh-widget-head"><h2>${ic(icon)}<span>${title}</span></h2>${aside}</div>
        ${body}
    </section>`;

/** "Tất cả" and the like on the right of a side card's title; `label` names it for screen readers. */
const headLink = (href, text, label) => `<a class="mh-link" href="${href}" aria-label="${esc(label)}">${text}</a>`;

/**
 * "Cẩm nang của bạn": how many guides of the user's stage (ARR until one is chosen) are done (the count opens "Cẩm nang
 * của tôi" on Tôi); "Việc tiếp theo" = the guide to open next, chosen by the server (a checklist already started, else
 * the first guide not done) with what is left of it; then up to 3 other guides of the user's own stage not done yet
 * (`stageGuides`: started checklists first, never the one "Việc tiếp theo" shows).
 */
function guideWidget(home) {
    const g = home.guide;
    const n = g.next;
    // no progress bar while the stage has no guide yet ("0/0")
    const progress = g.total ? `<div class="mh-progress">
        <div class="mh-progress-row"><span title="${esc(g.stageTitle)}">Giai đoạn ${esc(STAGES[g.stageCode] || g.stageTitle)}</span>
            <a href="/profile#guides" title="Xem tiến độ cẩm nang của bạn">${g.done}/${g.total} đã xong</a></div>
        <div class="bar2" role="progressbar" aria-label="Cẩm nang đã xong" aria-valuemin="0" aria-valuemax="${g.total}"
             aria-valuenow="${g.done}"><span style="width:${Math.round((g.done / g.total) * 100)}%"></span></div>
    </div>` : "";
    let next;
    if (n) {
        const href = guideUrl(n.guideId, n.checklist);
        next = `<div class="mh-next">
            <span class="mh-eyebrow">Việc tiếp theo</span>
            <a class="mh-next-title" href="${href}">${esc(n.title)}</a>
            <span class="mh-meta">${n.checklist
                ? `${n.tasksDone}/${n.tasksTotal} việc${n.nextTask ? ` · Tiếp theo: ${esc(n.nextTask)}` : ""}`
                : "Đọc xong thì bấm “Đánh dấu đã xong” trong cẩm nang."}</span>
            ${n.dueNote ? `<span><span class="badge warn">${ic("schedule")}${esc(n.dueNote)}</span></span>` : ""}
            <div class="mh-actions"><a class="btn sm pri" href="${href}">${ic("menu_book")}Mở cẩm nang này</a>
                ${n.placeName ? `<a class="btn sm" href="${placeUrl(n.placeId)}">${ic("location_on")}${esc(n.placeName)}</a>` : ""}</div>
        </div>`;
    } else {
        next = g.total
            ? `<p class="mh-done">${ic("task_alt")}Bạn đã xong giai đoạn này.</p>`
            : '<p class="mh-meta">Cẩm nang cho giai đoạn này đang được biên soạn.</p>';
    }
    return widget("menu_book", "Cẩm nang của bạn", progress + next + stageGuideList(home),
        headLink(stageGuidesUrl(g.stageCode), "Tất cả", `Xem tất cả cẩm nang giai đoạn ${g.stageTitle}`));
}

/** The other guides of the user's stage in "Cẩm nang của bạn": title, then started / tasks done / not read yet. */
function stageGuideList(home) {
    const guides = home.stageGuides || [];
    if (!guides.length || !home.stage) {
        return "";
    }
    return `<div class="mh-sub">
        <span class="mh-eyebrow">Cẩm nang cho giai đoạn ${esc(STAGES[home.stage] || "")}</span>
        <ul class="mh-list">${guides.map((c) => `<li><a class="mh-row" href="${guideUrl(c.id, c.checklist)}">
            <span class="mh-ico">${ic(c.inProgress ? "pending_actions" : "menu_book")}</span>
            <span class="mh-row-body"><b>${esc(c.title)}</b>
                <span class="mh-meta">${c.inProgress ? "Đang làm dở · " : ""}${c.checklist ? `${c.tasksDone}/${c.tasksTotal} việc` : "Chưa đọc"}${c.topicName ? ` · ${esc(c.topicName)}` : ""}</span></span>
        </a></li>`).join("")}</ul>
    </div>`;
}

/** A guide's page; a guide with a checklist opens on its "Hướng dẫn chuẩn bị" tab. */
const guideUrl = (id, checklist) => `/guides/${encodeURIComponent(id)}${checklist ? "?tab=preparation" : ""}`;

/** Every guide of one stage. */
const stageGuidesUrl = (stageCode) => `/guides?category=${encodeURIComponent(stageCode)}`;

/** The place's panel on the Map. */
const placeUrl = (placeId) => `/map?place=${encodeURIComponent(placeId)}`;

/**
 * "Review mới quanh …": 3 reviews, those in the user's area first, then its prefecture (chosen by the server). The
 * title says "quanh" only when one of them really is in the area; each review says where its place is and opens it on
 * the Map; "Tất cả" opens the page with every review.
 */
function reviewWidget(home) {
    const reviews = home.reviews;
    const inArea = reviews.some((r) => r.near === "AREA");
    const inPrefecture = reviews.some((r) => r.near === "PREFECTURE");
    const title = !home.areaName ? "Review mới"
        : inArea ? `Review mới quanh ${areaShort(home.areaName)}`
            : inPrefecture ? `Review mới ở ${prefectureShort(home.areaName)}` : "Review mới";
    const note = home.areaName && reviews.length && !inArea
        ? `<p class="mh-meta">Chưa có review nào ở ${esc(areaShort(home.areaName))}, đây là review mới ${inPrefecture ? "trong tỉnh" : "ở nơi khác"}.</p>` : "";
    const list = reviews.length ? `<ul class="mh-list">${reviews.map((r) => `<li><a class="mh-row" href="${placeUrl(r.placeId)}">
            <span class="mh-ico">${ic(r.categoryIcon)}</span>
            <span class="mh-row-body">
                <span class="mh-row-top"><b>${esc(r.placeName)}</b><span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span></span>
                <span class="mh-clamp">${esc(r.authorName)}: ${esc(r.excerpt)}</span>
                <span class="mh-meta">${r.placeArea ? `${esc(areaRoman(r.placeArea))} · ` : ""}${esc(timeAgo(r.createdAt))}</span>
            </span></a></li>`).join("")}</ul>`
        : '<p class="mh-meta">Chưa có review mới.</p>';
    return widget("rate_review", esc(title), note + list, headLink("/reviews", "Tất cả", "Xem tất cả review mới"));
}

/** "Sự kiện 2 tuần tới": date, title, when and where; when there is none, one line and a link to every event. */
function eventWidget(events) {
    const list = events.length ? `<ul class="mh-list">${events.map((e) => {
        const d = formatDay(e.startDate);
        return `<li><a class="mh-row" href="/events/${encodeURIComponent(e.id)}"><span class="mh-date"><b>${d.day}</b>${d.month}</span>
            <span class="mh-row-body"><b>${esc(e.title)}</b><span class="mh-meta">${eventWhen(e)} · ${esc(e.locationName)}</span></span></a></li>`;
    }).join("")}</ul>` : '<p class="mh-meta">Chưa có sự kiện nào trong 2 tuần tới.</p>';
    return widget("celebration", "Sự kiện 2 tuần tới", list,
        headLink("/events", events.length ? "Tất cả" : "Lịch sự kiện", "Xem tất cả sự kiện"));
}

/** "Bài nổi bật tuần này / tháng này" (FeaturedSnapshotService), when there is one. */
function featuredWidget(f) {
    if (!f) {
        return "";
    }
    return widget("star", `Bài nổi bật ${f.period === "WEEK" ? "tuần này" : "tháng này"}`, `<a class="mh-feat" href="/blog/${encodeURIComponent(f.id)}">
            <b>${esc(f.title)}</b>
            ${f.excerpt ? `<span class="mh-clamp">${esc(f.excerpt)}</span>` : ""}
            <span class="mh-feat-by">${esc(f.authorName)} ${tagChips(f.authorTags)}<span class="mh-meta">· ${f.readMinutes} phút đọc</span></span>
        </a>`);
}

/** "Câu tiếng Nhật hôm nay"; "Nghe" also counts for the day's quest (listenQuest). */
function phraseWidget(phrase) {
    if (!phrase) {
        return "";
    }
    return widget("translate", "Câu tiếng Nhật hôm nay", `<div class="mh-phrase">
            <span class="mh-cat">${ic(phrase.categoryIcon)}${esc(phrase.categoryName)}</span>
            <p class="mh-ja ja" lang="ja">${esc(phrase.japanese)}</p>
            ${phrase.romaji ? `<p class="mh-meta">${esc(phrase.romaji)}</p>` : ""}
            <p>${esc(phrase.meaningVi)}</p>
        </div>
        <div class="mh-actions">
            <button class="btn sm" type="button" data-speak="${esc(phrase.japanese)}">${ic("volume_up")}Nghe</button>
            <a class="btn sm" href="${phraseLink(phrase)}">${ic("school")}Học thêm chủ đề này</a>
        </div>`);
}

/** The links every page of the site keeps somewhere, at the end of the side column. */
const sideFooter = () => `<footer class="mh-foot">
        <a href="/terms?doc=tos">Điều khoản sử dụng</a> · <a href="/terms?doc=privacy">Chính sách bảo mật</a> ·
        <a href="/terms?doc=community">Quy tắc cộng đồng</a>
        <span>Senpai ơi © ${new Date().getFullYear()}</span>
    </footer>`;

function promptHidden() {
    try {
        return sessionStorage.getItem("asvsj.hidePrompt") === "1";
    } catch {
        return false; // storage blocked
    }
}

/** Same wording as the prototype; a generic greeting until area and stage are set. */
function greeting(home) {
    const name = esc(home.displayName.trim().split(/\s+/).pop());
    // the area's name ("Arakawa-ku") is never broken at its hyphen
    const area = `<span class="mh-nowrap">${esc(areaShort(home.areaName))}</span>`;
    if (!home.areaName || !home.stage) {
        return `Chào ${name}!`;
    }
    return `Chào ${name}, ` + {
        PRE: "sắp sang Nhật rồi, chuẩn bị đến đâu rồi?",
        ARR: `dạo này ở ${area} thế nào rồi?`,
        LEAVE: `sắp rời ${area} rồi, đừng quên các thủ tục nhé.`,
        HOME: "về nước rồi, giúp các bạn đi sau nhé!"
    }[home.stage];
}

/**
 * Area, stage and "Chủ đề quan tâm", inside "Dành cho bạn". Not required: while area or stage is missing it is a gentle
 * reminder, open until the user saves both or presses "Để sau"; afterwards "Điều chỉnh" opens it to change them.
 */
function profileForm(home, open) {
    const complete = home.areaName && home.stage;
    const where = home.stage === "PRE" ? "bạn sắp đến" : home.stage === "HOME" ? "bạn từng sống" : "bạn đang sinh sống";
    return `<div class="mh-card mh-prompt" id="prompt" ${open ? "" : "hidden"}>
        <b class="mh-prompt-title">${ic("tune")}${complete ? "Đổi khu vực, giai đoạn hoặc chủ đề quan tâm" : `Mong bạn cho Senpai ơi biết khu vực ${where}`}</b>
        <p class="note">${complete ? "Gợi ý bên dưới đổi theo ngay khi bạn lưu." : "Để website gợi ý đúng những gì gần bạn nhất: việc cần làm, review và sự kiện quanh đó."}
            Chỉ lưu khu vực (quận, thành phố), không lưu địa chỉ hay vị trí; chỉ mình bạn thấy.</p>
        <div class="field"><label for="hp-stage">Giai đoạn</label>
            <select class="select" id="hp-stage"><option value="">Chưa chọn</option>
            ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === home.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
        <div id="hp-area-box"><p class="note">Đang tải danh sách khu vực…</p></div>
        <div class="field"><span class="field-title">Chủ đề quan tâm</span>
            <p class="note">${interestNote(home)}</p>
            <div id="hp-interests-box"><p class="note">Đang tải danh sách chủ đề…</p></div></div>
        <p class="alert error" data-error role="alert" hidden></p>
        <div class="row"><button class="btn pri sm" type="button" data-save disabled>Lưu</button>
            <button class="btn sm" type="button" data-later>${complete ? "Đóng" : "Để sau"}</button></div>
    </div>`;
}

function bindForYou(home) {
    const prompt = document.querySelector("#prompt");
    const tune = document.querySelector("[data-tune]");
    if (!prompt || !tune) return;               // "Dành cho bạn" could not be drawn (forYouFallback)
    const save = prompt.querySelector("[data-save]");
    const error = prompt.querySelector("[data-error]");
    let picker = null;
    let interests = null;
    let mounting = null;
    // The pickers load the prefectures, the current area and the topics, so only once the form is shown. "Lưu" waits
    // for the first one: without the area picker (failed to load) the area stays as it is, without the topics they do.
    const mount = () => {
        mounting ??= Promise.all([
            mountAreaPicker(prompt.querySelector("#hp-area-box"), { id: "hp", areaId: home.areaId, stage: home.stage })
                .then((p) => {
                    picker = p;
                    prompt.querySelector("#hp-stage").addEventListener("change", (e) => p.setStage(e.target.value));
                    save.disabled = false;
                })
                .catch((err) => {
                    prompt.querySelector("#hp-area-box").innerHTML = `<p class="alert error">${esc(err.message)}</p>`;
                }),
            mountInterestPicker(prompt.querySelector("#hp-interests-box"), { id: "hp" })
                .then((p) => {
                    interests = p;
                    save.disabled = false;
                })
                .catch((err) => {
                    prompt.querySelector("#hp-interests-box").innerHTML = `<p class="alert error">${esc(err.message)}</p>`;
                })
        ]);
    };
    const show = (visible) => {
        prompt.hidden = !visible;
        tune.setAttribute("aria-expanded", String(visible));
        if (visible) {
            mount();
        }
    };
    if (!prompt.hidden) {
        mount();
    }
    tune.addEventListener("click", () => {
        show(prompt.hidden);
        if (!prompt.hidden) {
            prompt.querySelector("#hp-stage").focus();
        }
    });
    save.addEventListener("click", async () => {
        // Without the area picker the area is sent as it was, never cleared; without the topic picker, or when the
        // topics did not change, they are not sent (null) and stay as they are
        const areaId = picker ? picker.value() : home.areaId;
        const stage = document.querySelector("#hp-stage").value;
        const topicIds = interests?.changed() ? interests.value() : null;
        save.disabled = true;                       // one save at a time
        save.textContent = "Đang lưu…";
        error.hidden = true;
        let saved = false;
        try {
            // One request: area, stage and topics are saved together or not at all. It sends the whole choice, so
            // pressing "Lưu" again is safe
            await apiClient.saveFeedPreferences({ areaId, stage: stage || null, topicIds });
            saved = true;
            // Draws the page again: the suggestions, the greeting and the cards follow the new choices
            await renderMember("Đã lưu. Gợi ý bên dưới đã theo lựa chọn mới của bạn.");
            document.querySelector("#foryou").scrollIntoView({ block: "start" });
        } catch (err) {
            // The choices stay; "Lưu" can be pressed again (also when the page could not be reloaded after saving)
            save.disabled = false;
            save.textContent = "Lưu";
            // The server's message; a network failure gets MSG14 (the browser's own text is in English)
            const message = err instanceof ApiError && err.message ? err.message : "Đã có lỗi xảy ra. Vui lòng thử lại sau.";
            const prefix = saved ? "Đã lưu, nhưng chưa tải lại được gợi ý: " : "";
            error.innerHTML = err.status === 401
                ? `${esc(prefix + message)} <a class="link" href="${esc(loginUrl())}">Đăng nhập lại</a>`
                : esc(prefix + message);
            error.hidden = false;
        }
    });
    prompt.querySelector("[data-later]").addEventListener("click", () => {
        if (!(home.areaName && home.stage)) {
            try {
                sessionStorage.setItem("asvsj.hidePrompt", "1");    // "Để sau": not opened by itself again this session
            } catch {
                // storage blocked
            }
        }
        show(false);
        tune.focus();
    });
}

// ---------------------------------------------------------------- shared

/**
 * "Tìm trên Senpai ơi" (the prototype's "Tìm kiếm chung"): a plain GET form to the page /search, which searches places,
 * posts, questions, guides, events, phrases and FAQ entries at once. "Dành cho bạn" has its own search (/for-you).
 */
const searchForm = () => `<form class="site-search home-search" role="search" action="/search" method="get" data-search>
        ${ic("search")}
        <label class="sr-only" for="home-q">Tìm trên Senpai ơi</label>
        <input id="home-q" name="q" type="search" maxlength="100" autocomplete="off" enterkeyhint="search"
               placeholder="Tìm địa điểm, bài viết, sự kiện…">
        <button class="btn pri sm" type="submit">Tìm</button>
    </form>`;

/** An empty box is not sent: the focus stays in it. */
function bindSearch() {
    const form = main.querySelector("[data-search]");
    form?.addEventListener("submit", (event) => {
        const input = form.querySelector("input");
        if (!input.value.trim()) {
            event.preventDefault();
            input.focus();
        }
    });
}

function phraseCard(phrase) {
    if (!phrase) {
        return "";
    }
    return `<div class="card phrase">
        <div class="row"><span class="badge br">${ic("translate")}Câu tiếng Nhật hôm nay</span>
            <span class="note">${ic(phrase.categoryIcon)} ${esc(phrase.categoryName)}</span></div>
        <div class="ja big" lang="ja">${esc(phrase.japanese)}</div>
        <div class="note">${esc(phrase.romaji)}</div>
        <div>${esc(phrase.meaningVi)}</div>
        <div class="row">
            <button class="btn sm" type="button" data-speak="${esc(phrase.japanese)}">${ic("volume_up")}Nghe</button>
            <a class="btn sm" href="${phraseLink(phrase)}">${ic("school")}Học thêm chủ đề này</a>
        </div>
    </div>`;
}

/** "Học thêm chủ đề này": the phrase's topic on "Nhật ngữ", else its place category. */
const phraseLink = (phrase) => (phrase.topicId
    ? `/phrases?topic=${encodeURIComponent(phrase.topicId)}`
    : `/phrases?category=${encodeURIComponent(phrase.categoryId)}`);

/**
 * Reads the Japanese phrase aloud with the browser's built-in voice (free, no API). `afterSpeak` (Members only, see
 * `listenQuest`) runs once the speech has started, so it never holds the voice back.
 */
function bindSpeak(afterSpeak = null) {
    document.querySelectorAll("[data-speak]").forEach((button) => {
        if (!("speechSynthesis" in window)) {
            button.hidden = true;
            return;
        }
        button.addEventListener("click", () => {
            const utterance = new SpeechSynthesisUtterance(button.dataset.speak);
            utterance.lang = "ja-JP";
            utterance.rate = 0.85;
            speechSynthesis.cancel();
            speechSynthesis.speak(utterance);
            afterSpeak?.();
        });
    });
}

/**
 * "Nhiệm vụ hằng ngày": "Nghe" on "Câu tiếng Nhật hôm nay" tells the server, which completes the quest PHRASE_OF_DAY
 * ("Nghe câu tiếng Nhật hôm nay") when the phrase is still today's. Members only (role USER), never guests or staff
 * (null). Errors are ignored; a quest just completed shows "Xong nhiệm vụ “…” · +10 xu." in the header's notice box
 * (and the day's bonus, when this was the last quest). The server counts the quest once a day, so one call per page
 * is enough; a failed one is sent again on the next press.
 */
function listenQuest(phrase) {
    if (user?.role !== "USER" || phrase?.id == null) {
        return null;
    }
    let sent = false;
    return () => {
        if (sent) {
            return;
        }
        sent = true;
        apiClient.questEvent({ type: "LISTEN", phraseId: phrase.id }).then((change) => {
            (change?.justCompleted ?? []).forEach((quest) =>
                showSiteNotice(`Xong nhiệm vụ${quest.title.includes("“") ? `: ${quest.title}` : ` “${quest.title}”`}`
                    + ` · +${quest.coins} xu.`, "task_alt"));
            if (change?.bonusJustCompleted) {
                showSiteNotice(`Xong mọi nhiệm vụ hôm nay · +${change.bonusCoins} xu.`, "celebration");
            }
        }).catch(() => {
            sent = false;
        });
    };
}

// Decorative map for the guest hero: streets, the JR line, a park and blooming review pins.
const PIN = (x, y) => `<g transform="translate(${x} ${y})"><circle r="15" fill="#fff" stroke="#EBDCE0"/>
    <g fill="#C2456E"><circle cy="-5" r="4.2"/><circle cx="4.8" cy="-1.5" r="4.2"/><circle cx="3" cy="4" r="4.2"/>
    <circle cx="-3" cy="4" r="4.2"/><circle cx="-4.8" cy="-1.5" r="4.2"/></g><circle r="2.6" fill="#E8A33C"/></g>`;
const HERO_MAP = `<svg viewBox="0 0 560 300" role="img">
    <rect width="560" height="300" fill="#F3EFEA"/>
    <path d="M60 300 C120 210 170 190 230 120 S330 30 420 0" stroke="#DDEBD3" stroke-width="70" fill="none" opacity=".9"/>
    <g stroke="#fff" stroke-width="12" fill="none" stroke-linecap="round">
        <path d="M0 210 H560"/><path d="M0 95 C140 110 260 70 560 120"/><path d="M180 0 V300"/><path d="M390 0 L330 300"/>
    </g>
    <path d="M0 160 C150 150 300 180 560 150" stroke="#A59AA6" stroke-width="5" fill="none" stroke-dasharray="14 8"/>
    <path d="M0 160 C150 150 300 180 560 150" stroke="#7DB61C" stroke-width="2" fill="none"/>
    ${PIN(150, 135)}${PIN(265, 200)}${PIN(352, 92)}${PIN(440, 228)}${PIN(98, 245)}
    <g transform="translate(470 70)"><circle r="15" fill="#E8A33C"/><text y="5" text-anchor="middle" font-size="15" fill="#3A2400">祭</text></g>
    <rect x="215" y="148" width="66" height="22" rx="6" fill="#fff" stroke="#EBDCE0"/>
    <text x="248" y="163" text-anchor="middle" font-size="11" font-weight="700" fill="#2F3E6E">日暮里</text>
</svg>`;

// ---------------------------------------------------------------- start (after all constants are defined)

const user = await mountSiteHeader("home");
try {
    if (user) {
        // once: main keeps its listeners while "Điều chỉnh" redraws what is inside
        bindFeedCards(main, { kind: "POST", find: (id) => forYouPosts.get(id), notify: showSiteNotice });
        await renderMember();
    } else {
        renderGuest(await apiClient.guestHome());
    }
} catch (error) {
    // A message of the server is shown as it is; a fault of this page itself (in English) is not shown to members
    if (!(error instanceof ApiError)) console.error(error);
    const message = error instanceof ApiError ? error.message : "Không tải được trang chủ. Vui lòng tải lại trang.";
    main.innerHTML = `<div class="page"><div class="alert error">${esc(message)}</div></div>`;
}
