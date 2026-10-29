import { apiClient } from "../api/client.js";
import { loginUrl } from "./ui.js";
function el(tag, className, text) { const node = document.createElement(tag); if (className) node.className = className; if (text !== undefined) node.textContent = text ?? ""; return node; }

export function preparationTabs(target, guide, interactive = false) {
 const details = el("div"), panel = el("div", "guide-preparation-panel"), tabs = el("div", "guide-detail-tabs");
 details.id = "guide-details-panel"; panel.id = "guide-preparation-panel";
 details.append(...target.childNodes); target.append(tabs, details, panel);
 tabs.setAttribute("role", "tablist"); tabs.setAttribute("aria-label", "Nội dung cẩm nang");
 const detailTab = el("button", "btn on", "Xem chi tiết"), preparationTab = el("button", "btn", "Hướng dẫn chuẩn bị");
 const controls = [detailTab, preparationTab]; let loaded = false;
 for (const [index, tab] of controls.entries()) {
  tab.type = "button"; tab.id = index ? "guide-preparation-tab" : "guide-details-tab";
  tab.setAttribute("role", "tab"); tab.setAttribute("aria-controls", index ? panel.id : details.id);
  tab.addEventListener("click", () => select(index === 1));
  tab.addEventListener("keydown", event => {
   if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) { event.preventDefault(); const next = event.key === "Home" ? 0 : event.key === "End" ? 1 : 1 - index; select(next === 1); controls[next].focus(); }
  }); tabs.append(tab);
 }
 details.setAttribute("role", "tabpanel"); details.setAttribute("aria-labelledby", detailTab.id);
 panel.setAttribute("role", "tabpanel"); panel.setAttribute("aria-labelledby", preparationTab.id);
 function select(preparation) {
  details.hidden = preparation; panel.hidden = !preparation;
  controls.forEach((tab, index) => { const active = (index === 1) === preparation; tab.classList.toggle("on", active); tab.setAttribute("aria-selected", String(active)); tab.tabIndex = active ? 0 : -1; });
  if (interactive) { const url = new URL(location.href); if (preparation) url.searchParams.set("tab", "preparation"); else url.searchParams.delete("tab"); history.replaceState(null, "", url.pathname + url.search); }
  if (preparation && !loaded) { loaded = true; if (interactive) load(); else draw({ tasks: guide.preparationTasks || [], total: guide.preparationTasks?.length || 0, completed: 0, percent: 0, signedIn: false }); }
 }
 async function load() {
  panel.replaceChildren(el("p", "note", "Đang tải tiến độ chuẩn bị…"));
  try { draw(await apiClient.guidePreparation(guide.id)); }
  catch (error) {
   panel.replaceChildren(el("p", "alert error", error.message));
   const retry = el("button", "btn", "Tải lại danh sách"); retry.type = "button"; retry.addEventListener("click", load); panel.append(retry);
  }
 }
 function draw(progress) {
  panel.replaceChildren(el("h2", null, "Hướng dẫn chuẩn bị"), el("p", "note", guide.title));
  const summary = el("p", "guide-progress-summary", progress.completed + "/" + progress.total + " công việc đã hoàn thành · " + progress.percent + "%");
  summary.setAttribute("role", "status"); summary.setAttribute("aria-live", "polite");
  const bar = el("progress", "guide-progress-bar"); bar.max = 100; bar.value = progress.percent; bar.setAttribute("aria-label", "Tiến độ chuẩn bị");
  panel.append(summary, bar);
  if (interactive && !progress.signedIn) {
   const note = el("p", "info", "Đăng nhập để tick công việc và lưu tiến độ riêng của bạn. ");
   const login = el("a", "link", "Đăng nhập"); login.href = loginUrl(); note.append(login); panel.append(note);
  } else if (!interactive) panel.append(el("p", "note", "Xem trước danh sách chuẩn bị. Người đọc đã đăng nhập có thể tick từng việc."));
  const error = el("p", "alert error"); error.hidden = true; error.setAttribute("role", "alert"); panel.append(error);
  const list = el("ol", "guide-checklist");
  for (const task of progress.tasks) {
   const row = el("li", "guide-checklist-row"); row.classList.toggle("completed", task.completed);
   const label = el("label", "guide-check-label"), input = el("input"), text = el("span");
   input.type = "checkbox"; input.checked = !!task.completed; input.disabled = !interactive || !progress.signedIn;
   input.dataset.taskId = task.id; const title = el("strong", null, task.title); text.append(title);
   if (task.notes) text.append(el("span", "guide-task-notes", task.notes));
   label.append(input, text); row.append(label); list.append(row);
   input.addEventListener("change", async () => {
    const checked = input.checked; list.querySelectorAll("input").forEach(control => control.disabled = true); error.hidden = true;
    try {
     const updated = await apiClient.checkGuideTask(guide.id, task.id, checked); draw(updated);
     [...panel.querySelectorAll("input[data-task-id]")].find(control => control.dataset.taskId === String(task.id))?.focus();
    } catch (failure) {
     input.checked = !!task.completed; error.textContent = failure.message + " Tiến độ chưa được thay đổi. Hãy thử lại hoặc tải lại trang."; error.hidden = false;
     list.querySelectorAll("input").forEach(control => control.disabled = false);
    }
   });
  }
  panel.append(list);
  if (!progress.tasks.length) panel.append(el("p", "note", "Chưa có công việc trong danh sách chuẩn bị."));
 }
 const openPreparation = interactive && new URLSearchParams(location.search).get("tab") === "preparation";
 select(openPreparation);
}
