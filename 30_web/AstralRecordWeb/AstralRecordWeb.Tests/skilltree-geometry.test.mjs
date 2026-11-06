import { test } from 'node:test';
import assert from 'node:assert/strict';
import { projectNodes, graphBounds, fitCamera, zoomCamera, pinchCamera, wheelZoomFactor, nodeVisualScale, nodeArtScale, nodeDetailLevel, materialId, nodeState } from '../AstralRecordWeb/wwwroot/js/skilltree-geometry.mjs';

test('camera enlargement always enlarges nodes while lowering their relative footprint', () => {
    let previousSize = 0, previousRatio = Infinity;
    for (const zoom of [.02, .08, .24, .4, .7, .95, 1, 1.5, 2, 2.5]) {
        const relativeSize = nodeVisualScale(zoom), screenSize = 106 * zoom * relativeSize;
        assert.ok(screenSize > previousSize);
        assert.ok(relativeSize <= previousRatio);
        assert.ok(relativeSize >= 1.08 && relativeSize <= 2.25);
        previousSize = screenSize; previousRatio = relativeSize;
    }
    assert.ok(106 * .4 * nodeVisualScale(.4) >= 70);
});

test('progressive labels retain names at useful overview scales', () => {
    assert.equal(nodeDetailLevel(.1), 'overview');
    assert.equal(nodeDetailLevel(.24), 'compact');
    assert.equal(nodeDetailLevel(.69), 'compact');
    assert.equal(nodeDetailLevel(.7), 'full');
});

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

test('a very close pair stays selectable without stretching the rest of the map', () => {
    const { nodes, scale } = projectNodes([{ x: 0, z: 0 }, { x: .1, z: 0 }, { x: 2, z: 0 }], .7);
    assert.equal(scale, 420);
    const [a, b, c] = nodes;
    for (const zoom of [.1, 1, 2.5]) {
        const visual = nodeVisualScale(zoom);
        const sizeA = nodeArtScale(visual, a.nearest * scale);
        const sizeB = nodeArtScale(visual, b.nearest * scale);
        assert.ok(55 * (sizeA + sizeB) < b.px - a.px);
        assert.equal(nodeArtScale(visual, c.nearest * scale), visual);
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

test('pinch keeps the starting graph point under the moving two-finger midpoint', () => {
    const camera = { x: 125, y: -210, zoom: .8 };
    const start = { x: 300, y: 200 }, current = { x: 340, y: 230 };
    for (const zoom of [.8, 1.3, 2]) {
        const next = pinchCamera(camera, zoom, start, current);
        assert.ok(Math.abs((start.x - camera.x) / camera.zoom - (current.x - next.x) / next.zoom) < 1e-9);
        assert.ok(Math.abs((start.y - camera.y) / camera.zoom - (current.y - next.y) / next.zoom) < 1e-9);
    }
    assert.deepEqual(pinchCamera(camera, .8, start, current), { x: 165, y: -180, zoom: .8 });
});

test('wheel zoom respects small trackpad deltas, browser units and input limits', () => {
    const factor = (deltaY, deltaMode = 0) => wheelZoomFactor({ deltaY, deltaMode }, 600);
    assert.equal(factor(0), 1);
    assert.ok(factor(1) > .997 && factor(1) < 1);
    assert.equal(factor(3, 1), factor(48));
    assert.equal(factor(1, 2), factor(100));
    assert.equal(factor(10000), factor(100));
    assert.ok(Math.abs(factor(25) * factor(-25) - 1) < 1e-9);
    assert.ok(wheelZoomFactor({ deltaY: -1, ctrlKey: true }, 600) > factor(-1));
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
