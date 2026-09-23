import { test } from 'node:test';
import assert from 'node:assert/strict';
import { projectNodes, graphBounds, fitCamera, zoomCamera, materialId, nodeState } from '../AstralRecordWeb/wwwroot/js/skilltree-geometry.mjs';

test('all pairwise distances and directions retain the source proportions', () => {
    const source = [{ x: -9, z: 2 }, { x: -7, z: 2 }, { x: 4, z: 10 }, { x: 11, z: -8 }];
    const { nodes, scale } = projectNodes(source);
    for (let i = 0; i < source.length; i++) for (let j = i + 1; j < source.length; j++) {
        assert.equal(nodes[j].px - nodes[i].px, (source[j].x - source[i].x) * scale);
        assert.equal(nodes[j].py - nodes[i].py, (source[j].z - source[i].z) * scale);
    }
    assert.ok(scale >= 108); // The previous viewer used 36 units per block.
    assert.deepEqual(source, [{ x: -9, z: 2 }, { x: -7, z: 2 }, { x: 4, z: 10 }, { x: 11, z: -8 }]);
});

test('50% spacing halves projected node distances', () => {
    const source = [{ x: -3, z: 1 }, { x: 2, z: 1 }, { x: 7, z: 5 }];
    const normal = projectNodes(source);
    const compact = projectNodes(source, .5);
    assert.equal(compact.scale, normal.scale / 2);
    for (let i = 0; i < source.length; i++) {
        assert.equal(compact.nodes[i].px, normal.nodes[i].px / 2);
        assert.equal(compact.nodes[i].py, normal.nodes[i].py / 2);
    }
    assert.ok(graphBounds(compact.nodes).width < graphBounds(normal.nodes).width);
});

test('nearby horizontal, vertical and diagonal nodes leave room for icons and labels', () => {
    const { nodes } = projectNodes([{ x: 0, z: 0 }, { x: .5, z: 0 }, { x: .5, z: .5 }, { x: 1, z: 1 }]);
    for (let i = 0; i < nodes.length; i++) for (let j = i + 1; j < nodes.length; j++) {
        assert.ok(Math.max(Math.abs(nodes[i].px - nodes[j].px), Math.abs(nodes[i].py - nodes[j].py)) >= 200);
    }
});

test('overlapping source coordinates remain coincident without non-finite projection', () => {
    const result = projectNodes([{ x: 2, z: 3 }, { x: 2, z: 3 }, { x: NaN, z: 1 }]);
    assert.equal(result.nodes.length, 2);
    assert.ok(Number.isFinite(result.scale));
    assert.equal(result.nodes[0].px, result.nodes[1].px);
    assert.equal(result.nodes[0].py, result.nodes[1].py);
});

test('full-map camera fits all bounds on both desktop and phone', () => {
    const { nodes } = projectNodes([{ x: -25, z: -50 }, { x: 60, z: 100 }]);
    const bounds = graphBounds(nodes);
    for (const [width, height] of [[900, 690], [330, 520]]) {
        const camera = fitCamera(bounds, width, height);
        assert.ok(bounds.x * camera.zoom + camera.x >= 19.99);
        assert.ok(bounds.y * camera.zoom + camera.y >= 19.99);
        assert.ok((bounds.x + bounds.width) * camera.zoom + camera.x <= width - 19.99);
        assert.ok((bounds.y + bounds.height) * camera.zoom + camera.y <= height - 19.99);
    }
});

test('zoom leaves the pointer location anchored in graph coordinates', () => {
    const camera = { x: 125, y: -210, zoom: .8 };
    const next = zoomCamera(camera, 1.3, 311, 247);
    assert.ok(Math.abs((311 - camera.x) / camera.zoom - (311 - next.x) / next.zoom) < 1e-9);
    assert.ok(Math.abs((247 - camera.y) / camera.zoom - (247 - next.y) / next.zoom) < 1e-9);
});

test('material paths allow Bukkit names but reject URLs and traversal', () => {
    assert.equal(materialId(' NETHER_STAR '), 'nether_star');
    assert.equal(materialId('minecraft:IRON_SWORD'), 'iron_sword');
    for (const bad of ['../secret', 'https://example.org/icon', 'stone?x=1', 'a/b', '<svg>', '', 'a'.repeat(81)]) assert.equal(materialId(bad), null);
});

test('unlocked but unmet nodes are described as inactive instead of active', () => {
    assert.equal(nodeState({ isUnlocked: true, isConditionMet: false }), '解放済み・条件未達のため無効');
    assert.equal(nodeState({ isUnlocked: true, isConditionMet: true }), '解放済み');
    assert.equal(nodeState({ isUnlocked: false, isConditionMet: true }), '未解放');
});
