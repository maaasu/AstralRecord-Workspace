import { initializeViewer } from './skilltree-viewer.js';
import { createDraft, draftContext, settleDraft } from './skilltree-draft.mjs';

const editor = document.querySelector('[data-skilltree-editor]');
if (editor?.querySelector('[data-editor-state]')) startEditor(editor);

function startEditor(editor) {
    let state = JSON.parse(editor.querySelector('[data-editor-state]').textContent);
    let viewer = editor.querySelector('[data-skilltree-viewer]');
    if (!viewer) return;
    let selectedId = viewer.getViewState?.().selectedId, sourceClassId = '';
    let submitting = false, refreshing = false, lastRead = Date.now(), needsReview = false;
    let changes = [], draft = createDraft(state), baseline = draftContext(state), confirmation = null, draftOperationId = null;
    let operation = state.pendingOperation ?? null, unknownSubmission = null;
    const storageKey = `skilltree-operation:${editor.dataset.accountId}`;
    const terminal = new Set(['APPLIED', 'RECONFIRMATION_REQUIRED', 'FAILED', 'CANCELED', 'EXPIRED']);
    const labels = { PENDING_ONLINE: '適用待ち', PENDING_OFFLINE: '適用待ち（次回参加時に検証）', CLAIMED: '適用待ち（サーバーで確認中）', APPLIED: '適用済み', RECONFIRMATION_REQUIRED: '再確認が必要', FAILED: '失敗', CANCELED: '取消済み', EXPIRED: '期限切れ・再確認が必要' };
    const q = selector => editor.querySelector(selector);
    const text = (selector, value) => { const el = q(selector); if (el) el.textContent = value ?? ''; };
    const number = value => Number.isFinite(value) ? value.toLocaleString('ja-JP') : '—';
    const delta = (before, after) => Number.isFinite(before) && Number.isFinite(after) && after !== before ? `（${after > before ? '+' : ''}${number(after - before)}）` : '';
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
        if (state.connection?.status === 'unknown' || state.connection?.status === 'stale') return '接続状態を確認できません。マイページで接続情報を確認してください。';
        if (state.connection?.status === 'online' && !state.connection.canEdit) return 'ログイン中の編集は、拠点またはスキルツリーワールドで行ってください。';
        if (!state.canEdit) return 'サーバーの更新待ち、または状態の再確認が必要です。';
        if (!state.supportsBatch) return '一括編集に対応するサーバーの更新を待っています。';
        return '';
    };
    const busy = () => Boolean(submitting || unknownSubmission || operation && !terminal.has(operation.status));
    const invalidateConfirmation = () => { confirmation = null; q('[data-editor-confirm]').hidden = true; };
    const syncTree = () => {
        if (!draft.tree?.nodes?.length) return;
        if (viewer.updateTree?.(draft.tree)) return;
        const viewState = viewer.getViewState?.();
        const replacement = viewer.cloneNode(true);
        replacement.querySelector('[data-tree-json]').textContent = JSON.stringify(draft.tree);
        viewer.dispose?.(); viewer.replaceWith(replacement); viewer = replacement;
        initializeViewer(viewer, viewState);
    };
    const rebuildDraft = () => { draft = createDraft(state, changes); syncTree(); };
    const renderSelection = () => {
        const node = draft.tree.nodes.find(n => n.nodeId === selectedId);
        if (!node) return;
        const staged = changes.some(change => change.nodeId === node.nodeId);
        const source = q('[data-editor-source]');
        q('[data-editor-source-label]').hidden = !node.requiresCpSourceSelection || node.isUnlocked || staged;
        const sourceKey = JSON.stringify([node.nodeId, node.isUnlocked, node.pointCost, node.cpSources]);
        if (source.dataset.optionsKey !== sourceKey) {
        source.dataset.optionsKey = sourceKey;
        source.replaceChildren();
        if (node.requiresCpSourceSelection && !node.isUnlocked) {
            const empty = document.createElement('option'); empty.value = ''; empty.textContent = '消費元クラスを選択'; source.append(empty);
            for (const item of node.cpSources ?? []) {
                const option = document.createElement('option'); option.value = item.classId;
                option.textContent = `${item.className}（変更後 ${number(Math.max(0, item.availableCp))} CP）`;
                option.disabled = item.availableCp < node.pointCost; source.append(option);
            }
        }
        source.value = sourceClassId;
        }
        source.disabled = busy();
        const change = { action: node.isUnlocked ? 'RELOCK' : 'UNLOCK', nodeId: node.nodeId, sourceClassId: node.requiresCpSourceSelection ? sourceClassId || null : null };
        const error = staged ? '' : draft.check(change);
        text('[data-editor-consumed]', node.isUnlocked && node.pointType === 'CP' ? node.pointCost === 0 ? '消費なし（0 CP）' : `消費元：${node.consumedClassName || '確認が必要'}` : '');
        text('[data-editor-preview]', staged ? 'このノードは変更案に入っています。まだ適用されていません。' : node.isUnlocked
            ? `返還：${node.pointType === 'CP' ? (node.consumedClassName || '消費元クラス') + ' CP' : 'PP'} ${number(node.pointCost)} ／ 解除費用 ${number(state.relockGoldCost)} Gold`
            : `解放費用：${node.pointType === 'CP' ? (node.requiresCpSourceSelection ? '選択クラスのCP' : (node.cpSourceClassName || '対象クラス') + ' CP') : 'PP'} ${number(node.pointCost)}`);
        text('[data-editor-operation-reason]', error);
        text('[data-editor-submit]', staged ? 'この変更を案から外す' : node.isUnlocked ? '解除を変更案へ追加' : '解放を変更案へ追加');
        q('[data-editor-submit]').disabled = Boolean(reason() || busy() || error || draft.error && !staged);
    };
    const render = () => {
        const fresh = Date.now() - lastRead <= 15000 && state.balanceKind !== 'UNKNOWN';
        const before = fresh ? state.points ?? {} : {}, after = fresh ? draft.points : {};
        text('[data-editor-account]', state.accountName);
        text('[data-editor-heading-account]', state.accountName);
        text('[data-editor-heading-kind]', !fresh ? '残高未確認' : state.balanceKind === 'SAVED' ? '保存済み残高・オフラインの変更案' : '確定残高 → 変更後');
        text('[data-editor-balance-kind]', !fresh ? '残高未確認' : state.balanceKind === 'SAVED' ? '保存済み残高・オフラインの変更案' : 'サーバーで確定済みの残高');
        text('[data-editor-pp]', number(before.pp));
        text('[data-editor-pp-after]', changes.length ? `変更後 ${number(after.pp)} PP ${delta(before.pp, after.pp)}` : '変更なし');
        text('[data-editor-gold]', number(before.gold));
        text('[data-editor-gold-after]', draft.goldCost ? `必要 ${number(draft.goldCost)} Gold ／ 変更後 ${number(after.gold)}` : '解除費用なし');
        const cp = q('[data-editor-cp]'); cp.replaceChildren();
        for (const item of before.classes ?? []) {
            const planned = after.classes?.find(c => c.classId === item.classId)?.availableCp;
            const badge = document.createElement('span'), label = document.createElement('span'), value = document.createElement('strong'), preview = document.createElement('small');
            label.textContent = item.className; value.textContent = number(item.availableCp);
            preview.textContent = changes.length ? `変更後 ${number(planned)} CP ${delta(item.availableCp, planned)}` : '';
            badge.append(label, value, preview); cp.append(badge);
        }
        if (!cp.childElementCount) cp.textContent = '残高を確認できません';
        text('[data-editor-status]', unknownSubmission ? '適用結果を確認中' : needsReview ? operation?.status === 'CANCELED' ? '取消済み・変更案を再確認' : '再確認が必要' : operation ? (labels[operation.status] ?? '再確認が必要') + (operation.changes?.length ? `（${operation.changes.length}件）` : '') : '編集中');
        text('[data-editor-draft-count]', changes.length ? `変更案 ${changes.length} 件` : '変更なし');
        text('[data-editor-reason]', reason() || draft.error || (operation && terminal.has(operation.status) ? operation.reason : '') || (needsReview ? operation?.status === 'CANCELED' ? '要求を取り消しました。未適用の変更案を再確認すると、編集を続けられます。' : '最新の残高・条件・状態で変更案を再確認してください。' : ''));
        q('[data-editor-cancel]').hidden = !operation || terminal.has(operation.status);
        q('[data-editor-cancel]').disabled = submitting;
        q('[data-editor-undo]').disabled = busy() || !changes.length;
        q('[data-editor-clear]').disabled = busy() || !changes.length;
        q('[data-editor-review]').hidden = !needsReview;
        q('[data-editor-review]').disabled = busy() || Boolean(reason() || draft.error);
        q('[data-editor-batch-review]').disabled = busy() || !changes.length || needsReview || Boolean(reason() || draft.error);
        renderSelection();
    };
    const refresh = async () => {
        if (refreshing) return false;
        refreshing = true;
        try {
            const next = await request('State');
            const nextContext = draftContext(next);
            if (changes.length && nextContext !== baseline) { needsReview = true; invalidateConfirmation(); }
            state = next; lastRead = Date.now();
            if (!changes.length) baseline = nextContext;
            if (next.pendingOperation) operation = next.pendingOperation;
            rebuildDraft(); render(); return true;
        } catch { lastRead = 0; invalidateConfirmation(); render(); return false; }
        finally { refreshing = false; }
    };
    const acceptOperation = async result => {
        operation = result; unknownSubmission = null;
        if (terminal.has(result.status)) {
            forget();
            const settled = settleDraft(changes, draftOperationId, result);
            changes = settled.changes; needsReview = settled.needsReview;
            await refresh();
        }
        render();
    };
    const pollOperation = async () => {
        const id = unknownSubmission?.operationId ?? operation?.operationId;
        if (!id) return;
        try { await acceptOperation(await request('Operation', 'GET', undefined, { operationId: id })); }
        catch (error) {
            if (error.message === '404' && unknownSubmission) {
                try { await acceptOperation(await request('Operation', 'POST', unknownSubmission)); }
                catch (retryError) {
                    if (['400', '401', '403', '404', '409'].includes(retryError.message)) {
                        unknownSubmission = null; forget(); operation = { operationId: id, status: 'RECONFIRMATION_REQUIRED' }; needsReview = true; render();
                    }
                }
            } else text('[data-editor-status]', '適用結果を確認できません。再取得で確認してください。');
        }
    };
    const showConfirmation = () => {
        confirmation = { context: baseline, changes: structuredClone(changes) };
        text('[data-editor-confirm-summary]', `${changes.length} 件の変更 ／ 必要 ${number(draft.goldCost)} Gold ／ 変更後 PP ${number(draft.points.pp)}`);
        const list = q('[data-editor-confirm-list]'); list.replaceChildren();
        for (const change of changes) {
            const node = state.tree.nodes.find(n => n.nodeId === change.nodeId), row = document.createElement('li');
            row.textContent = `${change.action === 'UNLOCK' ? '解放' : '解除'}：${node?.name || '再確認が必要なノード'}${change.sourceClassId ? ' ／ ' + (node?.cpSources ?? []).find(c => c.classId === change.sourceClassId)?.className : ''}`;
            list.append(row);
        }
        q('[data-editor-confirm]').hidden = false;
        q('[data-editor-confirm-send]').focus();
    };
    editor.addEventListener('skilltree:select', event => { selectedId = event.detail.nodeId; sourceClassId = ''; renderSelection(); });
    editor.addEventListener('change', event => { if (event.target.matches('[data-editor-source]')) { sourceClassId = event.target.value; renderSelection(); } });
    editor.addEventListener('click', async event => {
        const button = event.target.closest('button'); if (!button || button.disabled) return;
        if (button.matches('[data-editor-refresh]')) { await refresh(); await pollOperation(); }
        if (button.matches('[data-editor-submit]') && !busy()) {
            const node = draft.tree.nodes.find(n => n.nodeId === selectedId); if (!node) return;
            const index = changes.findIndex(change => change.nodeId === selectedId);
            const next = index >= 0 ? changes.filter((_, i) => i !== index) : [...changes, { action: node.isUnlocked ? 'RELOCK' : 'UNLOCK', nodeId: node.nodeId, sourceClassId: !node.isUnlocked && node.requiresCpSourceSelection ? sourceClassId || null : null }];
            const candidate = createDraft(state, next);
            if (candidate.error) { text('[data-editor-operation-reason]', candidate.error); return; }
            if (!changes.length) baseline = draftContext(state);
            changes = next; operation = null; draftOperationId = null; invalidateConfirmation(); rebuildDraft(); render();
        }
        if (button.matches('[data-editor-undo], [data-editor-clear]') && !busy()) {
            changes = button.matches('[data-editor-clear]') ? [] : changes.slice(0, -1);
            if (!changes.length) { needsReview = false; baseline = draftContext(state); }
            invalidateConfirmation(); rebuildDraft(); render();
        }
        if (button.matches('[data-editor-review]') && !busy() && !draft.error) { needsReview = false; baseline = draftContext(state); operation = null; draftOperationId = null; render(); }
        if (button.matches('[data-editor-batch-review]')) showConfirmation();
        if (button.matches('[data-editor-confirm-back]')) invalidateConfirmation();
        if (button.matches('[data-editor-cancel]') && operation && !submitting) {
            submitting = true; render();
            try { await acceptOperation(await request('Cancel', 'POST', undefined, { operationId: operation.operationId })); }
            catch { text('[data-editor-status]', '取消結果を確認できません。再取得してください。'); }
            finally { submitting = false; render(); }
        }
        if (button.matches('[data-editor-confirm-send]') && confirmation && !busy()) {
            const captured = confirmation;
            submitting = true; invalidateConfirmation(); render();
            if (!await refresh() || reason() || needsReview || draft.error || unknownSubmission || operation && !terminal.has(operation.status)
                || captured.context !== draftContext(state) || JSON.stringify(captured.changes) !== JSON.stringify(changes)) {
                submitting = false; needsReview = true; render(); return;
            }
            const payload = { operationId: crypto.randomUUID(), targetServerId: state.connection?.serverId,
                expectedDefinitionGenerationId: state.generationId, expectedPlayerStateVersion: state.stateRevision,
                action: 'BATCH', nodeId: 'batch', changes: captured.changes };
            unknownSubmission = payload; draftOperationId = payload.operationId; remember(payload);
            try { await acceptOperation(await request('Operation', 'POST', payload)); }
            catch (error) {
                if (['400', '401', '403', '404', '409'].includes(error.message)) { operation = { operationId: payload.operationId, status: 'RECONFIRMATION_REQUIRED' }; unknownSubmission = null; forget(); needsReview = true; }
                else await pollOperation();
            }
            finally { submitting = false; render(); }
        }
    });
    try { unknownSubmission = JSON.parse(sessionStorage.getItem(storageKey) || 'null'); } catch { unknownSubmission = null; }
    if (unknownSubmission?.changes) { changes = unknownSubmission.changes; draftOperationId = unknownSubmission.operationId; }
    else if (operation && !terminal.has(operation.status) && operation.changes) { changes = operation.changes; draftOperationId = operation.operationId; }
    selectedId ??= state.tree?.rootNodeId ?? state.tree?.nodes[0]?.nodeId;
    rebuildDraft(); render();
    if (unknownSubmission || operation) pollOperation();
    setInterval(async () => {
        if (document.hidden || submitting || viewer.classList.contains('is-panning')) return;
        render(); await refresh();
        if (unknownSubmission || operation && !terminal.has(operation.status)) await pollOperation();
    }, 5000);
}
