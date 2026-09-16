import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { resolve } from 'node:path';
import { mobEntityType, mobImagePath } from '../AstralRecordWeb/wwwroot/js/mob-viewer.mjs';

test('known Bukkit entity types resolve to local mob image paths', () => {
    assert.equal(mobEntityType(' zombie '), 'ZOMBIE');
    assert.equal(mobEntityType('parched'), 'PARCHED');
    assert.equal(mobImagePath('PARCHED'), '/images/mobs/parched.png');
    assert.equal(mobImagePath('WITHER_SKELETON'), '/images/mobs/wither_skeleton.png');
    assert.equal(mobImagePath('WITCH'), '/images/mobs/witch.png');
});

test('every current enemy and boss image path is a bundled PNG', () => {
    for (const type of ['BLAZE', 'BREEZE', 'CAMEL', 'ENDER_DRAGON', 'EVOKER', 'HUSK', 'IRON_GOLEM', 'PARCHED', 'PARROT', 'PIG', 'PIGLIN', 'SHEEP', 'SKELETON', 'SPIDER', 'TURTLE', 'WITCH', 'WITHER_SKELETON', 'WOLF', 'ZOMBIE']) {
        const imagePath = mobImagePath(type);
        assert.ok(imagePath, `${type} has an image path`);
        assert.ok(existsSync(resolve('30_web/AstralRecordWeb/AstralRecordWeb/wwwroot', `.${imagePath}`)), `${type} image exists`);
    }
});

test('entity types cannot form arbitrary image paths', () => {
    for (const value of ['../secret', 'https://example.org/mob', 'ZOMBIE?x=1', '<img>', '', 'UNKNOWN_ENTITY']) {
        assert.equal(mobEntityType(value), null);
        assert.equal(mobImagePath(value), null);
    }
});
