const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = name => fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js', name), 'utf8');
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

test('log polling clears its timer, aborts fetch, and ignores a late result on page change', async () => {
    const log = { dataset: { sessionId: 'session', serverId: 'server', cursor: '0' }, textContent: '', isConnected: true };
    const status = { textContent: '', isConnected: true };
    const window = new EventTarget();
    const timers = new Map();
    let nextTimer = 0, signal, resolveFetch;
    window.setTimeout = callback => { const id = ++nextTimer; timers.set(id, callback); return id; };
    window.clearTimeout = id => timers.delete(id);
    const context = vm.createContext({
        document: { getElementById: id => ({ 'server-log': log, 'log-status': status })[id] },
        window, AbortController, URLSearchParams,
        fetch: (_url, options) => { signal = options.signal; return new Promise(resolve => { resolveFetch = resolve; }); }
    });

    vm.runInContext(script('server-logs.js'), context);
    assert.equal(timers.size, 1);
    const [timerId, poll] = [...timers][0];
    timers.delete(timerId);
    poll();
    assert.equal(signal.aborted, false);
    window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(signal.aborted, true);
    assert.equal(timers.size, 0);

    resolveFetch({ ok: true, json: async () => ({ sessionId: 'session', nextCursor: 1, rows: [{ text: 'late' }] }) });
    await flush();
    assert.equal(log.textContent, '');
    assert.equal(status.textContent, '');
    assert.equal(timers.size, 0);
});

test('metrics range change aborts the previous request and departure ignores late data', async () => {
    const range = new EventTarget();
    range.dataset = { serverId: 'server' };
    range.value = '24';
    range.isConnected = true;
    const status = { textContent: 'initial', isConnected: true };
    const window = new EventTarget();
    const requests = [];
    const context = vm.createContext({
        document: { getElementById: id => ({ 'metrics-range': range, 'metrics-status': status })[id] },
        window, AbortController, URLSearchParams,
        fetch: (_url, options) => new Promise(resolve => { requests.push({ signal: options.signal, resolve }); })
    });

    vm.runInContext(script('server-metrics.js'), context);
    assert.equal(requests.length, 1);
    range.value = '48';
    range.dispatchEvent(new Event('change'));
    assert.equal(requests.length, 2);
    assert.equal(requests[0].signal.aborted, true);
    window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(requests[1].signal.aborted, true);
    requests[1].resolve({ ok: true, json: async () => ({ connectionState: 'Online', host: [], server: [] }) });
    await flush();
    assert.equal(status.textContent, 'initial');
    range.dispatchEvent(new Event('change'));
    assert.equal(requests.length, 2);
});

test('P2 work blocks tab navigation and departure aborts its requests', async () => {
    const button = new EventTarget();
    button.dataset = { action: 'list' };
    button.disabled = false;
    const panel = {
        dataset: { enabled: 'true', mode: 'files', serverId: 'server', rootId: 'root', directory: 'dir' },
        querySelector: () => ({ value: 'csrf' }),
        querySelectorAll: () => [button]
    };
    const status = { textContent: '' }, output = { textContent: '' }, filePath = { value: '' };
    const window = new EventTarget();
    const signals = [];
    const context = vm.createContext({
        document: { getElementById: id => ({ 'p2-control': panel, 'p2-status': status, 'p2-result': output, 'file-path': filePath })[id] },
        window, AbortController, Blob, crypto: { randomUUID: () => 'request' },
        fetch: (_url, options) => new Promise((_resolve, reject) => {
            signals.push(options.signal);
            options.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
        })
    });

    vm.runInContext(script('p2-control.js'), context);
    button.dispatchEvent(new Event('click'));
    assert.equal(button.disabled, true);
    assert.equal(signals.length, 2);
    const navigation = new Event('control:before-navigate', { cancelable: true });
    window.dispatchEvent(navigation);
    assert.equal(navigation.defaultPrevented, true);
    assert.match(status.textContent, /処理中/);

    window.dispatchEvent(new Event('control:before-page-change'));
    assert.ok(signals.every(signal => signal.aborted));
    await flush();
    const afterStop = new Event('control:before-navigate', { cancelable: true });
    window.dispatchEvent(afterStop);
    assert.equal(afterStop.defaultPrevented, false);
});
