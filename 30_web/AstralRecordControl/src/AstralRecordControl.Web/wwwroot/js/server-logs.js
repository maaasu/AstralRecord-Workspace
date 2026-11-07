(() => {
    "use strict";
    const root = document.getElementById("server-console");
    const log = document.getElementById("server-log");
    const logStatus = document.getElementById("log-status");
    const process = document.getElementById("server-process-state");
    const session = document.getElementById("server-session");
    const availability = document.getElementById("server-availability");
    const history = document.getElementById("server-operation-history");
    const latestOperation = document.getElementById("server-latest-operation");
    const workflowLink = document.getElementById("server-workflow-link");
    const reloadLink = document.getElementById("server-operation-reload");
    const maintenanceForm = document.getElementById("server-maintenance-start");
    const maintenanceButton = maintenanceForm?.querySelector('button[name="kind"]');
    const reservationField = maintenanceForm?.querySelector('[name="reservationId"]');
    if (!root || !log || !logStatus || !process || !session || !availability || !history) return;

    const stateNames = { Stopped: "停止中", Starting: "起動中", Running: "稼働中", Stopping: "停止処理中", Unmanaged: "管理外・要確認" };
    const kindNames = { Start: "起動", Stop: "正常停止", Restart: "再起動", Kill: "強制終了", ConsoleCommand: "コマンド送信", UpdateGeyser: "GeyserMC 更新", CreateBackup: "バックアップ取得", RestoreBackup: "バックアップ復元", PruneBackups: "バックアップ整理" };
    const statusNames = { Queued: "待機中", Accepted: "受付済み", Running: "実行中", Succeeded: "完了", Failed: "失敗", Rejected: "受付拒否", Expired: "期限切れ", Cancelled: "中断済み", NeedsAttention: "要確認" };
    // A pre-update shell can install a direct POST response without loading new head assets.
    const formatTime = window.ControlTime?.format ?? (value => {
        if (value === null || value === undefined || value === "") return "—";
        if (typeof value === "string" && !/(?:Z|[+-]\d{2}:\d{2})$/i.test(value)) return "—";
        if (typeof value !== "string" && typeof value !== "number" && !(value instanceof Date)) return "—";
        const date = new Date(value);
        if (!Number.isFinite(date.getTime())) return "—";
        const formatter = new Intl.DateTimeFormat("ja-JP-u-ca-gregory-nu-latn", {
            timeZone: "Asia/Tokyo", year: "numeric", month: "2-digit", day: "2-digit",
            hour: "2-digit", minute: "2-digit", second: "2-digit", hourCycle: "h23"
        });
        const p = Object.fromEntries(formatter.formatToParts(date).map(part => [part.type, part.value]));
        return `${p.year}/${p.month}/${p.day} ${p.hour}:${p.minute}:${p.second} JST`;
    });
    const forms = [...root.querySelectorAll("[data-operation-form]"), ...document.querySelectorAll(".console-command-form")];
    const buttons = forms.flatMap(form => [...form.querySelectorAll('button[name="kind"]')]);
    const trimLog = value => {
        const text = value.slice(-262144);
        return /^[\uDC00-\uDFFF]/.test(text) ? text.slice(1) : text;
    };
    let logText = trimLog(log.textContent);
    const renderLog = () => {
        if (window.ControlConsoleLog) window.ControlConsoleLog.render(log, logText);
        else log.textContent = logText;
    };
    let cursor = Number(log.dataset.cursor) || 0;
    let stopped = false, pending = null, uncertain = false, statusFailed = false;
    let timer = null, statusRequest = null, logRequest = null, state = null;
    const connected = () => root.isConnected && log.isConnected && logStatus.isConnected;
    const workflowUrl = id => `/Workflows?workflowId=${encodeURIComponent(id)}`;
    const setButtons = () => {
        const stoppedState = state?.processState === "Stopped" && !state.currentSessionId;
        const running = state?.processState === "Running" && !!state.currentSessionId;
        const unlocked = !stopped && !pending && state !== null && state.stateRevision !== null && state.configRevision > 0 && !state.operationPending;
        const canStart = unlocked && stoppedState && (state.canStart ?? state.enabled === true);
        const canStop = unlocked && running && (state.canStop ?? state.enabled === true);
        const canRun = unlocked && running && (state.canRun ?? state.enabled === true);
        const maintenance = state?.maintenanceStarts?.[0];
        for (const button of buttons) {
            if (button === maintenanceButton) button.disabled = !unlocked || !stoppedState || !maintenance;
            else if (button.value === "Start") button.disabled = !canStart;
            else if (button.value === "Stop") button.disabled = !canStop;
            else button.disabled = !canRun;
        }
        if (maintenanceForm && !pending) {
            maintenanceForm.hidden = !maintenance;
            reservationField.value = maintenance?.reservationId || "";
            maintenanceButton.textContent = maintenance?.usesAppliedSettings ? "適用済みの設定で保守起動" : "保守作業の続きとして起動";
        }
        let reason;
        if (uncertain) reason = `${kindNames[pending]}の受付結果を確認できません。履歴を確認してから、最新の状態を開いてください。`;
        else if (pending) reason = `${kindNames[pending]}を送信しています。`;
        else if (statusFailed) reason = "現在の状態を取得できません。操作を停止し、自動で再確認します。";
        else if (!state) reason = "現在の状態を確認しています。";
        else if (state.reason) reason = state.reason;
        else if (state.operationPending) reason = "操作を処理中、または要確認の操作が残っています。履歴を確認してください。";
        else if (canStart) reason = "停止中です。起動できます。";
        else if (canStop) reason = "稼働中です。正常停止または再起動できます。";
        else if (state.processState === "Starting") reason = "起動処理中です。完了を待ってください。";
        else if (state.processState === "Stopping") reason = "停止処理中です。終了を待っています。";
        else reason = "操作を利用できません。実行PCの接続と設定の適用状況を確認してください。";
        if (availability.textContent !== reason) availability.textContent = reason;
        if (workflowLink) {
            const workflowId = state?.workflowId || maintenance?.workflowId;
            workflowLink.hidden = !workflowId && !uncertain;
            workflowLink.href = workflowId ? workflowUrl(workflowId) : "/Workflows";
        }
        if (reloadLink) reloadLink.hidden = !uncertain;
    };
    const onNavigationFailed = () => {
        if (!pending) return;
        uncertain = true;
        setButtons();
    };
    const stop = () => {
        if (stopped) return;
        stopped = true;
        window.clearTimeout(timer);
        statusRequest?.abort();
        logRequest?.abort();
        window.removeEventListener("control:before-page-change", stop);
        window.removeEventListener("control:navigation-failed", onNavigationFailed);
        window.removeEventListener("pagehide", stop);
    };
    window.addEventListener("control:before-page-change", stop);
    window.addEventListener("control:navigation-failed", onNavigationFailed);
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
        pending = submitter.value;
        queueMicrotask(setButtons);
    });

    const renderHistory = rows => {
        const fragment = document.createDocumentFragment();
        for (const row of rows) {
            const tr = document.createElement("tr");
            const occurred = formatTime(row.createdAtUtc);
            for (const value of [occurred, kindNames[row.kind] || row.kind, statusNames[row.status] || row.status]) {
                const td = document.createElement("td");
                td.textContent = value || "";
                tr.append(td);
            }
            const detail = document.createElement("td");
            detail.textContent = row.errorMessage || row.errorCode || "";
            if (row.workflowId) {
                const link = document.createElement("a");
                link.className = "operation-history-link";
                link.href = workflowUrl(row.workflowId);
                link.textContent = "詳細を開く";
                detail.append(link);
            }
            tr.append(detail);
            fragment.append(tr);
        }
        history.replaceChildren(fragment);
        if (latestOperation) {
            const latest = rows[0];
            latestOperation.hidden = !latest;
            latestOperation.textContent = latest ? `直近の操作 ${kindNames[latest.kind] || latest.kind} · ${statusNames[latest.status] || latest.status} · ${formatTime(latest.createdAtUtc)}` : "";
        }
    };
    const updateState = snapshot => {
        if (!snapshot || typeof snapshot.enabled !== "boolean" || typeof snapshot.operationPending !== "boolean" || !Array.isArray(snapshot.recent)
            || !Number.isSafeInteger(snapshot.configRevision) || snapshot.configRevision < 0
            || (snapshot.stateRevision !== null && (!Number.isSafeInteger(snapshot.stateRevision) || snapshot.stateRevision < 0))) throw new Error("Invalid status");
        state = snapshot;
        statusFailed = false;
        process.textContent = stateNames[snapshot.processState] || "確認不能";
        session.textContent = snapshot.currentSessionId || "なし";
        for (const form of pending ? [] : forms) {
            form.querySelector('[name="configRevision"]').value = String(snapshot.configRevision);
            form.querySelector('[name="expectedStateRevision"]').value = snapshot.stateRevision === null ? "" : String(snapshot.stateRevision);
            form.querySelector('[name="expectedServerSessionId"]').value = snapshot.currentSessionId || "";
        }
        if (snapshot.currentSessionId && snapshot.currentSessionId !== log.dataset.sessionId) {
            logRequest?.abort();
            log.dataset.sessionId = snapshot.currentSessionId;
            log.dataset.cursor = "0";
            logText = "";
            renderLog();
            cursor = 0;
            logStatus.textContent = "新しいSessionのログに切り替えました。";
        }
        renderHistory(snapshot.recent);
        setButtons();
    };
    const pollLogs = async signal => {
        const sessionId = log.dataset.sessionId;
        const query = new URLSearchParams({ sessionId, afterSequence: String(cursor), limit: "200" });
        const response = await fetch(`/api/control/v1/servers/${encodeURIComponent(root.dataset.serverId)}/logs?${query}`, { cache: "no-store", credentials: "same-origin", redirect: "error", signal });
        if (!response.ok) throw new Error("Log request failed");
        const batch = await response.json();
        if (signal.aborted || stopped || !connected() || sessionId !== log.dataset.sessionId) return;
        if (batch.sessionId !== sessionId || !Number.isSafeInteger(batch.nextCursor) || batch.nextCursor < cursor || !Array.isArray(batch.rows)) throw new Error("Invalid log batch");
        const follow = log.scrollHeight - log.scrollTop - log.clientHeight <= 32;
        const scrollTop = log.scrollTop;
        const appended = batch.rows.map(row => `[${formatTime(row.occurredAtUtc)}] ${row.stream}: ${row.text}${row.truncated ? " [分割]" : ""}\n`).join("");
        if (appended) {
            logText = trimLog(logText + appended);
            renderLog();
            if (follow) log.scrollTop = log.scrollHeight;
            else log.scrollTop = scrollTop;
        }
        cursor = batch.nextCursor;
        log.dataset.cursor = String(cursor);
        logStatus.textContent = batch.gap ? "保持期間を過ぎたログがあります。取得可能な位置から再開しました。" : "ログを更新しました。";
    };
    const requestTimeoutMs = 8000;
    const requestDeadline = request => {
        const deadline = window.setTimeout(() => request.abort(), requestTimeoutMs);
        request.signal.addEventListener("abort", () => window.clearTimeout(deadline), { once: true });
        return deadline;
    };
    const refreshLogs = async () => {
        if (!log.dataset.sessionId || (logRequest && !logRequest.signal.aborted)) return;
        const request = new AbortController();
        logRequest = request;
        const deadline = requestDeadline(request);
        try { await pollLogs(request.signal); }
        catch {
            if (!stopped && connected() && logRequest === request) logStatus.textContent = "ログを取得できません。次の更新で再試行します。";
        } finally {
            window.clearTimeout(deadline);
            if (logRequest === request) logRequest = null;
        }
    };
    const poll = async () => {
        if (stopped) return;
        if (!connected()) { stop(); return; }
        const request = new AbortController();
        statusRequest = request;
        const deadline = requestDeadline(request);
        try {
            const response = await fetch(`/Servers/${encodeURIComponent(root.dataset.serverId)}/Operations?handler=Status`, { cache: "no-store", credentials: "same-origin", redirect: "error", signal: request.signal });
            if (!response.ok) throw new Error("Status request failed");
            const snapshot = await response.json();
            if (stopped || !connected()) return;
            if (request.signal.aborted) throw new Error("Status request timed out");
            updateState(snapshot);
        } catch {
            if (!stopped && connected()) {
                state = null;
                statusFailed = true;
                process.textContent = "確認不能";
                session.textContent = "未確認";
                setButtons();
            }
        } finally {
            window.clearTimeout(deadline);
            statusRequest = null;
            if (!stopped && connected()) {
                void refreshLogs();
                timer = window.setTimeout(poll, 3000);
            }
        }
    };
    setButtons();
    renderLog();
    log.scrollTop = log.scrollHeight;
    poll();
})();
