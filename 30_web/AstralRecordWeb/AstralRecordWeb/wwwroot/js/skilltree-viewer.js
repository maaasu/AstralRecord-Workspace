/* Read-only graph: view transforms and selection only; no node mutation or network writes. */
(() => {
    'use strict';
    const ns = 'http://www.w3.org/2000/svg';
    const make = (tag, attrs = {}, text) => {
        const element = document.createElementNS(ns, tag);
        Object.entries(attrs).forEach(([key, value]) => element.setAttribute(key, String(value)));
        if (text !== undefined) element.textContent = text;
        return element;
    };
    document.querySelectorAll('[data-skilltree-viewer]').forEach(viewer => {
        let tree;
        try { tree = JSON.parse(viewer.querySelector('[data-tree-json]').textContent); } catch { return; }
        if (!Array.isArray(tree.nodes) || !tree.nodes.length) return;
        const viewport = viewer.querySelector('.ar-tree-viewport');
        const svg = viewport.querySelector('svg');
        const graph = make('g');
        svg.append(graph);
        const nodes = new Map(tree.nodes.filter(n => Number.isFinite(n.x) && Number.isFinite(n.z)).map(n => [n.nodeId, n]));
        const groups = new Map();
        const scale = 36; // Same X/Z block projection as the skill tree editor.
        tree.edges.forEach(edge => {
            const source = nodes.get(edge.sourceNodeId), target = nodes.get(edge.targetNodeId);
            if (!source || !target) return;
            graph.append(make('line', { x1: source.x * scale, y1: source.z * scale, x2: target.x * scale, y2: target.z * scale,
                class: `ar-tree-edge${source.isUnlocked && target.isUnlocked ? ' is-unlocked' : ''}` }));
        });
        const select = node => {
            groups.forEach((g, id) => g.setAttribute('aria-pressed', String(id === node.nodeId)));
            viewer.querySelector('[data-node-name]').textContent = node.name;
            viewer.querySelector('[data-node-state]').textContent = node.isUnlocked ? '◆ 解放済み' : node.isConditionMet === false ? '◇ 未解放・必要条件未達' : '◇ 未解放';
            viewer.querySelector('[data-node-requirement]').textContent = node.requirementText ?? '';
            viewer.querySelector('[data-node-requirement]').classList.toggle('ar-condition-unmet', node.isConditionMet === false);
            viewer.querySelector('[data-node-cost]').textContent = `${node.pointType === 'PP' ? 'プレイヤーポイント' : 'クラスポイント'}（${node.pointType}）: ${node.pointCost}`;
            viewer.querySelector('[data-node-lore]').textContent = (node.lore ?? []).join('\n');
            viewer.querySelector('[data-node-effects]').textContent = (node.displayEffects ?? []).join('\n');
        };
        nodes.forEach(node => {
            const root = node.nodeId === tree.rootNodeId;
            const g = make('g', { class: `ar-tree-node${node.isUnlocked ? ' is-unlocked' : ''}${root ? ' is-root' : ''}`,
                transform: `translate(${node.x * scale},${node.z * scale})`, role: 'button', tabindex: 0,
                'aria-label': `${node.name}、${node.isUnlocked ? '解放済み' : '未解放'}`, 'aria-pressed': 'false' });
            g.append(make('rect', { x: -47, y: -37, width: 94, height: 74, rx: 10 }));
            g.append(make('text', { y: -2, class: 'ar-tree-symbol', 'aria-hidden': 'true' }, root ? '✦' : node.isUnlocked ? '◆' : '◇'));
            g.append(make('text', { y: 17 }, node.name.length > 9 ? `${node.name.slice(0, 8)}…` : node.name));
            g.append(make('text', { y: 30 }, `${node.pointType} ${node.pointCost}`));
            g.append(make('title', {}, `${node.name}\n${(node.lore ?? []).join('\n')}`));
            g.addEventListener('click', () => { if (!dragged) select(node); });
            g.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); select(node); }
            });
            g.addEventListener('focus', () => {
                if (!g.matches(':focus-visible')) return;
                x = viewport.clientWidth / 2 - node.x * scale * zoom;
                y = viewport.clientHeight / 2 - node.z * scale * zoom;
                draw();
            });
            graph.append(g); groups.set(node.nodeId, g);
        });
        let zoom = 1, x = 0, y = 0, drag = null, dragged = false;
        const draw = () => graph.setAttribute('transform', `translate(${x},${y}) scale(${zoom})`);
        const fit = () => {
            const list = [...nodes.values()];
            if (!list.length) return;
            const minX = Math.min(...list.map(n => n.x * scale)) - 70, maxX = Math.max(...list.map(n => n.x * scale)) + 70;
            const minY = Math.min(...list.map(n => n.z * scale)) - 60, maxY = Math.max(...list.map(n => n.z * scale)) + 60;
            zoom = Math.min(1.3, viewport.clientWidth / (maxX - minX), viewport.clientHeight / (maxY - minY));
            x = viewport.clientWidth / 2 - (minX + maxX) * zoom / 2;
            y = viewport.clientHeight / 2 - (minY + maxY) * zoom / 2;
            draw();
        };
        const zoomBy = factor => {
            const next = Math.max(.015, Math.min(3, zoom * factor));
            const cx = viewport.clientWidth / 2, cy = viewport.clientHeight / 2;
            x = cx - (cx - x) * next / zoom; y = cy - (cy - y) * next / zoom; zoom = next; draw();
        };
        viewer.querySelector('[data-tree-zoom="in"]').addEventListener('click', () => zoomBy(1.3));
        viewer.querySelector('[data-tree-zoom="out"]').addEventListener('click', () => zoomBy(1 / 1.3));
        viewer.querySelector('[data-tree-fit]').addEventListener('click', fit);
        viewport.addEventListener('pointerdown', event => {
            if (event.button !== 0 || event.target.closest('.ar-tree-node')) return;
            drag = { id: event.pointerId, x: event.clientX, y: event.clientY, originX: x, originY: y };
            dragged = false; viewport.setPointerCapture(event.pointerId);
        });
        viewport.addEventListener('pointermove', event => {
            if (!drag || drag.id !== event.pointerId) return;
            const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
            if (Math.abs(dx) + Math.abs(dy) > 4) dragged = true;
            x = drag.originX + dx; y = drag.originY + dy; draw();
        });
        const endDrag = () => { drag = null; requestAnimationFrame(() => { dragged = false; }); };
        viewport.addEventListener('pointerup', endDrag);
        viewport.addEventListener('pointercancel', endDrag);
        viewport.addEventListener('keydown', event => {
            const shifts = { ArrowLeft: [50, 0], ArrowRight: [-50, 0], ArrowUp: [0, 50], ArrowDown: [0, -50] };
            if (shifts[event.key]) { event.preventDefault(); x += shifts[event.key][0]; y += shifts[event.key][1]; draw(); }
            else if (event.key === '+' || event.key === '=') { event.preventDefault(); zoomBy(1.3); }
            else if (event.key === '-') { event.preventDefault(); zoomBy(1 / 1.3); }
            else if (event.key === 'Home') { event.preventDefault(); fit(); }
        });
        new ResizeObserver(fit).observe(viewport);
        fit();
    });
})();
