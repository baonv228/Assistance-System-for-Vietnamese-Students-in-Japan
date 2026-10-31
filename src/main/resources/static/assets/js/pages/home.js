// Homepage: guests see an invitation, signed-in users see what matters to them today (as in the prototype).
import { apiClient, ApiError } from "../api/client.js";
import { mountAreaPicker } from "../components/area-picker.js";
import { mountSiteHeader } from "../components/site-header.js";
import {
    areaShort, esc, eventWhen, formatDay, ic, LOGO_SVG, loginUrl, STAGES, stars, tagChips, timeAgo
} from "../lib/ui.js";

const soon = (feature) => `/coming-soon?f=${feature}`;
const main = document.querySelector("#home");

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
                ${feature("school", "Học tiếng Nhật theo chủ đề", "Thanh toán, hỏi đường, khám bệnh… học đúng câu mình sắp dùng.", soon("learn"))}
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
    const g = home.guide;
    main.innerHTML = `
        <div class="page">
            <div class="greet">${LOGO_SVG}<div>
                <h2>${greeting(home)}</h2>
                <p class="note">${home.stage ? `Giai đoạn của bạn: <b>${STAGES[home.stage]}</b> · ` : ""}<a class="link" href="/profile#points">${home.points} điểm · ${esc(home.rankName)}</a></p>
            </div></div>
            ${forYouSection(home, notice)}
            <div class="homegrid">
                <div class="card">
                    <div class="row"><span class="badge br">${ic("menu_book")}Việc tiếp theo</span><span class="grow"></span>
                        <span class="note">${g.done}/${g.total} · ${esc(g.stageTitle)}</span></div>
                    <div class="bar2"><span style="width:${g.total ? (g.done / g.total) * 100 : 0}%"></span></div>
                    ${g.next ? `<b>${esc(g.next.title)}</b>
                        ${g.next.dueNote ? `<span><span class="badge warn">${ic("schedule")}${esc(g.next.dueNote)}</span></span>` : ""}
                        <div class="row">${g.next.placeName ? `<a class="btn sm" href="/map">${ic("location_on")}${esc(g.next.placeName)}</a>` : ""}
                            <a class="btn sm" href="/guides">Mở cẩm nang</a></div>`
                        : `<p>${g.total ? "Bạn đã xong giai đoạn này." : "Cẩm nang cho giai đoạn này đang được biên soạn."}</p>`}
                </div>
                <div class="card">
                    <div class="row"><span class="badge">${ic("rate_review")}${home.areaName ? `Review mới quanh ${esc(areaShort(home.areaName))}` : "Review mới"}</span></div>
                    ${home.reviews.map((r) => `<a class="mini" href="/map">${ic(r.categoryIcon)}
                        <span><b>${esc(r.placeName)}</b> <span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span><br>
                        <span class="note">${esc(r.authorName)}: ${esc(r.excerpt)}</span></span></a>`).join("")
                        || '<p class="note">Chưa có review mới.</p>'}
                </div>
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
    bindForYou(home);
}

/**
 * "Dành cho bạn": what the suggestions are based on (area, stage, topics learnt from the user's likes, saves, comments
 * and posts) with "Điều chỉnh" to change area and stage in place, then the posts chosen and ranked by the server, each
 * with the reasons it was chosen. Open at once while area or stage is missing, until "Để sau".
 */
function forYouSection(home, notice) {
    const posts = home.forYou || [];
    const open = !(home.areaName && home.stage) && !promptHidden();
    const [lead, ...rest] = posts;
    return `<section class="spot" id="foryou" aria-labelledby="foryou-title">
        <div class="spot-head">
            <h2 class="grow" id="foryou-title">${ic("auto_awesome")}Dành cho bạn</h2>
            <a class="btn sm" href="/blog" aria-label="Xem thêm bài viết trên Blog">Xem thêm${ic("arrow_forward")}</a>
        </div>
        <p class="spot-sub">Bài viết Senpai ơi chọn riêng cho bạn, theo khu vực, giai đoạn và những chủ đề bạn quan tâm.</p>
        <div class="spot-basis">
            <span class="spot-label">Dựa trên</span>
            ${home.areaName ? chip("location_on", areaShort(home.areaName), "Khu vực") : chip("location_off", "Chưa chọn khu vực", "", "off")}
            ${home.stage ? chip("flag", STAGES[home.stage], "Giai đoạn") : chip("flag", "Chưa chọn giai đoạn", "", "off")}
            ${(home.interests || []).map((t) => chip("interests", t, "Chủ đề bạn quan tâm")).join("")}
            <button class="btn sm" type="button" data-tune aria-expanded="${open}" aria-controls="prompt">${ic("tune")}Điều chỉnh</button>
        </div>
        <p class="note">${home.interests?.length
            ? "Chủ đề quan tâm được rút ra từ những bài bạn đã thích, lưu, bình luận hoặc viết trong 90 ngày qua."
            : "Hãy thích, lưu hoặc bình luận bài viết: Senpai ơi sẽ biết bạn quan tâm chủ đề nào để gợi ý sát hơn."}</p>
        ${notice ? `<p class="alert success" role="status">${ic("check_circle")} ${esc(notice)}</p>` : ""}
        ${profileForm(home, open)}
        ${lead ? `<div class="spot-posts">
            <article class="spot-lead">${reasonChips(lead)}
                <a class="feedtitle" href="/blog/${encodeURIComponent(lead.id)}">${esc(lead.title)}</a>
                ${lead.excerpt ? `<p class="spot-excerpt">${esc(lead.excerpt)}</p>` : ""}
                ${postMeta(lead)}
            </article>
            ${rest.map((p) => `<article class="spot-item">${reasonChips(p)}
                <a class="feedtitle" href="/blog/${encodeURIComponent(p.id)}">${esc(p.title)}</a>
                ${postMeta(p)}
            </article>`).join("")}
        </div>
        <a class="btn sm spot-more" href="/blog">Xem thêm bài viết${ic("arrow_forward")}</a>`
        : '<p class="spot-empty">Chưa có bài viết phù hợp. Hãy xem thêm ở <a class="link" href="/blog">Blog</a>.</p>'}
    </section>`;
}

/** A "Dựa trên" chip; `what` names it for screen readers ("Khu vực: Arakawa-ku"). */
const chip = (icon, text, what, extra = "") =>
    `<span class="spot-chip ${extra}">${ic(icon)}${what ? `<span class="sr-only">${what}: </span>` : ""}${esc(text)}</span>`;

/** Why the server chose the post, in its ranking order: the place near the user, the stage topic, the interest. */
const REASON_STYLE = {
    AREA: ["location_on", ""], PREFECTURE: ["map", ""], STAGE: ["flag", "stage"], INTEREST: ["interests", "interest"]
};
const reasonChips = (post) => (post.reasons?.length ? `<div class="whys">${post.reasons.map((r) => {
    const [icon, style] = REASON_STYLE[r.code] || ["auto_awesome", ""];
    return `<span class="why ${style}">${ic(icon)}<span>${esc(r.label)}${r.detail ? ` · <b>${esc(r.detail)}</b>` : ""}</span></span>`;
}).join("")}</div>` : "");

const postMeta = (p) => `<span class="row spot-meta">${p.topicName ? `<span class="badge">${esc(p.topicName)}</span>` : ""}
    <span class="note">${esc(p.authorName)} · ${esc(timeAgo(p.createdAt))} ·
        <span aria-label="${p.likeCount} lượt thích">${ic("favorite")}${p.likeCount}</span>
        <span aria-label="${p.commentCount} bình luận">${ic("chat_bubble")}${p.commentCount}</span></span></span>`;

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
 * Area and stage, inside "Dành cho bạn". Not required: while one is missing it is a gentle reminder, open until the
 * user saves both or presses "Để sau"; afterwards "Điều chỉnh" opens it to change them.
 */
function profileForm(home, open) {
    const complete = home.areaName && home.stage;
    const where = home.stage === "PRE" ? "bạn sắp đến" : home.stage === "HOME" ? "bạn từng sống" : "bạn đang sinh sống";
    return `<div class="card promptcard" id="prompt" ${open ? "" : "hidden"}>
        <b>${ic("location_on")} ${complete ? "Đổi khu vực hoặc giai đoạn của bạn" : `Mong bạn cho Senpai ơi biết khu vực ${where}`}</b>
        <p class="note">${complete ? "Gợi ý bên dưới đổi theo ngay khi bạn lưu." : "Để website gợi ý đúng những gì gần bạn nhất: việc cần làm, review và sự kiện quanh đó."}
            Chỉ lưu khu vực (quận, thành phố), không lưu địa chỉ hay vị trí; chỉ mình bạn thấy.</p>
        <div class="field"><label for="hp-stage">Giai đoạn</label>
            <select class="select" id="hp-stage"><option value="">Chưa chọn</option>
            ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === home.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
        <div id="hp-area-box"><p class="note">Đang tải danh sách khu vực…</p></div>
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
    let mounting = null;
    // The picker loads the prefectures and the current area, so only once the form is shown
    const mount = () => {
        mounting ??= mountAreaPicker(prompt.querySelector("#hp-area-box"), { id: "hp", areaId: home.areaId, stage: home.stage })
            .then((p) => {
                picker = p;
                prompt.querySelector("#hp-stage").addEventListener("change", (e) => p.setStage(e.target.value));
                save.disabled = false;
            })
            .catch((err) => {
                prompt.querySelector("#hp-area-box").innerHTML = `<p class="alert error">${esc(err.message)}</p>`;
            });
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
        const areaId = picker ? picker.value() : null;
        const stage = document.querySelector("#hp-stage").value;
        save.disabled = true;                       // one save at a time
        save.textContent = "Đang lưu…";
        error.hidden = true;
        try {
            await apiClient.updateProfile({ areaId, stage: stage || null });
            // Draws the page again: the suggestions, the greeting and the cards follow the new area and stage
            await renderMember("Đã lưu. Gợi ý bên dưới đã theo khu vực và giai đoạn mới của bạn.");
            document.querySelector("#foryou").scrollIntoView({ block: "start" });
        } catch (err) {
            // The choices stay; "Lưu" can be pressed again (also when the page could not be reloaded after saving)
            save.disabled = false;
            save.textContent = "Lưu";
            // The server's message; a network failure gets MSG14 (the browser's own text is in English)
            const message = err instanceof ApiError && err.message ? err.message : "Đã có lỗi xảy ra. Vui lòng thử lại sau.";
            error.innerHTML = err.status === 401
                ? `${esc(message)} <a class="link" href="${esc(loginUrl())}">Đăng nhập lại</a>`
                : esc(message);
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
            <a class="btn sm" href="${soon("learn")}">${ic("school")}Học thêm chủ đề này</a>
        </div>
    </div>`;
}

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
