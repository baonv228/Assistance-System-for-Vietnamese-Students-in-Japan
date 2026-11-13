// Homepage: guests see an invitation, signed-in users see what matters to them today (as in the prototype).
import { apiClient, ApiError } from "../api/client.js";
import { mountAreaPicker } from "../components/area-picker.js";
import { mountInterestPicker } from "../components/interest-picker.js";
import { mountSiteHeader } from "../components/site-header.js";
import { interestChips, interestNote, postMeta, postUrl, reasonChips } from "../lib/feed-posts.js";
import {
    areaRoman, areaShort, esc, eventWhen, formatDay, ic, LOGO_SVG, loginUrl, prefectureShort, STAGES, stars, tagChips, timeAgo
} from "../lib/ui.js";

const main = document.querySelector("#home");
/** Every post of "Dành cho bạn", ranked the same way ("Xem tất cả"). */
const FOR_YOU_PAGE = "/for-you";

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
 * The member Home leads with "Dành cho bạn", then the cards that follow the user's stage and area ("Việc tiếp theo",
 * "Review mới quanh …"), then what is the same for everyone. `notice` is shown in "Dành cho bạn" after a save.
 */
async function renderMember(notice = "") {
    const home = await apiClient.memberHome();
    main.innerHTML = `
        <div class="page">
            <div class="greet">${LOGO_SVG}<div>
                <h2>${greeting(home)}</h2>
                <p class="note">${home.stage ? `Giai đoạn của bạn: <b>${STAGES[home.stage]}</b> · ` : ""}<a class="link" href="/profile#points">${home.points} điểm · ${esc(home.rankName)}</a></p>
            </div></div>
            ${searchForm()}
            ${forYouSection(home, notice)}
            <div class="homegrid">
                ${nextCard(home.guide)}
                ${reviewCard(home)}
                <div class="card">
                    <div class="row"><span class="badge lan">${ic("celebration")}Sự kiện 2 tuần tới</span><span class="grow"></span>
                        <a class="link" href="/events">Tất cả</a></div>
                    ${home.events.map((e) => {
                        const d = formatDay(e.startDate);
                        return `<a class="mini" href="/events/${e.id}"><span class="when"><b>${d.day}</b>${d.month}</span>
                            <span><b>${esc(e.title)}</b><br><span class="note">${eventWhen(e)} · ${esc(e.locationName)}</span></span></a>`;
                    }).join("") || '<p class="note">Chưa có sự kiện nào trong 2 tuần tới.</p>'}
                </div>
                ${phraseCard(home.phrase)}
                ${home.featured ? `<a class="card blogcard" href="/blog/${home.featured.id}" style="grid-column:1/-1">
                    <span class="row"><span class="badge lan">${ic("star")}Bài nổi bật ${home.featured.period === "WEEK" ? "tuần này" : "tháng này"}</span>
                        <span class="note">${home.featured.readMinutes} phút đọc</span></span>
                    <h3>${esc(home.featured.title)}</h3>
                    <span class="note">${esc(home.featured.excerpt)}</span>
                    <span class="row">${esc(home.featured.authorName)} ${tagChips(home.featured.authorTags)}</span></a>` : ""}
            </div>
        </div>`;
    bindSpeak();
    bindSearch();
    bindForYou(home);
}

/**
 * "Dành cho bạn": what the suggestions are based on (area, stage, the topics the user chose, then those learnt from the
 * user's likes, saves, comments and posts) with "Điều chỉnh" to change area, stage and topics in place, then the first
 * 5 posts chosen and ranked by the server, each with the reasons it was chosen; "Xem tất cả" opens the whole feed
 * (/for-you). Then up to 3 guides of the user's stage (`stageGuidesBlock`), shown even when no post fits. The form is
 * open at once while area or stage is missing, until "Để sau".
 */
function forYouSection(home, notice) {
    const posts = home.forYou || [];
    const open = !(home.areaName && home.stage) && !promptHidden();
    const [lead, ...rest] = posts;
    return `<section class="spot" id="foryou" aria-labelledby="foryou-title">
        <div class="spot-head">
            <h2 class="grow" id="foryou-title">${ic("auto_awesome")}Dành cho bạn</h2>
            ${lead ? `<a class="btn sm spot-find" href="${FOR_YOU_PAGE}#search" title="Tìm trong bài dành cho bạn">${ic("search")}<span class="sr-only">Tìm trong bài dành cho bạn</span></a>
            <a class="btn sm" href="${FOR_YOU_PAGE}" aria-label="Xem tất cả bài viết dành cho bạn">Xem tất cả${ic("arrow_forward")}</a>` : ""}
        </div>
        <p class="spot-sub">Bài viết và cẩm nang Senpai ơi chọn riêng cho bạn, theo khu vực, giai đoạn và những chủ đề bạn quan tâm.</p>
        <div class="spot-basis">
            <span class="spot-label">Dựa trên</span>
            ${home.areaName ? chip("location_on", areaShort(home.areaName), "Khu vực") : chip("location_off", "Chưa chọn khu vực", "", "off")}
            ${home.stage ? chip("flag", STAGES[home.stage], "Giai đoạn") : chip("flag", "Chưa chọn giai đoạn", "", "off")}
            ${interestChips(home, chip)}
            <button class="btn sm" type="button" data-tune aria-expanded="${open}" aria-controls="prompt">${ic("tune")}Điều chỉnh</button>
        </div>
        <p class="note">${interestNote(home)}</p>
        ${notice ? `<p class="alert success" role="status">${ic("check_circle")} ${esc(notice)}</p>` : ""}
        ${profileForm(home, open)}
        ${lead ? `<div class="spot-posts">
            <article class="spot-lead">${reasonChips(lead)}
                <a class="feedtitle" href="${postUrl(lead)}">${esc(lead.title)}</a>
                ${lead.excerpt ? `<p class="spot-excerpt">${esc(lead.excerpt)}</p>` : ""}
                ${postMeta(lead)}
            </article>
            ${rest.map((p) => `<article class="spot-item">${reasonChips(p)}
                <a class="feedtitle" href="${postUrl(p)}">${esc(p.title)}</a>
                ${postMeta(p)}
            </article>`).join("")}
        </div>
        <a class="btn sm spot-more" href="${FOR_YOU_PAGE}">Xem tất cả bài viết dành cho bạn${ic("arrow_forward")}</a>`
        : '<p class="spot-empty">Chưa có bài viết phù hợp. Hãy xem thêm ở <a class="link" href="/blog">Blog</a>.</p>'}
        ${stageGuidesBlock(home)}
    </section>`;
}

/** A "Dựa trên" chip; `what` names it for screen readers ("Khu vực: Arakawa-ku"). */
const chip = (icon, text, what, extra = "") =>
    `<span class="spot-chip ${extra}">${ic(icon)}${what ? `<span class="sr-only">${what}: </span>` : ""}${esc(text)}</span>`;

/**
 * "Cẩm nang cho giai đoạn …" in "Dành cho bạn": up to 3 guides of the user's stage not finished yet, chosen and ordered
 * by the server (checklists already started first, never the guide "Việc tiếp theo" shows). Each card opens its guide.
 */
function stageGuidesBlock(home) {
    const guides = home.stageGuides || [];
    if (!guides.length || !home.stage) {
        return "";
    }
    const stage = STAGES[home.stage] || "";
    return `<div class="spot-guides">
        <div class="spot-guides-head">
            <h3>${ic("menu_book")}Cẩm nang cho giai đoạn ${esc(stage)}</h3>
            <a class="link" href="${stageGuidesUrl(home.stage)}" aria-label="Xem tất cả cẩm nang cho giai đoạn ${esc(stage)}">Xem tất cả</a>
        </div>
        <div class="spot-guide-list">${guides.map(guideCard).join("")}</div>
    </div>`;
}

/** One guide of "Dành cho bạn": why it is there, title, start of the summary, topic and progress. */
const guideCard = (c) => `<article class="spot-guide">
        <div class="whys">${c.inProgress
            ? `<span class="why stage">${ic("pending_actions")}<span>Đang làm dở</span></span>`
            : `<span class="why stage">${ic("flag")}<span>Hợp giai đoạn của bạn</span></span>`}</div>
        <a class="feedtitle" href="${guideUrl(c.id, c.checklist)}">${esc(c.title)}</a>
        ${c.excerpt ? `<p class="spot-excerpt">${esc(c.excerpt)}</p>` : ""}
        <div class="spot-guide-foot">
            ${c.topicName ? `<span class="badge">${esc(c.topicName)}</span>` : ""}
            ${c.checklist
                ? `<span class="spot-guide-progress"><span class="bar2" aria-hidden="true"><span style="width:${Number(c.percent) || 0}%"></span></span>
                    <span class="note">${c.tasksDone}/${c.tasksTotal} việc</span></span>`
                : '<span class="note">Chưa đọc</span>'}
        </div>
    </article>`;

/**
 * "Việc tiếp theo": how many guides of the user's stage (ARR until one is chosen) are done, then the guide to open next,
 * chosen by the server (a checklist already started, else the first guide not done) with what is left of it. The count
 * opens "Cẩm nang của tôi" on Tôi.
 */
function nextCard(g) {
    const n = g.next;
    const href = n ? guideUrl(n.guideId, n.checklist) : "";
    const percent = g.total ? Math.round((g.done / g.total) * 100) : 0;
    const whole = `<a class="btn sm" href="${stageGuidesUrl(g.stageCode)}">Cả giai đoạn</a>`;
    return `<div class="card">
        <div class="row"><span class="badge br">${ic("menu_book")}Việc tiếp theo</span><span class="grow"></span>
            <span class="note"><a class="link" href="/profile#guides" title="Xem tiến độ cẩm nang của bạn">${g.done}/${g.total} · ${esc(g.stageTitle)}</a></span></div>
        <div class="bar2" ${g.total ? `role="progressbar" aria-label="Cẩm nang đã xong" aria-valuemin="0" aria-valuemax="${g.total}"
             aria-valuenow="${g.done}"` : 'aria-hidden="true"'}><span style="width:${percent}%"></span></div>
        ${n ? `<a class="feedtitle" href="${href}">${esc(n.title)}</a>
            <span class="note">${n.checklist
                ? `${n.tasksDone}/${n.tasksTotal} việc${n.nextTask ? ` · Tiếp theo: ${esc(n.nextTask)}` : ""}`
                : "Đọc xong thì bấm “Đánh dấu đã xong” trong cẩm nang."}</span>
            ${n.dueNote ? `<span><span class="badge warn">${ic("schedule")}${esc(n.dueNote)}</span></span>` : ""}
            <div class="row">${n.placeName ? `<a class="btn sm" href="${placeUrl(n.placeId)}">${ic("location_on")}${esc(n.placeName)}</a>` : ""}
                <a class="btn sm pri" href="${href}">${ic("menu_book")}Mở cẩm nang này</a>${whole}</div>`
            : g.total ? `<p>Bạn đã xong giai đoạn này.</p><div class="row">${whole}</div>`
                : "<p>Cẩm nang cho giai đoạn này đang được biên soạn.</p>"}
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
function reviewCard(home) {
    const reviews = home.reviews;
    const inArea = reviews.some((r) => r.near === "AREA");
    const inPrefecture = reviews.some((r) => r.near === "PREFECTURE");
    const title = !home.areaName ? "Review mới"
        : inArea ? `Review mới quanh ${areaShort(home.areaName)}`
            : inPrefecture ? `Review mới ở ${prefectureShort(home.areaName)}` : "Review mới";
    return `<div class="card">
        <div class="row"><span class="badge br">${ic("rate_review")}${esc(title)}</span><span class="grow"></span>
            <a class="link" href="/reviews">Tất cả</a></div>
        ${home.areaName && reviews.length && !inArea
            ? `<p class="note">Chưa có review nào ở ${esc(areaShort(home.areaName))}, đây là review mới ${inPrefecture ? "trong tỉnh" : "ở nơi khác"}.</p>` : ""}
        ${reviews.map((r) => `<a class="mini" href="${placeUrl(r.placeId)}">${ic(r.categoryIcon)}
            <span><b>${esc(r.placeName)}</b> <span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span><br>
            <span class="note">${esc(r.authorName)}: ${esc(r.excerpt)}</span><br>
            <span class="note mini-where">${r.placeArea ? `${ic("location_on")}${esc(areaRoman(r.placeArea))} · ` : ""}${esc(timeAgo(r.createdAt))}</span></span></a>`).join("")
            || '<p class="note">Chưa có review mới.</p>'}
    </div>`;
}

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
    const area = esc(areaShort(home.areaName));
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
    return `<div class="card promptcard" id="prompt" ${open ? "" : "hidden"}>
        <b>${ic("location_on")} ${complete ? "Đổi khu vực, giai đoạn hoặc chủ đề quan tâm" : `Mong bạn cho Senpai ơi biết khu vực ${where}`}</b>
        <p class="note">${complete ? "Gợi ý bên dưới đổi theo ngay khi bạn lưu." : "Để website gợi ý đúng những gì gần bạn nhất: việc cần làm, review và sự kiện quanh đó."}
            Chỉ lưu khu vực (quận, thành phố), không lưu địa chỉ hay vị trí; chỉ mình bạn thấy.</p>
        <div class="field"><label for="hp-stage">Giai đoạn</label>
            <select class="select" id="hp-stage"><option value="">Chưa chọn</option>
            ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === home.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
        <div id="hp-area-box"><p class="note">Đang tải danh sách khu vực…</p></div>
        <div class="field"><span class="field-title">Chủ đề quan tâm</span>
            <div id="hp-interests-box"><p class="note">Đang tải danh sách chủ đề…</p></div></div>
        <p class="alert error" data-error role="alert" hidden></p>
        <div class="row"><button class="btn pri sm" type="button" data-save disabled>Lưu</button>
            <button class="btn sm" type="button" data-later>${complete ? "Đóng" : "Để sau"}</button></div>
    </div>`;
}

function bindForYou(home) {
    const prompt = document.querySelector("#prompt");
    const tune = document.querySelector("[data-tune]");
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

/** Reads the Japanese phrase aloud with the browser's built-in voice (free, no API). */
function bindSpeak() {
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
        });
    });
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
        await renderMember();
    } else {
        renderGuest(await apiClient.guestHome());
    }
} catch (error) {
    main.innerHTML = `<div class="page"><div class="alert error">${esc(error.message)}</div></div>`;
}
