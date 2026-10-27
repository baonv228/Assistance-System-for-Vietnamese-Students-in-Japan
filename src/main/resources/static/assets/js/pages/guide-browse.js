import { guideContent } from "../lib/guide-content.js";
import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";

const $ = selector => document.querySelector(selector);
const params = new URLSearchParams(location.search);
const state = {
 q: (params.get("q") || "").slice(0, 200),
 category: params.get("category") || "",
 topicCode: params.get("topicCode") || "",
 sort: ["NEWEST", "OLDEST", "TITLE"].includes(params.get("sort")) ? params.get("sort") : "NEWEST",
 page: /^\d{1,9}$/.test(params.get("page") || "") ? Number(params.get("page")) : 0,
 size: 12
};
const id = location.pathname.match(/^\/guides\/(\d+)\/?$/)?.[1];
function el(tag, className, text) {
 const node = document.createElement(tag);
 if (className) node.className = className;
 if (text !== undefined) node.textContent = text ?? "";
 return node;
}
function url(path, criteria = state) {
 const query = new URLSearchParams();
 for (const key of ["q", "category", "topicCode", "sort", "page"]) {
  const value = criteria[key];
  if (value && !(key === "sort" && value === "NEWEST")) query.set(key, value);
 }
 return path + (query.size ? "?" + query : "");
}
function link(text, href, className = "btn") {
 const a = el("a", className, text); a.href = href; return a;
}
function date(value) { return new Date(value).toLocaleDateString("vi-VN"); }
function safeUrl(value, image = false) {
 if (!value) return null;
 try {
  const parsed = new URL(value, location.origin);
  if (image && value.startsWith("/media/") && parsed.origin === location.origin) return parsed.href;
  if (["https:", "http:"].includes(parsed.protocol) && !parsed.username && !parsed.password) return parsed.href;
 } catch { /* Invalid links are omitted. */ }
 return null;
}
function showError(error) {
 $("#browse-error").hidden = false;
 $("#browse-error").textContent = error.status === 404
  ? "Cẩm nang không tồn tại hoặc hiện không còn công khai." : error.message;
 $("#browse-count").textContent = "";
}
function pager(result) {
 const target = $("#browse-pager"); target.replaceChildren();
 if (result.totalPages <= 1) { target.hidden = true; return; }
 target.hidden = false;
 if (result.page > 0) target.append(link("← Trang trước", url("/guides", { ...state, page: result.page - 1 })));
 const start = Math.max(0, result.page - 2), end = Math.min(result.totalPages - 1, result.page + 2);
 for (let page = start; page <= end; page++) {
  const a = link(String(page + 1), url("/guides", { ...state, page }));
  a.setAttribute("aria-label", "Trang " + (page + 1));
  if (page === result.page) { a.setAttribute("aria-current", "page"); a.classList.add("on"); }
  target.append(a);
 }
 if (result.page < result.totalPages - 1) target.append(link("Trang sau →", url("/guides", { ...state, page: result.page + 1 })));
}
async function list() {
 const result = await apiClient.browseGuides(state);
 state.page = result.page;
 history.replaceState(null, "", url("/guides"));
 $("#browse-count").textContent = result.total
  ? result.total + " cẩm nang phù hợp · Trang " + (result.page + 1) + "/" + result.totalPages
  : "Không có cẩm nang phù hợp. Hãy thử từ khóa hoặc chủ đề khác.";
 const cards = result.items.map(guide => {
  const article = el("article", "card guide-result");
  article.dataset.topic = guide.topicCode || "OTHER";
  const cover = link("", url("/guides/" + guide.id), "guide-card-cover");
  cover.setAttribute("aria-label", "Xem cẩm nang: " + guide.title);
  const imageUrl = safeUrl(guide.coverImageUrl, true);
  const fallback = () => { const icon = el("span", "ic guide-cover-placeholder", "menu_book"); icon.setAttribute("aria-hidden", "true"); cover.prepend(icon); };
  if (imageUrl) {
   const image = el("img"); image.src = imageUrl; image.alt = guide.title; image.loading = "lazy"; image.decoding = "async"; image.referrerPolicy = "no-referrer";
   image.addEventListener("error", () => { image.remove(); fallback(); }, { once: true }); cover.append(image);
  } else fallback();
  cover.append(el("span", "guide-card-topic", guide.topicName || "Cẩm nang"));
  const body = el("div", "guide-card-body");
  const title = el("h2"); title.append(link(guide.title, url("/guides/" + guide.id), "guide-title"));
  body.append(title, el("p", "guide-summary", guide.summary || "Hướng dẫn dành cho sinh viên Việt Nam tại Nhật Bản."));
  if (guide.stageName) body.append(el("p", "guide-card-stage", guide.stageName));
  const time = el("time", "guide-card-date", date(guide.createdAt)); time.dateTime = guide.createdAt; body.append(time);
  article.append(cover, body);
  return article;
 });
 $("#browse-list").replaceChildren(...cards); pager(result);
}
async function detail() {
 $("#browse-listing").hidden = true;
 $("#browse-back").href = url("/guides"); $("#browse-back").hidden = false;
 const target = $("#browse-detail"); target.hidden = false;
 target.append(el("p", "note", "Đang tải cẩm nang…"));
 try {
  const guide = await apiClient.guide(id);
  document.title = guide.title + " – Cẩm nang – Senpai ơi";
  target.replaceChildren(el("span", "badge", guide.topicName), el("h2", null, guide.title),
   el("p", "note", "Ngày tạo: " + date(guide.createdAt) + (guide.creatorName ? " · " + guide.creatorName : "")),
   el("p", "guide-content", guide.summary));
  if (guide.coverImageUrl && safeUrl(guide.coverImageUrl, true)) { const image = el("img"); image.src = safeUrl(guide.coverImageUrl, true); image.alt = guide.title; target.append(image); }
  if (guide.stageName) target.append(el("p", "note", "Giai đoạn: " + guide.stageName));
  if (guide.sourceUrl && safeUrl(guide.sourceUrl)) { const source = link("Đường dẫn gốc ↗", safeUrl(guide.sourceUrl), "link"); source.target = "_blank"; source.rel = "noopener noreferrer"; target.append(source); }
  for (const [i, section] of guide.sections.entries()) {
   const block = el("section", "guide-detail-section");
   block.append(el("h3", null, (i + 1) + ". " + section.heading), guideContent(section.content));
   const imageUrl = safeUrl(section.imageUrl, true);
   if (imageUrl) {
    const image = el("img"); image.src = imageUrl; image.alt = section.heading;
    image.loading = "lazy"; image.referrerPolicy = "no-referrer"; block.append(image);
   }
   const referenceUrl = safeUrl(section.referenceUrl);
   if (referenceUrl) {
    const reference = link("Tài liệu tham khảo ↗", referenceUrl, "link");
    reference.target = "_blank"; reference.rel = "noopener noreferrer"; block.append(reference);
   }
   target.append(block);
  }
  if (!guide.sections.length) target.append(el("p", "note", "Nội dung hướng dẫn đang được cập nhật."));
 } catch (error) { target.hidden = true; showError(error); }
}

$("#browse-filters").addEventListener("submit", event => {
 event.preventDefault();
 const filters = Object.fromEntries(new FormData(event.target));
 location.assign(url("/guides", { ...state, ...filters, q: filters.q.trim(), page: 0 }));
});
for (const select of document.querySelectorAll("#browse-filters select, select[form=\"browse-filters\"]")) {
 select.addEventListener("change", () => $("#browse-filters").requestSubmit());
}
try {
 await mountSiteHeader("guide");
 if (id) await detail();
 else {
  const topics = await apiClient.guideTopics();
  const select = $("#browse-filters").elements.category;
  for (const topic of topics) { const option = el("option", null, topic.title); option.value = topic.code; select.append(option); }
  if (state.category && !topics.some(topic => topic.code === state.category)) {
   const option = el("option", null, "Giai đoạn không tồn tại"); option.value = state.category; select.append(option);
  }
  const subjects = await apiClient.guideSubjects();
  const pill = (code, title) => {
   const node = el("button", "guide-topic-pill", title); node.type = "button";
   node.setAttribute("aria-pressed", String(state.topicCode === code));
   node.addEventListener("click", () => { $("#browse-filters").elements.topicCode.value = code; $("#browse-filters").requestSubmit(); });
   $("#browse-topics").append(node);
  };
  pill("", "Tất cả"); for (const topic of subjects) pill(topic.code, topic.title);
  if (state.topicCode && !subjects.some(topic => topic.code === state.topicCode)) pill(state.topicCode, "Chủ đề không tồn tại");
  for (const key of ["q", "category", "topicCode", "sort"]) $("#browse-filters").elements[key].value = state[key];
  await list();
 }
} catch (error) { showError(error); }
