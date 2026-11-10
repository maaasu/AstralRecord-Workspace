import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nodeClassId, inClassScope, readOnlyClassNode, mergeClassTree, selectableTreeClasses, classPointUsage } from '../AstralRecordWeb/wwwroot/js/skilltree-scope.mjs';

const nodes = [
    { nodeId: 'root', isUnlocked: true },
    { nodeId: 's1', unlockCondition: { classId: 'Swordsman' }, requirementText: '必要クラス: ソードマン', isUnlocked: true },
    { nodeId: 's2', unlockCondition: { classId: 'swordsman' }, requirementText: '必要レベル: 10\n必要クラス: ソードマン' },
    { nodeId: 'h1', unlockCondition: { classId: 'hunter' }, cpSourceClassName: 'ハンター' },
    { nodeId: 'm1', cpSourceClassId: 'mage' },
];

test('node class comes from the unlock condition, then the fixed CP source', () => {
    assert.equal(nodeClassId(nodes[0]), '');
    assert.equal(nodeClassId(nodes[1]), 'swordsman');
    assert.equal(nodeClassId(nodes[4]), 'mage');
    assert.equal(nodeClassId({ unlockCondition: { classId: null } }), '');
});

test('an advanced class with no own nodes shows shared and prerequisite branches', () => {
    const [entry] = selectableTreeClasses(nodes, [{
        classId: ' Paladin ', className: 'パラディン', scopeClassIds: ['Swordsman', 'Adventurer'],
    }], 'paladin');
    const shown = nodes.filter(node => inClassScope(node, entry.scopeIds, true)).map(node => node.nodeId);
    assert.deepEqual(shown, ['root', 's1', 's2']);
    assert.deepEqual([...entry.scopeIds], ['paladin', 'swordsman', 'adventurer']);
});

test('editor data without unlockCondition still groups CP nodes by their fixed CP source', () => {
    const editorNodes = [
        { nodeId: 'root', isUnlocked: true },
        { nodeId: 'cp', pointType: 'CP', cpSourceClassId: 'Swordsman', cpSourceClassName: 'ソードマン' },
        { nodeId: 'named', unlockCondition: { classId: 'mage', classDisplayName: 'メイジ' }, requirementText: '必要クラス: 魔法' },
    ];
    assert.deepEqual(selectableTreeClasses(editorNodes, [{ classId: 'swordsman' }, { classId: 'mage' }])
        .map(entry => [entry.id, entry.name]), [['swordsman', 'ソードマン'], ['mage', 'メイジ']]);
    assert.equal(inClassScope(editorNodes[1], new Set(['mage']), false), false);
    assert.equal(inClassScope(editorNodes[1], new Set(['paladin']), true), true);
    assert.equal(inClassScope(readOnlyClassNode(editorNodes[1]), new Set(['paladin']), true), false);
    assert.equal(inClassScope({ ...editorNodes[1], isConditionMet: false }, new Set(['paladin']), true), false);
});

test('a published class merges in as browse-only without replacing live nodes or edges', () => {
    const live = { rootNodeId: 'root', nodes: [{ nodeId: 'root', isUnlocked: true }, { nodeId: 's1', cpSourceClassId: 'swordsman', canUnlock: true }],
        edges: [{ sourceNodeId: 'root', targetNodeId: 's1' }] };
    const fragment = {
        nodes: [{ nodeId: 'h1', unlockCondition: { classId: 'hunter' }, isUnlocked: true }, { nodeId: 's1', unlockCondition: { classId: 'hunter' } }],
        edges: [{ sourceNodeId: 'root', targetNodeId: 'h1' }, { sourceNodeId: 'h1', targetNodeId: 'gone' }, { sourceNodeId: 'root', targetNodeId: 's1' }],
    };
    const merged = mergeClassTree(live, fragment);
    assert.deepEqual(merged.nodes.map(node => node.nodeId), ['root', 's1', 'h1']);
    assert.equal(merged.nodes[1].canUnlock, true, 'live node is kept as is');
    const hunter = merged.nodes[2];
    assert.equal(hunter.readOnly, true);
    assert.equal(hunter.canUnlock, false);
    assert.equal(hunter.isConditionMet, false);
    assert.equal(hunter.stateText, '解放済み・就職中のクラスでのみ有効');
    assert.deepEqual(merged.edges.map(edge => `${edge.sourceNodeId}>${edge.targetNodeId}`), ['root>s1', 'root>h1']);
    assert.equal(mergeClassTree(live, null), live);
    assert.equal(readOnlyClassNode({ nodeId: 'x', isUnlocked: false }).stateText, '閲覧のみ・就職中のクラスで解放できます');
});

test('only class history and the current class are selectable regardless of node progress', () => {
    const saved = [{ classId: 'Hunter', className: 'ハンター', nodeCount: 10, unlockedCount: 0 }];
    assert.deepEqual(selectableTreeClasses(nodes, saved, 'Paladin', 'パラディン'), [
        { id: 'hunter', scopeIds: new Set(['hunter']), name: 'ハンター' },
        { id: 'paladin', scopeIds: new Set(['paladin']), name: 'パラディン' },
    ]);
    assert.deepEqual(selectableTreeClasses(nodes, undefined, 'Paladin').map(entry => entry.id), ['paladin']);
});

test('class bars compare spent CP with earned CP and clamp only the fill', () => {
    const points = classPointUsage([
        { classId: ' SwordSman ', spentCp: 3, earnedCp: 12 },
        { classId: 'mage', spentCp: 6, earnedCp: 4 },
        { classId: 'hunter', spentCp: 0, earnedCp: 0 },
        { classId: 'priest', spentCp: 2, earnedCp: 0 },
        { classId: 'paladin', availableCp: 10 },
    ]);
    assert.deepEqual([...points], [
        ['swordsman', { spentCp: 3, earnedCp: 12, percent: 25 }],
        ['mage', { spentCp: 6, earnedCp: 4, percent: 100 }],
        ['hunter', { spentCp: 0, earnedCp: 0, percent: 0 }],
        ['priest', { spentCp: 2, earnedCp: 0, percent: 100 }],
    ]);
});
