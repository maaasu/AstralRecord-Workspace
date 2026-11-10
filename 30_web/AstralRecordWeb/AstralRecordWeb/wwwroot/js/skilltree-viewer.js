import { projectNodes, graphBounds, fitCamera, zoomCamera, pinchCamera, wheelZoomFactor, nodeVisualScale, nodeArtScale, nodeDetailLevel, nodeStatus, nodeState, nodeCost, nodeLabelLines } from './skilltree-geometry.mjs';
import { loadIcon } from './minecraft-icons.mjs';
import { searchNodes } from './skilltree-draft.mjs';
import { initializeStarfield } from './skilltree-stars.mjs';
import { nodeClassId, inClassScope, mergeClassTree, selectableTreeClasses, classPointUsage } from './skilltree-scope.mjs';

// Selection never changes player state. The editor submits explicit server-validated operations.
const ns = 'http://www.w3.org/2000/svg';
const expandedViews = new WeakMap();
const maxZoom = 2.5;
const compactLabelWidth = 176;
const classIndexIntervalMs = 60000;
const statusMarks = { unlocked: '✓', available: '+', locked: '−', unmet: '!', 'draft-unlock': '↑', 'draft-relock': '↓' };
const make = (tag, attrs = {}, text) => {
    const element = document.createElementNS(ns, tag);
    Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, String(value)));
    if (text !== undefined) element.textContent = text;
    return element;
};
// Other class branches come from the published definition when the player switches to them.
// The cache lives on the map container so rebuilt viewers keep what was already loaded.
const remoteStores = new WeakMap();
function remoteClassSource(viewer) {
    const host = viewer.closest('[data-skilltree-editor]'), sanctum = viewer.closest('.ar-tree-sanctum');
    if (!host || !sanctum) return null;
    let generation = host.dataset.treeGeneration;
    if (!generation) { try { generation = JSON.parse(host.querySelector('[data-editor-state]')?.textContent || '{}').generationId; } catch { generation = ''; } }
    if (!generation) return null;
    let store = remoteStores.get(sanctum);
    const currentClass = sanctum.dataset.treeDefaultClass;
    if (!store || store.generation !== generation || store.currentClass !== currentClass) {
        store = { generation, currentClass, index: null, indexRequest: null, indexRequestedAt: 0, trees: new Map(), treeRequests: new Map() };
        remoteStores.set(sanctum, store);
    }
    const request = async classId => {
        const url = new URL(host.dataset.editorEndpoint || location.href, location.href);
        url.search = new URLSearchParams({ handler: 'ClassTrees', generationId: generation, ...(classId ? { classId } : {}) });
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
            const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: { Accept: 'application/json' } });
            if (!response.ok || response.redirected) throw new Error(String(response.status));
            return await response.json();
        } finally { clearTimeout(timeout); }
    };
    return {
        generation,
        currentClass,
        index: () => store.index,
        loadIndex: force => {
            if (store.indexRequest) return store.indexRequest;
            if (!force && Date.now() - store.indexRequestedAt < classIndexIntervalMs) return null;
            store.indexRequestedAt = Date.now();
            store.indexRequest = request().then(value => {
                if (value.generationId !== generation || !Array.isArray(value.classes)
                    || value.classes.some(entry => !Array.isArray(entry.scopeClassIds))) return null;
                store.index = value.classes;
                return store.index;
            }).catch(() => null).finally(() => { store.indexRequest = null; });
            return store.indexRequest;
        },
        fragmentFor: classId => classId ? store.trees.get(classId) ?? null : null,
        loadFragment: classId => {
            const pending = store.treeRequests.get(classId);
            if (pending) return pending;
            const loaded = request(classId).then(value => {
                if (value.generationId !== generation || value.classId !== classId || !Array.isArray(value.nodes)) throw new Error('class not published');
                const fragment = { nodes: value.nodes, edges: value.edges ?? [] };
                store.trees.set(classId, fragment);
                return fragment;
            }).finally(() => { store.treeRequests.delete(classId); });
            store.treeRequests.set(classId, loaded);
            return loaded;
        },
    };
}
export function initializeViewer(viewer, viewState) {
    let tree;
    try { tree = JSON.parse(viewer.querySelector('[data-tree-json]').textContent); } catch { return; }
    if (!Array.isArray(tree.nodes) || !tree.nodes.length) return;
    const sanctum = viewer.closest('.ar-tree-sanctum');
    const currentClassId = sanctum.dataset.treeDefaultClass ?? '';
    const remote = remoteClassSource(viewer);
    const profileClasses = JSON.parse(viewer.querySelector('[data-tree-profile-classes]').textContent);
    let classPoints = classPointUsage(JSON.parse(viewer.querySelector('[data-tree-class-points]')?.textContent ?? '[]'));
    let classPointsKey = JSON.stringify([...classPoints]);
    let classIndex = remote?.index();
    const classChoices = () => {
        const localClassIds = new Set(tree.nodes.map(nodeClassId));
        return selectableTreeClasses(tree.nodes, classIndex ?? profileClasses, currentClassId, sanctum.dataset.treeDefaultClassName)
            .filter(entry => remote || entry.id === currentClassId || [...entry.scopeIds].some(id => localClassIds.has(id)));
    };
    let classes = classChoices();
    const rememberedClass = classes.some(entry => entry.id === viewState?.scopeClass) ? viewState.scopeClass : null;
    let fragment = rememberedClass !== currentClassId ? remote?.fragmentFor(rememberedClass) : null;
    let scopeClass = rememberedClass && (!remote || rememberedClass === currentClassId || fragment)
        ? rememberedClass : currentClassId;
    tree = mergeClassTree(tree, fragment);
    let scopeIds = classes.find(entry => entry.id === scopeClass)?.scopeIds ?? new Set();
    const statusCategories = Object.entries(JSON.parse(viewer.querySelector('[data-tree-status-categories]').textContent))
        .sort(([a], [b]) => b.length - a.length);
    const statusName = text => statusCategories.find(([name]) => text.startsWith(name + ' '));
    const effectItem = text => {
        const item = document.createElement('li');
        const status = statusName(text);
        if (status) {
            const [name, category] = status;
            const label = document.createElement('span');
            label.dataset.statCategory = category;
            label.textContent = name;
            item.append(label, text.slice(name.length));
        } else item.textContent = text;
        return item;
    };
    const spacingInput = viewer.querySelector('[data-tree-spacing]');
    let spacingPercent = Math.max(50, Math.min(200, Number(viewState?.spacingPercent) || Number(spacingInput.defaultValue)));
    spacingInput.value = String(spacingPercent);
    viewer.querySelector('[data-tree-spacing-label]').textContent = `${spacingPercent}%`;
    const projected = projectNodes(tree.nodes, spacingPercent / 100);
    let layoutScale = projected.scale;
    const artScaleFor = (node, visualScale) => nodeArtScale(visualScale, node.nearest * layoutScale);
    const nodes = new Map(projected.nodes.map(node => [node.nodeId, node]));
    if (!nodes.size) return;
    const inScope = node => node.nodeId === tree.rootNodeId || inClassScope(node, scopeIds, scopeClass === currentClassId);
    const scopedNodes = () => [...nodes.values()].filter(inScope);
    let bounds = graphBounds(scopedNodes());
    const viewport = viewer.querySelector('.ar-tree-viewport');
    const mapAction = viewport.querySelector('[data-editor-map-action]');
    const lifecycle = new AbortController();
    const starsContainer = viewport.querySelector('[data-tree-stars]');
    starsContainer.replaceChildren();
    const stars = initializeStarfield(starsContainer, null);
    const motionButton = viewer.querySelector('[data-tree-motion]');
    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    let motionPaused = false;
    try { motionPaused = sessionStorage.getItem('astral-skilltree-motion-paused') === 'true'; } catch { /* Storage is optional. */ }
    const renderMotion = () => {
        motionButton.disabled = reducedMotion.matches;
        motionButton.textContent = reducedMotion.matches ? '背景の動き：控えめ' : motionPaused ? '背景の動きを再生' : '背景の動きを停止';
        motionButton.setAttribute('aria-pressed', String(motionPaused || reducedMotion.matches));
    };
    motionButton.addEventListener('click', () => { motionPaused = !motionPaused; stars.pause(motionPaused); renderMotion(); });
    reducedMotion.addEventListener('change', renderMotion, { signal: lifecycle.signal });
    renderMotion();
    const layout = viewer.querySelector('.ar-tree-layout');
    const detail = viewer.querySelector('.ar-tree-detail');
    const nodeDetail = detail.querySelector('.ar-tree-node-detail');
    const effects = nodeDetail.querySelector('[data-node-effects]');
    const detailToggle = viewer.querySelector('[data-tree-detail-toggle]');
    const resizeHandle = viewer.querySelector('[data-tree-detail-resize]');
    let detailWidth = viewState?.detailWidth, resizing = null;
    if (detailWidth !== undefined) layout.style.setProperty('--tree-detail-size', `${detailWidth}px`);
    const detailLimits = () => {
        const max = Math.round(layout.clientWidth * .45);
        return { min: Math.min(240, max), max };
    };
    const updateResizeHandle = () => {
        const { min, max } = detailLimits();
        resizeHandle.setAttribute('aria-valuemin', min);
        resizeHandle.setAttribute('aria-valuemax', max);
        resizeHandle.setAttribute('aria-valuenow', detail.offsetWidth);
    };
    const resizeDetail = width => {
        const { min, max } = detailLimits();
        detailWidth = Math.round(Math.min(max, Math.max(min, width)));
        layout.style.setProperty('--tree-detail-size', `${detailWidth}px`);
        updateResizeHandle();
    };
    resizeHandle.addEventListener('pointerdown', event => {
        if (event.button !== 0 || resizing) return;
        resizeHandle.setPointerCapture(event.pointerId);
        resizing = { id: event.pointerId, x: event.clientX, width: detail.offsetWidth };
        layout.classList.add('is-resizing');
    });
    resizeHandle.addEventListener('pointermove', event => {
        if (event.pointerId === resizing?.id) resizeDetail(resizing.width + resizing.x - event.clientX);
    });
    resizeHandle.addEventListener('lostpointercapture', () => {
        resizing = null;
        layout.classList.remove('is-resizing');
    });
    resizeHandle.addEventListener('keydown', event => {
        const widths = { ArrowLeft: detail.offsetWidth + 24, ArrowRight: detail.offsetWidth - 24, Home: 0, End: layout.clientWidth };
        if (!Object.hasOwn(widths, event.key)) return;
        event.preventDefault();
        resizeDetail(widths[event.key]);
    });
    const setDetailVisible = visible => {
        viewer.classList.toggle('is-detail-hidden', !visible);
        detail.hidden = !visible;
        detailToggle.textContent = visible ? '詳細を隠す' : '詳細を表示';
        detailToggle.setAttribute('aria-expanded', String(visible));
        if (visible) updateResizeHandle();
        else if (detail.contains(document.activeElement)) detailToggle.focus({ preventScroll: true });
    };
    const svg = viewer.querySelector('.ar-tree-canvas');
    const summary = viewer.querySelector('.ar-tree-node-summary');
    const summaryName = summary.querySelector('[data-tree-summary-name]');
    const summaryCaption = summary.querySelector('[data-tree-summary-caption]');
    const summaryEffects = summary.querySelector('[data-tree-summary-effects]');
    const summaryCost = summary.querySelector('[data-tree-summary-cost]');
    const summaryState = summary.querySelector('[data-tree-summary-state]');
    const detailState = viewer.querySelector('[data-node-state]');
    const renderStateChip = (element, node) => {
        element.dataset.state = nodeStatus(node);
        const mark = document.createElement('b');
        mark.setAttribute('aria-hidden', 'true');
        mark.textContent = statusMarks[element.dataset.state];
        element.replaceChildren(mark, nodeState(node));
    };
    let hoveredId, focusedId;
    const updateSummary = () => {
        const node = nodes.get(hoveredId ?? focusedId ?? selectedId);
        if (!node) return;
        summary.hidden = false;
        summary.toggleAttribute('data-tree-previewing', node.nodeId !== selectedId);
        summaryCaption.textContent = node.nodeId === selectedId ? '選択中' : 'ノードのプレビュー';
        summaryName.textContent = node.name;
        summaryCost.textContent = nodeCost(node);
        renderStateChip(summaryState, node);
        const effects = node.displayEffects ?? [];
        const lines = effects.slice(0, 3);
        if (!effects.length) lines.push('追加効果なし');
        if (effects.length > 3) lines.push(`ほか ${effects.length - 3} 件 · 選択して表示`);
        summaryEffects.replaceChildren(...lines.map(effectItem));
    };
    const minimap = viewer.querySelector('[data-tree-minimap]');
    svg.replaceChildren(); minimap.replaceChildren();
    const graph = make('g');
    svg.append(graph);
    const groups = new Map(), links = [], neighbors = new Map([...nodes.keys()].map(id => [id, []]));
    let camera = { zoom: 1, x: 0, y: 0 }, selectedId, drag = null, dragged = false, frame = 0;
    let previousWidth = viewState?.width ?? viewport.clientWidth, previousHeight = viewState?.height ?? viewport.clientHeight, disposed = false;
    let viewportWidth = viewport.clientWidth, viewportHeight = viewport.clientHeight;
    const zoomLabel = viewer.querySelector('[data-tree-zoom-label]');
    let cullBounds = null, interactionUntil = 0, rightPress = null, leftMouseNodeId;
    let showNodeAction = false, mapActionSize, mapActionPointer;
    let paintedVisualScale = 0;
    const visibleNodes = new Map(), visibleEdges = new Map();
    const root = nodes.get(tree.rootNodeId) ?? projected.nodes.find(node => node.isUnlocked) ?? projected.nodes[0];
    const minZoom = () => Math.min(.08, fitCamera(bounds, viewportWidth, viewportHeight).zoom);
    minimap.setAttribute('viewBox', `${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`);
    const placeEdge = edge => {
        const source = nodes.get(edge.source), target = nodes.get(edge.target);
        const dx = target.px - source.px, dy = target.py - source.py, length = Math.hypot(dx, dy);
        const visualScale = nodeVisualScale(camera.zoom);
        const startInset = 52 * artScaleFor(source, visualScale);
        const endInset = 52 * artScaleFor(target, visualScale);
        for (const [key, value] of Object.entries({
            x1: source.px + dx / length * startInset, y1: source.py + dy / length * startInset,
            x2: target.px - dx / length * endInset, y2: target.py - dy / length * endInset,
        })) edge.line.setAttribute(key, value);
        for (const [key, value] of Object.entries({ x1: source.px, y1: source.py, x2: target.px, y2: target.py })) edge.miniLine.setAttribute(key, value);
        edge.minX = Math.min(source.px, target.px); edge.maxX = Math.max(source.px, target.px);
        edge.minY = Math.min(source.py, target.py); edge.maxY = Math.max(source.py, target.py);
    };
    for (const edge of tree.edges ?? []) {
        const source = nodes.get(edge.sourceNodeId), target = nodes.get(edge.targetNodeId);
        if (!source || !target) continue;
        neighbors.get(source.nodeId).push(target);
        neighbors.get(target.nodeId).push(source);
        const dx = target.px - source.px, dy = target.py - source.py, length = Math.hypot(dx, dy);
        if (!length) continue;
        const line = make('line', {
            class: `ar-tree-edge${source.isUnlocked && target.isUnlocked ? ' is-unlocked' : ''}`,
            'vector-effect': 'non-scaling-stroke',
        });
        graph.append(line);
        const miniLine = make('line', { class: 'ar-tree-mini-edge' });
        minimap.append(miniLine);
        const link = { line, miniLine, source: source.nodeId, target: target.nodeId, inScope: true };
        placeEdge(link);
        links.push(link);
    }
    let miniRadius = Math.max(bounds.width, bounds.height) / 110;
    const miniNodes = new Map();
    for (const node of nodes.values()) {
        const point = make('circle', { cx: node.px, cy: node.py, r: miniRadius, class: 'ar-tree-mini-node', 'data-state': nodeStatus(node) });
        minimap.append(point); miniNodes.set(node.nodeId, point);
    }
    const miniViewport = make('rect', { class: 'ar-tree-mini-viewport' });
    minimap.append(miniViewport);
    const placeMapAction = artScale => {
        if (!mapAction) return;
        const node = showNodeAction ? nodes.get(selectedId) : null;
        let position;
        if (node && mapActionSize) {
            const x = camera.x + node.px * camera.zoom;
            const y = camera.y + node.py * camera.zoom;
            const { width, height } = mapActionSize;
            const margin = 4, gap = 7;
            if (x >= 0 && x <= viewportWidth && y >= 0 && y <= viewportHeight) {
                const radius = 55 * artScaleFor(node, artScale) * camera.zoom;
                const centerLeft = Math.max(margin, Math.min(viewportWidth - width - margin, x - width / 2));
                const placements = [
                    [centerLeft, y - radius - gap - height],
                    [centerLeft, y + radius + gap],
                    [x + radius + gap, y - height / 2],
                    [x - radius - gap - width, y - height / 2],
                ];
                position = placements.find(([left, top]) => {
                    const right = left + width, bottom = top + height;
                    if (left < margin || top < margin || right > viewportWidth - margin || bottom > viewportHeight - margin) return false;
                    for (const [id, visible] of visibleNodes) {
                        if (!visible || id === selectedId) continue;
                        const other = nodes.get(id);
                        const otherX = camera.x + other.px * camera.zoom;
                        const otherY = camera.y + other.py * camera.zoom;
                        const dx = Math.max(left - otherX, otherX - right, 0);
                        const dy = Math.max(top - otherY, otherY - bottom, 0);
                        const core = 34 * artScaleFor(other, artScale) * camera.zoom;
                        if (dx * dx + dy * dy < core * core) return false;
                    }
                    return true;
                });
            }
        }
        mapAction.style.visibility = position ? 'visible' : 'hidden';
        if (position) {
            mapAction.style.left = `${position[0]}px`;
            mapAction.style.top = `${position[1]}px`;
        }
    };
    const paint = () => {
        if (disposed) return;
        const artScale = nodeVisualScale(camera.zoom);
        const level = nodeDetailLevel(camera.zoom);
        if (artScale !== paintedVisualScale) {
            for (const [id, group] of groups) {
                const node = nodes.get(id);
                const scale = artScaleFor(node, artScale);
                group.setAttribute('transform', `translate(${node.px},${node.py}) scale(${scale})`);
                const clearance = node.nearest * layoutScale / scale;
                group.classList.toggle('is-label-compact', clearance < Math.max(256, node.labelExtent + 126));
                group.classList.toggle('is-crowded', clearance < compactLabelWidth);
            }
            links.forEach(placeEdge);
            paintedVisualScale = artScale;
        }
        const viewLeft = -camera.x / camera.zoom, viewTop = -camera.y / camera.zoom;
        const viewRight = (viewportWidth - camera.x) / camera.zoom, viewBottom = (viewportHeight - camera.y) / camera.zoom;
        // Keep a padded visible set while panning; only rescan when its inner margin is crossed.
        if (!cullBounds || camera.zoom !== cullBounds.zoom || viewLeft < cullBounds.x0 + 150 || viewTop < cullBounds.y0 + 150
            || viewRight > cullBounds.x1 - 150 || viewBottom > cullBounds.y1 - 150) {
        const x0 = viewLeft - 350, y0 = viewTop - 350, x1 = viewRight + 350, y1 = viewBottom + 350;
        cullBounds = { x0, y0, x1, y1, zoom: camera.zoom };
        for (const [id, group] of groups) {
            const node = nodes.get(id);
            const shown = inScope(node) && node.px >= x0 && node.px <= x1 && node.py >= y0 - (90 + (node.labelExtent ?? 200)) * artScale && node.py <= y1;
            if (visibleNodes.get(id) !== shown) { group.style.display = shown ? '' : 'none'; visibleNodes.set(id, shown); }
        }
        for (const edge of links) {
            const shown = edge.inScope && edge.maxX >= x0 && edge.minX <= x1 && edge.maxY >= y0 && edge.minY <= y1;
            if (visibleEdges.get(edge.line) !== shown) { edge.line.style.display = shown ? '' : 'none'; visibleEdges.set(edge.line, shown); }
        }
        }
        graph.setAttribute('transform', `translate(${camera.x},${camera.y}) scale(${camera.zoom})`);
        placeMapAction(artScale);
        const zoomText = `${Math.round(camera.zoom * 100)}%`;
        if (viewer.dataset.nodeDetail !== level) viewer.dataset.nodeDetail = level;
        if (zoomLabel.textContent !== zoomText) zoomLabel.textContent = zoomText;
        const left = Math.max(bounds.x, -camera.x / camera.zoom);
        const top = Math.max(bounds.y, -camera.y / camera.zoom);
        const right = Math.min(bounds.x + bounds.width, (viewportWidth - camera.x) / camera.zoom);
        const bottom = Math.min(bounds.y + bounds.height, (viewportHeight - camera.y) / camera.zoom);
        for (const [key, value] of Object.entries({ x: left, y: top, width: Math.max(0, right - left), height: Math.max(0, bottom - top) })) miniViewport.setAttribute(key, value);
        frame = 0;
    };
    const draw = () => { if (!frame) frame = requestAnimationFrame(paint); };
    const focusNode = node => {
        // A detail toggle can resize the map before ResizeObserver delivers its callback.
        viewportWidth = previousWidth = viewport.clientWidth;
        viewportHeight = previousHeight = viewport.clientHeight;
        cullBounds = null;
        const scale = node.nodeId ? artScaleFor(node, nodeVisualScale(camera.zoom)) * camera.zoom : 0;
        const labelCenter = node.nodeId && nodeDetailLevel(camera.zoom) === 'full' ? 9 + node.labelExtent / 2 : 0;
        let x = viewportWidth / 2, y = viewportHeight / 2 - labelCenter * scale;
        const radius = 55 * scale;
        const summaryWidth = summary.offsetWidth, summaryTop = summary.offsetTop;
        const summaryRight = summary.offsetLeft + summaryWidth;
        if (node.nodeId && summaryWidth && x - radius < summaryRight && y + radius > summaryTop) {
            const spaceRight = viewportWidth - summaryRight;
            if (summaryTop >= spaceRight) y = Math.min(y, summaryTop / 2);
            else {
                x = summaryRight + spaceRight / 2;
                const minimapTop = minimap.parentElement.offsetTop;
                y = Math.min(y, minimapTop - Math.min(radius, minimapTop / 2));
            }
        }
        camera.x = x - node.px * camera.zoom;
        camera.y = y - node.py * camera.zoom;
        draw();
    };
    const showDetails = (node, announce = true) => {
        selectedId = node.nodeId;
        if (announce) showNodeAction = true;
        updateSummary();
        draw();
        groups.forEach((g, id) => {
            g.setAttribute('aria-pressed', String(id === selectedId));
            g.classList.toggle('is-neighbor', neighbors.get(selectedId).some(n => n.nodeId === id));
        });
        links.forEach(edge => edge.line.classList.toggle('is-connected', edge.source === selectedId || edge.target === selectedId));
        miniNodes.forEach((point, id) => point.classList.toggle('is-selected', id === selectedId));
        viewer.querySelector('[data-node-name]').textContent = node.name;
        viewer.querySelector('[data-node-type]').textContent = node.pointType === 'CP' ? 'クラスの成長 · CP' : 'プレイヤーの成長 · PP';
        renderStateChip(detailState, node);
        viewer.querySelector('[data-node-requirement]').textContent = node.requirementText || 'クラス・プレイヤーレベルの指定条件なし';
        viewer.querySelector('[data-node-requirement]').classList.toggle('ar-condition-unmet', node.isConditionMet === false);
        viewer.querySelector('[data-node-cost]').textContent = nodeCost(node);
        effects.replaceChildren(...(node.displayEffects?.length ? node.displayEffects : ['追加効果の指定はありません']).map(effectItem));
        const connections = viewer.querySelector('[data-node-connections]'); connections.replaceChildren();
        const linked = neighbors.get(node.nodeId), hiddenLinks = linked.filter(other => !inScope(other)).length;
        for (const other of linked.filter(inScope)) {
            const button = document.createElement('button'); button.type = 'button';
            button.dataset.state = nodeStatus(other);
            button.textContent = other.name;
            button.addEventListener('click', () => { selectNode(other); focusNode(other); });
            connections.append(button);
        }
        if (!connections.childElementCount) connections.textContent = '表示中の接続ノードはありません';
        if (hiddenLinks) {
            const note = document.createElement('small');
            note.textContent = `ほかに別クラスのノードへ ${hiddenLinks} 件つながっています`;
            connections.append(note);
        }
        const icon = viewer.querySelector('[data-node-icon]'), fallback = viewer.querySelector('[data-node-icon-fallback]');
        icon.hidden = true; fallback.hidden = false;
        icon.removeAttribute('src');
        loadIcon(node.icon).then(url => {
            if (selectedId !== node.nodeId || !url) return;
            icon.src = url; icon.hidden = false; fallback.hidden = true;
        });
        if (announce) viewer.querySelector('[data-tree-announcement]').textContent =
            [node.name, nodeState(node), nodeCost(node), ...(node.displayEffects ?? [])].join('。');
        viewer.dispatchEvent(new CustomEvent('skilltree:select', { bubbles: true, detail: node }));
    };
    const selectNode = node => {
        setDetailVisible(true);
        showDetails(node);
        const panel = detail.getBoundingClientRect();
        const section = nodeDetail.getBoundingClientRect();
        if (section.top < panel.top || effects.firstElementChild.getBoundingClientRect().bottom > panel.bottom)
            detail.scrollTop += section.top - panel.top;
    };
    const renderNodeStatus = (group, node) => {
        const status = nodeStatus(node);
        group.dataset.state = status;
        group.setAttribute('aria-label', `${node.name}、${nodeState(node)}`);
        group.querySelector('.ar-tree-node-status-mark').textContent = statusMarks[status];
        group.querySelector('title').textContent = [node.name, nodeState(node), node.requirementText].filter(Boolean).join('\n');
        miniNodes.get(node.nodeId).dataset.state = status;
    };
    const renderLabel = (label, node) => {
        const lines = nodeLabelLines(node, statusName);
        node.labelExtent = lines.length * 20;
        label.replaceChildren(make('rect', { x: -128, y: 61, width: 256, height: node.labelExtent + 12, rx: 4 }));
        lines.forEach((line, index) => {
            const text = make('text', { y: 81 + index * 20, class: `ar-tree-label-${line.kind}` });
            if (line.category) text.append(make('tspan', { 'data-stat-category': line.category }, line.name));
            text.append(line.text);
            label.append(text);
        });
    };
    const editable = sanctum.dataset.treeMode === 'edit';
    const notifyNodeAction = action => viewer.dispatchEvent(new CustomEvent('skilltree:activate', { bubbles: true, detail: action }));
    const openNodeActions = (node, action) => {
        selectNode(node);
        notifyNodeAction(action);
    };
    // Context menus can fire before or after release. Wait for both signals.
    const completeRightClick = () => {
        if (!rightPress?.released || !rightPress.contextRequested) return;
        const { nodeId, moved } = rightPress;
        rightPress = null;
        if (!moved) openNodeActions(nodes.get(nodeId), 'RELOCK');
    };
    for (const node of nodes.values()) {
        const isRoot = node.nodeId === tree.rootNodeId;
        const g = make('g', {
            class: `ar-tree-node${isRoot ? ' is-root' : ''}`,
            transform: `translate(${node.px},${node.py})`, role: 'button', tabindex: 0,
            'aria-pressed': 'false', 'data-point-type': node.pointType, 'data-node-key': node.nodeId,
        });
        if (editable) {
            g.setAttribute('aria-haspopup', 'dialog');
            g.setAttribute('aria-keyshortcuts', 'Shift+F10');
        }
        g.append(make('circle', { r: 52, class: 'ar-tree-node-aura', 'aria-hidden': 'true' }));
        g.append(node.pointType === 'CP'
            ? make('rect', { x: -38, y: -38, width: 76, height: 76, rx: 14, class: 'ar-tree-node-frame' })
            : make('circle', { r: 40, class: 'ar-tree-node-frame' }));
        const fallback = make('text', { y: 10, class: 'ar-tree-symbol', 'aria-hidden': 'true' }, '✧');
        g.append(fallback);
        const icon = make('image', { x: -24, y: -24, width: 48, height: 48, class: 'ar-tree-node-icon', 'aria-hidden': 'true' });
        loadIcon(node.icon).then(url => {
            if (disposed || !url) return;
            icon.setAttribute('href', url); fallback.replaceWith(icon);
        });
        g.append(make('circle', { cx: 32, cy: 32, r: 14, class: 'ar-tree-node-status', 'aria-hidden': 'true' }));
        g.append(make('text', { x: 32, y: 32, class: 'ar-tree-node-status-mark', 'aria-hidden': 'true' }));
        const label = make('g', { class: 'ar-tree-node-label', 'aria-hidden': 'true' });
        renderLabel(label, node);
        g.append(label);
        const compact = make('g', { class: 'ar-tree-node-compact-label', 'aria-hidden': 'true' });
        compact.append(make('rect', { x: -compactLabelWidth / 2, y: 61, width: compactLabelWidth, height: 51, rx: 4 }));
        const nameChars = Array.from(node.name);
        compact.append(make('text', { y: 81, class: 'ar-tree-label-name' }, nameChars.length > 12 ? nameChars.slice(0, 11).join('') + '…' : node.name));
        compact.append(make('text', { y: 101, class: 'ar-tree-label-cost' }, `${node.pointCost} ${node.pointType}`));
        g.append(compact);
        g.append(make('title'));
        renderNodeStatus(g, node);
        g.addEventListener('click', event => {
            const mouse = event.detail > 0 && leftMouseNodeId === node.nodeId
                && (!event.pointerType || event.pointerType === 'mouse');
            leftMouseNodeId = undefined;
            if (dragged && event.detail !== 0) return;
            selectNode(node);
            if (editable && mouse) notifyNodeAction('UNLOCK');
        });
        g.addEventListener('keydown', event => {
            if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); selectNode(node); }
            else if (editable && (event.key === 'ContextMenu' || event.shiftKey && event.key === 'F10')) {
                event.preventDefault();
                openNodeActions(node);
            }
        });
        g.addEventListener('contextmenu', event => {
            if (!rightPress || rightPress.nodeId !== node.nodeId) return;
            if (event.button !== 2 || event.pointerType && event.pointerType !== 'mouse' || !editable) return;
            event.preventDefault();
            rightPress.contextRequested = true;
            completeRightClick();
        });
        g.addEventListener('pointerenter', event => {
            if (event.pointerType !== 'mouse' || pointers.size) return;
            hoveredId = node.nodeId;
            updateSummary();
        });
        g.addEventListener('pointerleave', () => {
            if (hoveredId !== node.nodeId) return;
            hoveredId = undefined;
            updateSummary();
        });
        g.addEventListener('focus', () => {
            if (!g.matches(':focus-visible')) return;
            focusedId = node.nodeId;
            hoveredId = undefined;
            updateSummary();
            focusNode(node);
        });
        g.addEventListener('blur', () => {
            if (focusedId !== node.nodeId) return;
            focusedId = undefined;
            updateSummary();
        });
        graph.append(g); groups.set(node.nodeId, g);
    }
    spacingInput.addEventListener('input', () => {
        const nextPercent = Number(spacingInput.value);
        if (nextPercent === spacingPercent) return;
        const ratio = nextPercent / spacingPercent;
        const centerX = viewport.clientWidth / 2, centerY = viewport.clientHeight / 2;
        camera.x = centerX - (centerX - camera.x) * ratio;
        camera.y = centerY - (centerY - camera.y) * ratio;
        spacingPercent = nextPercent;
        layoutScale *= ratio;
        paintedVisualScale = 0;
        viewer.querySelector('[data-tree-spacing-label]').textContent = `${spacingPercent}%`;
        for (const node of nodes.values()) {
            node.px = node.x * layoutScale;
            node.py = node.z * layoutScale;
            const point = miniNodes.get(node.nodeId);
            point.setAttribute('cx', node.px); point.setAttribute('cy', node.py);
        }
        bounds = graphBounds(scopedNodes());
        minimap.setAttribute('viewBox', `${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`);
        miniRadius = Math.max(bounds.width, bounds.height) / 110;
        miniNodes.forEach(point => point.setAttribute('r', miniRadius));
        cullBounds = null;
        draw();
    });
    const zoomBy = (factor, x = viewportWidth / 2, y = viewportHeight / 2) => {
        camera = zoomCamera(camera, Math.max(minZoom(), Math.min(maxZoom, camera.zoom * factor)), x, y); draw();
    };
    viewer.querySelector('[data-tree-zoom="in"]').addEventListener('click', () => zoomBy(1.25));
    viewer.querySelector('[data-tree-zoom="out"]').addEventListener('click', () => zoomBy(1 / 1.25));
    viewer.querySelector('[data-tree-fit]').addEventListener('click', () => { camera = fitCamera(bounds, viewport.clientWidth, viewport.clientHeight); draw(); });
    const classList = viewer.querySelector('[data-tree-class-list]');
    const scopeLabel = viewer.querySelector('[data-tree-scope-label]');
    const textList = viewer.querySelector('[data-tree-text-list]');
    const renderTextList = () => {
        if (!textList.closest('details').open) return;
        const items = scopedNodes().map(node => {
            const item = document.createElement('li');
            const title = document.createElement('strong');
            title.textContent = node.name;
            item.append(title, ` · ${nodeState(node)} / ${nodeCost(node)}`);
            for (const line of [node.requirementText, ...(node.displayEffects ?? [])].filter(Boolean))
                item.append(document.createElement('br'), line);
            return item;
        });
        textList.replaceChildren(...items);
    };
    textList.closest('details').addEventListener('toggle', renderTextList);
    const renderClassList = () => {
        const focusedClass = classList.contains(document.activeElement) ? document.activeElement.dataset.classId : null;
        classList.replaceChildren();
        for (const entry of classes) {
            const button = document.createElement('button');
            button.type = 'button';
            button.setAttribute('role', 'radio');
            button.setAttribute('aria-checked', String(entry.id === scopeClass));
            button.tabIndex = entry.id === scopeClass ? 0 : -1;
            button.dataset.classId = entry.id;
            const name = document.createElement('span');
            name.className = 'ar-tree-class-name';
            name.textContent = entry.name;
            if (entry.id === currentClassId) {
                const badge = document.createElement('small');
                badge.textContent = '就職中';
                name.append(badge);
            }
            button.append(name);
            const points = classPoints.get(entry.id);
            if (points) {
                const progress = document.createElement('span');
                progress.className = 'ar-tree-class-count';
                progress.textContent = `${points.spentCp} / ${points.earnedCp} CP`;
                progress.title = '使用CP / 獲得CP';
                button.setAttribute('aria-label', `${name.textContent}、${points.earnedCp} CP 獲得、${points.spentCp} CP 使用済み`);
                const bar = document.createElement('span');
                bar.className = 'ar-tree-class-bar';
                bar.setAttribute('aria-hidden', 'true');
                bar.style.setProperty('--progress', `${points.percent}%`);
                button.append(progress, bar);
            }
            classList.append(button);
        }
        classList.closest('.ar-tree-class-switch').hidden = !classes.length;
        // On narrow screens the list scrolls sideways; keep the selected class in view without moving the page.
        const checked = classList.querySelector('[aria-checked="true"]');
        if (focusedClass) (classList.querySelector(`[data-class-id="${CSS.escape(focusedClass)}"]`) ?? checked)?.focus({ preventScroll: true });
        if (checked && classList.scrollWidth > classList.clientWidth)
            classList.scrollLeft = Math.max(0, checked.offsetLeft - classList.offsetLeft - 8);
        const active = classes.find(entry => entry.id === scopeClass);
        scopeLabel.textContent = active ? active.name : '共通ノード';
    };
    viewer.setClassPoints = points => {
        const next = classPointUsage(points);
        const key = JSON.stringify([...next]);
        if (key === classPointsKey) return;
        classPoints = next;
        classPointsKey = key;
        renderClassList();
    };
    const applyScope = () => {
        for (const link of links) {
            link.inScope = inScope(nodes.get(link.source)) && inScope(nodes.get(link.target));
            link.miniLine.style.display = link.inScope ? '' : 'none';
        }
        miniNodes.forEach((point, id) => { point.style.display = inScope(nodes.get(id)) ? '' : 'none'; });
        renderTextList();
        visibleNodes.clear(); visibleEdges.clear();
        groups.forEach(group => { group.style.display = 'none'; });
        links.forEach(link => { link.line.style.display = 'none'; });
        bounds = graphBounds(scopedNodes());
        minimap.setAttribute('viewBox', `${bounds.x} ${bounds.y} ${bounds.width} ${bounds.height}`);
        cullBounds = null;
        renderClassList();
    };
    const replaceScope = (classId, recenter = true) => {
        const focused = classList.contains(document.activeElement);
        const state = { ...viewer.getViewState(), scopeClass: classId, recenter };
        const replacement = viewer.cloneNode(true);
        replacement.querySelector('[data-tree-stars]')?.replaceChildren();
        viewer.dispose(); viewer.replaceWith(replacement);
        initializeViewer(replacement, state);
        replacement.dispatchEvent(new CustomEvent('skilltree:replaced', { bubbles: true, detail: replacement }));
        if (focused) replacement.querySelector(`[data-class-id="${CSS.escape(classId)}"]`)?.focus({ preventScroll: true });
    };
    let classRequest = 0, loadingClass = false, loadedClass = null;
    const canApplyClass = () => !disposed && !viewer.isInteracting() && !viewer.querySelector('dialog[open]');
    const applyLoadedClass = () => {
        if (loadedClass === null || !canApplyClass()) return false;
        const classId = loadedClass;
        loadedClass = null;
        if (!classes.some(entry => entry.id === classId)) return false;
        replaceScope(classId);
        return true;
    };
    const setScope = async classId => {
        const choice = classes.find(entry => entry.id === classId);
        if (!choice) return;
        const request = ++classRequest;
        loadedClass = null;
        loadingClass = Boolean(remote && classId !== scopeClass && classId !== currentClassId);
        if (classId === scopeClass) { renderClassList(); return; }
        if (remote) {
            if (loadingClass) {
                scopeLabel.textContent = `${choice.name}を読み込み中…`;
                try { await remote.loadFragment(classId); }
                catch {
                    if (disposed || request !== classRequest) return;
                    scopeLabel.textContent = 'クラスのノードを読み込めませんでした';
                    return;
                } finally {
                    if (request === classRequest) loadingClass = false;
                }
            }
            if (!disposed && request === classRequest) {
                loadedClass = classId;
                applyLoadedClass();
            }
            return;
        }
        scopeClass = classId;
        scopeIds = choice.scopeIds;
        applyScope();
        search(false);
        const selected = nodes.get(selectedId);
        if (!selected || !inScope(selected)) { showDetails(root); focusNode(root); }
        else { showDetails(selected, false); draw(); }
        viewer.querySelector('[data-tree-announcement]').textContent = `${scopeLabel.textContent} のノードを表示しています`;
    };
    classList.addEventListener('click', event => {
        const button = event.target.closest('[data-class-id]');
        if (button) setScope(button.dataset.classId);
    });
    classList.addEventListener('keydown', event => {
        const step = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 }[event.key];
        if (!step || !classes.length) return;
        event.preventDefault();
        const index = classes.findIndex(entry => entry.id === scopeClass);
        const next = classes[(index + step + classes.length) % classes.length];
        setScope(next.id);
        classList.querySelector(`[data-class-id="${CSS.escape(next.id)}"]`)?.focus();
    });
    viewer.querySelector('[data-tree-home]').addEventListener('click', () => focusNode(nodes.get(selectedId) ?? root));
    viewer.querySelector('[data-tree-reset]')?.addEventListener('click', () => focusNode(root));
    detailToggle.addEventListener('click', () => setDetailVisible(viewer.classList.contains('is-detail-hidden')));
    const expandButton = viewer.querySelector('[data-tree-expand]');
    const renderExpand = () => {
        const expanded = sanctum.classList.contains('is-expanded');
        expandButton.textContent = expanded ? '通常表示に戻る' : '最大表示';
        expandButton.setAttribute('aria-pressed', String(expanded));
    };
    renderExpand();
    const setExpanded = expanded => {
        if (expanded === sanctum.classList.contains('is-expanded')) return;
        if (expanded) expandedViews.set(sanctum, { focus: document.activeElement, x: window.scrollX, y: window.scrollY });
        sanctum.classList.toggle('is-expanded', expanded);
        renderExpand();
        document.body.classList.toggle('ar-tree-expanded', expanded);
        if (!expanded) {
            const previous = expandedViews.get(sanctum);
            (previous?.focus?.isConnected ? previous.focus : expandButton).focus({ preventScroll: true });
            if (previous) window.scrollTo({ left: previous.x, top: previous.y, behavior: 'instant' });
            expandedViews.delete(sanctum);
        }
    };
    expandButton.addEventListener('click', () => setExpanded(!sanctum.classList.contains('is-expanded')));
    document.addEventListener('keydown', event => {
        if (!sanctum.classList.contains('is-expanded') || event.defaultPrevented || sanctum.querySelector('dialog[open]')) return;
        if (event.key === 'Escape') { event.preventDefault(); setExpanded(false); }
        else if (event.key === 'Tab') {
            const focusable = [...sanctum.querySelectorAll('button, input, select, a[href], summary, [tabindex="0"]')]
                .filter(element => !element.disabled && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden');
            const first = focusable[0], last = focusable.at(-1);
            if (event.shiftKey && (document.activeElement === first || !sanctum.contains(document.activeElement))) {
                event.preventDefault(); last?.focus({ preventScroll: true });
            } else if (!event.shiftKey && (document.activeElement === last || !sanctum.contains(document.activeElement))) {
                event.preventDefault(); first?.focus({ preventScroll: true });
            }
        }
    }, { signal: lifecycle.signal });
    const pointers = new Map();
    let pinch = null;
    viewport.addEventListener('pointerdown', event => {
        if (event.target === mapAction) {
            if (event.button === 0) mapActionPointer = event.pointerId;
            return;
        }
        leftMouseNodeId = event.button === 0 && event.pointerType === 'mouse'
            ? event.target.closest('[data-node-key]')?.dataset.nodeKey : undefined;
        if (event.button === 0 && pointers.size >= 2) return;
        const nodeId = event.target.closest('[data-node-key]')?.dataset.nodeKey;
        rightPress = editable && nodeId && event.button === 2 && event.pointerType === 'mouse'
            ? { pointerId: event.pointerId, nodeId, x: event.clientX, y: event.clientY } : null;
        if (event.button !== 0) return;
        if (event.pointerType === 'mouse' && !viewport.contains(document.activeElement)) viewport.focus({ preventScroll: true });
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        drag = { id: event.pointerId, x: event.clientX, y: event.clientY, originX: camera.x, originY: camera.y };
        dragged = false;
        if (pointers.size === 2) {
            const [a, b] = [...pointers.values()], rect = viewport.getBoundingClientRect();
            pinch = { distance: Math.hypot(a.x - b.x, a.y - b.y), camera: { ...camera }, center: { x: (a.x + b.x) / 2 - rect.left, y: (a.y + b.y) / 2 - rect.top } };
            dragged = true;
            for (const id of pointers.keys()) viewport.setPointerCapture(id);
        }
    });
    viewport.addEventListener('pointermove', event => {
        if (!pointers.has(event.pointerId)) return;
        pointers.set(event.pointerId, { x: event.clientX, y: event.clientY });
        if (pinch && pointers.size === 2) {
            const [a, b] = [...pointers.values()], rect = viewport.getBoundingClientRect();
            camera = pinchCamera(pinch.camera, Math.max(minZoom(), Math.min(maxZoom, pinch.camera.zoom * Math.hypot(a.x - b.x, a.y - b.y) / Math.max(1, pinch.distance))), pinch.center, { x: (a.x + b.x) / 2 - rect.left, y: (a.y + b.y) / 2 - rect.top });
            dragged = true; viewer.classList.add('is-panning'); draw(); return;
        }
        if (!drag || drag.id !== event.pointerId) return;
        const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
        if (!dragged && Math.abs(dx) + Math.abs(dy) > 6) { dragged = true; viewport.setPointerCapture(event.pointerId); viewer.classList.add('is-panning'); }
        if (!dragged) return;
        camera.x = drag.originX + dx; camera.y = drag.originY + dy; draw();
    });
    const endDrag = event => {
        if (event.pointerId === mapActionPointer) {
            mapActionPointer = undefined;
            interactionUntil = performance.now() + 180;
        }
        if (!pointers.delete(event.pointerId)) return;
        pinch = null;
        const remaining = [...pointers.entries()][0];
        drag = remaining ? { id: remaining[0], ...remaining[1], originX: camera.x, originY: camera.y } : null;
        if (!remaining) viewer.classList.remove('is-panning');
    };
    const movedRight = event => Math.abs(event.clientX - rightPress.x) + Math.abs(event.clientY - rightPress.y) > 6;
    window.addEventListener('pointermove', event => {
        if (event.pointerId === rightPress?.pointerId && !rightPress.released && movedRight(event)) rightPress.moved = true;
    }, { capture: true, signal: lifecycle.signal });
    window.addEventListener('pointerup', event => {
        if (event.button === 2 && event.pointerId === rightPress?.pointerId) {
            rightPress.moved ||= movedRight(event) || event.target.closest?.('[data-node-key]')?.dataset.nodeKey !== rightPress.nodeId;
            rightPress.released = true;
            interactionUntil = performance.now() + 180;
            completeRightClick();
        }
        endDrag(event);
    }, { signal: lifecycle.signal });
    window.addEventListener('pointercancel', event => {
        if (event.pointerId === rightPress?.pointerId) rightPress = null;
        leftMouseNodeId = undefined;
        endDrag(event);
    }, { signal: lifecycle.signal });
    window.addEventListener('blur', () => {
        mapActionPointer = undefined;
        rightPress = null;
        leftMouseNodeId = undefined;
    }, { signal: lifecycle.signal });
    viewport.addEventListener('lostpointercapture', event => { if (event.target === viewport) endDrag(event); });
    viewport.addEventListener('wheel', event => {
        event.preventDefault();
        interactionUntil = performance.now() + 180;
        const rect = viewport.getBoundingClientRect();
        zoomBy(wheelZoomFactor(event, viewportHeight), event.clientX - rect.left, event.clientY - rect.top);
    }, { passive: false });
    viewport.addEventListener('keydown', event => {
        if (event.target === mapAction) return;
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
        focusNode({ px: point.x, py: point.y });
    });
    const query = viewer.querySelector('[data-tree-query]'), results = viewer.querySelector('[data-tree-results]');
    const searchMode = viewer.querySelector('[data-tree-search-mode]'), effectFilter = viewer.querySelector('[data-tree-effect-filter]');
    const effectNames = new Set();
    for (const node of nodes.values()) for (const effect of node.displayEffects ?? []) {
        const name = String(effect).replace(/\s*[+−-]?[\d,.]+(?:%|％)?(?:\s*.*)?$/, '').trim();
        if (name) effectNames.add(name);
    }
    effectFilter.replaceChildren(new Option('指定なし', ''));
    for (const effect of [...effectNames].sort((a, b) => a.localeCompare(b, 'ja'))) effectFilter.append(new Option(effect, effect));
    if (viewState?.search) { query.value = viewState.search.query; searchMode.value = viewState.search.mode; effectFilter.value = viewState.search.effect; }
    const searchFeedback = viewer.querySelector('[data-tree-search-feedback]');
    const matchCount = viewer.querySelector('[data-tree-match-count]');
    let matches = [], matchIndex = -1, searchFrame = 0;
    const search = (showResults = true) => {
        const hasQuery = Boolean(query.value.trim() || effectFilter.value);
        const searchable = scopedNodes();
        matches = query.value.trim() ? searchNodes(searchable, query.value, searchMode.value) : searchable;
        if (effectFilter.value) matches = matches.filter(node => (node.displayEffects ?? []).some(effect => effect.includes(effectFilter.value)));
        if (!hasQuery) matches = [];
        matchIndex = -1;
        const matched = new Set(matches.map(node => node.nodeId));
        for (const [id, group] of groups) { group.classList.toggle('is-search-match', matched.has(id)); group.classList.toggle('is-search-dim', hasQuery && !matched.has(id)); }
        results.replaceChildren(); results.hidden = !hasQuery || !showResults;
        for (const node of matches.slice(0, 12)) {
            const button = document.createElement('button'); button.type = 'button';
            const effects = (node.displayEffects ?? []).slice(0, 2);
            button.textContent = [node.name, ...effects].join(' · ');
            button.addEventListener('click', () => {
                matchIndex = matches.indexOf(node);
                showDetails(node);
                focusNode(node);
                query.focus({ preventScroll: true });
                results.hidden = true;
                updateMatchCount();
            });
            results.append(button);
        }
        if (hasQuery && !matches.length) results.textContent = '一致するノードはありません';
        updateMatchCount();
    };
    const updateMatchCount = () => {
        const hasQuery = Boolean(query.value.trim() || effectFilter.value);
        searchFeedback.hidden = !hasQuery;
        matchCount.textContent = matches.length ? `${matchIndex >= 0 ? (matchIndex + 1) + ' / ' : ''}${matches.length} 件を強調` : hasQuery ? '一致なし' : '';
        viewer.querySelector('[data-tree-match-prev]').disabled = !matches.length;
        viewer.querySelector('[data-tree-match-next]').disabled = !matches.length;
    };
    const stepMatch = direction => {
        if (!matches.length) return;
        matchIndex = matchIndex < 0 && direction < 0 ? matches.length - 1 : (matchIndex + direction + matches.length) % matches.length;
        showDetails(matches[matchIndex]); focusNode(matches[matchIndex]); results.hidden = true; updateMatchCount();
    };
    query.addEventListener('input', () => { cancelAnimationFrame(searchFrame); searchFrame = requestAnimationFrame(() => search()); });
    searchMode.addEventListener('change', () => search());
    effectFilter.addEventListener('change', () => search(false));
    const searchForm = viewer.querySelector('[data-tree-search]');
    searchForm.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || results.hidden) return;
        event.preventDefault();
        results.hidden = true;
        query.focus({ preventScroll: true });
    });
    searchForm.addEventListener('submit', event => { event.preventDefault(); search(false); stepMatch(1); });
    viewer.querySelector('[data-tree-match-prev]').addEventListener('click', () => stepMatch(-1));
    viewer.querySelector('[data-tree-match-next]').addEventListener('click', () => stepMatch(1));
    viewer.querySelector('[data-tree-search-clear]').addEventListener('click', () => {
        query.value = ''; effectFilter.value = ''; search(false);
        query.focus({ preventScroll: true });
    });
    viewer.addEventListener('click', event => { if (!event.target.closest('[data-tree-search]')) results.hidden = true; });
    const geometryKey = value => JSON.stringify([value.rootNodeId, value.edges, value.nodes.map(node => [node.nodeId, node.x, node.y, node.z, node.name, node.icon, node.pointType, node.pointCost, node.displayEffects, node.costText])]);
    const originalGeometry = geometryKey(tree);
    viewer.updateTree = live => {
        // A new definition generation invalidates the browse-only class; rebuild from the fresh source.
        const source = remote && remoteClassSource(viewer);
        if (source && (source.generation !== remote.generation || source.currentClass !== remote.currentClass)) return false;
        const next = mergeClassTree(live, fragment);
        if (geometryKey(next) !== originalGeometry) return false;
        // Class membership decides what the map shows; rebuild the viewer when it changes.
        if (next.nodes.some(value => nodes.has(value.nodeId) && nodeClassId(nodes.get(value.nodeId)) !== nodeClassId(value))) return false;
        for (const value of next.nodes) {
            const node = nodes.get(value.nodeId), group = groups.get(value.nodeId);
            if (!node || !group) return false;
            const changed = nodeStatus(node) !== nodeStatus(value) || nodeState(node) !== nodeState(value)
                || node.requirementText !== value.requirementText;
            Object.assign(node, value);
            if (!changed) continue;
            renderNodeStatus(group, node);
            renderLabel(group.querySelector('.ar-tree-node-label'), node);
        }
        links.forEach(edge => edge.line.classList.toggle('is-unlocked', nodes.get(edge.source).isUnlocked && nodes.get(edge.target).isUnlocked));
        const current = nodes.get(selectedId);
        if (current) {
            updateSummary();
            renderStateChip(detailState, current);
            const requirement = viewer.querySelector('[data-node-requirement]');
            requirement.textContent = current.requirementText || 'クラス・プレイヤーレベルの指定条件なし';
            requirement.classList.toggle('ar-condition-unmet', current.isConditionMet === false);
            viewer.querySelector('[data-node-cost]').textContent = nodeCost(current);
        }
        renderTextList();
        cullBounds = null; paintedVisualScale = 0; draw();
        return true;
    };
    applyScope();
    search(false);
    const observer = new ResizeObserver(entries => {
        if (mapAction && entries.some(entry => entry.target === mapAction))
            mapActionSize = { width: mapAction.offsetWidth, height: mapAction.offsetHeight };
        const width = viewport.clientWidth, height = viewport.clientHeight;
        if (width <= 0 || height <= 0) return;
        if (!detail.hidden) updateResizeHandle();
        viewportWidth = width; viewportHeight = height;
        camera.x += (width - previousWidth) / 2; camera.y += (height - previousHeight) / 2;
        previousWidth = width; previousHeight = height;
        draw();
    });
    observer.observe(viewport);
    if (mapAction) {
        mapAction.hidden = false;
        observer.observe(mapAction, { box: 'border-box' });
    }
    viewer.dispose = () => { disposed = true; stars.dispose(); lifecycle.abort(); observer.disconnect(); cancelAnimationFrame(frame); cancelAnimationFrame(searchFrame); };
    viewer.getViewState = () => ({ camera: { ...camera }, scopeClass, spacingPercent, selectedId, detailVisible: !detail.hidden, detailWidth, showNodeAction, width: previousWidth, height: previousHeight, search: { query: query.value, mode: searchMode.value, effect: effectFilter.value } });
    viewer.isInteracting = () => pointers.size > 0 || mapActionPointer !== undefined
        || Boolean(rightPress && !rightPress.released) || Boolean(resizing) || performance.now() < interactionUntil;
    viewer.openDetails = () => setDetailVisible(true);
    viewer.isReadOnlyNode = nodeId => Boolean(nodes.get(nodeId)?.readOnly);
    const applyRemoteClasses = () => {
        if (loadingClass || !canApplyClass()) return;
        const index = remote.index();
        if (index && index !== classIndex) {
            classIndex = index;
            classes = classChoices();
            const active = classes.find(entry => entry.id === scopeClass);
            if (scopeClass && scopeClass !== currentClassId && !active) {
                if (!applyLoadedClass()) replaceScope(currentClassId);
                return;
            }
            const nextIds = active?.scopeIds ?? new Set();
            if (scopeIds.size === nextIds.size && [...scopeIds].every(id => nextIds.has(id))) {
                renderClassList();
            } else {
                scopeIds = nextIds;
                applyScope();
                search(false);
                const selected = nodes.get(selectedId);
                if (selected && inScope(selected)) showDetails(selected, false);
                else { showDetails(root, false); focusNode(root); }
            }
        }
        if (applyLoadedClass()) return;
        const updated = scopeClass !== currentClassId ? remote.fragmentFor(scopeClass) : null;
        if (updated && updated !== fragment) {
            if (JSON.stringify(updated) !== JSON.stringify(fragment)) replaceScope(scopeClass, false);
            else fragment = updated;
        }
    };
    viewer.refreshClasses = force => {
        if (!remote) return;
        const request = remote.loadIndex(force);
        if (!request) { applyRemoteClasses(); return; }
        return request.then(async index => {
            if (disposed || !index) return;
            if (scopeClass && scopeClass !== currentClassId && index.some(entry => entry.classId === scopeClass))
                await remote.loadFragment(scopeClass).catch(() => null);
            applyRemoteClasses();
        });
    };
    viewer.refreshClasses();
    const remembered = nodes.get(viewState?.selectedId);
    const initialNode = remembered && inScope(remembered) ? remembered : root;
    showNodeAction = Boolean(viewState?.showNodeAction && initialNode.nodeId === viewState.selectedId);
    showDetails(initialNode, false);
    setDetailVisible(viewState?.detailVisible ?? !window.matchMedia('(max-width: 760px)').matches);
    if (viewState) {
        camera = { ...viewState.camera };
        if (viewState.recenter && initialNode.nodeId === root.nodeId) {
            camera = fitCamera(bounds, viewportWidth, viewportHeight);
            draw();
        } else if (viewState.recenter || (viewState.selectedId && initialNode.nodeId !== viewState.selectedId)) focusNode(initialNode);
        else draw();
    }
    else {
        // Open on the area around the root (about five blocks each way) instead of a single node.
        const opening = Math.min(viewportWidth, viewportHeight) / (2 * 5 * layoutScale);
        if (opening > 0) camera.zoom = Math.max(minZoom(), Math.min(1, opening));
        focusNode(root);
    }
    viewer.querySelector('[data-tree-controls]').hidden = false;
    viewer.querySelector('[data-tree-empty]')?.setAttribute('hidden', '');
}
for (const viewer of document.querySelectorAll('[data-skilltree-viewer]')) initializeViewer(viewer);
