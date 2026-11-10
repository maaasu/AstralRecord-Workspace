// The map shows the shared nodes plus one class at a time. Never every class at once.

const conditionClassId = condition => {
    if (!condition || typeof condition !== 'object') return '';
    const value = condition.classId ?? condition.ClassId;
    return typeof value === 'string' ? value.trim().toLowerCase() : '';
};

/** Returns the class a node belongs to, or '' for shared nodes. */
export function nodeClassId(node) {
    return conditionClassId(node?.unlockCondition) || String(node?.cpSourceClassId ?? '').trim().toLowerCase();
}

const requirementClassName = node => {
    const match = /必要クラス[:：]\s*(.+)/.exec(node?.requirementText ?? '');
    return match ? match[1].trim() : '';
};

/** Lists the classes that own nodes, in map order, with progress counts. */
export function treeClasses(nodes) {
    const classes = new Map();
    for (const node of nodes ?? []) {
        const id = nodeClassId(node);
        if (!id) continue;
        if (!classes.has(id)) classes.set(id, { id, name: '', total: 0, unlocked: 0 });
        const entry = classes.get(id);
        entry.total++;
        if (node.isUnlocked) entry.unlocked++;
        entry.name ||= node.unlockCondition?.classDisplayName || node.cpSourceClassName || requirementClassName(node) || node.consumedClassName || '';
    }
    for (const entry of classes.values()) entry.name ||= entry.id;
    return [...classes.values()];
}

/** Picks the preferred class (the current class), then the most progressed one. */
export function defaultTreeClass(classes, preferred) {
    const wanted = String(preferred ?? '').trim().toLowerCase();
    if (classes.some(entry => entry.id === wanted)) return wanted;
    const progressed = [...classes].sort((a, b) => b.unlocked - a.unlocked)[0];
    return progressed?.unlocked ? progressed.id : classes[0]?.id ?? '';
}

/** Whether a node is shown while the given class is selected. */
export function inClassScope(node, classId) {
    const id = nodeClassId(node);
    return !id || id === classId;
}

/** Marks a node from the published definition as browse-only. */
export function readOnlyClassNode(node) {
    return {
        ...node, readOnly: true, isConditionMet: false, isEffectiveUnlocked: false, canUnlock: false, canRelock: false,
        stateText: node.isUnlocked ? '解放済み・就職中のクラスでのみ有効' : '閲覧のみ・就職中のクラスで解放できます',
    };
}

/** Adds one browse-only class to the live tree. Live nodes and edges always win. */
export function mergeClassTree(base, fragment) {
    if (!fragment?.nodes?.length) return base;
    const liveIds = new Set(base.nodes.map(node => node.nodeId));
    const nodes = [...base.nodes, ...fragment.nodes.filter(node => !liveIds.has(node.nodeId)).map(readOnlyClassNode)];
    const ids = new Set(nodes.map(node => node.nodeId));
    const key = edge => `${edge.sourceNodeId}>${edge.targetNodeId}`;
    const seen = new Set((base.edges ?? []).map(key));
    const extra = (fragment.edges ?? []).filter(edge => ids.has(edge.sourceNodeId) && ids.has(edge.targetNodeId) && !seen.has(key(edge)));
    return { ...base, nodes, edges: [...(base.edges ?? []), ...extra] };
}

/** Combines classes on the map with the published class index, keeping the index order. */
export function mergeClassLists(local, remote) {
    const byId = new Map(local.map(entry => [entry.id, entry]));
    const merged = (remote ?? []).map(entry => {
        const id = String(entry.classId ?? '').trim().toLowerCase();
        return byId.get(id) ?? { id, name: entry.className || id, total: entry.nodeCount ?? 0, unlocked: entry.unlockedCount ?? 0 };
    }).filter(entry => entry.id);
    const seen = new Set(merged.map(entry => entry.id));
    return [...merged, ...local.filter(entry => !seen.has(entry.id))];
}
