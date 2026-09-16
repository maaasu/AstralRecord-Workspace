import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mobEntityType, mobImagePath } from '../AstralRecordWeb/wwwroot/js/mob-viewer.mjs';

test('known Bukkit entity types resolve to local mob image paths', () => {
    assert.equal(mobEntityType(' zombie '), 'ZOMBIE');
    assert.match(mobImagePath('WITHER_SKELETON'), /^https:\/\/minecraft\.wiki\/images\//);
    assert.match(mobImagePath('WITCH'), /^https:\/\/minecraft\.wiki\/images\//);
});

test('entity types cannot form arbitrary image paths', () => {
    for (const value of ['../secret', 'https://example.org/mob', 'ZOMBIE?x=1', '<img>', '', 'UNKNOWN_ENTITY']) {
        assert.equal(mobEntityType(value), null);
        assert.equal(mobImagePath(value), null);
    }
});
