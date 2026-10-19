// Homepage: guests see an invitation, signed-in users see what matters to them today (as in the prototype).
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { esc, eventWhen, formatDay, ic, LOGO_SVG, STAGES, stars, tagChips, timeAgo } from "../lib/ui.js";

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
                        <a class="btn pri" href="${soon("map")}">${ic("map")}Xem bản đồ Nippori</a>
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
                ${feature("location_on", "Bản đồ có review", "Nơi nào đã có review sẽ nở hoa trên bản đồ.", soon("map"))}
                ${feature("school", "Học tiếng Nhật theo chủ đề", "Thanh toán, hỏi đường, khám bệnh… học đúng câu mình sắp dùng.", soon("learn"))}
                ${feature("contact_support", "Hỏi senpai", "Đặt câu hỏi, Mentor trả lời, bạn chọn câu hay nhất.", "/qa")}
                ${feature("celebration", "Lễ hội gần bạn", "Sự kiện đang và sắp diễn ra hiện ngay trên bản đồ.", soon("fest"))}
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

async function renderMember() {
    const [home, areas] = await Promise.all([apiClient.memberHome(), apiClient.areas()]);
    const g = home.guide;
    main.innerHTML = `
        <div class="page">
            <div class="greet">${LOGO_SVG}<div>
                <h2>${greeting(home)}</h2>
                <p class="note">${home.stage ? `Giai đoạn của bạn: <b>${STAGES[home.stage]}</b> · ` : ""}${home.points} điểm · ${esc(home.rankName)}</p>
            </div></div>
            ${profilePrompt(home, areas)}
            <div class="homegrid">
                ${phraseCard(home.phrase)}
                <div class="card">
                    <div class="row"><span class="badge br">${ic("menu_book")}Việc tiếp theo</span><span class="grow"></span>
                        <span class="note">${g.done}/${g.total} · ${esc(g.stageTitle)}</span></div>
                    <div class="bar2"><span style="width:${g.total ? (g.done / g.total) * 100 : 0}%"></span></div>
                    ${g.next ? `<b>${esc(g.next.title)}</b>
                        ${g.next.dueNote ? `<span><span class="badge warn">${ic("schedule")}${esc(g.next.dueNote)}</span></span>` : ""}
                        <div class="row">${g.next.placeName ? `<a class="btn sm" href="${soon("map")}">${ic("location_on")}${esc(g.next.placeName)}</a>` : ""}
                            <a class="btn sm" href="${soon("guide")}">Mở cẩm nang</a></div>`
                        : `<p>${g.total ? "Bạn đã xong giai đoạn này." : "Cẩm nang cho giai đoạn này đang được biên soạn."}</p>`}
                </div>
                <div class="card">
                    <div class="row"><span class="badge lan">${ic("celebration")}Sự kiện 2 tuần tới</span><span class="grow"></span>
                        <a class="link" href="${soon("fest")}">Tất cả</a></div>
                    ${home.events.map((e) => {
                        const d = formatDay(e.startDate);
                        return `<a class="mini" href="${soon("fest")}"><span class="when"><b>${d.day}</b>${d.month}</span>
                            <span><b>${esc(e.title)}</b><br><span class="note">${eventWhen(e)} · ${esc(e.locationName)}</span></span></a>`;
                    }).join("") || '<p class="note">Chưa có sự kiện nào trong 2 tuần tới.</p>'}
                </div>
                <div class="card">
                    <div class="row"><span class="badge">${ic("rate_review")}${home.areaName ? `Review mới quanh ${esc(areaShort(home.areaName))}` : "Review mới"}</span></div>
                    ${home.reviews.map((r) => `<a class="mini" href="${soon("map")}">${ic(r.categoryIcon)}
                        <span><b>${esc(r.placeName)}</b> <span class="stars" aria-label="${r.rating} sao">${stars(r.rating)}</span><br>
                        <span class="note">${esc(r.authorName)}: ${esc(r.excerpt)}</span></span></a>`).join("")
                        || '<p class="note">Chưa có review mới.</p>'}
                </div>
                ${home.featured ? `<a class="card blogcard" href="/blog/${home.featured.id}" style="grid-column:1/-1">
                    <span class="row"><span class="badge lan">${ic("star")}Bài nổi bật ${home.featured.period === "WEEK" ? "tuần này" : "tháng này"}</span>
                        <span class="note">${home.featured.readMinutes} phút đọc</span></span>
                    <h3>${esc(home.featured.title)}</h3>
                    <span class="note">${esc(home.featured.excerpt)}</span>
                    <span class="row">${esc(home.featured.authorName)} ${tagChips(home.featured.authorTags)}</span></a>` : ""}
                ${forYouCard(home)}
            </div>
        </div>`;
    bindSpeak();
    bindPrompt();
}

/** "Dành cho bạn": posts near the user's area or suiting their stage first (chosen by the server). */
function forYouCard(home) {
    const posts = home.forYou || [];
    const hint = home.areaName && home.stage ? "" : `<p class="note" id="foryou-hint">${forYouHint(!promptHidden())}</p>`;
    return `<section class="card foryou" style="grid-column:1/-1" aria-labelledby="foryou-title">
        <div class="row"><span class="badge br" id="foryou-title">${ic("auto_awesome")}Dành cho bạn</span><span class="grow"></span>
            <a class="link" href="/blog">Xem thêm</a></div>
        ${hint}
        ${posts.map((p) => `<article class="feeditem">
            <a class="feedtitle" href="/blog/${encodeURIComponent(p.id)}">${esc(p.title)}</a>
            ${p.excerpt ? `<span class="note">${esc(p.excerpt)}</span>` : ""}
            <span class="row">${p.topicName ? `<span class="badge">${esc(p.topicName)}</span>` : ""}
                ${p.reason ? `<span class="badge lan">${ic(p.reason === "Gần khu vực của bạn" ? "location_on" : "flag")}${esc(p.reason)}</span>` : ""}
                <span class="note">${esc(p.authorName)} · ${esc(timeAgo(p.createdAt))} ·
                    <span aria-label="${p.likeCount} lượt thích">${ic("favorite")}${p.likeCount}</span>
                    <span aria-label="${p.commentCount} bình luận">${ic("chat_bubble")}${p.commentCount}</span></span></span>
        </article>`).join("") || '<p class="note">Chưa có bài viết phù hợp. Hãy xem thêm ở <a class="link" href="/blog">Blog</a>.</p>'}
    </section>`;
}

/** Points to the prompt above while it is shown, otherwise to the "Tôi" page. */
const forYouHint = (promptShown) => `${ic("tips_and_updates")}Chọn khu vực và giai đoạn ${promptShown
    ? "ở ô phía trên" : 'ở trang <a class="link" href="/profile">Tôi</a>'} để mục này gợi ý bài hợp với bạn hơn.`;

function promptHidden() {
    try {
        return sessionStorage.getItem("asvsj.hidePrompt") === "1";
    } catch {
        return false; // storage blocked
    }
}

const areaShort = (name) => (name ? name.split("–").pop().trim() : "");

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

function profilePrompt(home, areas) {
    if ((home.areaName && home.stage) || promptHidden()) {
        return "";
    }
    return `<div class="card promptcard" id="prompt">
        <b>${ic("location_on")} Chọn khu vực và giai đoạn của bạn</b>
        <p class="note">Để Senpai ơi gợi ý việc cần làm, review và sự kiện gần bạn. Chỉ lưu khu vực, không lưu địa chỉ; chỉ mình bạn thấy.</p>
        <div class="two">
            <div class="field"><label for="hp-area">Khu vực sinh sống</label>
                <select class="select" id="hp-area"><option value="">Chưa chọn</option>
                ${areas.map((a) => `<option value="${a.id}" ${a.name === home.areaName ? "selected" : ""}>${esc(a.name)}</option>`).join("")}</select></div>
            <div class="field"><label for="hp-stage">Giai đoạn</label>
                <select class="select" id="hp-stage"><option value="">Chưa chọn</option>
                ${Object.entries(STAGES).map(([k, v]) => `<option value="${k}" ${k === home.stage ? "selected" : ""}>${v}</option>`).join("")}</select></div>
        </div>
        <div class="row"><button class="btn pri sm" type="button" data-save>Lưu</button>
            <button class="btn sm" type="button" data-later>Để sau</button></div>
    </div>`;
}

function bindPrompt() {
    const prompt = document.querySelector("#prompt");
    if (!prompt) {
        return;
    }
    prompt.querySelector("[data-save]").addEventListener("click", async () => {
        const areaId = document.querySelector("#hp-area").value;
        const stage = document.querySelector("#hp-stage").value;
        await apiClient.updateProfile({ areaId: areaId ? Number(areaId) : null, stage: stage || null });
        await renderMember();
    });
    prompt.querySelector("[data-later]").addEventListener("click", () => {
        try {
            sessionStorage.setItem("asvsj.hidePrompt", "1");
        } catch {
            // storage blocked
        }
        prompt.remove();
        const hint = document.querySelector("#foryou-hint");
        if (hint) {
            hint.innerHTML = forYouHint(false);
        }
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
