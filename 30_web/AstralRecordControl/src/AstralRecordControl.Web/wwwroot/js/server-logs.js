(() => {
    const log = document.getElementById("server-log");
    const status = document.getElementById("log-status");
    if (!log || !status || !log.dataset.sessionId) return;
    let cursor = Number(log.dataset.cursor);
    let stopped = false;
    window.addEventListener("pagehide", () => { stopped = true; });
    const poll = async () => {
        if (stopped) return;
        try {
            const query = new URLSearchParams({ sessionId: log.dataset.sessionId, afterSequence: String(cursor), limit: "200" });
            const response = await fetch(`/api/control/v1/servers/${encodeURIComponent(log.dataset.serverId)}/logs?${query}`, { cache: "no-store", credentials: "same-origin", redirect: "error" });
            if (!response.ok) throw new Error();
            const batch = await response.json();
            if (batch.sessionId !== log.dataset.sessionId || !Number.isSafeInteger(batch.nextCursor) || batch.nextCursor < cursor) throw new Error();
            const appended = batch.rows.map(row => `[${row.occurredAtUtc}] ${row.stream}: ${row.text}${row.truncated ? " [分割]" : ""}\n`).join("");
            // Text output only; console output must never become browser markup.
            log.textContent = (log.textContent + appended).slice(-262144);
            cursor = batch.nextCursor;
            status.textContent = batch.gap ? "保持期間を過ぎたログがあります。取得可能な位置から再開しました。" : "ログを更新しました。このSessionを追跡しています。";
        } catch {
            status.textContent = "ログを取得できません。認証とPCの接続を確認してください。";
        } finally {
            if (!stopped) window.setTimeout(poll, 3000);
        }
    };
    window.setTimeout(poll, 3000);
})();
