import { guideContent } from "./guide-content.js";
import { faqHref } from "./qa-state.js";
export function el(tag, cls, text) { const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text??"";return n; }
export function button(text,action,cls="btn"){const b=el("button",cls,text);b.type="button";b.addEventListener("click",action);return b;}
export function resourceUrl(resource){if(resource.kind==="EXTERNAL"){try{const u=new URL(resource.url);return ["http:","https:"].includes(u.protocol)&&!u.username&&!u.password?u.href:null;}catch{return null;}}const base={GUIDE:"/guides/",BLOG:"/blog/",QUESTION:"/qa/"}[resource.kind];return base&&/^\d+$/.test(String(resource.targetId))?base+resource.targetId:null;}
export function answer(entry){const box=el("div","faq-answer");box.append(guideContent(entry.answer));const list=el("ul","faq-resources-list");for(const resource of entry.resources||[]){const href=resourceUrl(resource);if(!href)continue;const item=el("li"),a=el("a","link",resource.label);a.href=href;if(resource.kind==="EXTERNAL"){a.target="_blank";a.rel="noopener noreferrer";}item.append(a);list.append(item);}if(list.children.length)box.append(el("strong",null,"Xem thêm"),list);return box;}
/**
 * One FAQ entry that opens in place (Hỏi-Đáp "Câu hỏi thường gặp", the matching entries above the community search,
 * "Đặt câu hỏi" suggestions): question and topic, then the answer, "Xem thêm" links and when it was last updated.
 * link: "self" = a link to this entry alone, "new-tab" = the same opening a new tab (a form being typed stays),
 * "none" = no link (the entry is already shown alone).
 */
export function faqItem(entry, { open = false, link = "self" } = {}) {
 const details = el("details", "faq-q"); details.open = open; details.dataset.faqId = String(entry.id);
 const summary = el("summary"), text = el("span", "faq-q-text"), caret = el("span", "ic caret", "expand_more");
 caret.setAttribute("aria-hidden", "true");
 text.append(el("b", null, entry.question), el("span", "note", entry.topicName));
 summary.append(text, caret);
 const foot = el("div", "faq-q-foot note");
 const updated = new Date(entry.updatedAt);
 if (!Number.isNaN(updated.getTime())) foot.append(el("span", null, `Cập nhật ${updated.toLocaleDateString("vi-VN")}`));
 if (link !== "none") {
  const a = el("a", "link", link === "new-tab" ? "Mở trong tab mới" : "Mở riêng câu này"); a.href = faqHref({ id: entry.id });
  if (link === "new-tab") { a.target = "_blank"; a.rel = "noopener"; }
  foot.append(a);
 }
 details.append(summary, answer(entry), foot);
 return details;
}
export function paging(target,result,go){target.replaceChildren();if(result.totalPages<=1)return;if(result.page>0)target.append(button("← Trang trước",()=>go(result.page-1)));for(let p=Math.max(0,result.page-2);p<=Math.min(result.totalPages-1,result.page+2);p++){const b=button(String(p+1),()=>go(p));if(p===result.page){b.classList.add("on");b.setAttribute("aria-current","page");}target.append(b);}if(result.page+1<result.totalPages)target.append(button("Trang sau →",()=>go(result.page+1)));}
