const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const code = fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js/inventory-live.js'), 'utf8');
const flush = () => new Promise(setImmediate);

function fixture(fetch, parse) {
    const button = { name: 'kind', value: 'Start', disabled: false };
    const fields = {};
    const form = {
        method: 'post', hasAttribute: name => name === 'data-list-operation-form',
        contains: element => element === button,
        querySelector: () => fields.kind,
        append: field => { fields[field.name] = field; }
    };
    const region = {
        dataset: { liveRegion: 'servers' }, value: 'initial',
        querySelectorAll: () => [], contains: () => false,
        replaceChildren(node) { this.value = node.value; button.disabled = node.disabled; }
    };
    const root = new EventTarget();
    root.isConnected = true;
    root.querySelectorAll = selector => selector === '[data-live-region]' ? [region] : selector === '[data-live-collection]' ? [] : [button];
    const status = { textContent: '' };
    const window = new EventTarget();
    const timers = new Map();
    let timerId = 0;
    window.setTimeout = (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; };
    window.clearTimeout = id => timers.delete(id);
    const document = new EventTarget();
    Object.assign(document, {
        hidden: false, activeElement: null,
        getElementById: id => id === 'main' ? root : status,
        importNode: node => ({ ...node }), createElement: () => ({})
    });
    const location = { href: 'https://control.test/Servers?handler=Operate', assign(url) { this.assigned = url; } };
    class DOMParser {
        parseFromString(text) {
            if (parse) return parse(text);
            return {
                getElementById: () => ({ dataset: { controlPage: 'true' } }),
                querySelectorAll: selector => selector === '[data-live-collection]' ? [] : [{ dataset: { liveRegion: 'servers' }, childNodes: [{ value: text, disabled: text === 'running' }] }]
            };
        }
    }
    vm.runInContext(code, vm.createContext({ document, window, location, URL, AbortController, DOMParser, fetch, queueMicrotask }));
    return { root, region, status, window, document, timers, button, form, fields, location };
}
const response = (text, url = 'https://control.test/Servers') => ({ ok: true, url, status: 200, headers: { get: () => 'text/html; charset=utf-8' }, text: async () => text });
const nextPoll = async state => {
    const [id, timer] = [...state.timers].find(([, timer]) => timer.delay === 3000);
    state.timers.delete(id); timer.callback(); await flush();
};

test('inventory follows another browser state without replacing registration input and recovers after failure', async () => {
    const calls = [];
    let value = 'stopped', fail = false;
    const state = fixture((url, options) => {
        calls.push({ url: String(url), options });
        return fail ? Promise.reject(new Error('offline')) : Promise.resolve(response(value));
    });
    assert.equal(state.button.disabled, true);
    await flush();
    assert.equal(state.region.value, 'stopped');
    assert.equal(state.button.disabled, false);
    assert.equal(calls[0].url, 'https://control.test/Servers');
    assert.equal(calls[0].options.cache, 'no-store');
    value = 'running';
    await nextPoll(state);
    assert.equal(state.region.value, 'running');
    assert.equal(state.button.disabled, true);
    fail = true;
    await nextPoll(state);
    assert.match(state.status.textContent, /取得できません/);
    assert.equal(state.button.disabled, true);
    fail = false; value = 'stopped';
    await nextPoll(state);
    assert.equal(state.button.disabled, false);
    state.window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(state.timers.size, 0);
});

test('inventory departure aborts the request and ignores late responses', async () => {
    let resolve, signal;
    const state = fixture((_url, options) => { signal = options.signal; return new Promise(done => { resolve = done; }); });
    state.window.dispatchEvent(new Event('control:before-page-change'));
    assert.equal(signal.aborted, true);
    resolve(response('late'));
    await flush();
    assert.equal(state.region.value, 'initial');
    assert.equal(state.timers.size, 0);
});

test('list submit preserves kind, blocks duplicates and cannot be overwritten by a late poll', async () => {
    let resolve, calls = 0, signal;
    const state = fixture((_url, options) => ++calls === 1 ? Promise.resolve(response('stopped'))
        : new Promise(done => { resolve = done; signal = options.signal; }));
    await flush();
    state.window.dispatchEvent(new Event('focus'));
    const submit = () => {
        const event = new Event('submit', { cancelable: true });
        Object.defineProperties(event, { target: { value: state.form }, submitter: { value: state.button } });
        state.root.dispatchEvent(event);
        return event;
    };
    assert.equal(submit().defaultPrevented, false);
    assert.equal(signal.aborted, true);
    resolve(response('late-running'));
    await flush();
    assert.equal(state.region.value, 'stopped');
    assert.equal(state.fields.kind.value, 'Start');
    assert.equal(state.button.disabled, true);
    assert.equal(submit().defaultPrevented, true);
    assert.equal(state.timers.size, 0);
    state.window.dispatchEvent(new Event('control:before-page-change'));
});

test('inventory returns to login after authentication expires', async () => {
    const state = fixture(() => Promise.resolve(response('login', 'https://control.test/Login?ReturnUrl=%2FServers')));
    await flush();
    assert.equal(state.location.assigned, '/Login');
    assert.equal(state.button.disabled, true);
    state.window.dispatchEvent(new Event('pagehide'));
});

test('empty host list receives the first and later PCs while preserving existing edit fields', async () => {
    let count = 0;
    const items = new Map();
    const collection = {
        dataset: { liveCollection: 'hosts' },
        querySelectorAll: () => [...items.values()],
        append(item) { items.set(item.dataset.liveItem, item); }
    };
    const state = fixture(() => Promise.resolve(response('hosts')), () => ({
        getElementById: () => ({ dataset: { controlPage: 'true' } }),
        querySelectorAll: selector => selector === '[data-live-collection]' ? [{
            dataset: { liveCollection: 'hosts' },
            querySelectorAll: () => Array.from({ length: count }, (_, i) => ({ dataset: { liveItem: String(i) }, input: '' }))
        }] : [{ dataset: { liveRegion: 'servers' }, childNodes: [{ value: String(count), disabled: false }] }]
    }));
    state.root.querySelectorAll = selector => selector === '[data-live-region]' ? [state.region]
        : selector === '[data-live-collection]' ? [collection] : [];
    await flush();
    assert.equal(items.size, 0);
    assert.doesNotMatch(state.status.textContent, /取得できません/);
    count = 1;
    await nextPoll(state);
    assert.equal(items.size, 1);
    const first = items.get('0');
    first.input = '編集中の名前';
    count = 2;
    await nextPoll(state);
    assert.equal(items.size, 2);
    assert.equal(items.get('0'), first);
    assert.equal(first.input, '編集中の名前');
    state.window.dispatchEvent(new Event('pagehide'));
});
