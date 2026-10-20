const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../../src/AstralRecordControl.Web/wwwroot/js/p2-control.js'), 'utf8');
const Sha = require('../../src/AstralRecordControl.Web/wwwroot/js/p2-control.js');

test('browser SHA-256 matches independent crypto for empty, multibyte, block and chunk boundaries', () => {
    for (const size of [0, 1, 55, 56, 63, 64, 65, 127, 128, 129, 1024 * 1024]) {
        const bytes = crypto.randomBytes(size), expected = crypto.createHash('sha256').update(bytes).digest('hex');
        for (const chunk of [1, 37, 64, 4096, 4 * 1024 * 1024]) {
            const hash = new Sha();
            for (let offset = 0; offset < size; offset += chunk) hash.update(bytes.subarray(offset, offset + chunk));
            assert.equal(hash.hex(), expected, `size=${size} chunk=${chunk}`);
        }
    }
});

test('classic script can be evaluated repeatedly without redeclaring ControlSha256', () => {
    const context = vm.createContext({ Uint8Array, Uint32Array, DataView });
    vm.runInContext(script, context);
    vm.runInContext(script, context);
});
