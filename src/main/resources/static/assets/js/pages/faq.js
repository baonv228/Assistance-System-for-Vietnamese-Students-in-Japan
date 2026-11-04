import { apiClient } from "../api/client.js";
import { mountSiteHeader } from "../components/site-header.js";
import { el,answer,paging } from "../lib/faq-ui.js";
const $=s=>document.querySelector(s),query=new URLSearchParams(location.search);
const state={q:(query.get("q")||"").slice(0,200),topic:query.get("topic")||"",page:/^\d{1,9}$/.test(query.get("page")||"")?Number(query.get("page")):0,size:20};
function url(page=0,topic=state.topic){const p=new URLSearchParams();if(state.q)p.set("q",state.q);if(topic)p.set("topic",topic);if(page)p.set("page",page);return "/faq"+(p.size?"?"+p:"");}
function item(entry,open=false){const details=el("details","faq-item"),summary=el("summary",null,entry.question);details.open=open;details.append(summary,answer(entry));return details;}
$("#faq-q").value=state.q;$("#faq-search").addEventListener("submit",event=>{event.preventDefault();state.q=$("#faq-q").value.trim();location.assign(url());});
try{await mountSiteHeader("faq");const topics=await apiClient.faqTopics(state.q);const all=topics.reduce((sum,t)=>sum+t.count,0);for(const t of [{code:"",title:"Tất cả",count:all},...topics]){const a=el("a",null,t.title);a.href=url(0,t.code);a.append(el("span","note",t.count));if(t.code===state.topic)a.setAttribute("aria-current","page");$("#faq-topics").append(a);}
 if(/^\d+$/.test(query.get("id")||"")){const entry=await apiClient.faq(query.get("id"));$("#faq-list").append(item(entry,true));const a=el("a","btn","← Danh sách FAQ");a.href=url();$("#faq-list").append(a);$("#faq-count").textContent=entry.topicName;}
 else{const result=await apiClient.faqs(state);state.page=result.page;history.replaceState(null,"",url(result.page));$("#faq-count").textContent=result.total+" câu hỏi thường gặp";$("#faq-list").replaceChildren(...result.items.map(entry=>item(entry)));if(!result.total)$("#faq-list").append(el("p","note","Chưa có câu hỏi phù hợp. Hãy thử chủ đề hoặc từ khóa khác."));paging($("#faq-pager"),result,page=>location.assign(url(page)));}
}catch(error){$("#faq-message").textContent=error.message;}
