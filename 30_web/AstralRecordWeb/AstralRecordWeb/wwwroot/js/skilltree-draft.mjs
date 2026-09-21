// A client-side preview only. The participating Plugin revalidates this ordered batch before saving it.
const amount = value => Number.isFinite(value) ? value : null;
export function createDraft(state, changes = []) {
    const tree = state.tree ?? { nodes: [], edges: [], rootNodeId: '' };
    const nodes = new Map((tree.nodes ?? []).map(node => [node.nodeId, { ...node, draftAction: null }]));
    const points = { pp: Number.isFinite(state.points?.earnedPp) && Number.isFinite(state.points?.spentPp) ? state.points.earnedPp - state.points.spentPp : amount(state.points?.pp), gold: amount(state.points?.gold), classes: (state.points?.classes ?? []).map(c => ({ ...c, availableCp: Number.isFinite(c.earnedCp) && Number.isFinite(c.spentCp) ? c.earnedCp - c.spentCp : c.availableCp })) };
    const balances = new Map(points.classes.map(c => [c.classId, c]));
    const unlocked = new Set([...nodes.values()].filter(n => n.isUnlocked).map(n => n.nodeId));
    const active = new Set([...nodes.values()].filter(n => n.isUnlocked && (n.isEffectiveUnlocked ?? n.isConditionMet !== false)).map(n => n.nodeId));
    const neighbors = new Map([...nodes.keys()].map(id => [id, []]));
    for (const edge of tree.edges ?? []) {
        neighbors.get(edge.sourceNodeId)?.push(edge.targetNodeId);
        neighbors.get(edge.targetNodeId)?.push(edge.sourceNodeId);
    }
    const connectedAfterRemoval = id => {
        const remaining = new Set([...unlocked].filter(other => other !== id));
        if (!remaining.size) return true;
        if (!remaining.has(tree.rootNodeId)) return false;
        const seen = new Set([tree.rootNodeId]), queue = [tree.rootNodeId];
        for (let i = 0; i < queue.length; i++) for (const next of neighbors.get(queue[i]) ?? [])
            if (remaining.has(next) && !seen.has(next)) { seen.add(next); queue.push(next); }
        return seen.size === remaining.size;
    };
    const check = change => {
        const node = nodes.get(change.nodeId);
        if (!node || !['UNLOCK', 'RELOCK'].includes(change.action)) return '変更するノードを再確認してください。';
        if (node.isConditionMet === false) return '必要なクラス・レベルの条件を満たしていません。';
        if (!Number.isFinite(node.pointCost) || node.pointCost < 0) return 'ノードの費用を確認できません。';
        if (change.action === 'RELOCK') {
            if (!unlocked.has(node.nodeId)) return 'まだ解放されていません。';
            if (!connectedAfterRemoval(node.nodeId)) return 'この解除で経路が途切れます。末端のノードから解除してください。';
            if (!Number.isFinite(state.relockGoldCost) || points.gold === null || points.gold < state.relockGoldCost) return '解除に必要なGoldが不足しているか、残高を確認できません。';
            if (node.pointType === 'CP' && node.pointCost > 0 && !balances.has(node.consumedClassId)) return 'CPの返還先を確認できません。';
            return '';
        }
        if (unlocked.has(node.nodeId)) return 'すでに解放されています。';
        if (!unlocked.size ? node.nodeId !== tree.rootNodeId : !(neighbors.get(node.nodeId) ?? []).some(id => active.has(id))) return '接続するノードを先に解放案へ追加してください。';
        if (node.pointType === 'PP') return points.pp === null || points.pp < node.pointCost ? 'PPが不足しています。' : '';
        if (node.pointType !== 'CP') return 'ポイント種別を確認できません。';
        if (!node.pointCost) return '';
        const sourceId = node.requiresCpSourceSelection ? change.sourceClassId : node.cpSourceClassId;
        if (!sourceId || !(node.cpSources ?? []).some(c => c.classId === sourceId)) return '消費するクラスのCPを選択してください。';
        return !balances.has(sourceId) || balances.get(sourceId).availableCp < node.pointCost ? '選択したクラスのCPが不足しています。' : '';
    };
    let error = '', goldCost = 0;
    const seenChanges = new Set();
    for (const change of changes) {
        if (seenChanges.has(change.nodeId) || changes.length > 512) { error = '変更案を整理してください。'; break; }
        seenChanges.add(change.nodeId);
        error = check(change);
        if (error) break;
        const node = nodes.get(change.nodeId), relock = change.action === 'RELOCK';
        const sourceId = relock ? node.consumedClassId : node.requiresCpSourceSelection ? change.sourceClassId : node.cpSourceClassId;
        if (node.pointType === 'PP') points.pp += (relock ? 1 : -1) * node.pointCost;
        else if (node.pointCost) balances.get(sourceId).availableCp += (relock ? 1 : -1) * node.pointCost;
        if (relock) {
            points.gold -= state.relockGoldCost; goldCost += state.relockGoldCost;
            unlocked.delete(node.nodeId); active.delete(node.nodeId);
        } else { unlocked.add(node.nodeId); active.add(node.nodeId); }
        node.isUnlocked = !relock;
        node.consumedClassId = relock ? null : sourceId;
        node.consumedClassName = relock ? null : balances.get(sourceId)?.className;
        node.draftAction = change.action;
        node.stateText = relock ? '解除予定' : '解放予定';
        for (const candidate of nodes.values()) {
            const remaining = candidate.pointType === 'PP' ? points.pp : balances.get(candidate.consumedClassId)?.availableCp;
            if (unlocked.has(candidate.nodeId) && candidate.isConditionMet !== false
                && (candidate.pointCost === 0 || Number.isFinite(remaining) && remaining >= 0 || candidate.isEffectiveUnlocked === true)) active.add(candidate.nodeId);
        }
    }
    for (const node of nodes.values()) {
        node.canUnlock = !check({ action: 'UNLOCK', nodeId: node.nodeId, sourceClassId: node.requiresCpSourceSelection ? node.cpSources?.find(c => balances.get(c.classId)?.availableCp >= node.pointCost)?.classId : null });
        node.canRelock = !check({ action: 'RELOCK', nodeId: node.nodeId });
        if (!node.draftAction) node.stateText = node.isUnlocked ? nodeStateText(node) : node.canUnlock ? '解放可能' : node.isConditionMet === false ? '未解放・条件不足' : '未解放';
        node.cpSources = (node.cpSources ?? []).map(c => ({ ...c, availableCp: balances.get(c.classId)?.availableCp ?? c.availableCp }));
    }
    return { tree: { ...tree, nodes: [...nodes.values()] }, points: { ...points, pp: points.pp === null ? null : Math.max(0, points.pp), classes: points.classes.map(c => ({ ...c, availableCp: Math.max(0, c.availableCp) })) }, goldCost, error, check };
}
function nodeStateText(node) { return node.isConditionMet === false ? '解放済み・条件不足' : node.isEffectiveUnlocked === false ? '解放済み・効果無効' : '解放済み'; }
export function draftContext(state) {
    return JSON.stringify([state.generationId, state.stateRevision, state.connection?.serverId, state.canEdit,
        state.connection?.status, state.connection?.canEdit, state.relockGoldCost, state.points,
        (state.tree?.nodes ?? []).map(n => [n.nodeId, n.isUnlocked, n.isConditionMet, n.isEffectiveUnlocked, n.pointCost, n.cpSourceClassId, n.consumedClassId])]);
}
export function searchNodes(nodes, query, mode = 'all') {
    const terms = String(query).normalize('NFKC').toLocaleLowerCase('ja-JP').trim().split(/\s+/).filter(Boolean);
    if (!terms.length) return [];
    return nodes.filter(node => {
        const fields = mode === 'name' ? [node.name] : mode === 'effect' ? (node.displayEffects ?? []) : [node.name, ...(node.displayEffects ?? []), ...(node.lore ?? [])];
        const text = fields.join(' ').normalize('NFKC').toLocaleLowerCase('ja-JP');
        return terms.every(term => text.includes(term));
    });
}

export function settleDraft(changes, draftOperationId, result) {
    if (result.status === 'APPLIED' && result.operationId === draftOperationId)
        return { changes: [], needsReview: false };
    return { changes, needsReview: changes.length > 0 };
}
