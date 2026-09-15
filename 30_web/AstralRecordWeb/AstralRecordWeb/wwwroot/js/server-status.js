(() => {
    const panel = document.querySelector('[data-server-status]');
    if (!panel) return;
    let busy = false;
    const refresh = async () => {
        if (document.hidden || busy) return;
        busy = true;
        try {
            const response = await fetch(panel.dataset.statusUrl, { cache: 'no-store', signal: AbortSignal.timeout(7000) });
            if (!response.ok) throw new Error('Status request failed');
            const status = await response.json();
            panel.dataset.state = status.state;
            panel.querySelector('[data-server-state]').textContent = status.label;
            panel.querySelector('[data-server-players]').textContent = status.players?.toLocaleString('ja-JP') ?? '—';
            panel.querySelector('[data-server-maximum]').textContent = status.maximumPlayers?.toLocaleString('ja-JP') ?? '—';
            panel.querySelector('[data-server-checked]').textContent = new Date(status.checkedAt).toLocaleTimeString('ja-JP', { timeZone: 'Asia/Tokyo' });
        } catch {
            panel.dataset.state = 'unknown';
            panel.querySelector('[data-server-state]').textContent = '状況を確認できません';
            panel.querySelector('[data-server-players]').textContent = '—';
            panel.querySelector('[data-server-maximum]').textContent = '—';
        } finally { busy = false; }
    };
    setInterval(refresh, 30000);
    document.addEventListener('visibilitychange', () => { if (!document.hidden) refresh(); });
})();
