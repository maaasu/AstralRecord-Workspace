import { initializeViewer } from './skilltree-viewer.js';
import { nodeCost } from './skilltree-geometry.mjs';

const editor = document.querySelector('[data-skilltree-editor]');
if (editor?.querySelector('[data-editor-state]')) startEditor(editor);

function startEditor(editor) {
    let state = JSON.parse(editor.querySelector('[data-editor-state]').textContent);
    let viewer = editor.querySelector('[data-skilltree-viewer]');
    if (!viewer) return;
    let selected, sourceClassId = '', submitting = false, refreshing = false, lastRead = Date.now();
    let operation = state.pendingOperation ?? null, unknownSubmission = null;
    const storageKey = `skilltree-operation:${editor.dataset.accountId}`;
    const terminal = new Set(['APPLIED', 'RECONFIRMATION_REQUIRED', 'FAILED', 'CANCELED', 'EXPIRED']);
    const labels = { PENDING_ONLINE: '適用待ち', PENDING_OFFLINE: '適用待ち（次回参加時に再確認）', CLAIMED: '適用待ち（サーバーで確認中）', APPLIED: '適用済み', RECONFIRMATION_REQUIRED: '再確認が必要', FAILED: '失敗', CANCELED: '取消済み', EXPIRED: '期限切れ・再確認が必要' };
    const q = selector => editor.querySelector(selector);
    const text = (selector, value) => { const el = q(selector); if (el) el.textContent = value ?? ''; };
    const endpoint = (handler, extra = {}) => {
        const url = new URL(location.href); url.search = new URLSearchParams({ handler, ...extra }); return url;
    };
    const request = async (handler, method = 'GET', body, extra) => {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 10000);
        try {
        const response = await fetch(endpoint(handler, extra), {
            method, cache: 'no-store', credentials: 'same-origin',
            signal: controller.signal,
            headers: { 'Content-Type': 'application/json', RequestVerificationToken: q('input[name="__RequestVerificationToken"]').value },
            body: body === undefined ? undefined : JSON.stringify(body),
        });
        if (!response.ok || response.redirected) throw new Error(String(response.status));
        return await response.json();
        } finally { clearTimeout(timeout); }
    };
    const remember = payload => { try { sessionStorage.setItem(storageKey, JSON.stringify(payload)); } catch { /* The operation id is still held in memory. */ } };
    const forget = () => { try { sessionStorage.removeItem(storageKey); } catch { /* Storage may be disabled. */ } };
    const reason = () => {
        if (Date.now() - lastRead > 15000) return '最新の状態を再取得してください。';
        if (state.connection?.status === 'unknown') return '接続状態を確認できません。最新の状態を再取得してください。';
        if (state.connection?.status === 'online' && !state.connection.canEdit) return 'ログイン中の編集は、拠点またはスキルツリーワールドで行ってください。';
        if (!state.canEdit) return 'サーバーの更新待ち、または再確認が必要な状態です。最新の状態を再取得してください。';
        return '';
    };
    const busy = () => submitting || unknownSubmission || (operation && !terminal.has(operation.status));
    const render = () => {
        const fresh = Date.now() - lastRead <= 15000;
        const connection = fresh ? state.connection ?? {} : {}, points = fresh ? state.points ?? {} : {};
        text('[data-editor-account]', state.accountName);
        text('[data-editor-balance-kind]', !fresh || state.balanceKind === 'UNKNOWN' ? '残高未確認' : state.balanceKind === 'SAVED' ? 'オフライン・保存済み残高' : '確定済み');
        text('[data-editor-connection]', connection.status === 'online' ? `ログイン中 · ${connection.channelName || 'チャンネル確認中'}` : connection.status === 'offline' ? 'オフライン · 編集案として保存' : '接続状態を確認できません');
        text('[data-editor-location]', connection.status === 'online' ? `${connection.worldName || '現在地を確認中'}${[connection.x, connection.y, connection.z].every(Number.isFinite) ? ` / X ${Math.floor(connection.x)} · Y ${Math.floor(connection.y)} · Z ${Math.floor(connection.z)}` : ''}` : '');
        text('[data-editor-observed]', connection.observedAtUtc ? `確認時刻 ${new Date(connection.observedAtUtc).toLocaleTimeString('ja-JP')}` : '');
        text('[data-editor-pp]', `PP ${Number.isFinite(points.pp) ? points.pp.toLocaleString('ja-JP') : '—'}`);
        text('[data-editor-gold]', `${Number.isFinite(points.gold) ? points.gold.toLocaleString('ja-JP') : '—'} Gold`);
        const cp = q('[data-editor-cp]'); cp.replaceChildren();
        for (const item of points.classes ?? []) {
            const badge = document.createElement('span'); badge.textContent = `${item.className} CP ${item.availableCp}`; cp.append(badge);
        }
        text('[data-editor-status]', unknownSubmission ? '適用結果を確認中' : operation ? labels[operation.status] ?? '再確認が必要' : '編集中');
        text('[data-editor-reason]', reason());
        q('[data-editor-cancel]').hidden = !operation || terminal.has(operation.status);
        q('[data-editor-cancel]').disabled = submitting;
        renderSelection();
    };
    const renderSelection = () => {
        if (!selected) return;
        const node = selected, source = q('[data-editor-source]');
        q('[data-editor-source-label]').hidden = !node.requiresCpSourceSelection || node.isUnlocked;
        source.replaceChildren();
        if (node.requiresCpSourceSelection && !node.isUnlocked) {
            const empty = document.createElement('option'); empty.value = ''; empty.textContent = '消費元クラスを選択'; source.append(empty);
            for (const item of node.cpSources ?? []) {
                const option = document.createElement('option'); option.value = item.classId;
                option.textContent = `${item.className}（使用可能 ${item.availableCp} CP）`; option.disabled = item.availableCp < node.pointCost; source.append(option);
            }
            source.value = sourceClassId;
        }
        text('[data-editor-consumed]', node.isUnlocked && node.pointType === 'CP' ? node.pointCost === 0 ? '消費なし（0 CP）' : `実際の消費元：${node.consumedClassName || '確認が必要'}` : '');
        const sourceName = (node.cpSources ?? []).find(c => c.classId === sourceClassId)?.className;
        const refund = node.pointType === 'CP' ? `${node.consumedClassName || '消費元クラス'} CP` : 'PP';
        const goldCost = state.relockGoldCost;
        const preview = node.isUnlocked
            ? `編集案：${refund} +${node.pointCost} 返還 / ${Number.isFinite(goldCost) ? goldCost : '—'} Gold 消費`
            : `編集案：${sourceName ? sourceName + ' CP ' + node.pointCost : nodeCost(node)} を消費`;
        text('[data-editor-preview]', preview);
        const lacksGold = node.isUnlocked && (!Number.isFinite(goldCost) || !Number.isFinite(state.points?.gold) || state.points.gold < goldCost);
        const validSource = !node.requiresCpSourceSelection || node.isUnlocked || (node.cpSources ?? []).some(c => c.classId === sourceClassId && c.availableCp >= node.pointCost);
        const possible = node.isUnlocked ? node.canRelock : node.canUnlock;
        text('[data-editor-operation-reason]', lacksGold ? '解除に必要なGoldが不足しているか、残高を確認できません。' : !validSource ? '消費するクラスのCPを選択してください。' : !possible ? '接続・条件・ポイントを確認してください。このノードは現在変更できません。' : '');
        q('[data-editor-submit]').textContent = node.isUnlocked ? '解放解除の内容を確認' : '解放の内容を確認';
        q('[data-editor-submit]').disabled = Boolean(reason() || busy() || !possible || !validSource || lacksGold);
        source.disabled = Boolean(busy());
    };
    const replaceTree = () => {
        if (!state.tree) return;
        const viewState = viewer.getViewState?.();
        const replacement = viewer.cloneNode(true);
        replacement.querySelector('[data-tree-json]').textContent = JSON.stringify(state.tree);
        viewer.dispose?.(); viewer.replaceWith(replacement); viewer = replacement;
        initializeViewer(viewer, viewState);
        selected = state.tree.nodes.find(n => n.nodeId === selected?.nodeId) ?? state.tree.nodes[0];
        q('[data-editor-confirm]').hidden = true;
    };
    const refresh = async () => {
        if (refreshing) return false;
        refreshing = true;
        try {
            const next = await request('State');
            const changed = next.generationId !== state.generationId || next.stateRevision !== state.stateRevision || JSON.stringify(next.tree) !== JSON.stringify(state.tree);
            state = next; lastRead = Date.now();
            if (next.pendingOperation) operation = next.pendingOperation;
            if (changed) { sourceClassId = ''; replaceTree(); }
            else if (selected) selected = state.tree?.nodes.find(n => n.nodeId === selected.nodeId) ?? selected;
            render(); return true;
        } catch {
            lastRead = 0; render(); return false;
        } finally { refreshing = false; }
    };
    const pollOperation = async () => {
        const id = unknownSubmission?.operationId ?? operation?.operationId;
        if (!id) return;
        try {
            operation = await request('Operation', 'GET', undefined, { operationId: id });
            unknownSubmission = null;
            if (terminal.has(operation.status)) { forget(); await refresh(); }
            render();
        } catch (error) {
            if (error.message === '404' && unknownSubmission) {
                // The original request may not have reached the API. Reuse its exact id and preconditions.
                try { operation = await request('Operation', 'POST', unknownSubmission); unknownSubmission = null; render(); }
                catch (retryError) {
                    if (['400', '403', '404', '409'].includes(retryError.message)) {
                        operation = { operationId: id, status: 'RECONFIRMATION_REQUIRED' }; unknownSubmission = null; forget(); render();
                    }
                }
            } else text('[data-editor-status]', '適用結果を確認できません。再取得で確認してください。');
        }
    };
    editor.addEventListener('skilltree:select', event => {
        selected = event.detail; sourceClassId = ''; q('[data-editor-confirm]').hidden = true; renderSelection();
    });
    editor.addEventListener('change', event => {
        if (event.target.matches('[data-editor-source]')) { sourceClassId = event.target.value; q('[data-editor-confirm]').hidden = true; renderSelection(); }
    });
    editor.addEventListener('click', async event => {
        const button = event.target.closest('button'); if (!button) return;
        if (button.matches('[data-editor-refresh]')) { await refresh(); await pollOperation(); }
        if (button.matches('[data-editor-submit]') && !button.disabled) q('[data-editor-confirm]').hidden = false;
        if (button.matches('[data-editor-confirm-back]')) q('[data-editor-confirm]').hidden = true;
        if (button.matches('[data-editor-cancel]') && operation && !submitting) {
            submitting = true; render();
            try { operation = await request('Cancel', 'POST', undefined, { operationId: operation.operationId }); forget(); await refresh(); }
            catch { text('[data-editor-status]', '取消結果を確認できません。最新の状態を再取得してください。'); }
            finally { submitting = false; render(); }
        }
        if (button.matches('[data-editor-confirm-send]') && selected && !busy()) {
            const expectedGeneration = state.generationId, expectedRevision = state.stateRevision;
            const chosenNode = selected, chosenSource = sourceClassId;
            submitting = true; q('[data-editor-confirm]').hidden = true; render();
            if (!await refresh() || reason() || unknownSubmission || (operation && !terminal.has(operation.status))
                || state.generationId !== expectedGeneration || state.stateRevision !== expectedRevision) {
                submitting = false; render(); text('[data-editor-status]', '再確認が必要。最新の状態で変更内容を確認してください。'); return;
            }
            const payload = {
                operationId: crypto.randomUUID(), targetServerId: state.connection?.serverId ?? null,
                expectedDefinitionGenerationId: expectedGeneration, expectedPlayerStateVersion: expectedRevision,
                action: chosenNode.isUnlocked ? 'RELOCK' : 'UNLOCK', nodeId: chosenNode.nodeId,
                sourceClassId: chosenNode.isUnlocked ? null : chosenSource || null,
            };
            unknownSubmission = payload; remember(payload);
            try { operation = await request('Operation', 'POST', payload); unknownSubmission = null; }
            catch (error) {
                if (['400', '401', '403', '404', '409'].includes(error.message)) {
                    operation = { operationId: payload.operationId, status: 'RECONFIRMATION_REQUIRED' };
                    unknownSubmission = null; forget();
                } else await pollOperation();
            }
            finally { submitting = false; render(); }
        }
    });
    try { unknownSubmission = JSON.parse(sessionStorage.getItem(storageKey) || 'null'); } catch { unknownSubmission = null; }
    selected = state.tree?.nodes.find(n => n.nodeId === viewer.getViewState?.().selectedId) ?? state.tree?.nodes[0];
    render();
    if (unknownSubmission || operation) pollOperation();
    setInterval(async () => {
        if (document.hidden || submitting) return;
        render();
        await refresh();
        if (unknownSubmission || (operation && !terminal.has(operation.status))) await pollOperation();
    }, 5000);
}
