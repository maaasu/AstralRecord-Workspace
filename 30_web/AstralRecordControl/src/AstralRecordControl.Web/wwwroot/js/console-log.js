(function (global) {
    "use strict";
    const palette = ["#657085", "#ed7b83", "#9ac76f", "#e2bd69", "#82aaff", "#c792ea", "#78ccd1", "#d7e2f0", "#8b98ad", "#ff9098", "#b1dd8b", "#f4d58a", "#a4c5ff", "#dfb0fa", "#9be5e6", "#f4f7fa"];
    const levels = { INFO: "info", WARN: "warn", WARNING: "warn", ERROR: "error", SEVERE: "error", DEBUG: "debug", TRACE: "debug" };
    const control = /\x1b(?:\[([0-9;]*)m|\[[0-?]*[ -/]*(?:[@-~]|$)|\][^\x07\x1b\r\n]*(?:\x07|\x1b\\|(?=[\r\n]|$))|.)|[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/g;
    const level = /\[(?:\d{2}:\d{2}:\d{2} )?(INFO|WARN|WARNING|ERROR|SEVERE|DEBUG|TRACE)\]/g;
    const rgb = (r, g, b) => `rgb(${r}, ${g}, ${b})`;
    const byte = n => Number.isInteger(n) && n >= 0 && n <= 255;
    const indexedColor = n => {
        if (!byte(n)) return null;
        if (n < 16) return palette[n];
        if (n >= 232) return rgb(...Array(3).fill(8 + (n - 232) * 10));
        const index = n - 16, cube = [0, 95, 135, 175, 215, 255];
        return rgb(cube[Math.floor(index / 36)], cube[Math.floor(index / 6) % 6], cube[index % 6]);
    };
    function tokenize(text) {
        text = String(text);
        const tokens = [];
        let state = {}, offset = 0;
        const push = (value, severity) => {
            if (!value) return;
            const previous = tokens.at(-1), keys = Object.keys(state);
            if (previous && previous.level === severity && keys.length === Object.keys(previous.style).length
                && keys.every(key => previous.style[key] === state[key])) previous.text += value;
            else tokens.push({ text: value, style: { ...state }, ...(severity ? { level: severity } : {}) });
        };
        const emit = value => {
            let start = 0;
            for (const match of value.matchAll(level)) {
                push(value.slice(start, match.index));
                push(match[0], levels[match[1]]);
                start = match.index + match[0].length;
            }
            push(value.slice(start));
        };
        for (const match of text.matchAll(control)) {
            emit(text.slice(offset, match.index));
            offset = match.index + match[0].length;
            if (match[1] === undefined) continue;
            const codes = match[1].split(";").map(value => value === "" ? 0 : Number(value));
            for (let i = 0; i < codes.length; i++) {
                const code = codes[i];
                if (code === 0) state = {};
                else if (code === 1) state.fontWeight = "700";
                else if (code === 2) state.opacity = "0.75";
                else if (code === 3) state.fontStyle = "italic";
                else if (code === 4) state.textDecoration = "underline";
                else if (code === 9) state.textDecoration = "line-through";
                else if (code === 22) { delete state.fontWeight; delete state.opacity; }
                else if (code === 23) delete state.fontStyle;
                else if (code === 24 || code === 29) delete state.textDecoration;
                else if (code >= 30 && code <= 37) state.color = palette[code - 30];
                else if (code >= 90 && code <= 97) state.color = palette[code - 90 + 8];
                else if (code >= 40 && code <= 47) state.backgroundColor = palette[code - 40];
                else if (code >= 100 && code <= 107) state.backgroundColor = palette[code - 100 + 8];
                else if (code === 39) delete state.color;
                else if (code === 49) delete state.backgroundColor;
                else if (code === 38 || code === 48) {
                    const key = code === 38 ? "color" : "backgroundColor";
                    if (codes[i + 1] === 5) {
                        const color = indexedColor(codes[i + 2]);
                        if (color) state[key] = color;
                        i += 2;
                    } else if (codes[i + 1] === 2) {
                        const channels = codes.slice(i + 2, i + 5);
                        if (channels.length === 3 && channels.every(byte)) state[key] = rgb(...channels);
                        i += 4;
                    }
                }
            }
        }
        emit(text.slice(offset));
        return tokens;
    }
    function render(element, text) {
        const document = element.ownerDocument;
        const fragment = document.createDocumentFragment();
        for (const token of tokenize(text)) {
            const span = document.createElement("span");
            span.textContent = token.text;
            Object.assign(span.style, token.style);
            if (token.level) span.className = "console-level-" + token.level;
            fragment.append(span);
        }
        element.replaceChildren(fragment);
    }
    const api = { tokenize, render };
    if (typeof module !== "undefined" && module.exports) module.exports = api;
    else global.ControlConsoleLog = api;
})(typeof window === "undefined" ? globalThis : window);
