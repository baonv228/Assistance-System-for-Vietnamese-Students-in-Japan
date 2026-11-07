// "Chủ đề quan tâm": the blog topics a Member chooses for "Dành cho bạn", on Tôi and in "Điều chỉnh" on the Home.
// The server keeps the choice (GET/PUT /api/me/interests, InterestService); styles .topic-pick in site.css.
import { apiClient } from "../api/client.js";
import { esc, ic } from "../lib/ui.js";

/**
 * Loads the topics a Member can choose and draws them in `box` as toggle buttons, at most `max` pressed (the others
 * then say so and cannot be pressed). A topic learnt from what the member did and not chosen yet carries a "Gợi ý"
 * mark. `selected` (topic ids) starts from a choice not saved yet instead of the saved one, e.g. kept across a redraw
 * of the page; ids no longer offered are dropped. `onChange(changed)` is called after every press.
 * Resolves to `{ value(), changed(), save() }`: `save()` sends the choice, draws the server's answer and resolves to it;
 * the buttons ignore presses until it is back.
 */
export async function mountInterestPicker(box, { id, selected = null, onChange = null } = {}) {
    const first = await apiClient.interests();
    const max = first.max;
    let topics = first.topics;
    let saved = chosenIds(topics);
    const offered = new Set(topics.map((t) => t.id));
    let chosen = selected ? [...new Set(selected)].filter((x) => offered.has(x)).slice(0, max) : [...saved];

    box.innerHTML = `<div class="interest-picker">
        <p class="note" id="${id}-interest-help">Chọn tối đa ${max} chủ đề. Bài thuộc các chủ đề này được gợi ý trước
            trong “Dành cho bạn”. <span data-count></span></p>
        <div class="topic-picks" role="group" aria-label="Chủ đề quan tâm" aria-describedby="${id}-interest-help"></div>
        <p class="note interest-learnt-note" hidden>${ic("history")} Gợi ý: chủ đề bạn hay đọc, thích hoặc lưu gần đây.</p>
    </div>`;
    const group = box.querySelector(".topic-picks");
    const count = box.querySelector("[data-count]");
    const learntNote = box.querySelector(".interest-learnt-note");

    const draw = () => {
        const full = chosen.length >= max;
        group.innerHTML = topics.map((t) => {
            const on = chosen.includes(t.id);
            const blocked = full && !on;
            const hint = t.learnt && !on;
            return `<button class="topic-pick${hint ? " learnt" : ""}" type="button" data-topic="${t.id}"
                aria-pressed="${on}" ${blocked ? `aria-disabled="true" title="Đã chọn đủ ${max} chủ đề"` : ""}>
                ${on ? ic("check") : ""}${esc(t.name)}${hint
                    ? `${ic("history", "pick-hint")}<span class="sr-only"> (gợi ý)</span>` : ""}</button>`;
        }).join("");
        count.textContent = `Đã chọn ${chosen.length}/${max}.`;
        learntNote.hidden = !topics.some((t) => t.learnt && !chosen.includes(t.id));
    };
    draw();

    // While a save is on its way the buttons do nothing, so no press is lost when the answer is drawn
    let saving = false;
    group.addEventListener("click", (event) => {
        const button = event.target.closest("[data-topic]");
        if (!button || saving || button.getAttribute("aria-disabled") === "true") {
            return;
        }
        const topicId = Number(button.dataset.topic);
        chosen = chosen.includes(topicId) ? chosen.filter((x) => x !== topicId) : [...chosen, topicId];
        draw();
        group.querySelector(`[data-topic="${topicId}"]`)?.focus();
        onChange?.(changed());
    });

    const changed = () => chosen.length !== saved.length || chosen.some((x) => !saved.includes(x));
    return {
        value: () => [...chosen],
        changed,
        async save() {
            saving = true;
            group.setAttribute("aria-busy", "true");
            try {
                const answer = await apiClient.saveInterests(chosen);
                topics = answer.topics;
                saved = chosenIds(topics);
                chosen = [...saved];
                draw();
                return answer;
            } finally {
                saving = false;
                group.removeAttribute("aria-busy");
            }
        }
    };
}

const chosenIds = (topics) => topics.filter((t) => t.chosen).map((t) => t.id);
