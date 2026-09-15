import { test } from 'node:test';
import assert from 'node:assert/strict';

test('icons use the bundled image first and share concurrent requests', async () => {
    const calls = [];
    const oldImage = globalThis.Image;
    globalThis.Image = class {
        naturalWidth = 128;
        set src(url) { calls.push(url); queueMicrotask(() => this.onload?.()); }
        removeAttribute() {}
    };
    try {
        const { loadIcon } = await import('../AstralRecordWeb/wwwroot/js/minecraft-icons.mjs?bundled');
        const first = loadIcon('NETHER_STAR'), second = loadIcon('minecraft:nether_star');
        assert.equal(first, second);
        assert.equal(await first, '/images/minecraft/nether_star.png');
        assert.deepEqual(calls, ['/images/minecraft/nether_star.png']);
        assert.equal(await loadIcon('../unsafe'), null);
        assert.equal(calls.length, 1);
    } finally { globalThis.Image = oldImage; }
});

test('missing local and item images fall back to a block texture', async () => {
    const calls = [];
    const oldImage = globalThis.Image;
    globalThis.Image = class {
        naturalWidth = 128;
        set src(url) { calls.push(url); queueMicrotask(() => url.includes('/block/') ? this.onload?.() : this.onerror?.()); }
        removeAttribute() {}
    };
    try {
        const { loadIcon } = await import('../AstralRecordWeb/wwwroot/js/minecraft-icons.mjs?block');
        assert.equal(await loadIcon('STONE'), 'https://assets.mcasset.cloud/1.21.11/assets/minecraft/textures/block/stone.png');
        assert.equal(calls.length, 3);
    } finally { globalThis.Image = oldImage; }
});

test('a missing material resolves without substituting another item', async () => {
    const oldImage = globalThis.Image;
    globalThis.Image = class {
        set src(url) { queueMicrotask(() => this.onerror?.()); }
        removeAttribute() {}
    };
    try {
        const { loadIcon } = await import('../AstralRecordWeb/wwwroot/js/minecraft-icons.mjs?missing');
        assert.equal(await loadIcon('unknown_material'), null);
    } finally { globalThis.Image = oldImage; }
});
