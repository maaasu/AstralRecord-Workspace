const selector = document.querySelector("[data-bestiary-level-selector]");

if (selector) {
    const panels = document.querySelectorAll("[data-bestiary-level-panel]");
    const name = document.querySelector("[data-bestiary-level-name]");
    const title = document.querySelector("[data-bestiary-level-title]");
    const lore = document.querySelector("[data-bestiary-level-lore]");

    const showLevel = () => {
        const level = selector.value;
        panels.forEach(panel => { panel.hidden = panel.dataset.level !== level; });

        const header = document.querySelector(`[data-bestiary-level-header][data-level="${level}"]`);
        if (header) {
            const nextName = header.content.querySelector("[data-bestiary-level-template-name]")?.textContent ?? "";
            const nextTitle = header.content.querySelector("[data-bestiary-level-template-title]")?.textContent ?? "";
            if (name) name.textContent = nextName;
            if (title) {
                title.textContent = nextTitle;
                title.hidden = nextTitle.length === 0;
            }
        }

        const loreTemplate = document.querySelector(`[data-bestiary-level-lore-template][data-level="${level}"]`);
        if (lore && loreTemplate) lore.replaceChildren(loreTemplate.content.cloneNode(true));
    };

    selector.addEventListener("change", showLevel);
    showLevel();
}
