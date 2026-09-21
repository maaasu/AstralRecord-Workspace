import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDraft, searchNodes, draftContext, settleDraft } from '../AstralRecordWeb/wwwroot/js/skilltree-draft.mjs';

const node = (nodeId, values = {}) => ({ nodeId, name: nodeId, pointType: 'PP', pointCost: 3, isConditionMet: true, displayEffects: [], ...values });
const state = () => ({ generationId: 'generation', stateRevision: 1, canEdit: true, relockGoldCost: 100,
    connection: { status: 'online', serverId: 'one', canEdit: true },
    points: { pp: 10, earnedPp: 10, spentPp: 0, gold: 500, classes: [{ classId: 'swordsman', className: '剣士', availableCp: 8, earnedCp: 8, spentCp: 0 }] },
    tree: { rootNodeId: 'root', nodes: [node('root', { isUnlocked: true, isEffectiveUnlocked: true, pointCost: 0 }), node('a'), node('b', { pointType: 'CP', pointCost: 4, cpSourceClassId: 'swordsman', cpSources: [{ classId: 'swordsman', availableCp: 8 }] })],
        edges: [{ sourceNodeId: 'root', targetNodeId: 'a' }, { sourceNodeId: 'a', targetNodeId: 'b' }] } });
test('ordered multi-node draft uses preceding unlock and keeps confirmed state unchanged', () => {
    const before = state(), original = structuredClone(before);
    const draft = createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }, { action: 'UNLOCK', nodeId: 'b' }]);
    assert.equal(draft.error, ''); assert.equal(draft.points.pp, 7); assert.equal(draft.points.classes[0].availableCp, 4);
    assert.equal(draft.tree.nodes.find(n => n.nodeId === 'b').draftAction, 'UNLOCK'); assert.deepEqual(before, original);
});
test('rejects disconnected, duplicate, missing-class, condition and insufficient funds', () => {
    const before = state();
    assert.ok(createDraft(before, [{ action: 'UNLOCK', nodeId: 'b' }]).error);
    assert.ok(createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }, { action: 'UNLOCK', nodeId: 'a' }]).error);
    before.tree.nodes[1].isConditionMet = false;
    assert.ok(createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }]).error);
    before.tree.nodes[1].isConditionMet = true; before.points.pp = 0; before.points.earnedPp = 0;
    assert.ok(createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }]).error);
});
test('relock preserves root connectivity and totals Gold across all changes', () => {
    const before = state(); before.tree.nodes[1].isUnlocked = true; before.tree.nodes[1].isEffectiveUnlocked = true;
    before.points.pp = 7; before.points.spentPp = 3;
    assert.ok(createDraft(before, [{ action: 'RELOCK', nodeId: 'root' }]).error);
    const draft = createDraft(before, [{ action: 'RELOCK', nodeId: 'a' }, { action: 'RELOCK', nodeId: 'root' }]);
    assert.equal(draft.error, ''); assert.equal(draft.points.pp, 10); assert.equal(draft.goldCost, 200); assert.equal(draft.points.gold, 300);
    before.points.gold = 150;
    assert.ok(createDraft(before, [{ action: 'RELOCK', nodeId: 'a' }, { action: 'RELOCK', nodeId: 'root' }]).error);
});
test('return first repays overspent points instead of inflating available balance', () => {
    const before = state(); before.tree.nodes[1].isUnlocked = true; before.tree.nodes[1].pointCost = 3;
    before.points.earnedPp = 2; before.points.spentPp = 5; before.points.pp = 0;
    const draft = createDraft(before, [{ action: 'RELOCK', nodeId: 'a' }]);
    assert.equal(draft.error, ''); assert.equal(draft.points.pp, 0);
});
test('CP source selection and refunds stay with the actual chosen class', () => {
    const before = state(); before.tree.nodes[1] = node('a', { pointType: 'CP', requiresCpSourceSelection: true, cpSources: [{ classId: 'swordsman', availableCp: 8 }] });
    assert.ok(createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }]).error);
    const draft = createDraft(before, [{ action: 'UNLOCK', nodeId: 'a', sourceClassId: 'swordsman' }]);
    assert.equal(draft.error, ''); assert.equal(draft.points.classes[0].availableCp, 5); assert.equal(draft.tree.nodes[1].consumedClassName, '剣士');
});
test('search finds all matching names, stats and granted skills without internal IDs', () => {
    const nodes = [node('hidden_identifier', { name: '生命の器', displayEffects: ['最大HP +10', 'スキル：ヒール'] }), node('speed', { name: '疾風', displayEffects: ['移動速度 +5%'] })];
    assert.equal(searchNodes(nodes, '最大ＨＰ').length, 1); assert.equal(searchNodes(nodes, 'ヒール', 'effect').length, 1);
    assert.equal(searchNodes(nodes, '生命 HP').length, 1); assert.equal(searchNodes(nodes, 'hidden_identifier').length, 0);
    assert.equal(searchNodes(nodes, 'HP', 'name').length, 0);
});
test('draft context changes for balances/conditions but not observation timestamps', () => {
    const before = state(), unchanged = structuredClone(before); unchanged.connection.observedAtUtc = 'later';
    assert.equal(draftContext(before), draftContext(unchanged)); unchanged.points.gold--;
    assert.notEqual(draftContext(before), draftContext(unchanged));
});

test('another tab completing an operation retains this tabs unsent draft for review', () => {
    const changes = [{ action: 'UNLOCK', nodeId: 'a' }];
    assert.deepEqual(settleDraft(changes, 'mine', { operationId: 'mine', status: 'APPLIED' }), { changes: [], needsReview: false });
    assert.deepEqual(settleDraft(changes, null, { operationId: 'another-tab', status: 'APPLIED' }), { changes, needsReview: true });
    assert.deepEqual(settleDraft(changes, 'mine', { operationId: 'mine', status: 'CANCELED' }), { changes, needsReview: true });
});

test('zero-cost PP unlock stays possible with inactive overspent allocations', () => {
    const before = state(); before.points.pp = 0; before.points.earnedPp = 2; before.points.spentPp = 5;
    before.tree.nodes[1].pointCost = 0;
    before.tree.nodes.push(node('debt', { pointCost: 5, isUnlocked: true, isEffectiveUnlocked: false }));
    before.tree.edges.push({ sourceNodeId: 'root', targetNodeId: 'debt' });
    const draft = createDraft(before, [{ action: 'UNLOCK', nodeId: 'a' }]);
    assert.equal(draft.error, ''); assert.equal(draft.points.pp, 0);
});
