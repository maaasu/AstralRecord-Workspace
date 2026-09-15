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
    // Node art and its label occupy at most 152 × 160 units; leave breathing room.
    const scale = Math.max(108, 200 / nearestAxisDistance);
    return { scale, nodes: valid.map(node => ({ ...node, px: node.x * scale, py: node.z * scale })) };
}

export function graphBounds(nodes) {
    if (!nodes.length) return { x: -100, y: -100, width: 200, height: 200 };
    const minX = Math.min(...nodes.map(node => node.px)) - 100;
    const minY = Math.min(...nodes.map(node => node.py)) - 90;
    return {
        x: minX, y: minY,
        width: Math.max(...nodes.map(node => node.px)) + 100 - minX,
        height: Math.max(...nodes.map(node => node.py)) + 130 - minY,
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
    if (node.isUnlocked) return node.isConditionMet === false ? '解放済み・条件未達のため無効' : '解放済み';
    return node.isConditionMet === false ? '未解放・必要条件未達' : '未解放';
}
