import { apiClient } from "../api/client.js";
import { preparationTabs } from "../lib/guide-preparation.js";
import { guideContent } from "../lib/guide-content.js";
await import("./staff.js");
const $ = selector => document.querySelector(selector);
const form = $("#guide-form"), filters = $("#filters");
const query = new URLSearchParams(location.search);
const state = { q: query.get("q") || "", category: query.get("category") || "", topicCode: query.get("topicCode") || "", status: query.get("status") || "ALL", page: /^\d{1,9}$/.test(query.get("page") || "") ? Number(query.get("page")) : 0, size: 10 };
let sequence = 0, editingId = null, coverFile = null, coverUrl = null, savedCover = null, removeCover = false, busy = false;
let sectionReaders = [];
const sectionImages = new Map();
const sectionEditor = document.createElement("div"); sectionEditor.id = "guide-existing-sections"; sectionEditor.hidden = true;
$(".guide-rich-editor").after(sectionEditor);
// Reuse the existing rich/section editors as the first panel; checklist editing has its own tab.
const contentPanel = document.createElement("section"); contentPanel.id = "editor-content-panel";
contentPanel.setAttribute("role", "tabpanel"); contentPanel.setAttribute("aria-labelledby", "editor-content-tab");
const contentLabel = form.querySelector('label[for="guide-content-input"]');
contentLabel.before(contentPanel); contentPanel.append(contentLabel, $(".guide-rich-editor"), sectionEditor);
$("#editor-preparation-panel").before($(".guide-editor-tabs"), contentPanel);
let savedTaskCount = 0;
function editorTab(preparation = false) {
 contentPanel.hidden = preparation; $("#editor-preparation-panel").hidden = !preparation;
 for (const [selector, active] of [["#editor-content-tab", !preparation], ["#editor-preparation-tab", preparation]]) {
  $(selector).classList.toggle("on", active); $(selector).setAttribute("aria-selected", String(active)); $(selector).tabIndex = active ? 0 : -1;
 }
}
function addPreparationTask(task = {}) {
 if ($("#preparation-rows").children.length >= 100) return message("Tối đa 100 công việc.");
 const row = el("fieldset", "guide-preparation-row"); if (task.id) row.dataset.taskId = task.id;
 const titleLabel = el("label", null, "Công việc *"), title = el("input"); title.dataset.taskTitle = ""; title.maxLength = 255; title.value = task.title || ""; title.placeholder = "Ví dụ: Chuẩn bị hộ chiếu và visa"; titleLabel.append(title);
 const notesLabel = el("label", null, "Ghi chú (tùy chọn)"), notes = el("textarea"); notes.dataset.taskNotes = ""; notes.maxLength = 2000; notes.value = task.notes || ""; notesLabel.append(notes);
 const actions = el("div", "guide-actions"); actions.append(button("↑ Lên", () => { if (row.previousElementSibling) row.before(row.previousElementSibling); }), button("↓ Xuống", () => { if (row.nextElementSibling) row.after(row.nextElementSibling); }), button("Xóa công việc", () => { if (!task.id || window.confirm("Xóa công việc này? Tiến độ đã tick của công việc sẽ bị xóa khi lưu cẩm nang.")) row.remove(); }));
 row.append(titleLabel, notesLabel, actions); $("#preparation-rows").append(row);
}
function setupPreparation(guide) {
 $("#preparation-enabled").checked = !!guide?.preparationEnabled;
 $("#editor-preparation-tab").hidden = !guide?.preparationEnabled;
 $("#preparation-rows").replaceChildren(); savedTaskCount = guide?.preparationTasks?.length || 0;
 for (const task of guide?.preparationTasks || []) addPreparationTask(task);
 editorTab(false);
}
$("#preparation-enabled").addEventListener("change", event => {
 const enabled = event.target.checked;
 if (!enabled && savedTaskCount && !window.confirm("Bỏ danh sách chuẩn bị? Các công việc và tiến độ của người đọc sẽ bị xóa khi lưu.")) { event.target.checked = true; return; }
 $("#editor-preparation-tab").hidden = !enabled;
 if (enabled && !$("#preparation-rows").children.length) addPreparationTask();
 editorTab(enabled);
});
$("#editor-content-tab").addEventListener("click", () => editorTab(false));
$("#editor-preparation-tab").addEventListener("click", () => editorTab(true));
$("#add-preparation-task").addEventListener("click", () => addPreparationTask());
for (const tab of document.querySelectorAll(".guide-editor-tabs [role=tab]")) tab.addEventListener("keydown", event => {
 if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key) && !$("#editor-preparation-tab").hidden) { event.preventDefault(); const next = event.key === "Home" ? false : event.key === "End" ? true : tab.id === "editor-content-tab"; editorTab(next); $(next ? "#editor-preparation-tab" : "#editor-content-tab").focus(); }
});
const message = text => { $("#message").textContent = text; };
function el(tag, cls, text) { const node = document.createElement(tag); if (cls) node.className = cls; if (text !== undefined) node.textContent = text ?? ""; return node; }
function button(text, action) { const node = el("button", "btn", text); node.type = "button"; node.addEventListener("click", action); return node; }
function setUrl(id = null, edit = false) { const params = new URLSearchParams(); for (const key of ["q", "category", "topicCode", "status", "page"]) if (state[key] && state[key] !== "ALL") params.set(key, state[key]); if (id) params.set(edit ? "edit" : "id", id); history.replaceState(null, "", location.pathname + (params.size ? "?" + params : "")); }
function show(view) { for (const id of ["listing", "editor", "detail"]) $("#" + id).hidden = id !== view; $(".page-heading").hidden = view === "editor"; }
function safeUrl(value) { if (!value) return null; try { const url = new URL(value, location.origin); return ["http:", "https:"].includes(url.protocol) && !url.username && !url.password ? url.href : null; } catch { return null; } }
function revokeCover() { if (coverUrl) URL.revokeObjectURL(coverUrl); coverUrl = null; }
function displayCover() { const url = coverUrl || savedCover; $("#cover-preview").hidden = !url; if (url) $("#cover-preview").src = url; else $("#cover-preview").removeAttribute("src"); $("#remove-cover").hidden = !url; }
function selectCover(file) {
 if (!file) return;
 if (!["image/jpeg", "image/png"].includes(file.type) || file.size > 5 * 1024 * 1024) return message("Ảnh bìa phải là JPG/PNG, tối đa 5 MB.");
 revokeCover(); coverFile = file; coverUrl = URL.createObjectURL(file); removeCover = false; displayCover(); message("");
}
function setupSections(guide) {
 sectionReaders = []; sectionImages.clear(); sectionEditor.replaceChildren();
 const sections = guide?.sections || [];
 const structured = sections.length > 1 || sections.some(section => section.heading !== "Nội dung" || section.mediaId || section.imageUrl || section.referenceUrl);
 sectionEditor.hidden = !structured; $(".guide-rich-editor").hidden = structured;
 if (!structured) return;
 for (const section of sections) {
  const box = el("fieldset"); box.append(el("legend", null, "Mục hướng dẫn"));
  const fields = {};
  for (const [key, label, max, area] of [["heading", "Tiêu đề mục", 200], ["content", "Nội dung", 20000, true], ["referenceUrl", "Tài liệu tham khảo (tùy chọn)", 2000]]) {
   const labelNode = el("label", null, label), input = el(area ? "textarea" : "input");
   input.value = section[key] || ""; input.maxLength = max; if (key === "referenceUrl") input.type = "url";
   fields[key] = input; labelNode.append(input); box.append(labelNode);
  }
  let mediaId = section.mediaId || null, imageUrl = section.imageUrl || null;
  if (mediaId && imageUrl) sectionImages.set(mediaId, imageUrl);
  if (imageUrl && safeUrl(imageUrl)) {
   const image = el("img", "guide-thumbnail"); image.src = safeUrl(imageUrl); image.alt = section.heading || "Ảnh minh họa";
   const remove = button("Bỏ ảnh trong mục", () => { mediaId = null; imageUrl = null; image.remove(); remove.remove(); });
   box.append(image, remove);
  }
  sectionReaders.push(() => ({ heading: fields.heading.value, content: fields.content.value,
   referenceUrl: fields.referenceUrl.value || null, imageUrl: mediaId ? null : imageUrl, mediaId }));
  sectionEditor.append(box);
 }
}
function resetEditor(guide = null) {
 revokeCover(); form.reset(); coverFile = null; savedCover = guide?.coverImageUrl || null; removeCover = false; editingId = guide?.id || null;
 form.elements.title.value = guide?.title || ""; form.elements.summary.value = guide?.summary || "";
 form.elements.category.value = guide?.category || ""; form.elements.topicCode.value = guide?.topicCode || "";
 form.elements.sourceUrl.value = guide?.sourceUrl || ""; form.elements.status.value = guide?.status || "PUBLISHED";
 form.elements.content.value = guide?.sections.map(section => section.heading === "Nội dung" ? section.content : "## " + section.heading + "\n" + section.content).join("\n\n") || "";
 setupSections(guide); setupPreparation(guide); $("#publish").textContent = guide?.published ? "Cập nhật công khai" : "Đăng công khai";
 $("#editor-title").textContent = guide ? "Chỉnh sửa cẩm nang" : "Tạo mới cẩm nang"; displayCover(); show("editor"); setUrl(editingId, true); message("");
}
async function editGuide(id) { if (busy) return; try { const guide = await apiClient.moderatorGuide(id); resetEditor(guide); } catch (error) { message(error.message); } }
function pager(result) {
 const target = $("#guide-pager"); target.replaceChildren(); target.hidden = result.totalPages <= 1;
 if (target.hidden) return;
 const previous = button("← Trang trước", () => loadList(result.page - 1)); previous.disabled = result.page === 0; target.append(previous);
 for (let page = Math.max(0, result.page - 2); page <= Math.min(result.totalPages - 1, result.page + 2); page++) {
  const node = button(String(page + 1), () => loadList(page)); node.setAttribute("aria-label", "Trang " + (page + 1));
  if (page === result.page) { node.classList.add("on"); node.setAttribute("aria-current", "page"); } target.append(node);
 }
 const next = button("Trang sau →", () => loadList(result.page + 1)); next.disabled = result.page + 1 >= result.totalPages; target.append(next);
}
async function loadList(page = state.page) {
 const request = ++sequence; $("#guide-list").textContent = "Đang tải…"; $("#guide-pager").hidden = true;
 try {
  const result = await apiClient.moderatorGuides({ ...state, page }); if (request !== sequence) return;
  state.page = result.page;
  if (!$("#listing").hidden) setUrl();
  $("#count").textContent = result.total + " cẩm nang" + (result.total ? " · Trang " + (result.page + 1) + "/" + result.totalPages : "");
  $("#guide-list").replaceChildren(...result.items.map(guide => {
   const card = el("article", "list-item card");
   card.append(el("span", "badge", guide.published ? "Công khai" : "Nháp"), el("h2", null, guide.title), el("p", "note", [guide.stageName, guide.topicName].filter(Boolean).join(" · ")), el("p", null, guide.summary), el("p", "guide-meta", new Date(guide.createdAt).toLocaleString("vi-VN")));
   const actions = el("div", "guide-actions"); actions.append(button("Xem chi tiết", async () => { try { renderGuide(await apiClient.moderatorGuide(guide.id)); setUrl(guide.id); } catch (error) { message(error.message); } })); actions.append(button("Chỉnh sửa", () => editGuide(guide.id)), deleteButton(guide)); card.append(actions); return card;
  }));
  if (!result.items.length) $("#guide-list").append(el("p", null, "Không có cẩm nang phù hợp.")); pager(result);
 } catch (error) { if (request === sequence) { $("#guide-list").replaceChildren(); message(error.message); } }
}
function renderGuide(guide, preview = false) {
 const target = $("#detail"); target.replaceChildren(el("p", "badge", preview ? "Xem trước – chưa lưu" : guide.published ? "Công khai" : "Nháp"), el("h2", null, guide.title), el("p", "note", [guide.stageName, guide.topicName].filter(Boolean).join(" · ")), el("p", "guide-content", guide.summary));
 const cover = safeUrl(guide.coverImageUrl); if (cover || (preview && coverUrl)) { const image = el("img"); image.src = preview && coverUrl ? coverUrl : cover; image.alt = guide.title; target.append(image); }
 for (const section of guide.sections) {
  if (section.heading !== "Nội dung") target.append(el("h3", null, section.heading)); target.append(guideContent(section.content));
  const imageUrl = safeUrl(section.imageUrl || sectionImages.get(section.mediaId)); if (imageUrl) { const image = el("img"); image.src = imageUrl; image.alt = section.heading; target.append(image); }
  if (section.referenceUrl && safeUrl(section.referenceUrl)) { const a = el("a", "link", "Tài liệu tham khảo"); a.href = safeUrl(section.referenceUrl); a.target = "_blank"; a.rel = "noopener noreferrer"; target.append(a); }
 }
 if (guide.sourceUrl && safeUrl(guide.sourceUrl)) { const a = el("a", "link", "Đường dẫn gốc ↗"); a.href = safeUrl(guide.sourceUrl); a.target = "_blank"; a.rel = "noopener noreferrer"; target.append(a); }
 if (guide.preparationEnabled) preparationTabs(target, guide);
 if (preview) target.append(button("Tiếp tục chỉnh sửa", () => show("editor")), button("Lưu nháp", () => save("DRAFT")), button("Đăng công khai", () => save("PUBLISHED")));
 else { target.append(button("Chỉnh sửa", () => editGuide(guide.id)), deleteButton(guide)); target.append(el("p", "note", "Người tạo: " + (guide.creatorName || "Chưa có thông tin") + " · " + new Date(guide.createdAt).toLocaleString("vi-VN")), button("Quay lại danh sách", () => { show("listing"); setUrl(); })); }
 show("detail");
}
function deleteButton(guide) {
 const node = button("Xóa", () => deleteGuide(guide, node)); node.classList.add("dan"); return node;
}
async function deleteGuide(guide, node) {
 if (busy || !window.confirm('Xóa cẩm nang “' + guide.title + '”? Cẩm nang sẽ không còn hiển thị trong danh sách và trang người dùng.')) return;
 busy = true; node.disabled = true;
 try {
  await apiClient.deleteGuide(guide.id);
  if (editingId === guide.id) { editingId = null; revokeCover(); }
  show("listing"); setUrl(); await loadList(state.page); message("Đã xóa cẩm nang.");
 } catch (error) { message(error.message); }
 finally { busy = false; node.disabled = false; }
}
function collect(status) {
 const data = Object.fromEntries(new FormData(form));
 if (!data.title.trim()) throw new Error("Vui lòng nhập tiêu đề cẩm nang.");
 const sections = sectionReaders.length ? sectionReaders.map(read => read()) : [{ heading: "Nội dung", content: data.content }];
 if (status === "PUBLISHED" && (!data.category || !data.topicCode || sections.some(section => !section.heading.trim() || !section.content.trim()))) throw new Error("Đăng công khai cần chọn giai đoạn, chủ đề và nhập nội dung.");
 if (data.sourceUrl && !safeUrl(data.sourceUrl)) throw new Error("Đường dẫn gốc phải dùng HTTP hoặc HTTPS.");
 const preparationEnabled = $("#preparation-enabled").checked;
 const preparationTasks = preparationEnabled ? [...$("#preparation-rows").children].map(row => ({ id: row.dataset.taskId ? Number(row.dataset.taskId) : null, title: row.querySelector("[data-task-title]").value.trim(), notes: row.querySelector("[data-task-notes]").value.trim() })) : [];
 if (preparationEnabled && status === "PUBLISHED" && !preparationTasks.length) throw new Error("Danh sách chuẩn bị cần ít nhất một công việc.");
 if (preparationTasks.some(task => !task.title)) throw new Error("Vui lòng nhập tên cho từng công việc.");
 return { preparationEnabled, preparationTasks, title: data.title.trim(), summary: data.summary, category: data.category, topicCode: data.topicCode, sourceUrl: data.sourceUrl, status,
  sections, coverImageIndex: coverFile ? 0 : null, removeCover };
}
async function save(status) {
 if (busy) return;
 try {
  const wasEditing = Boolean(editingId); const data = collect(status); busy = true; document.querySelectorAll("#editor button, #detail button").forEach(node => node.disabled = true);
  const body = new FormData(); body.append("guide", new Blob([JSON.stringify(data)], { type: "application/json" })); if (coverFile) body.append("images", coverFile);
  const guide = editingId ? await apiClient.updateGuide(editingId, body) : await apiClient.createGuide(body);
  editingId = guide.id; setupPreparation(guide); savedCover = guide.coverImageUrl; revokeCover(); coverFile = null; removeCover = false; $("#cover-input").value = ""; displayCover(); form.elements.status.value = guide.status;
  if (status === "DRAFT") { show("editor"); $("#editor-title").textContent = "Chỉnh sửa cẩm nang"; setUrl(guide.id, true); }
  else { renderGuide(guide); setUrl(guide.id); }
  await loadList(wasEditing ? state.page : 0); message(status === "DRAFT" ? "Đã lưu nháp. Bạn có thể mở lại để tiếp tục chỉnh sửa." : wasEditing ? "Đã cập nhật cẩm nang công khai." : "Đã đăng cẩm nang công khai.");
 } catch (error) { message(error.message); }
 finally { busy = false; document.querySelectorAll("#editor button, #detail button").forEach(node => node.disabled = false); }
}
$("#create").addEventListener("click", () => { if (!busy) resetEditor(); });
$("#cancel").addEventListener("click", () => { show("listing"); setUrl(); message(""); });
$("#save-draft").addEventListener("click", () => save("DRAFT")); $("#publish").addEventListener("click", () => save("PUBLISHED"));
form.addEventListener("submit", event => { event.preventDefault(); save(form.elements.status.value); });
$("#preview").addEventListener("click", () => { try { const data = collect("DRAFT"); renderGuide({ ...data, published: form.elements.status.value === "PUBLISHED", coverImageUrl: savedCover, stageName: form.elements.category.selectedOptions[0].textContent, topicName: form.elements.topicCode.selectedOptions[0].textContent }, true); message(""); } catch (error) { message(error.message); } });
$("#cover-input").addEventListener("change", event => selectCover(event.target.files[0]));
$("#remove-cover").addEventListener("click", () => { revokeCover(); coverFile = null; savedCover = null; removeCover = true; $("#cover-input").value = ""; displayCover(); });
$("#cover-drop").addEventListener("dragover", event => event.preventDefault()); $("#cover-drop").addEventListener("drop", event => { event.preventDefault(); selectCover(event.dataTransfer.files[0]); });
for (const control of document.querySelectorAll("[data-format]")) control.addEventListener("click", () => {
 const input = form.elements.content, start = input.selectionStart, end = input.selectionEnd, selected = input.value.slice(start, end) || "Nội dung";
 const formats = { bold: "**" + selected + "**", italic: "*" + selected + "*", underline: "__" + selected + "__", heading: "\n## " + selected, bullet: "\n- " + selected, number: "\n1. " + selected, link: "[" + selected + "](https://example.com)" };
 const inserted = formats[control.dataset.format]; if (input.value.length - (end - start) + inserted.length > 20000) return message("Nội dung tối đa 20.000 ký tự."); input.setRangeText(inserted, start, end, "select"); input.focus();
});
function search() { Object.assign(state, Object.fromEntries(new FormData(filters)), { page: 0 }); message(""); loadList(0); }
filters.addEventListener("submit", event => { event.preventDefault(); search(); }); filters.querySelectorAll("select").forEach(select => select.addEventListener("change", search));
try {
 const [stages, topics] = await Promise.all([apiClient.guideTopics(), apiClient.guideSubjects()]);
 for (const [name, options] of [["category", stages], ["topicCode", topics]]) for (const select of document.querySelectorAll("select[name=" + name + "]")) for (const option of options) { const node = el("option", null, option.title); node.value = option.code; select.append(node); }
 for (const key of ["q", "category", "topicCode", "status"]) filters.elements[key].value = state[key];
 $("#create").hidden = false; await loadList();
 if (/^\d+$/.test(query.get("edit") || "")) await editGuide(query.get("edit"));
 else if (/^\d+$/.test(query.get("id") || "")) renderGuide(await apiClient.moderatorGuide(query.get("id")));
} catch (error) { message(error.message); }
window.addEventListener("pagehide", revokeCover);
