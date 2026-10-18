const test = require('node:test');
const assert = require('node:assert/strict');
const { segments, latest } = require('../../src/AstralRecordControl.Web/wwwroot/js/server-metrics.js');

test('missing and delayed samples break graph lines; zero stays measurable', () => {
    const points = [
        { atUtc: '2026-01-01T00:00:00Z', cpuPercent: 0 },
        { atUtc: '2026-01-01T00:01:00Z', cpuPercent: null },
        { atUtc: '2026-01-01T00:02:00Z', cpuPercent: 50 },
        { atUtc: '2026-01-01T00:05:00Z', cpuPercent: 40 }
    ];
    assert.deepEqual(segments(points, 'cpuPercent', 'minute').map(line => line.map(p => p.value)), [[0], [50], [40]]);
    assert.equal(latest(points, 'cpuPercent'), 40);
    assert.equal(latest([{ cpuPercent: null }], 'cpuPercent'), null);
});
