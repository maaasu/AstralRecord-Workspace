(() => {
    "use strict";
    const root = document.getElementById("main");
    const status = document.getElementById("inventory-live-status");
    if (!root || !status) return;
    const url = new URL(location.href);
    // A POST error page can retain its handler in the address bar. Poll only the read-only page.
    url.search = "";
    url.hash = "";
    let stopped = false, pending = false, controller = null, timer = null;
    const buttons = () => root.querySelectorAll('[data-list-operation-form] button[name="kind"]');
    const disable = () => { for (const button of buttons()) button.disabled = true; };
    const stop = () => {
        stopped = true;
        window.clearTimeout(timer);
        controller?.abort();
        window.removeEventListener("control:before-page-change", stop);
        window.removeEventListener("pagehide", stop);
        window.removeEventListener("focus", wake);
        window.removeEventListener("control:navigation-failed", resume);
        document.removeEventListener("visibilitychange", wake);
        root.removeEventListener("submit", submit);
    };
    const refresh = async () => {
        if (stopped || pending || controller) return;
        if (!root.isConnected) { stop(); return; }
        window.clearTimeout(timer);
        const request = new AbortController();
        controller = request;
        const timeout = window.setTimeout(() => request.abort(), 8000);
        try {
            const response = await fetch(url, { cache: "no-store", credentials: "same-origin", signal: request.signal, headers: { Accept: "text/html" } });
            if (stopped || pending || !root.isConnected) return;
            const finalUrl = new URL(response.url);
            if (finalUrl.origin !== url.origin) throw new Error("Unexpected origin");
            if (response.status === 401 || /^\/(?:Login|Mfa)(?:\/|$)/i.test(finalUrl.pathname)) { location.assign("/Login"); return; }
            if (!response.ok || finalUrl.pathname !== url.pathname || !response.headers.get("Content-Type")?.startsWith("text/html")) throw new Error("Invalid snapshot");
            const doc = new DOMParser().parseFromString(await response.text(), "text/html");
            if (stopped || pending || !root.isConnected || request.signal.aborted) return;
            if (doc.getElementById("main")?.dataset.controlPage !== "true") throw new Error("Invalid page");
            // Add newly registered PCs without replacing existing cards and their edit forms.
            const collections = new Map([...doc.querySelectorAll("[data-live-collection]")].map(collection => [collection.dataset.liveCollection, collection]));
            for (const collection of root.querySelectorAll("[data-live-collection]")) {
                const next = collections.get(collection.dataset.liveCollection);
                if (!next) throw new Error("Missing collection");
                const items = new Map([...collection.querySelectorAll("[data-live-item]")].map(item => [item.dataset.liveItem, item]));
                const nextItems = [...next.querySelectorAll("[data-live-item]")];
                const nextKeys = new Set(nextItems.map(item => item.dataset.liveItem));
                for (const item of nextItems) if (!items.has(item.dataset.liveItem)) collection.append(document.importNode(item, true));
                for (const [key, item] of items) if (!nextKeys.has(key)) item.remove();
            }
            const regions = new Map([...doc.querySelectorAll("[data-live-region]")].map(region => [region.dataset.liveRegion, region]));
            const current = [...root.querySelectorAll("[data-live-region]")];
            if (!current.length || current.some(region => !regions.has(region.dataset.liveRegion))) throw new Error("Missing snapshot");
            for (const region of current) {
                const next = regions.get(region.dataset.liveRegion);
                const details = new Map([...region.querySelectorAll("details[data-live-detail]")].map(detail => [detail.dataset.liveDetail, detail.open]));
                const scroll = [...region.querySelectorAll(".table-scroll")].map(element => element.scrollLeft);
                const active = region.contains(document.activeElement) ? document.activeElement : null;
                const focusKey = active?.dataset.liveFocus;
                const focusHref = active?.getAttribute("href");
                region.replaceChildren(...[...next.childNodes].map(node => document.importNode(node, true)));
                for (const detail of region.querySelectorAll("details[data-live-detail]")) if (details.has(detail.dataset.liveDetail)) detail.open = details.get(detail.dataset.liveDetail);
                [...region.querySelectorAll(".table-scroll")].forEach((element, index) => { element.scrollLeft = scroll[index] || 0; });
                if (focusKey) [...region.querySelectorAll("[data-live-focus]")].find(element => element.dataset.liveFocus === focusKey)?.focus({ preventScroll: true });
                else if (focusHref) [...region.querySelectorAll("a[href]")].find(element => element.getAttribute("href") === focusHref)?.focus({ preventScroll: true });
            }
            status.textContent = "3秒ごとに自動更新しています。";
        } catch {
            if (!stopped && !pending && root.isConnected) {
                disable();
                status.textContent = "最新の状態を取得できません。起動・停止を無効にして再接続を待っています。";
            }
        } finally {
            window.clearTimeout(timeout);
            controller = null;
            if (!stopped && !pending) timer = window.setTimeout(refresh, 3000);
        }
    };
    const submit = event => {
        const form = event.target;
        if (event.defaultPrevented || form.method.toLowerCase() !== "post") return;
        if (pending) { event.preventDefault(); return; }
        if (form.hasAttribute("data-list-operation-form")) {
            const button = event.submitter;
            if (!button || button.disabled || !form.contains(button) || button.name !== "kind" || !["Start", "Stop"].includes(button.value)) { event.preventDefault(); return; }
            let kind = form.querySelector('input[name="kind"]');
            if (!kind) {
                kind = document.createElement("input");
                kind.type = "hidden";
                kind.name = "kind";
                form.append(kind);
            }
            kind.value = button.value;
        }
        pending = true;
        controller?.abort();
        window.clearTimeout(timer);
        queueMicrotask(disable);
    };
    const wake = () => { if (!document.hidden) refresh(); };
    const resume = () => { pending = false; refresh(); };
    window.addEventListener("control:before-page-change", stop);
    window.addEventListener("pagehide", stop);
    window.addEventListener("focus", wake);
    window.addEventListener("control:navigation-failed", resume);
    document.addEventListener("visibilitychange", wake);
    root.addEventListener("submit", submit);
    disable();
    refresh();
})();
