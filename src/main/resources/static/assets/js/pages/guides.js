import { apiClient, currentUser } from "../api/client.js";
import { mountNavbar } from "../components/navbar.js";
const $ = s => document.querySelector(s);
function el(tag, className, text) {
 const node = document.createElement(tag);
 if (className) node.className = className;
 if (text !== undefined) node.textContent = text ?? "";
 return node;
}
const message = text => { $("#message").textContent = text; };
const managing = location.pathname.startsWith("/moderator") || location.pathname.includes("/pages/moderator/");
if (managing) await import("./staff.js");
let draft = null, busy = false, sequence = 0;
const initialQuery = new URLSearchParams(location.search);
const listState = {
 q: (initialQuery.get("q") || "").slice(0, 200),
 category: initialQuery.get("category") || "",
 page: /^\d{1,9}$/.test(initialQuery.get("page") || "") ? Number(initialQuery.get("page")) : 0,
 size: 10
};
function updateListUrl(id = new URLSearchParams(location.search).get("id")) {
 const query = new URLSearchParams();
 for (const key of ["q", "category", "page"]) if (listState[key]) query.set(key, listState[key]);
 if (id) query.set("id", id);
 history.replaceState(null, "", location.pathname + (query.size ? "?" + query : ""));
}
function renderPager(result) {
 const pager = $("#guide-pager"); pager.replaceChildren();
 pager.hidden = result.totalPages <= 1;
 if (pager.hidden) return;
 const go = page => { message(""); loadList(page); };
 const previous = button("← Trang trước", () => go(result.page - 1));
 previous.disabled = result.page === 0; pager.append(previous);
 for (let page = Math.max(0, result.page - 2); page <= Math.min(result.totalPages - 1, result.page + 2); page++) {
  const item = button(String(page + 1), () => go(page));
  item.setAttribute("aria-label", "Trang " + (page + 1));
  if (page === result.page) { item.classList.add("on"); item.setAttribute("aria-current", "page"); }
  pager.append(item);
 }
 const next = button("Trang sau →", () => go(result.page + 1));
 next.disabled = result.page >= result.totalPages - 1; pager.append(next);
}
const previewUrls = new Map();
function clearPreviews() { previewUrls.forEach(url => URL.revokeObjectURL(url)); previewUrls.clear(); }
function show(view) {
 for (const id of ["listing", "editor", "detail"]) $("#" + id).hidden = id !== view;
}
function button(text, action) { const b = el("button", "btn", text); b.type = "button"; b.addEventListener("click", action); return b; }
function safeUrl(value) { try { const u = new URL(value); return ["https:", "http:"].includes(u.protocol) && !u.username && !u.password ? u.href : null; } catch { return null; } }
function renderGuide(guide, preview = false) {
 const target = $("#detail"); target.replaceChildren();
 target.append(el("h2", null, guide.title), el("p", "guide-meta", guide.topicName), el("p", "guide-content", guide.summary));
 if (!preview) target.append(el("p", "guide-meta", "Người tạo: " + (guide.creatorName || "Chưa có thông tin") + " · " + new Date(guide.createdAt).toLocaleString("vi-VN") + " · Công khai"));
 guide.sections.forEach((s, i) => {
  const section = el("section"); section.append(el("h3", null, (i + 1) + ". " + s.heading), el("p", "guide-content", s.content));
  const storedImage = s.imageUrl?.startsWith("/media/") ? new URL(s.imageUrl, location.origin).href : safeUrl(s.imageUrl);
  const image = preview ? draft?.previews[i] : storedImage;
  if (image) { const img = el("img"); img.src = image; img.alt = s.heading; img.loading = "lazy"; img.referrerPolicy = "no-referrer"; section.append(img); }
  const reference = safeUrl(s.referenceUrl);
  if (reference) { const a = el("a", null, "Tài liệu tham khảo"); a.href = reference; a.target = "_blank"; a.rel = "noopener noreferrer"; section.append(a); }
  target.append(section);
 });
 if (preview) {
  target.prepend(el("p", "guide-meta", "Bản nháp xem trước — chưa xuất bản"));
  target.append(button("Tiếp tục chỉnh sửa", () => show("editor")), button("Lưu và xuất bản", publish));
 } else target.append(button("Quay lại danh sách", () => { updateListUrl(null); show("listing"); }));
 show("detail");
}
async function loadList(page = listState.page) {
 const request = ++sequence;
 $("#guide-list").textContent = "Đang tải…";
 $("#guide-pager").hidden = true;
 $("#count").textContent = "";
 try {
  const result = await apiClient.moderatorGuides({ ...listState, page });
  if (request !== sequence) return;
  const guides = result.items;
  listState.page = result.page; updateListUrl();
  $("#count").textContent = result.total
   ? result.total + " cẩm nang · Trang " + (result.page + 1) + "/" + result.totalPages
   : "0 cẩm nang";
  renderPager(result);
  $("#guide-list").replaceChildren(...guides.map(g => {
   const article = el("article", "list-item card");
   article.append(el("span", "tag", g.topicName), el("h2", null, g.title), el("p", null, g.summary), el("p", "guide-meta", new Date(g.createdAt).toLocaleString("vi-VN")), button("Xem chi tiết", async () => {
    try { const guide = await apiClient.guide(g.id); updateListUrl(g.id); renderGuide(guide); message(""); } catch(e) { message(e.message); }
   })); return article;
  }));
  if (!guides.length) $("#guide-list").append(el("p", null, "Không có cẩm nang phù hợp."));
 } catch(e) { if (request === sequence) { $("#guide-list").replaceChildren(); message(e.message); } }
}
function addSection() {
 if ($("#sections").children.length >= 50) return message("Tối đa 50 mục hướng dẫn.");
 const box = el("fieldset"); box.append(el("legend", null, "Mục hướng dẫn"));
 for (const [name, title, max, area] of [["heading", "Tiêu đề mục *", 200], ["content", "Nội dung / bước hướng dẫn *", 20000, true], ["referenceUrl", "Đường dẫn tài liệu tham khảo (tùy chọn)", 2000]]) {
  const label = el("label", null, title), input = el(area ? "textarea" : "input"); input.dataset.field = name; input.maxLength = max; input.required = ["heading", "content"].includes(name); if (name.endsWith("Url")) input.type = "url"; label.append(input); box.append(label);
 }
 const imageLabel = el("label", null, "Ảnh minh họa từ máy tính (tùy chọn)");
 const input = el("input"); input.type = "file"; input.accept = "image/jpeg,image/png"; input.dataset.image = "";
 const thumbnail = el("img", "guide-thumbnail"); thumbnail.hidden = true;
 const removeImage = button("Bỏ ảnh", () => { if (previewUrls.has(box)) URL.revokeObjectURL(previewUrls.get(box)); previewUrls.delete(box); input.value = ""; thumbnail.removeAttribute("src"); thumbnail.hidden = true; removeImage.hidden = true; }); removeImage.hidden = true;
 input.addEventListener("change", () => {
  if (previewUrls.has(box)) URL.revokeObjectURL(previewUrls.get(box)); previewUrls.delete(box);
  thumbnail.hidden = true; removeImage.hidden = true;
  const file = input.files[0]; if (!file) return;
  if (!["image/jpeg", "image/png"].includes(file.type) || file.size > 20 * 1024 * 1024) { input.value = ""; message("Ảnh phải là JPG/PNG, tối đa 20 MB mỗi ảnh."); return; }
  const url = URL.createObjectURL(file); previewUrls.set(box, url); thumbnail.src = url; thumbnail.alt = file.name; thumbnail.hidden = false; removeImage.hidden = false; message("");
 });
 imageLabel.append(input); box.append(imageLabel, el("p", "note", "JPG/PNG, tối đa 20 MB/ảnh. Tối đa 5 ảnh cho mỗi cẩm nang."), thumbnail, removeImage);
 const tools = el("div", "guide-section-tools");
 tools.append(button("↑ Lên", () => { if (box.previousElementSibling) box.before(box.previousElementSibling); }), button("↓ Xuống", () => { if (box.nextElementSibling) box.after(box.nextElementSibling); }), button("Xóa mục", () => { if ($("#sections").children.length > 1) { if (previewUrls.has(box)) URL.revokeObjectURL(previewUrls.get(box)); previewUrls.delete(box); box.remove(); } else message("Cần ít nhất một mục hướng dẫn."); })); box.append(tools); $("#sections").append(box);
}
async function publish() {
 if (busy || !draft) return;
 busy = true; $("#detail").querySelectorAll("button").forEach(b => b.disabled = true);
 try {
  const form = new FormData();
  form.append("guide", new Blob([JSON.stringify(draft.data)], { type: "application/json" }));
  draft.images.forEach(file => form.append("images", file));
  const guide = await apiClient.createGuide(form); clearPreviews(); draft = null; $("#guide-form").reset(); $("#sections").replaceChildren(); addSection();
  updateListUrl(guide.id); renderGuide(guide); await loadList(0); message("Đã lưu và xuất bản cẩm nang thành công.");
 } catch(e) { message(e.message + (Object.values(e.fields || {}).length ? " " + Object.values(e.fields).join(" ") : "")); }
 finally { busy = false; $("#detail").querySelectorAll("button").forEach(b => b.disabled = false); }
}
function searchGuides() {
 Object.assign(listState, Object.fromEntries(new FormData($("#filters"))), { page: 0 });
 listState.q = listState.q.trim(); message(""); loadList(0);
}
$("#filters").addEventListener("submit", e => { e.preventDefault(); searchGuides(); });
$("#filters select").addEventListener("change", searchGuides);
$("#create").addEventListener("click", () => { message(""); show("editor"); });
$("#add-section").addEventListener("click", addSection);
$("#cancel").addEventListener("click", () => { show("listing"); message(""); });
$("#guide-form").addEventListener("submit", e => {
 e.preventDefault(); message("");
 const data = Object.fromEntries(new FormData(e.target));
 data.sections = [...$("#sections").children].map(box => Object.fromEntries([...box.querySelectorAll("[data-field]")].map(input => [input.dataset.field, input.value.trim()])));
 if (!data.title.trim() || !data.summary.trim() || data.sections.some(s => !s.heading || !s.content)) return message("Vui lòng nhập đầy đủ các trường bắt buộc.");
 if (data.sections.some(s => s.referenceUrl && !safeUrl(s.referenceUrl))) return message("Đường dẫn phải dùng HTTP hoặc HTTPS.");
 const images = [], previews = [];
 [...$("#sections").children].forEach((box, i) => {
  const file = box.querySelector("[data-image]").files[0];
  if (file) { data.sections[i].imageIndex = images.length; images.push(file); }
  previews.push(previewUrls.get(box) || null);
 });
 if (images.length > 5) return message("Mỗi cẩm nang chỉ được đính kèm tối đa 5 ảnh.");
 draft = { data, images, previews }; renderGuide({ ...data, topicName: e.target.elements.category.selectedOptions[0].textContent }, true);
});
try {
 const user = await currentUser();
 if (managing && user?.role !== "MODERATOR") location.replace(user?.homePath || "/login");
 else {
  if (!managing) await mountNavbar();
  $("#create").hidden = user?.role !== "MODERATOR"; $("#dashboard").hidden = managing || user?.role !== "MODERATOR";
  const topics = await apiClient.guideTopics();
  for (const select of document.querySelectorAll("select[name=category]")) for (const topic of topics) { const option = el("option", null, topic.title); option.value = topic.code; select.append(option); }
  $("#filters").elements.q.value = listState.q;
  $("#filters").elements.category.value = listState.category;
  addSection(); await loadList();
  const id = new URLSearchParams(location.search).get("id"); if (id && /^\d+$/.test(id)) renderGuide(await apiClient.guide(id));
 }
} catch(e) { message(e.message); }

window.addEventListener("pagehide", clearPreviews);
