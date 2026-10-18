(function () {
    'use strict';

    function segments(points, field, granularity) {
        const step = granularity === 'hour' ? 3600000 : 60000;
        const result = [];
        let current = [];
        let last = null;
        for (const point of points) {
            const at = Date.parse(point.atUtc);
            const value = point[field];
            if (!Number.isFinite(at) || value === null || value === undefined || !Number.isFinite(value)) {
                if (current.length) result.push(current);
                current = []; last = null; continue;
            }
            if (last !== null && at - last > step * 1.5) {
                if (current.length) result.push(current);
                current = [];
            }
            current.push({ at, value }); last = at;
        }
        if (current.length) result.push(current);
        return result;
    }

    function latest(points, field) {
        for (let i = points.length - 1; i >= 0; i--) {
            const value = points[i][field];
            if (value !== null && value !== undefined && Number.isFinite(value)) return value;
        }
        return null;
    }

    function draw(canvas, points, field, granularity, label, fixedMax) {
        if (!canvas) return;
        const ctx = canvas.getContext('2d');
        const width = canvas.width, height = canvas.height;
        ctx.clearRect(0, 0, width, height);
        ctx.fillStyle = '#132139'; ctx.fillRect(0, 0, width, height);
        ctx.fillStyle = '#e4edf7'; ctx.font = '16px sans-serif'; ctx.fillText(label, 16, 24);
        const lines = segments(points, field, granularity);
        if (!lines.length) { ctx.fillText('欠測', 16, 60); return; }
        const all = lines.flat();
        const minTime = all[0].at, maxTime = all[all.length - 1].at;
        const maximum = fixedMax || Math.max(1, ...all.map(p => p.value)) * 1.1;
        const x = time => 48 + (time - minTime) / Math.max(1, maxTime - minTime) * (width - 64);
        const y = value => height - 28 - Math.max(0, value) / maximum * (height - 68);
        ctx.strokeStyle = '#66819b'; ctx.beginPath(); ctx.moveTo(48, 36); ctx.lineTo(48, height - 28); ctx.lineTo(width - 16, height - 28); ctx.stroke();
        ctx.fillStyle = '#b6c9d9'; ctx.font = '12px sans-serif'; ctx.fillText(maximum.toFixed(maximum < 100 ? 1 : 0), 3, 44); ctx.fillText('0', 24, height - 30);
        ctx.strokeStyle = '#61d6bd'; ctx.lineWidth = 2;
        for (const line of lines) {
            ctx.beginPath();
            line.forEach((p, index) => { if (index === 0) ctx.moveTo(x(p.at), y(p.value)); else ctx.lineTo(x(p.at), y(p.value)); });
            ctx.stroke();
            if (line.length === 1) { ctx.beginPath(); ctx.arc(x(line[0].at), y(line[0].value), 3, 0, Math.PI * 2); ctx.fillStyle = '#61d6bd'; ctx.fill(); }
        }
    }

    if (typeof module !== 'undefined' && module.exports) module.exports = { segments, latest };
    if (typeof document === 'undefined') return;
    const range = document.getElementById('metrics-range');
    if (!range) return;
    const id = range.dataset.serverId;
    const status = document.getElementById('metrics-status');
    async function refresh() {
        try {
            const to = new Date();
            const from = new Date(to.getTime() - Number(range.value) * 3600000);
            const granularity = Number(range.value) <= 24 ? 'minute' : 'hour';
            const query = new URLSearchParams({ fromUtc: from.toISOString(), toUtc: to.toISOString(), granularity });
            const response = await fetch('/api/control/v1/servers/' + encodeURIComponent(id) + '/metrics?' + query, { credentials: 'same-origin', cache: 'no-store' });
            if (!response.ok) throw new Error('取得に失敗しました (' + response.status + ')');
            const data = await response.json();
            document.getElementById('host-state').textContent = data.connectionState;
            document.getElementById('process-state').textContent = data.processState || '状態未取得';
            const specs = [
                ['host-cpu', data.host, 'cpuPercent', 'Host CPU (%)', 100],
                ['host-memory', data.host, 'memoryUsedBytes', 'Host メモリ (bytes)', null],
                ['host-disk', data.host, 'diskFreeBytes', 'Host 空き容量 (bytes)', null],
                ['server-cpu', data.server, 'cpuPercent', 'プロセス CPU (%)', 100],
                ['server-memory', data.server, 'memoryUsedBytes', 'プロセス メモリ (bytes)', null],
                ['server-players', data.server, 'playerCount', '人数', null],
                ['server-tps', data.server, 'tps', 'TPS', 20],
                ['server-mspt', data.server, 'mspt', 'MSPT', null]
            ];
            for (const [canvasId, points, field, label, maximum] of specs) draw(document.getElementById(canvasId), points, field, data.granularity, label, maximum);
            const online = data.connectionState === 'Online';
            const hostUptime = online ? latest(data.host, 'uptimeSeconds') : null;
            const serverUptime = online && data.processState === 'Running' ? latest(data.server, 'uptimeSeconds') : null;
            document.getElementById('host-uptime').textContent = hostUptime === null ? '欠測' : Math.round(hostUptime) + '秒';
            document.getElementById('server-uptime').textContent = serverUptime === null ? '欠測' : Math.round(serverUptime) + '秒';
            status.textContent = data.host.length || data.server.length ? '時系列を更新しました。' : 'この期間の計測値はありません。';
        } catch (error) { status.textContent = error.message; }
    }
    range.addEventListener('change', refresh);
    refresh();
})();
