// Render the editor's formatting as DOM nodes; stored text is never interpreted as HTML.
export function guideContent(text) {
 const root = document.createElement("div"); root.className = "guide-content";
 function inline(parent, value) {
  const pattern = /(\*\*([^*]+)\*\*|__([^_]+)__|\*([^*]+)\*|\[([^\]]+)\]\((https?:\/\/[^\s)]+)\))/g;
  let end = 0;
  for (const match of value.matchAll(pattern)) {
   parent.append(document.createTextNode(value.slice(end, match.index)));
   const tag = match[2] ? "strong" : match[3] ? "u" : match[4] ? "em" : "a";
   const node = document.createElement(tag); node.textContent = match[2] || match[3] || match[4] || match[5];
   if (tag === "a") { node.href = match[6]; node.target = "_blank"; node.rel = "noopener noreferrer"; }
   parent.append(node); end = match.index + match[0].length;
  }
  parent.append(document.createTextNode(value.slice(end)));
 }
 let list = null;
 for (const line of (text || "").split("\n")) {
  const bullet = line.match(/^\s*([-*]|\d+\.)\s+(.*)$/);
  if (bullet) {
   const tag = /^\d/.test(bullet[1]) ? "ol" : "ul";
   if (!list || list.tagName.toLowerCase() !== tag) { list = document.createElement(tag); root.append(list); }
   const li = document.createElement("li"); inline(li, bullet[2]); list.append(li);
  } else {
   list = null; const heading = line.match(/^##\s+(.*)$/);
   const block = document.createElement(heading ? "h3" : "p"); inline(block, heading ? heading[1] : line); root.append(block);
  }
 }
 return root;
}
