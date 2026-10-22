const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { pageUrl } = require('../../src/AstralRecordControl.Web/wwwroot/js/control-navigation.js');
const source = fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js/control-navigation.js'), 'utf8');
const origin = 'https://control.example';

test('only same-origin management pages are enhanced', () => {
    for (const href of ['/', '/Hosts', '/Servers/123/Operations', '/Workflows?workflowId=123', '/Transfers', '/Notifications', '/Security']) assert.ok(pageUrl(href, origin));
    for (const href of ['/Login', '/Logout', '/api/control/v1/servers', '/HostsOther', '//other.example/Servers', 'javascript:alert(1)', 'https://user:password@control.example/Hosts']) assert.equal(pageUrl(href, origin), null);
});

// Model the browser boundaries, while exercising the production event handlers and history logic.
function browser({ initialIndex = null } = {}) {
    const docEvents = new EventTarget(), win = new EventTarget();
    const calls = [], historyCalls = [], assigned = [], pages = new Map(), steps = [];
    let main = node('initial'), scriptLoads = 0;
    function node(label) {
        return { label, dataset: { controlPage: 'true' }, setAttribute() {}, focus() {}, replaceWith(next) { main = next; } };
    }
    const status = { hidden: true, textContent: '' };
    const shell = [{ getAttribute: name => name === 'src' ? '/js/control-navigation.js?v=1' : null }];
    const container = { replaceChildren() {}, append(script) { scriptLoads++; queueMicrotask(() => script.onload()); } };
    const document = {
        title: 'Initial', getElementById: id => ({ main, 'navigation-status': status, 'page-scripts': container })[id],
        querySelectorAll: selector => selector.startsWith('head ') ? shell : [],
        importNode: value => value, createElement: () => ({}), addEventListener: docEvents.addEventListener.bind(docEvents)
    };
    const location = { href: origin + '/Hosts', origin, pathname: '/Hosts', search: '', assign: url => assigned.push(url), reload: () => assigned.push('reload') };
    const update = url => { const value = new URL(url, location.href); location.href = value.href; location.pathname = value.pathname; location.search = value.search; };
    const history = {
        state: initialIndex === null ? null : { controlPage: true, controlIndex: initialIndex }, replaceState(state, _, url) { this.state = state; update(url); historyCalls.push(['replace', url]); },
        pushState(state, _, url) { this.state = state; update(url); historyCalls.push(['push', url]); },
        go(delta) { steps.push(delta); }
    };
    const context = vm.createContext({ document, window: win, location, history, URL, URLSearchParams, Event, AbortController, scrollX: 0, scrollY: 0,
        DOMParser: class { parseFromString(key) { return pages.get(key); } },
        FormData: class { constructor(form) { this.values = form.values; } },
        fetch: async (url, options) => { const call = { url: String(url), options }; calls.push(call); return call.promise = await nextResponse(call); }
    });
    win.scrollTo = () => {};
    let nextResponse = () => { throw new Error('No response'); };
    vm.runInContext(source, context);
    function response(label, url, { scripts = [], authenticated = true, redirected = false, assetVersion = 1 } = {}) {
        const page = node(label); page.dataset.controlPage = String(authenticated);
        pages.set(label, { title: label, getElementById: () => page, querySelector: () => ({ querySelectorAll: () => [] }),
            querySelectorAll: selector => selector.startsWith('head ') ? [{ getAttribute: name => name === 'src' ? '/js/control-navigation.js?v=' + assetVersion : null }] : scripts.map(src => ({ getAttribute: () => src, hasAttribute: () => true })) });
        return { url: origin + url, ok: true, status: 200, redirected, headers: new Headers({ 'Content-Type': 'text/html; charset=utf-8' }), text: async () => label };
    }
    function click(href, extra = {}) {
        const link = { href: new URL(href, origin).href, target: '', hasAttribute: () => false, closest: () => true };
        const event = new Event('click', { cancelable: true });
        Object.assign(event, { button: 0, ...extra });
        Object.defineProperty(event, 'target', { value: { closest: () => link } }); docEvents.dispatchEvent(event); return event;
    }
    function submit() {
        const form = { action: origin + '/Hosts?handler=Issue', method: 'post', target: '', closest: () => true, hasAttribute: () => false,
            values: { __RequestVerificationToken: 'test-csrf', hostId: 'test-host' } };
        const event = new Event('submit', { cancelable: true }); Object.defineProperty(event, 'target', { value: form }); docEvents.dispatchEvent(event); return event;
    }
    const pop = (index = history.state.controlIndex) => { const event = new Event('popstate'); event.state = { controlIndex: index }; win.dispatchEvent(event); };
    return { calls, assigned, historyCalls, steps, status, win, click, submit, response, update, pop,
        get main() { return main; }, get scriptLoads() { return scriptLoads; }, set respond(fn) { nextResponse = fn; } };
}
const settle = () => new Promise(resolve => setImmediate(resolve));

test('tab GET keeps old content until complete, then swaps only main and pushes GET history', async () => {
    const b = browser(); let finish;
    b.respond = () => new Promise(resolve => finish = resolve);
    assert.ok(b.click('/Servers').defaultPrevented);
    assert.equal(b.main.label, 'initial');
    finish(b.response('servers', '/Servers')); await settle();
    assert.equal(b.main.label, 'servers'); assert.equal(b.calls[0].options.method, 'GET');
    assert.equal(b.calls[0].options.cache, 'no-store'); assert.equal(b.calls[0].options.credentials, 'same-origin');
    assert.deepEqual(b.historyCalls.at(-1), ['push', origin + '/Servers']); assert.deepEqual(b.assigned, []);
});

test('POST is sent once with CSRF form body, rendered without a POST history entry', async () => {
    const b = browser(); b.respond = () => b.response('one-time-token', '/Hosts?handler=Issue');
    b.submit(); b.submit(); await settle();
    assert.equal(b.calls.length, 1); assert.equal(b.calls[0].options.method, 'POST');
    assert.equal(b.calls[0].options.body.values.__RequestVerificationToken, 'test-csrf');
    assert.equal(b.main.label, 'one-time-token'); assert.deepEqual(b.historyCalls.at(-1), ['replace', origin + '/Hosts']);
    b.respond = () => b.response('hosts-refetched', '/Hosts');
    b.pop(); await settle();
    assert.equal(b.calls.at(-1).options.method, 'GET'); assert.equal(b.main.label, 'hosts-refetched');
});

test('failed POST is not automatically retried or converted into another mutation', async () => {
    const b = browser(); b.respond = () => { throw new TypeError('Network unavailable'); };
    b.submit(); await settle();
    assert.equal(b.calls.length, 1); assert.equal(b.main.label, 'initial'); assert.match(b.status.textContent, /自動再送信はしていません/);
});

test('expired session goes to Login instead of placing the login form inside the management shell', async () => {
    const b = browser(); b.respond = () => b.response('login', '/Login', { authenticated: false, redirected: true });
    b.click('/Servers'); await settle(); assert.deepEqual(b.assigned, ['/Login']); assert.equal(b.main.label, 'initial');
});

test('late earlier GET cannot replace the most recent tab', async () => {
    const b = browser(); let first;
    b.respond = () => new Promise(resolve => first = resolve); b.click('/Servers');
    b.respond = () => b.response('notifications', '/Notifications'); b.click('/Notifications'); await settle();
    first(b.response('servers', '/Servers')); await settle();
    assert.equal(b.calls[0].options.signal.aborted, true); assert.equal(b.main.label, 'notifications');
});

test('page script is reloaded after old page cleanup, while unknown scripts never execute', async () => {
    const b = browser(); let cleaned = 0; b.win.addEventListener('control:before-page-change', () => cleaned++);
    b.respond = () => b.response('transfers', '/Transfers', { scripts: ['/js/p2-control.js?v=1'] });
    b.click('/Transfers'); await settle(); assert.equal(cleaned, 1); assert.equal(b.scriptLoads, 1);
    b.respond = () => b.response('history', '/Workflows', { scripts: ['/js/history-selection.js?v=1'] });
    b.click('/Workflows'); await settle(); assert.equal(b.main.label, 'history'); assert.equal(b.scriptLoads, 2);
    b.respond = () => b.response('invalid', '/Servers', { scripts: ['https://other.example/code.js'] });
    b.click('/Servers'); await settle(); assert.equal(b.main.label, 'history'); assert.equal(b.scriptLoads, 2);
});

test('busy page may prevent navigation, modified clicks and auth links remain native', async () => {
    const b = browser(); b.win.addEventListener('control:before-navigate', event => event.preventDefault());
    assert.equal(b.click('/Servers').defaultPrevented, true); assert.equal(b.calls.length, 0);
    assert.equal(b.click('/Servers', { ctrlKey: true }).defaultPrevented, false);
    assert.equal(b.click('/Logout').defaultPrevented, false);
});

test('Back while an operation is busy restores the current entry without overwriting the previous URL', async () => {
    const b = browser(); b.respond = () => b.response('transfers', '/Transfers');
    b.click('/Transfers'); await settle();
    const before = b.historyCalls.length;
    b.win.addEventListener('control:before-navigate', event => event.preventDefault());
    b.update('/Hosts'); b.pop(0);
    assert.deepEqual(b.steps, [1]); assert.equal(b.historyCalls.length, before);
    b.update('/Transfers'); b.pop(1); await settle();
    assert.equal(b.calls.length, 1); assert.equal(b.main.label, 'transfers');
});

test('deployment asset change preserves a one-time POST result until the next GET', async () => {
    const b = browser(); b.respond = () => b.response('one-time-token', '/Hosts?handler=Issue', { assetVersion: 2 });
    b.submit(); await settle(); assert.equal(b.main.label, 'one-time-token'); assert.deepEqual(b.assigned, []);
    b.respond = () => b.response('servers', '/Servers', { assetVersion: 2 });
    b.click('/Servers'); await settle(); assert.deepEqual(b.assigned, [origin + '/Servers']);
});

test('reloading an existing history entry preserves its index when busy Back is refused', () => {
    const b = browser({ initialIndex: 1 });
    b.win.addEventListener('control:before-navigate', event => event.preventDefault());
    b.update('/Servers'); b.pop(0);
    assert.deepEqual(b.steps, [1]); assert.equal(b.calls.length, 0); assert.deepEqual(b.assigned, []);
});
