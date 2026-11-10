import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nodeClassId, treeClasses, defaultTreeClass, inClassScope } from '../AstralRecordWeb/wwwroot/js/skilltree-scope.mjs';

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

test('classes are listed in map order with names and progress', () => {
    assert.deepEqual(treeClasses(nodes), [
        { id: 'swordsman', name: 'ソードマン', total: 2, unlocked: 1 },
        { id: 'hunter', name: 'ハンター', total: 1, unlocked: 0 },
        { id: 'mage', name: 'mage', total: 1, unlocked: 0 },
    ]);
});

test('the current class is the default, otherwise the most progressed class', () => {
    const classes = treeClasses(nodes);
    assert.equal(defaultTreeClass(classes, 'HUNTER'), 'hunter');
    assert.equal(defaultTreeClass(classes, 'paladin'), 'swordsman');
    assert.equal(defaultTreeClass(classes.map(c => ({ ...c, unlocked: 0 })), ''), 'swordsman');
    assert.equal(defaultTreeClass([], 'hunter'), '');
});

test('only shared nodes and the selected class are in scope', () => {
    const shown = nodes.filter(node => inClassScope(node, 'hunter')).map(node => node.nodeId);
    assert.deepEqual(shown, ['root', 'h1']);
});

test('editor data without unlockCondition still groups CP nodes by their fixed CP source', () => {
    const editorNodes = [
        { nodeId: 'root', isUnlocked: true },
        { nodeId: 'cp', pointType: 'CP', cpSourceClassId: 'Swordsman', cpSourceClassName: 'ソードマン' },
        { nodeId: 'named', unlockCondition: { classId: 'mage', classDisplayName: 'メイジ' }, requirementText: '必要クラス: 魔法' },
    ];
    assert.deepEqual(treeClasses(editorNodes).map(entry => [entry.id, entry.name]), [['swordsman', 'ソードマン'], ['mage', 'メイジ']]);
    assert.equal(inClassScope(editorNodes[1], 'mage'), false);
});
