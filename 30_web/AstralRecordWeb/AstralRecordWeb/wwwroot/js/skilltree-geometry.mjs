// A single scale for both axes preserves every original distance ratio and direction.
export function projectNodes(nodes) {
    const valid = nodes.filter(node => Number.isFinite(node.x) && Number.isFinite(node.z));
    let nearestAxisDistance = Infinity;
    for (let i = 0; i < valid.length; i++) {
        for (let j = i + 1; j < valid.length; j++) {
            const distance = Math.max(Math.abs(valid[i].x - valid[j].x), Math.abs(valid[i].z - valid[j].z));
            if (distance > 0) nearestAxisDistance = Math.min(nearestAxisDistance, distance);
        }
    }
    // Keep the master's geometry and circular node art; reserve room for the expanded labels below it.
    const extent = Math.max(300, ...valid.map(node => nodeLabelLines(node).length * 20 + 170));
    const scale = Math.max(108, extent / nearestAxisDistance);
    return { scale, nodes: valid.map(node => ({ ...node, px: node.x * scale, py: node.z * scale })) };
}

export function graphBounds(nodes) {
    if (!nodes.length) return { x: -100, y: -100, width: 200, height: 200 };
    const minX = Math.min(...nodes.map(node => node.px)) - 150;
    const minY = Math.min(...nodes.map(node => node.py)) - 70;
    return {
        x: minX, y: minY,
        width: Math.max(...nodes.map(node => node.px)) + 150 - minX,
        height: Math.max(...nodes.map(node => node.py + nodeLabelLines(node).length * 20 + 90)) - minY,
    };
}

export function fitCamera(bounds, width, height) {
    const zoom = Math.min(1, Math.max(1, width - 40) / bounds.width, Math.max(1, height - 40) / bounds.height);
    return { zoom, x: width / 2 - (bounds.x + bounds.width / 2) * zoom, y: height / 2 - (bounds.y + bounds.height / 2) * zoom };
}

export function zoomCamera(camera, zoom, anchorX, anchorY) {
    return { zoom, x: anchorX - (anchorX - camera.x) * zoom / camera.zoom, y: anchorY - (anchorY - camera.y) * zoom / camera.zoom };
}

export { materialId } from './minecraft-icons.mjs';

export function nodeState(node) {
    if (node.stateText) return node.stateText;
    if (node.isUnlocked) return node.isConditionMet === false ? '解放済み・条件未達のため無効' : '解放済み';
    if (node.canUnlock === true) return '解放可能';
    if (node.blockedReason) return ({ NOT_CONNECTED: '未解放・隣接ノードが必要', INSUFFICIENT_POINTS: '未解放・ポイント不足', UNLOCK_CONDITION_NOT_MET: '未解放・必要条件未達' })[node.blockedReason] || '条件を確認してください';
    return node.isConditionMet === false ? '未解放・必要条件未達' : '未解放';
}

export function nodeCost(node) {
    return node.costText || `${node.pointCost} ${node.pointType}`;
}

export function nodeLabelLines(node) {
    const wrap = (text, kind) => {
        const chars = Array.from(String(text));
        const lines = [];
        for (let i = 0; i < chars.length; i += 16) lines.push({ text: chars.slice(i, i + 16).join(''), kind });
        return lines;
    };
    return [
        ...wrap(node.name, 'name'),
        ...(node.displayEffects || []).flatMap(text => wrap(text, 'effect')),
        ...wrap(nodeCost(node), 'cost'),
        ...wrap(nodeState(node), 'state'),
    ];
}
