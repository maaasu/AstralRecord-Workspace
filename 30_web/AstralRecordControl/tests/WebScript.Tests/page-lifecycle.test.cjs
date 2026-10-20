const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = name => fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js', name), 'utf8');
const flush = async () => { await Promise.resolve(); await Promise.resolve(); };

const consoleFixture = fetch => {
    const fields = Object.fromEntries(['configRevision', 'expectedStateRevision', 'expectedServerSessionId'].map(name => [name, { value: '' }]));
    const buttons = ['Start', 'Stop', 'Restart', 'Kill', 'ConsoleCommand'].map(value => ({ value, disabled: false }));
    const form = new EventTarget();
    form.querySelector = selector => fields[selector.match(/name="([^"]+)/)[1]];
    form.querySelectorAll = () => buttons;
    const root = { dataset: { serverId: 'server' }, isConnected: true, querySelectorAll: () => [form] };
    const log = { dataset: { sessionId: 'old', cursor: '0' }, textContent: '', isConnected: true, scrollHeight: 100, scrollTop: 0, clientHeight: 100 };
    const status = { textContent: 'initial', isConnected: true };
    const process = { textContent: '' }, session = { textContent: '' }, availability = { hidden: false };
    const history = { rows: [], replaceChildren(fragment) { this.rows = fragment.children; } };
    const elements = { 'server-console': root, 'server-log': log, 'log-status': status, 'server-process-state': process, 'server-session': session, 'server-availability': availability, 'server-operation-history': history };
    const window = new EventTarget();
    const timers = new Map();
    let nextTimer = 0;
    window.setTimeout = callback => { const id = ++nextTimer; timers.set(id, callback); return id; };
    window.clearTimeout = id => timers.delete(id);
    const document = {
        getElementById: id => elements[id], querySelectorAll: () => [],
        createDocumentFragment: () => ({ children: [], append(row) { this.children.push(row); } }),
        createElement: () => ({ children: [], textContent: '', append(cell) { this.children.push(cell); } })
    };
    vm.runInContext(script('server-logs.js'), vm.createContext({ document, window, AbortController, URLSearchParams, fetch, queueMicrotask }));
    return { root, log, status, process, session, availability, history, buttons, fields, form, window, timers };
};

test('console polling aborts status fetch and ignores late results on page change', async () => {
    let signal, resolveFetch;
    const fixture = consoleFixture((_url, options) => {
        signal = options.signal;
        return new Promise(resolve => { resolveFetch = resolve; });
    });
    assert.equal(fixture.buttons.every(button => button.disabled), true);
    fixture.window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(signal.aborted, true);
    resolveFetch({ ok: true, json: async () => ({ enabled: true, operationPending: false, configRevision: 1, stateRevision: 1, processState: 'Running', currentSessionId: 'new', recent: [] }) });
    await flush();
    assert.equal(fixture.process.textContent, '');
    assert.equal(fixture.timers.size, 0);
});

test('console follows new session, updates hidden revisions/history, and preserves scroll away from bottom', async () => {
    const requests = [];
    const fixture = consoleFixture((url, options) => {
        requests.push({ url: String(url), signal: options.signal });
        if (String(url).includes('handler=Status')) return Promise.resolve({ ok: true, json: async () => ({ enabled: true, operationPending: false, configRevision: 4, stateRevision: 9, processState: 'Running', currentSessionId: 'new', recent: [{ createdAtUtc: '2026-10-20T00:00:00Z', kind: 'Start', status: 'Succeeded', errorCode: null }] }) });
        return Promise.resolve({ ok: true, json: async () => ({ sessionId: 'new', nextCursor: 2, rows: [{ occurredAtUtc: 'now', stream: 'stdout', text: '<unsafe>', truncated: false }] }) });
    });
    fixture.log.scrollHeight = 500;
    fixture.log.scrollTop = 10;
    fixture.log.clientHeight = 100;
    await new Promise(setImmediate);
    assert.equal(fixture.log.dataset.sessionId, 'new');
    assert.equal(fixture.fields.expectedStateRevision.value, '9');
    assert.equal(fixture.fields.expectedServerSessionId.value, 'new');
    assert.equal(fixture.buttons[0].disabled, true);
    assert.equal(fixture.buttons[1].disabled, false);
    assert.equal(fixture.history.rows[0].children[1].textContent, '起動');
    assert.equal(fixture.history.rows[0].children[0].textContent, '2026-10-20 00:00:00 UTC');
    assert.match(fixture.log.textContent, /<unsafe>/);
    assert.equal(fixture.log.scrollTop, 10);
    assert.match(requests[1].url, /sessionId=new&afterSequence=0/);
    fixture.window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(fixture.timers.size, 0);
});

test('console disables operations when status cannot be checked', async () => {
    let calls = 0;
    const fixture = consoleFixture(() => {
        calls++;
        return calls === 1
            ? Promise.resolve({ ok: true, json: async () => ({ enabled: true, operationPending: false, configRevision: 1, stateRevision: 1, processState: 'Stopped', currentSessionId: null, recent: [] }) })
            : Promise.reject(new Error('offline'));
    });
    await new Promise(setImmediate);
    assert.equal(fixture.buttons[0].disabled, false);
    const [timerId, callback] = [...fixture.timers][0];
    fixture.timers.delete(timerId);
    callback();
    await new Promise(setImmediate);
    assert.equal(fixture.buttons.every(button => button.disabled), true);
    assert.match(fixture.status.textContent, /操作を停止/);
    fixture.window.dispatchEvent(new Event('control:before-page-change'));
});

test('console follows log bottom and locks controls after submit', async () => {
    const fixture = consoleFixture(url => String(url).includes('handler=Status')
        ? Promise.resolve({ ok: true, json: async () => ({ enabled: true, operationPending: false, configRevision: 2, stateRevision: 4, processState: 'Running', currentSessionId: 'old', recent: [] }) })
        : Promise.resolve({ ok: true, json: async () => ({ sessionId: 'old', nextCursor: 1, rows: [{ occurredAtUtc: 'now', stream: 'stdout', text: 'line' }] }) }));
    fixture.log.scrollHeight = 200;
    fixture.log.scrollTop = 100;
    fixture.log.clientHeight = 100;
    await new Promise(setImmediate);
    assert.equal(fixture.log.scrollTop, 200);
    assert.equal(fixture.buttons[1].disabled, false);
    fixture.form.dispatchEvent(new Event('submit'));
    await flush();
    assert.equal(fixture.buttons.every(button => button.disabled), true);
    fixture.window.dispatchEvent(new Event('control:before-page-change'));
});

test('console stays disabled after redirect while an accepted operation awaits a state update', async () => {
    for (const processState of ['Stopped', 'Running']) {
        const fixture = consoleFixture(url => String(url).includes('handler=Status')
            ? Promise.resolve({ ok: true, json: async () => ({ enabled: true, operationPending: true, configRevision: 2, stateRevision: 4, processState, currentSessionId: processState === 'Running' ? 'old' : null, recent: [] }) })
            : Promise.resolve({ ok: true, json: async () => ({ sessionId: 'old', nextCursor: 0, rows: [] }) }));
        await new Promise(setImmediate);
        assert.equal(fixture.buttons.every(button => button.disabled), true);
        assert.equal(fixture.availability.hidden, false);
        assert.match(fixture.availability.textContent, /操作を処理中/);
        fixture.window.dispatchEvent(new Event('control:before-page-change'));
    }
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
