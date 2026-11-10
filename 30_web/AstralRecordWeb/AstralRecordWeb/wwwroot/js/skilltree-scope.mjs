const normalizeClassId = value => String(value ?? '').trim().toLowerCase();

const conditionClassId = condition => {
    if (!condition || typeof condition !== 'object') return '';
    const value = condition.classId ?? condition.ClassId;
    return typeof value === 'string' ? normalizeClassId(value) : '';
};

/** Returns the class a node belongs to, or '' for shared nodes. */
export function nodeClassId(node) {
    return conditionClassId(node?.unlockCondition) || normalizeClassId(node?.cpSourceClassId);
}

const requirementClassName = node => {
    const match = /必要クラス[:：]\s*(.+)/.exec(node?.requirementText ?? '');
    return match ? match[1].trim() : '';
};

/** A current-class runtime view already contains only CP nodes visible in the game. */
export function inClassScope(node, classIds, isCurrentClass) {
    const id = nodeClassId(node);
    return !id || classIds.has(id) || (isCurrentClass && node.pointType === 'CP' && node.isConditionMet !== false && !node.readOnly);
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

/** The saved class list determines eligibility. Visible nodes can supply a class name. */
export function selectableTreeClasses(nodes, savedClasses, currentClassId, currentClassName) {
    const localNames = new Map();
    for (const node of nodes ?? []) {
        const id = nodeClassId(node);
        if (!id || localNames.has(id)) continue;
        const name = node.unlockCondition?.classDisplayName || node.cpSourceClassName || requirementClassName(node) || node.consumedClassName;
        if (name) localNames.set(id, name);
    }
    const current = normalizeClassId(currentClassId);
    const classes = new Map();
    for (const entry of savedClasses ?? []) {
        const id = normalizeClassId(entry.classId);
        if (!id) continue;
        const scopeIds = new Set([id, ...(entry.scopeClassIds ?? []).map(normalizeClassId)]);
        classes.set(id, { id, scopeIds, name: entry.className || localNames.get(id) || id });
    }
    if (current && !classes.has(current)) {
        classes.set(current, {
            id: current, scopeIds: new Set([current]), name: localNames.get(current) || currentClassName || '就職中のクラス',
        });
    }
    return [...classes.values()];
}

export function classPointUsage(points) {
    const usage = new Map();
    for (const { classId, spentCp, earnedCp } of points ?? []) {
        const id = normalizeClassId(classId);
        if (!id || !Number.isInteger(spentCp) || spentCp < 0 || !Number.isInteger(earnedCp) || earnedCp < 0) continue;
        const percent = earnedCp ? Math.min(100, spentCp / earnedCp * 100) : spentCp > 0 ? 100 : 0;
        usage.set(id, { spentCp, earnedCp, percent });
    }
    return usage;
}
