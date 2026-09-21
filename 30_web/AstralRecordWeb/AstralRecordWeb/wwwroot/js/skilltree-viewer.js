import { projectNodes, graphBounds, fitCamera, zoomCamera, nodeState, nodeCost, nodeLabelLines } from './skilltree-geometry.mjs';
import { loadIcon } from './minecraft-icons.mjs';

// Selection never changes player state. The editor submits explicit server-validated operations.
const ns = 'http://www.w3.org/2000/svg';
const make = (tag, attrs = {}, text) => {
    const element = document.createElementNS(ns, tag);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (text !== undefined) element.textContent = text;
    return element;
};
export function initializeViewer(viewer, viewState) {
    let tree;
    try { tree = JSON.parse(viewer.querySelector('[data-tree-json]').textContent); } catch { return; }
    if (!Array.isArray(tree.nodes) || !tree.nodes.length) return;
    const projected = projectNodes(tree.nodes);
    const nodes = new Map(projected.nodes.map(node => [node.nodeId, node]));
    if (!nodes.size) return;
    const bounds = graphBounds(projected.nodes);
    const viewport = viewer.querySelector('.ar-tree-viewport');
    const svg = viewer.querySelector('.ar-tree-canvas');
    const minimap = viewer.querySelector('[data-tree-minimap]');
    svg.replaceChildren(); minimap.replaceChildren();
    const graph = make('g');
    svg.append(graph);
    const groups = new Map(), links = [], neighbors = new Map([...nodes.keys()].map(id => [id, []]));
    let camera = { zoom: .9, x: 0, y: 0 }, selectedId, drag = null, dragged = false, frame = 0;
    let previousWidth = 0, previousHeight = 0;
    const root = nodes.get(tree.rootNodeId) ?? projected.nodes.find(node => node.isUnlocked) ?? projected.nodes[0];
    const minZoom = () => Math.min(.08, fitCamera(bounds, viewport.clientWidth, viewport.clientHeight).zoom);
    minimap.setAttribute('viewBox', `${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`);
    for (const edge of tree.edges ?? []) {
        const source = nodes.get(edge.sourceNodeId), target = nodes.get(edge.targetNodeId);
        if (!source || !target) continue;
        neighbors.get(source.nodeId).push(target);
        neighbors.get(target.nodeId).push(source);
        const dx = target.px - source.px, dy = target.py - source.py, length = Math.hypot(dx, dy);
        if (!length) continue;
        const inset = Math.min(52, length / 2);
        const line = make('line', {
            x1: source.px + dx / length * inset, y1: source.py + dy / length * inset,
            x2: target.px - dx / length * inset, y2: target.py - dy / length * inset,
            class: `ar-tree-edge${source.isUnlocked && target.isUnlocked ? ' is-unlocked' : ''}`,
            'vector-effect': 'non-scaling-stroke',
        });
        graph.append(line);
        links.push({ line, source: source.nodeId, target: target.nodeId });
        minimap.append(make('line', { x1: source.px, y1: source.py, x2: target.px, y2: target.py, class: 'ar-tree-mini-edge' }));
    }
    const miniRadius = Math.max(bounds.width, bounds.height) / 110;
    const miniNodes = new Map();
    for (const node of nodes.values()) {
        const point = make('circle', { cx: node.px, cy: node.py, r: miniRadius, class: `ar-tree-mini-node${node.isUnlocked ? ' is-unlocked' : ''}` });
        minimap.append(point); miniNodes.set(node.nodeId, point);
    }
    const miniViewport = make('rect', { class: 'ar-tree-mini-viewport' });
    minimap.append(miniViewport);
    const paint = () => {
        graph.setAttribute('transform', `translate(${camera.x},${camera.y}) scale(${camera.zoom})`);
        viewer.dataset.overview = String(camera.zoom < .4);
        viewer.querySelector('[data-tree-zoom-label]').textContent = `${Math.round(camera.zoom * 100)}%`;
        const left = Math.max(bounds.x, -camera.x / camera.zoom);
        const top = Math.max(bounds.y, -camera.y / camera.zoom);
        const right = Math.min(bounds.x + bounds.width, (viewport.clientWidth - camera.x) / camera.zoom);
        const bottom = Math.min(bounds.y + bounds.height, (viewport.clientHeight - camera.y) / camera.zoom);
        for (const [key, value] of Object.entries({ x: left, y: top, width: Math.max(0, right - left), height: Math.max(0, bottom - top) })) miniViewport.setAttribute(key, value);
        frame = 0;
    };
    const draw = () => { if (!frame) frame = requestAnimationFrame(paint); };
    const focusNode = (node, readable = true) => {
        if (readable) camera.zoom = .95;
        camera.x = viewport.clientWidth / 2 - node.px * camera.zoom;
        camera.y = viewport.clientHeight / 2 - node.py * camera.zoom;
        draw();
    };
    const showDetails = (node, announce = true) => {
        selectedId = node.nodeId;
        if (announce) viewer.classList.remove('is-detail-hidden');
        groups.forEach((g, id) => {
            g.setAttribute('aria-pressed', String(id === selectedId));
            g.classList.toggle('is-neighbor', neighbors.get(selectedId).some(n => n.nodeId === id));
        });
        links.forEach(edge => edge.line.classList.toggle('is-connected', edge.source === selectedId || edge.target === selectedId));
        miniNodes.forEach((point, id) => point.classList.toggle('is-selected', id === selectedId));
        const detail = viewer.querySelector('.ar-tree-detail');
        detail.dataset.state = node.isUnlocked ? 'unlocked' : 'locked';
        detail.dataset.pointType = node.pointType;
        viewer.querySelector('[data-node-name]').textContent = node.name;
        viewer.querySelector('[data-node-id]').textContent = '';
        viewer.querySelector('[data-node-type]').textContent = node.pointType === 'CP' ? 'クラスの成長 · CP' : 'プレイヤーの成長 · PP';
        viewer.querySelector('[data-node-state]').textContent = nodeState(node);
        viewer.querySelector('[data-node-state]').classList.toggle('ar-condition-unmet', node.isConditionMet === false);
        viewer.querySelector('[data-node-requirement]').textContent = node.requirementText || 'クラス・プレイヤーレベルの指定条件なし';
        viewer.querySelector('[data-node-requirement]').classList.toggle('ar-condition-unmet', node.isConditionMet === false);
        viewer.querySelector('[data-node-cost]').textContent = nodeCost(node);
        const lore = viewer.querySelector('[data-node-lore]');
        lore.textContent = (node.lore ?? []).join('\n'); lore.hidden = !lore.textContent;
        const effects = viewer.querySelector('[data-node-effects]'); effects.replaceChildren();
        for (const text of node.displayEffects?.length ? node.displayEffects : ['追加効果の指定はありません']) {
            const li = document.createElement('li'); li.textContent = text; effects.append(li);
        }
        const connections = viewer.querySelector('[data-node-connections]'); connections.replaceChildren();
        for (const other of neighbors.get(node.nodeId)) {
            const button = document.createElement('button'); button.type = 'button';
            button.textContent = other.name;
            button.addEventListener('click', () => { showDetails(other); focusNode(other); });
            connections.append(button);
        }
        if (!connections.childElementCount) connections.textContent = '表示中の接続ノードはありません';
        const icon = viewer.querySelector('[data-node-icon]'), fallback = viewer.querySelector('[data-node-icon-fallback]');
        icon.hidden = true; fallback.hidden = false;
        icon.removeAttribute('src');
        loadIcon(node.icon).then(url => {
            if (selectedId !== node.nodeId || !url) return;
            icon.src = url; icon.hidden = false; fallback.hidden = true;
        });
        if (announce) viewer.querySelector('[data-tree-announcement]').textContent = `${node.name}。${nodeState(node)}。${node.pointCost} ${node.pointType}`;
        viewer.dispatchEvent(new CustomEvent('skilltree:select', { bubbles: true, detail: node }));
    };
    for (const node of nodes.values()) {
        const isRoot = node.nodeId === tree.rootNodeId;
        const g = make('g', {
            class: `ar-tree-node${node.isUnlocked ? ' is-unlocked' : ''}${node.canUnlock ? ' is-available' : ''}${isRoot ? ' is-root' : ''}${node.isConditionMet === false ? ' is-unmet' : ''}`,
            transform: `translate(${node.px},${node.py})`, role: 'button', tabindex: 0,
            'aria-label': `${node.name}、${nodeState(node)}`, 'aria-pressed': 'false', 'data-point-type': node.pointType,
        });
        const labelLines = nodeLabelLines(node);
        g.append(make('circle', { r: 53, class: 'ar-tree-node-aura', 'aria-hidden': 'true' }));
        g.append(make('circle', { r: 48, class: 'ar-tree-node-runes', 'aria-hidden': 'true' }));
        g.append(make('path', { d: 'M-24-40 L24-40 40-24 40 24 24 40-24 40-40 24-40-24Z', class: 'ar-tree-node-frame' }));
        g.append(make('circle', { r: 34, class: 'ar-tree-node-core' }));
        const fallback = make('text', { y: 10, class: 'ar-tree-symbol', 'aria-hidden': 'true' }, '✧');
        g.append(fallback);
        const icon = make('image', { x: -24, y: -24, width: 48, height: 48, class: 'ar-tree-node-icon', 'aria-hidden': 'true' });
        loadIcon(node.icon).then(url => {
            if (!url) return;
            icon.setAttribute('href', url); g.append(icon); fallback.remove();
        });
        g.append(make('path', { d: 'M0-55 L5-48 0-41-5-48Z', class: 'ar-tree-node-gem', 'aria-hidden': 'true' }));
        g.append(make('circle', { cx: 31, cy: 31, r: 10, class: 'ar-tree-node-status' }));
        g.append(make('text', { x: 31, y: 35, class: 'ar-tree-node-status-mark', 'aria-hidden': 'true' }, node.isUnlocked ? '✓' : node.canUnlock ? '＋' : '·'));
        const label = make('g', { class: 'ar-tree-node-label', 'aria-hidden': 'true' });
        label.append(make('rect', { x: -128, y: 61, width: 256, height: labelLines.length * 20 + 12, rx: 4 }));
        labelLines.forEach((line, index) => label.append(make('text', { y: 81 + index * 20, class: `ar-tree-label-${line.kind}` }, line.text)));
        g.append(label);
        g.append(make('title', {}, `${node.name}\n${nodeState(node)}\n${node.requirementText ?? ''}`));
        g.addEventListener('click', () => {
            if (dragged) return;
            const wasOverview = camera.zoom < .4;
            showDetails(node);
            viewer.classList.remove('is-detail-hidden');
            if (wasOverview) focusNode(node);
        });
        g.addEventListener('keydown', event => {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); showDetails(node); }
        });
        g.addEventListener('focus', () => { if (g.matches(':focus-visible')) focusNode(node, camera.zoom < .4); });
        graph.append(g); groups.set(node.nodeId, g);
    }
    const zoomBy = (factor, x = viewport.clientWidth / 2, y = viewport.clientHeight / 2) => {
        camera = zoomCamera(camera, Math.max(minZoom(), Math.min(2, camera.zoom * factor)), x, y); draw();
    };
    viewer.querySelector('[data-tree-zoom="in"]').addEventListener('click', () => zoomBy(1.25));
    viewer.querySelector('[data-tree-zoom="out"]').addEventListener('click', () => zoomBy(1 / 1.25));
    viewer.querySelector('[data-tree-fit]').addEventListener('click', () => { camera = fitCamera(bounds, viewport.clientWidth, viewport.clientHeight); draw(); });
    viewer.querySelector('[data-tree-home]').addEventListener('click', () => focusNode(nodes.get(selectedId) ?? root));
    viewer.querySelector('[data-tree-reset]')?.addEventListener('click', () => focusNode(root));
    viewer.querySelector('[data-tree-detail-close]')?.addEventListener('click', () => viewer.classList.add('is-detail-hidden'));
    viewer.querySelector('[data-tree-expand]')?.addEventListener('click', event => {
        const expanded = viewer.closest('.ar-tree-sanctum').classList.toggle('is-expanded');
        event.currentTarget.textContent = expanded ? '通常表示に戻る' : '画面いっぱいに開く';
        event.currentTarget.setAttribute('aria-pressed', String(expanded));
        document.body.classList.toggle('ar-tree-expanded', expanded);
    });
    const pointers = new Map();
    let pinch = null;
    viewport.addEventListener('pointerdown', event => {
        if (event.button !== 0) return;
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        drag = { id: event.pointerId, x: event.clientX, y: event.clientY, originX: camera.x, originY: camera.y };
        dragged = false;
        if (pointers.size === 2) {
            const [a, b] = [...pointers.values()];
            pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), camera: { ...camera } };
            dragged = true;
        }
    });
    viewport.addEventListener('pointermove', event => {
        if (!pointers.has(event.pointerId)) return;
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pinch && pointers.size === 2) {
            const [a, b] = [...pointers.values()], rect = viewport.getBoundingClientRect();
            camera = zoomCamera(pinch.camera, Math.max(minZoom(), Math.min(2, pinch.camera.zoom * Math.hypot(a.x - b.x, a.y - b.y) / Math.max(1, pinch.distance))), (a.x + b.x) / 2 - rect.left, (a.y + b.y) / 2 - rect.top);
            dragged = true; draw(); return;
        }
        if (!drag || drag.id !== event.pointerId) return;
        const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
        if (Math.abs(dx) + Math.abs(dy) > 6) { dragged = true; viewport.setPointerCapture(event.pointerId); }
        if (!dragged) return;
        camera.x = drag.originX + dx; camera.y = drag.originY + dy; draw();
    });
    const endDrag = event => { pointers.delete(event.pointerId); pinch = null; drag = null; };
    viewport.addEventListener('pointerup', endDrag);
    viewport.addEventListener('pointercancel', endDrag);
    viewport.addEventListener('lostpointercapture', endDrag);
    viewport.addEventListener('wheel', event => {
        event.preventDefault();
        const rect = viewport.getBoundingClientRect();
        zoomBy(Math.exp(-Math.sign(event.deltaY) * .15), event.clientX - rect.left, event.clientY - rect.top);
    }, { passive: false });
    viewport.addEventListener('keydown', event => {
        const shifts = { ArrowLeft: [70, 0], ArrowRight: [-70, 0], ArrowUp: [0, 70], ArrowDown: [0, -70] };
        if (shifts[event.key]) { event.preventDefault(); camera.x += shifts[event.key][0]; camera.y += shifts[event.key][1]; draw(); }
        else if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomBy(1.25); }
        else if (event.key === '-') { event.preventDefault(); zoomBy(1 / 1.25); }
        else if (event.key === 'Home') { event.preventDefault(); focusNode(nodes.get(selectedId) ?? root); }
    });
    minimap.addEventListener('click', event => {
        const transform = minimap.getScreenCTM();
        if (!transform) return;
        const point = new DOMPoint(event.clientX, event.clientY).matrixTransform(transform.inverse());
        focusNode({ px: point.x, py: point.y }, false);
    });
    const query = viewer.querySelector('[data-tree-query]'), results = viewer.querySelector('[data-tree-results]');
    let matches = [];
    const search = () => {
        const term = query.value.trim().toLocaleLowerCase();
        matches = term ? [...nodes.values()].filter(node => node.name.toLocaleLowerCase().includes(term) || node.nodeId === term).slice(0, 8) : [];
        results.replaceChildren(); results.hidden = !term;
        for (const node of matches) {
            const button = document.createElement('button'); button.type = 'button';
            button.textContent = `${node.name} · ${nodeState(node)}`;
            button.addEventListener('click', () => { showDetails(node); focusNode(node); results.hidden = true; query.focus(); });
            results.append(button);
        }
        if (term && !matches.length) results.textContent = '一致するノードはありません';
    };
    query.addEventListener('input', search);
    query.addEventListener('keydown', event => { if (event.key === 'Escape') results.hidden = true; });
    viewer.querySelector('[data-tree-search]').addEventListener('submit', event => {
        event.preventDefault(); search();
        if (matches.length) { showDetails(matches[0]); focusNode(matches[0]); results.hidden = true; }
    });
    viewer.addEventListener('click', event => { if (!event.target.closest('[data-tree-search]')) results.hidden = true; });
    const observer = new ResizeObserver(() => {
        const width = viewport.clientWidth, height = viewport.clientHeight;
        if (previousWidth && previousHeight) {
            camera.x += (width - previousWidth) / 2; camera.y += (height - previousHeight) / 2; draw();
        } else if (!viewState) focusNode(root);
        previousWidth = width; previousHeight = height;
    });
    observer.observe(viewport);
    viewer.dispose = () => observer.disconnect();
    viewer.getViewState = () => ({ camera: { ...camera }, selectedId });
    showDetails(nodes.get(viewState?.selectedId) ?? root, false);
    if (viewState) { camera = { ...viewState.camera }; draw(); }
    else { focusNode(root); if (viewport.clientWidth < 760) viewer.classList.add('is-detail-hidden'); }
}
for (const viewer of document.querySelectorAll('[data-skilltree-viewer]')) initializeViewer(viewer);
