import test from 'node:test';
import assert from 'node:assert/strict';
import { purchaseEstimate } from '../AstralRecordWeb/wwwroot/js/market-purchase.mjs';

test('purchase total uses selected quantity and reports remaining or insufficient Gold', () => {
    assert.deepEqual(purchaseEstimate('120', '3', '10', '1000', 'Gold'), { total: 360n, balance: 640n });
    assert.deepEqual(purchaseEstimate('120', '10', '10', '1000', 'gold'), { total: 1200n, balance: -200n });
});

test('unknown wallet or another currency never produces a misleading remaining balance', () => {
    for (const [gold, currency] of [['', 'Gold'], [null, 'Gold'], ['1000', 'token']]) {
        assert.deepEqual(purchaseEstimate('120', '2', '10', gold, currency), { total: 240n, balance: null });
    }
});

test('amounts above Number precision remain exact, and long overflow is rejected', () => {
    assert.deepEqual(purchaseEstimate('9007199254740993', '1', '1', '9007199254740994', 'Gold'),
        { total: 9007199254740993n, balance: 1n });
    assert.equal(purchaseEstimate('9223372036854775807', '2', '2', '', 'Gold'), null);
});

test('invalid quantities, unavailable stock and zero prices cannot be submitted', () => {
    for (const quantity of ['', '0', '-1', '1.5', '1e2', '11']) {
        assert.equal(purchaseEstimate('120', quantity, '10', '1000', 'Gold'), null);
    }
    assert.equal(purchaseEstimate('0', '1', '1', '1000', 'Gold'), null);
    assert.equal(purchaseEstimate('120', '2', '1', '1000', 'Gold'), null);
});
