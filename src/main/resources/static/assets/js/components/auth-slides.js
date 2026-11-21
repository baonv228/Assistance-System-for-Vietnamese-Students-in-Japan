// Slides of Japan on the sign-in page (pages/login.html, [data-hero-slides]; styles in auth.css).
// Without this script the first slide shows and the controls stay hidden.
// Tabbed carousel as in the WAI-ARIA Authoring Practices: the bars are tabs (one Tab stop, arrow keys move between
// them), a pause/play button, rotation waits while the pointer is over a photo or something in a slide has focus and
// stops for good once the keyboard enters the carousel (until "Phát"). Phones swipe left/right on the photo.

const DURATION_MS = 6500;
const FADE_MS = 950;
const SWIPE_PX = 40;

const PLAY_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M4.5 2.6v10.8c0 .5.5.8.9.5l8.2-5.4a.6.6 0 0 0 0-1L5.4 2.1c-.4-.3-.9 0-.9.5z"/></svg>';
const PAUSE_ICON = '<svg viewBox="0 0 16 16" aria-hidden="true"><rect x="3" y="2" width="3.4" height="12" rx="1" fill="currentColor"/><rect x="9.6" y="2" width="3.4" height="12" rx="1" fill="currentColor"/></svg>';

document.querySelectorAll("[data-hero-slides]").forEach(setupSlides);

export function setupSlides(root) {
    const slides = [...root.querySelectorAll(".hero-slide")];
    const track = root.querySelector(".hero-track");
    const controls = root.querySelector(".hero-controls");
    const dotsBox = root.querySelector(".hero-dots");
    const toggle = root.querySelector(".hero-toggle");
    if (slides.length < 2 || !track || !controls || !dotsBox || !toggle) {
        return;
    }

    let index = Math.max(0, slides.findIndex((slide) => slide.classList.contains("is-active")));
    let stopped = false; // set by the pause button or by the keyboard entering the carousel
    const holds = new Set(); // "hover", "focus", "hidden": rotation waits while any of them is on
    let leaveTimer = 0;

    root.style.setProperty("--hero-duration", `${DURATION_MS}ms`);
    const dots = slides.map((slide, i) => {
        slide.id ||= `hero-slide-${i + 1}`;
        slide.setAttribute("role", "tabpanel");
        slide.setAttribute("aria-roledescription", "slide");
        slide.setAttribute("aria-label", `${i + 1} trên ${slides.length}`);
        const dot = document.createElement("button");
        dot.type = "button";
        dot.className = "hero-dot";
        dot.id = `${slide.id}-tab`;
        dot.setAttribute("role", "tab");
        dot.setAttribute("aria-controls", slide.id);
        dot.setAttribute("aria-label", `Ảnh ${i + 1}: ${slide.dataset.label || ""}`.trim().replace(/:$/, ""));
        dot.innerHTML = '<span class="hero-dot-bar"><span class="hero-dot-fill"></span></span>';
        dot.addEventListener("click", () => show(i));
        dotsBox.append(dot);
        return dot;
    });

    function show(next, focusDot = false) {
        next = (next + slides.length) % slides.length;
        if (next !== index) {
            const previous = slides[index];
            slides.forEach((slide) => slide.classList.remove("is-leaving"));
            previous.classList.add("is-leaving");
            clearTimeout(leaveTimer);
            leaveTimer = setTimeout(() => previous.classList.remove("is-leaving"), FADE_MS);
            index = next;
        }
        render();
        if (focusDot) {
            dots[index].focus();
        }
    }

    function render() {
        slides.forEach((slide, i) => {
            const shown = i === index;
            slide.classList.toggle("is-active", shown);
            // a slide out of sight keeps its credit link out of the Tab order and away from screen readers
            slide.inert = !shown;
        });
        dots.forEach((dot, i) => {
            dot.setAttribute("aria-selected", String(i === index));
            dot.tabIndex = i === index ? 0 : -1;
            dot.classList.toggle("is-past", i < index);
        });
        update();
    }

    function update() {
        root.classList.toggle("is-playing", !stopped);
        root.classList.toggle("is-stopped", stopped);
        root.classList.toggle("is-held", holds.size > 0);
        toggle.innerHTML = stopped ? PLAY_ICON : PAUSE_ICON;
        toggle.setAttribute("aria-label", stopped ? "Phát trình chiếu ảnh" : "Tạm dừng trình chiếu ảnh");
        // a slide changed by the visitor is read out; one changed by the timer is not
        track.setAttribute("aria-live", !stopped && holds.size === 0 ? "off" : "polite");
    }

    function hold(reason, on) {
        if (on === holds.has(reason)) {
            return;
        }
        if (on) {
            holds.add(reason);
        } else {
            holds.delete(reason);
        }
        update();
    }

    // the current bar's fill animation is the timer: it pauses with the bar and its end turns the page
    dotsBox.addEventListener("animationend", (event) => {
        if (event.animationName === "hero-fill" && !stopped && holds.size === 0) {
            show(index + 1);
        }
    });

    toggle.addEventListener("click", () => {
        stopped = !stopped;
        update();
    });

    dotsBox.addEventListener("keydown", (event) => {
        const moves = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: slides.length - 1 };
        if (event.key in moves) {
            event.preventDefault();
            show(moves[event.key], true);
        }
    });

    root.addEventListener("focusin", (event) => {
        if (focusedByKeyboard(event.target) && !stopped) {
            stopped = true; // keyboard users: the slides wait until they press "Phát"
            update();
        }
        hold("focus", track.contains(event.target));
    });
    root.addEventListener("focusout", (event) => {
        if (!track.contains(event.relatedTarget)) {
            hold("focus", false);
        }
    });

    track.addEventListener("pointerenter", (event) => {
        if (event.pointerType === "mouse") {
            hold("hover", true);
        }
    });
    track.addEventListener("pointerleave", () => hold("hover", false));

    let swipe = null;
    track.addEventListener("pointerdown", (event) => {
        swipe = event.pointerType === "mouse" ? null : { id: event.pointerId, x: event.clientX, y: event.clientY };
    });
    track.addEventListener("pointerup", (event) => {
        if (!swipe || swipe.id !== event.pointerId) {
            return;
        }
        const dx = event.clientX - swipe.x;
        const dy = event.clientY - swipe.y;
        swipe = null;
        if (Math.abs(dx) >= SWIPE_PX && Math.abs(dx) > 1.5 * Math.abs(dy)) {
            show(index + (dx < 0 ? 1 : -1));
        }
    });
    track.addEventListener("pointercancel", () => {
        swipe = null;
    });

    document.addEventListener("visibilitychange", () => hold("hidden", document.hidden));

    holds.clear();
    if (document.hidden) {
        holds.add("hidden");
    }
    controls.hidden = false;
    render();
}

function focusedByKeyboard(element) {
    try {
        return element.matches(":focus-visible");
    } catch {
        return false; // browsers without :focus-visible (Safari before 15.4)
    }
}
