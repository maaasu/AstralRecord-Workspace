const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = name => fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js', name), 'utf8');

function fixture(loadTime = true) {
    class Element extends EventTarget {
        constructor() { super(); this.children = []; this.textContent = ''; this.value = ''; }
        append(child) { this.children.push(child); }
        replaceChildren(...children) { this.children = children; }
        insertRow() { const row = new Element(); this.append(row); return row; }
        insertCell() { const cell = new Element(); this.append(cell); return cell; }
        add(option) { this.append(option); }
        setAttribute() {}
    }
    const requests = [];
    let elements;
    const window = new EventTarget();
    const context = vm.createContext({
        window, AbortController,
        Option: class { constructor(text, value) { this.textContent = text; this.value = value; } },
        document: { getElementById: id => elements[id], createElement: () => new Element() },
        fetch: async (url, options) => {
            requests.push({ url, options });
            let data;
            if (url.includes('/backup-profiles?')) data = [];
            else if (url.includes('/backups?')) data = { rows: [
                { backupId: 'one', createdAtUtc: '2026-10-31T15:02:03Z', state: 'Complete', sizeBytes: 123, protected: false },
                { backupId: 'two', createdAtUtc: null, state: 'Complete', sizeBytes: 456, protected: true },
                { backupId: 'three', createdAtUtc: 'invalid', state: 'Complete', sizeBytes: 789, protected: false }
            ], nextCursor: null };
            else data = [
                { transferId: '<transfer>', status: 'Expired', committedOffset: 1, sizeBytes: 2, expiresAtUtc: '2026-10-31T08:02:03-07:00' },
                { transferId: 'missing', status: 'Expired', committedOffset: 1, sizeBytes: 2, expiresAtUtc: null },
                { transferId: 'invalid', status: 'Expired', committedOffset: 1, sizeBytes: 2, expiresAtUtc: 'invalid' }
            ];
            return { ok: true, json: async () => data };
        }
    });
    if (loadTime) vm.runInContext(script('control-time.js'), context);
    return {
        requests, window,
        async open(mode) {
            window.dispatchEvent(new Event('control:before-page-change'));
            elements = Object.fromEntries(['p2-status', 'p2-result', 'backup-profile', 'backup-entries', 'backups-next', 'transfer-entries', 'transfers-refresh'].map(id => [id, new Element()]));
            elements['p2-control'] = {
                dataset: { mode, enabled: 'true', serverId: 'server' },
                querySelector: () => ({ value: 'csrf' }), querySelectorAll: () => []
            };
            vm.runInContext(script('p2-control.js'), context);
            await new Promise(setImmediate);
            assert.equal(elements['p2-status'].textContent, '');
            return elements;
        }
    };
}

for (const loadTime of [true, false]) test(`backup and transfer timestamps stay JST during navigation (shared asset=${loadTime})`, async () => {
    const browser = fixture(loadTime);
    for (let visit = 0; visit < 2; visit++) {
        const backups = await browser.open('backups');
        const rows = backups['backup-entries'].children;
        assert.equal(rows[0].children[1].textContent, '2026/11/01 00:02:03 JST');
        assert.equal(rows[1].children[1].textContent, '—');
        assert.equal(rows[2].children[1].textContent, '—');
        assert.equal(rows[0].children[3].textContent, '123 bytes');
        assert.equal(rows[1].children[4].textContent, '保護');

        const transfers = await browser.open('transfers');
        const transferRows = transfers['transfer-entries'].children;
        assert.equal(transferRows[0].children[3].textContent, '2026/11/01 00:02:03 JST');
        assert.equal(transferRows[1].children[3].textContent, '—');
        assert.equal(transferRows[2].children[3].textContent, '—');
        assert.equal(transferRows[0].children[0].textContent, '<transfer>');
        assert.equal(transferRows[0].children[2].textContent, '1 / 2');
    }
    assert.ok(browser.requests.every(({ options }) => options.method === 'GET'));
    browser.window.dispatchEvent(new Event('control:before-page-change'));
});
