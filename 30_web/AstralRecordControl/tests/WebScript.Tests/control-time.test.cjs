const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { spawnSync } = require('node:child_process');

const helperPath = path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js/control-time.js');
const source = fs.readFileSync(helperPath, 'utf8');
const createContext = () => {
    const context = vm.createContext({ window: new EventTarget() });
    vm.runInContext(source, context);
    return context;
};

test('JST formatting handles midnight, year rollover, and equivalent offsets', () => {
    const { window } = createContext();
    for (const input of ['2026-10-31T15:02:03Z', '2026-11-01T00:02:03+09:00', '2026-10-31T08:02:03-07:00']) {
        assert.equal(window.ControlTime.format(input), '2026/11/01 00:02:03 JST');
    }
    assert.equal(window.ControlTime.format('2026-12-31T15:00:00Z'), '2027/01/01 00:00:00 JST');
    assert.equal(window.ControlTime.format('2026-10-31T14:59:59Z'), '2026/10/31 23:59:59 JST');
});

test('JST formatting is independent of browser timezone and daylight saving', () => {
    for (const TZ of ['UTC', 'America/Los_Angeles', 'Europe/Berlin', 'Asia/Tokyo']) {
        const child = spawnSync(process.execPath, ['-e', `
            const fs = require('node:fs'), vm = require('node:vm');
            const context = vm.createContext({ window: {} });
            vm.runInContext(fs.readFileSync(process.argv[1], 'utf8'), context);
            process.stdout.write(context.window.ControlTime.format('2026-03-08T15:02:03Z'));
        `, helperPath], { env: { ...process.env, TZ }, encoding: 'utf8' });
        assert.equal(child.status, 0, child.stderr);
        assert.equal(child.stdout, '2026/03/09 00:02:03 JST', TZ);
    }
});

test('missing, invalid, and timezone-ambiguous values are safe placeholders', () => {
    const { window } = createContext();
    for (const input of [null, undefined, '', 'invalid', 'invalidZ', '2026-11-01T00:02:03', '2026-11-01', NaN, Infinity, false, {}]) {
        assert.equal(window.ControlTime.format(input), '—');
    }
});

test('helper can be evaluated again and remains available after enhanced navigation', () => {
    const context = createContext();
    context.window.dispatchEvent(new Event('control:before-page-change'));
    vm.runInContext(source, context);
    assert.equal(context.window.ControlTime.format('2026-10-31T15:02:03Z'), '2026/11/01 00:02:03 JST');
});

test('layout loads the shared helper before navigation and page scripts', () => {
    const layout = fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/Pages/Shared/_Layout.cshtml'), 'utf8');
    assert.match(layout, /<script src="~\/js\/control-time\.js"[^>]*defer><\/script>/);
    assert.ok(layout.indexOf('/js/control-time.js') < layout.indexOf('/js/control-navigation.js'));
    assert.ok(layout.indexOf('/js/control-time.js') < layout.indexOf('RenderSectionAsync("Scripts"'));
});
