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
