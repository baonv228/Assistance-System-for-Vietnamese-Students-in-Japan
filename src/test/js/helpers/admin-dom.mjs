/** Minimal DOM boundary for exercising the real Admin modal callbacks in Node. */
export function installModalDocument(t, { lockReason = "" } = {}) {
    const previousDocument = globalThis.document;
    let active = null;

    class Element extends EventTarget {
        constructor() {
            super();
            this.attributes = new Map();
            this.children = new Map();
        }

        setAttribute(name, value) {
            this.attributes.set(name, value);
        }
        removeAttribute(name) {
            this.attributes.delete(name);
        }
        hasAttribute(name) {
            return this.attributes.has(name);
        }

        querySelector(selector) {
            if (!this.children.has(selector)) this.children.set(selector, new Element());
            return this.children.get(selector);
        }

        remove() {
            if (active === this) active = null;
        }
        click() {
            this.dispatchEvent(new Event("click"));
        }
    }

    globalThis.document = {
        createElement: () => new Element(),
        body: {
            appendChild: (element) => {
                active = element;
            }
        },
        getElementById: (id) =>
            id === "activeAdminModal" ? active : id === "lockReasonInput" ? { value: lockReason } : null
    };
    t.after(() => {
        if (previousDocument === undefined) delete globalThis.document;
        else globalThis.document = previousDocument;
    });
}
