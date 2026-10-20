(() => {
    "use strict";
    const pagePath = path => /^\/(?:$|Hosts(?:\/|$)|Servers(?:\/|$)|Workflows(?:\/|$)|Transfers\/?$|Notifications\/?$|Security\/?$)/i.test(path);
    const pageUrl = (href, base) => {
        const url = new URL(href, base);
        return url.origin === new URL(base).origin && !url.username && !url.password && pagePath(url.pathname) ? url : null;
    };
    if (typeof module !== "undefined" && module.exports) module.exports = { pageUrl };
    if (typeof document === "undefined" || document.getElementById("main")?.dataset.controlPage !== "true") return;

    const status = document.getElementById("navigation-status");
    const scriptPaths = new Set(["/js/server-logs.js", "/js/server-metrics.js", "/js/p2-control.js"]);
    const shellVersion = doc => [...doc.querySelectorAll('head link[rel="stylesheet"], head script[src]')].map(el => el.getAttribute("href") || el.getAttribute("src")).join("\n");
    const version = shellVersion(document);
    let currentUrl = location.href, request = null, sequence = 0, submitting = false, installing = false;
    let historyIndex = history.state?.controlPage === true && Number.isSafeInteger(history.state.controlIndex) ? history.state.controlIndex : 0;
    let restoringHistory = false;
    history.scrollRestoration = "manual";
    history.replaceState({ controlPage: true, controlIndex: historyIndex, scroll: [scrollX, scrollY] }, "", currentUrl);
    const message = text => { status.textContent = text; status.hidden = !text; };
    const mayLeave = () => !submitting && !installing && window.dispatchEvent(new Event("control:before-navigate", { cancelable: true }));
    function busy(value, url) {
        document.getElementById("main").setAttribute("aria-busy", String(value));
        for (const link of document.querySelectorAll(".main-tabs a")) {
            if (value && new URL(link.href).pathname === url.pathname) link.setAttribute("aria-busy", "true");
            else link.removeAttribute("aria-busy");
        }
    }
    function saveScroll() {
        history.replaceState({ controlPage: true, controlIndex: historyIndex, scroll: [scrollX, scrollY] }, "", currentUrl);
    }
    async function install(doc, scripts) {
        window.dispatchEvent(new Event("control:before-page-change"));
        document.getElementById("main").replaceWith(document.importNode(doc.getElementById("main"), true));
        document.title = doc.title;
        const tabs = doc.querySelector(".main-tabs");
        for (const link of document.querySelectorAll(".main-tabs a")) {
            const active = [...tabs.querySelectorAll("a")].some(next => next.getAttribute("href") === link.getAttribute("href") && next.getAttribute("aria-current") === "page");
            link.classList.toggle("is-active", active);
            if (active) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
        }
        const container = document.getElementById("page-scripts");
        container.replaceChildren();
        for (const source of scripts) {
            await new Promise((resolve, reject) => {
                const script = document.createElement("script");
                script.src = source; script.onload = resolve; script.onerror = reject;
                container.append(script);
            });
        }
    }
    async function navigate(url, { method = "GET", body, pop = false, scroll = [0, 0] } = {}) {
        const id = ++sequence;
        request?.abort();
        const controller = new AbortController(); request = controller;
        submitting = method === "POST";
        busy(true, url); message(submitting ? "送信しています…" : "読み込み中…");
        try {
            const response = await fetch(url, { method, body, credentials: "same-origin", cache: "no-store", headers: { Accept: "text/html" }, signal: controller.signal });
            if (id !== sequence) return;
            const finalUrl = new URL(response.url);
            if (finalUrl.origin !== location.origin) throw new Error("Unexpected origin");
            if (/^\/(?:Login|Logout|Mfa)(?:\/|$)/i.test(finalUrl.pathname) || response.status === 401) {
                location.assign("/Login"); return;
            }
            if (!response.ok || !response.headers.get("Content-Type")?.startsWith("text/html")) throw new Error("Unexpected response");
            const doc = new DOMParser().parseFromString(await response.text(), "text/html");
            if (id !== sequence) return;
            if (doc.getElementById("main")?.dataset.controlPage !== "true" || !doc.querySelector(".main-tabs") || !pageUrl(finalUrl.href, currentUrl)) throw new Error("Unexpected page");
            const scripts = [...doc.querySelectorAll("#page-scripts script")].map(script => {
                const src = new URL(script.getAttribute("src"), finalUrl);
                if (!script.hasAttribute("src") || src.origin !== location.origin || !scriptPaths.has(src.pathname)) throw new Error("Unexpected script");
                return src.href;
            });
            // A deployment changed shared assets: load a fresh GET, never repeat the POST.
            if (shellVersion(doc) !== version && !(method === "POST" && !response.redirected)) { location.assign(finalUrl.href); return; }
            const target = method === "POST" && !response.redirected ? currentUrl : finalUrl.href;
            installing = true;
            if (!pop) {
                saveScroll();
                if (target !== currentUrl && method === "GET") history.pushState({ controlPage: true, controlIndex: ++historyIndex, scroll: [0, 0] }, "", target);
                else history.replaceState({ controlPage: true, controlIndex: historyIndex, scroll: [0, 0] }, "", target);
            }
            currentUrl = target;
            await install(doc, scripts);
            window.scrollTo(...scroll);
            document.getElementById("main").focus({ preventScroll: true });
            message("");
        } catch (error) {
            if (id !== sequence || error.name === "AbortError") return;
            message(method === "POST" ? "送信結果を確認できません。自動再送信はしていません。保守履歴や台帳で結果を確認してください。" : "画面を読み込めませんでした。もう一度タブを選択するか、再読み込みしてください。");
        } finally {
            if (id === sequence) { request = null; submitting = false; installing = false; busy(false, url); }
        }
    }
    document.addEventListener("click", event => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        const link = event.target.closest?.("a[href]");
        if (!link || link.hasAttribute("download") || (link.target && link.target !== "_self") || link.hasAttribute("data-native-navigation")) return;
        const url = pageUrl(link.href, currentUrl);
        if (!url || (url.hash && url.pathname === location.pathname && url.search === location.search)) return;
        if (!link.closest(".app-header, #main")) return;
        event.preventDefault();
        if (mayLeave()) navigate(url);
    });
    document.addEventListener("submit", event => {
        const form = event.target;
        if (event.defaultPrevented || !form.closest?.('#main[data-control-page="true"]') || form.hasAttribute("data-native-navigation")) return;
        const button = event.submitter;
        const url = pageUrl(button?.getAttribute("formaction") || form.action, currentUrl);
        const method = (button?.getAttribute("formmethod") || form.method).toUpperCase();
        if (!url || !["POST", "GET"].includes(method) || (form.target && form.target !== "_self")) return;
        event.preventDefault();
        if (!mayLeave()) return;
        const data = new FormData(form, button);
        if (method === "GET") { url.search = new URLSearchParams(data).toString(); navigate(url); }
        else navigate(url, { method, body: data });
    });
    window.addEventListener("popstate", event => {
        const url = pageUrl(location.href, currentUrl);
        const index = event.state?.controlIndex;
        if (!url || !Number.isSafeInteger(index)) { location.reload(); return; }
        if (restoringHistory && index === historyIndex) { restoringHistory = false; return; }
        if (restoringHistory || !mayLeave()) {
            restoringHistory = true;
            history.go(historyIndex - index);
            return;
        }
        historyIndex = index;
        currentUrl = url.href;
        navigate(url, { pop: true, scroll: event.state?.scroll || [0, 0] });
    });
    window.addEventListener("pagehide", () => { request?.abort(); window.dispatchEvent(new Event("control:before-page-change")); });
    window.addEventListener("pageshow", event => { if (event.persisted) location.reload(); });
})();
