(() => {
    "use strict";
    const root = document.getElementById("server-console");
    const log = document.getElementById("server-log");
    const logStatus = document.getElementById("log-status");
    const process = document.getElementById("server-process-state");
    const session = document.getElementById("server-session");
    const availability = document.getElementById("server-availability");
    const history = document.getElementById("server-operation-history");
    if (!root || !log || !logStatus || !process || !session || !availability || !history) return;

    const stateNames = { Stopped: "停止中", Starting: "起動中", Running: "稼働中", Stopping: "停止処理中", Unmanaged: "管理外" };
    const kindNames = { Start: "起動", Stop: "正常停止", Restart: "再起動", Kill: "強制終了", ConsoleCommand: "コマンド送信" };
    const statusNames = { Queued: "受付", Accepted: "受理", Running: "実行中", Succeeded: "成功", Failed: "失敗", Rejected: "拒否", Expired: "期限切れ", Cancelled: "取消", NeedsAttention: "要確認" };
    const forms = [...root.querySelectorAll("[data-operation-form]"), ...document.querySelectorAll(".console-command-form")];
    const buttons = forms.flatMap(form => [...form.querySelectorAll('button[name="kind"]')]);
    let cursor = Number(log.dataset.cursor) || 0;
    let stopped = false, pending = false, timer = null, controller = null;
    let state = null;
    const connected = () => root.isConnected && log.isConnected && logStatus.isConnected;
    const setButtons = () => {
        const processing = pending || state?.operationPending === true;
        const enabled = !stopped && !processing && state?.enabled === true;
        const processState = state?.processState;
        const running = processState === "Running" && !!state.currentSessionId;
        const stoppedState = processState === "Stopped" && !state.currentSessionId;
        for (const button of buttons) button.disabled = !enabled || (button.value === "Start" ? !stoppedState : !running);
        availability.hidden = enabled;
        availability.textContent = processing ? "操作を処理中、または要確認の操作が残っています。履歴を確認してください。" : "操作は現在利用できません。PC接続・共通gate・設定適用を確認してください。";
    };
    const stop = () => {
        if (stopped) return;
        stopped = true;
        window.clearTimeout(timer);
        controller?.abort();
        window.removeEventListener("control:before-page-change", stop);
        window.removeEventListener("pagehide", stop);
    };
    window.addEventListener("control:before-page-change", stop);
    window.addEventListener("pagehide", stop);
    for (const form of forms) form.addEventListener("submit", event => {
        const submitter = event.submitter;
        if (pending || !submitter || submitter.name !== "kind" || submitter.disabled || !form.contains(submitter) || !Object.hasOwn(kindNames, submitter.value)) {
            event.preventDefault();
            return;
        }
        // A native event can run microtasks before the document listener builds FormData.
        // Keep the selected action in a successful control after buttons become disabled.
        let selectedKind = form.querySelector('input[name="kind"]');
        if (!selectedKind) {
            selectedKind = document.createElement("input");
            selectedKind.type = "hidden";
            selectedKind.name = "kind";
            form.append(selectedKind);
        }
        selectedKind.value = submitter.value;
        pending = true;
        queueMicrotask(setButtons);
    });

    const renderHistory = rows => {
        const fragment = document.createDocumentFragment();
        for (const row of rows) {
            const tr = document.createElement("tr");
            const occurred = row.createdAtUtc ? new Date(row.createdAtUtc).toISOString().slice(0, 19).replace("T", " ") + " UTC" : "";
            for (const value of [occurred, kindNames[row.kind] || row.kind, statusNames[row.status] || row.status, row.errorCode || ""]) {
                const td = document.createElement("td");
                td.textContent = value || "";
                tr.append(td);
            }
            fragment.append(tr);
        }
        history.replaceChildren(fragment);
    };
    const updateState = snapshot => {
        if (!snapshot || typeof snapshot.enabled !== "boolean" || typeof snapshot.operationPending !== "boolean" || !Array.isArray(snapshot.recent)
            || !Number.isSafeInteger(snapshot.configRevision) || snapshot.configRevision < 1
            || (snapshot.stateRevision !== null && (!Number.isSafeInteger(snapshot.stateRevision) || snapshot.stateRevision < 0))) throw new Error("Invalid status");
        state = snapshot;
        process.textContent = stateNames[snapshot.processState] || "状態未取得";
        session.textContent = snapshot.currentSessionId || "なし";
        for (const form of forms) {
            form.querySelector('[name="configRevision"]').value = String(snapshot.configRevision);
            form.querySelector('[name="expectedStateRevision"]').value = snapshot.stateRevision === null ? "" : String(snapshot.stateRevision);
            form.querySelector('[name="expectedServerSessionId"]').value = snapshot.currentSessionId || "";
        }
        if (snapshot.currentSessionId && snapshot.currentSessionId !== log.dataset.sessionId) {
            log.dataset.sessionId = snapshot.currentSessionId;
            log.dataset.cursor = "0";
            log.textContent = "";
            cursor = 0;
            logStatus.textContent = "新しいSessionのログに切り替えました。";
        }
        renderHistory(snapshot.recent);
        setButtons();
    };
    const pollLogs = async signal => {
        if (!log.dataset.sessionId) return;
        const query = new URLSearchParams({ sessionId: log.dataset.sessionId, afterSequence: String(cursor), limit: "200" });
        const response = await fetch(`/api/control/v1/servers/${encodeURIComponent(root.dataset.serverId)}/logs?${query}`, { cache: "no-store", credentials: "same-origin", redirect: "error", signal });
        if (!response.ok) throw new Error("Log request failed");
        const batch = await response.json();
        if (stopped || !connected()) return;
        if (batch.sessionId !== log.dataset.sessionId || !Number.isSafeInteger(batch.nextCursor) || batch.nextCursor < cursor || !Array.isArray(batch.rows)) throw new Error("Invalid log batch");
        const follow = log.scrollHeight - log.scrollTop - log.clientHeight <= 32;
        const appended = batch.rows.map(row => `[${row.occurredAtUtc}] ${row.stream}: ${row.text}${row.truncated ? " [分割]" : ""}\n`).join("");
        // Console output is always text, never browser markup.
        log.textContent = (log.textContent + appended).slice(-262144);
        cursor = batch.nextCursor;
        log.dataset.cursor = String(cursor);
        if (follow) log.scrollTop = log.scrollHeight;
        logStatus.textContent = batch.gap ? "保持期間を過ぎたログがあります。取得可能な位置から再開しました。" : "ログを更新しました。";
    };
    const poll = async () => {
        if (stopped) return;
        if (!connected()) { stop(); return; }
        controller = new AbortController();
        try {
            const response = await fetch(`/Servers/${encodeURIComponent(root.dataset.serverId)}/Operations?handler=Status`, { cache: "no-store", credentials: "same-origin", redirect: "error", signal: controller.signal });
            if (!response.ok) throw new Error("Status request failed");
            const snapshot = await response.json();
            if (stopped || !connected()) return;
            updateState(snapshot);
            try { await pollLogs(controller.signal); }
            catch { if (!stopped && connected()) logStatus.textContent = "ログを取得できません。PCの接続を確認してください。"; }
        } catch {
            if (!stopped && connected()) {
                state = null;
                setButtons();
                logStatus.textContent = "状態を確認できません。操作を停止しました。接続を確認してください。";
            }
        } finally {
            controller = null;
            if (!stopped) timer = window.setTimeout(poll, 3000);
        }
    };
    state = null;
    setButtons();
    log.scrollTop = log.scrollHeight;
    poll();
})();
