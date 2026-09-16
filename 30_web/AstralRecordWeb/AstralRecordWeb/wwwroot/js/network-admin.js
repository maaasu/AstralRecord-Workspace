(() => {
    'use strict';
    const form = document.querySelector('[data-network-admin]');
    if (form) initializePlayerPicker(form);
    const management = document.querySelector('[data-ban-management]');
    if (management) initializeBanEditor(management);

    function initializePlayerPicker(form) {
        const channel = form.querySelector('[data-user-channel]');
        const role = form.querySelector('[data-user-role]');
        const query = form.querySelector('[data-player-query]');
        const candidates = form.querySelector('[data-player-candidates]');
        const status = form.querySelector('[data-search-status]');
        const feedback = form.querySelector('[data-member-feedback]');
        if (!channel || !role || !query || !candidates || !status || !feedback) return;
        let timer, request;
        let generation = 0;
        let channelRole = role.value;
        const targetList = () => {
            if (channel.value === 'authority') return form.querySelector('[data-user-list="authority"]');
            if (!/^\d+$/.test(channel.value) || !['debug', 'whitelist'].includes(role.value)) return null;
            return form.querySelector(`[data-user-list="${role.value}"][data-channel-index="${channel.value}"]`);
        };
        const targetLabel = () => `${channel.selectedOptions[0]?.textContent ?? ''} / ${role.selectedOptions[0]?.textContent ?? ''}`;
        const contains = (list, uuid) => list && Array.from(list.querySelectorAll('li[data-user-uuid]'))
            .some(item => item.dataset.userUuid.toLowerCase() === uuid.toLowerCase());
        const refreshNames = list => list.querySelectorAll('input[type="hidden"]').forEach((input, index) => {
            input.name = list.dataset.userList === 'authority' ? `Input.AuthorityUsers[${index}]`
                : `Input.Channels[${list.dataset.channelIndex}].${list.dataset.userList === 'debug' ? 'DebugUsers' : 'WhitelistUsers'}[${index}]`;
        });
        const updateButtons = () => {
            const list = targetList();
            candidates.querySelectorAll('[data-add-network-user]').forEach(button => {
                const added = contains(list, button.dataset.userUuid);
                button.disabled = !list || added;
                button.textContent = added ? '追加済み' : '追加';
            });
        };
        const updateTarget = () => {
            const authority = channel.value === 'authority';
            role.replaceChildren(...(authority ? [new Option('全体の管理権限', 'authority')]
                : [new Option('デバッグ利用者', 'debug'), new Option('ホワイトリスト', 'whitelist')]));
            role.disabled = authority;
            if (!authority) role.value = channelRole;
            feedback.textContent = '';
            updateButtons();
        };
        channel.addEventListener('change', updateTarget);
        role.addEventListener('change', () => { channelRole = role.value; feedback.textContent = ''; updateButtons(); });
        form.querySelectorAll('[data-channel-label]').forEach(input => input.addEventListener('input', () => {
            const option = Array.from(channel.options).find(item => item.value === input.dataset.channelLabel);
            if (option) option.textContent = input.value.trim() || '名前未設定のチャンネル';
        }));
        updateTarget();
        function playerText(player) {
            const text = document.createElement('span');
            const name = document.createElement('strong');
            name.textContent = player.mcid;
            const uuid = document.createElement('small');
            uuid.textContent = player.userUuid;
            text.append(name, uuid);
            return text;
        }
        function renderPlayers(players) {
            candidates.replaceChildren();
            players.forEach(player => {
                const row = document.createElement('li');
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'btn ar-btn-outline';
                button.dataset.addNetworkUser = '';
                button.dataset.userUuid = player.userUuid;
                button.dataset.mcid = player.mcid;
                row.append(playerText(player), button);
                candidates.append(row);
            });
            updateButtons();
        }
        async function search(searchGeneration) {
            const prefix = query.value.trim();
            if (!prefix || searchGeneration !== generation) return;
            request = new AbortController();
            status.textContent = '候補を検索しています…';
            candidates.setAttribute('aria-busy', 'true');
            try {
                const url = new URL(form.dataset.playerSearchUrl, window.location.href);
                url.searchParams.set('query', prefix);
                const response = await fetch(url, { signal: request.signal, credentials: 'same-origin', cache: 'no-store',
                    headers: { Accept: 'application/json', 'X-Requested-With': 'XMLHttpRequest' } });
                if (searchGeneration !== generation) return;
                if (response.redirected || response.status === 401 || response.status === 403) {
                    status.textContent = '管理権限を確認できません。再ログインしてからお試しください。';
                    return;
                }
                if (!response.ok) throw new Error('Search unavailable');
                const result = await response.json();
                if (searchGeneration !== generation) return;
                renderPlayers(result.players);
                status.textContent = result.players.length ? `${result.players.length}人の候補が見つかりました。追加先を確認して選択してください。` : '一致するMCIDが見つかりませんでした。';
            } catch (error) {
                if (error.name !== 'AbortError' && searchGeneration === generation)
                    status.textContent = '候補を取得できませんでした。入力し直して再試行してください。';
            } finally {
                if (searchGeneration === generation) candidates.setAttribute('aria-busy', 'false');
            }
        }
        function scheduleSearch(immediate = false) {
            clearTimeout(timer);
            request?.abort();
            generation++;
            candidates.replaceChildren();
            candidates.setAttribute('aria-busy', 'false');
            if (!query.value.trim()) { status.textContent = 'MCIDを入力すると候補が表示されます。'; return; }
            status.textContent = '候補を検索しています…';
            const current = generation;
            timer = setTimeout(() => search(current), immediate ? 0 : 250);
        }
        query.addEventListener('input', event => { if (!event.isComposing) scheduleSearch(); });
        query.addEventListener('compositionstart', () => { clearTimeout(timer); request?.abort(); generation++; candidates.replaceChildren(); });
        query.addEventListener('compositionend', () => scheduleSearch());
        query.addEventListener('keydown', event => {
            if (event.key === 'Enter' && !event.isComposing) { event.preventDefault(); scheduleSearch(true); }
        });
        if (query.value.trim()) scheduleSearch();
        form.addEventListener('click', event => {
            const removeAction = event.target.closest('[data-remove-network-user]');
            if (removeAction) {
                const list = removeAction.closest('[data-user-list]');
                removeAction.closest('li')?.remove();
                if (list) refreshNames(list);
                updateButtons();
                feedback.textContent = '一覧から外しました。「設定を保存する」で反映します。';
                return;
            }
            const addAction = event.target.closest('[data-add-network-user]');
            if (!addAction) return;
            const list = targetList();
            const userUuid = addAction.dataset.userUuid;
            if (!list || !userUuid || contains(list, userUuid)) return;
            const item = document.createElement('li');
            item.dataset.userUuid = userUuid;
            const input = document.createElement('input');
            input.type = 'hidden';
            input.value = userUuid;
            const removeActionButton = document.createElement('button');
            removeActionButton.type = 'button';
            removeActionButton.className = 'btn ar-btn-outline';
            removeActionButton.dataset.removeNetworkUser = '';
            removeActionButton.textContent = '外す';
            item.append(playerText({ userUuid, mcid: addAction.dataset.mcid }), input, removeActionButton);
            list.append(item);
            refreshNames(list);
            updateButtons();
            feedback.textContent = `${addAction.dataset.mcid} を「${targetLabel()}」に追加しました。「設定を保存する」で反映します。`;
        });
    }

    function initializeBanEditor(management) {
        const editor = management.querySelector('[data-ban-editor]');
        const dialog = management.querySelector('[data-ban-confirm]');
        const open = management.querySelector('[data-ban-open]');
        if (!editor || !dialog || !open) return;
        const date = editor.querySelector('[data-ban-date]');
        const time = editor.querySelector('[data-ban-time]');
        const indefinite = editor.querySelector('[data-ban-indefinite]');
        const reason = editor.querySelector('[data-ban-reason]');
        const expiryFields = editor.querySelector('[data-ban-expiry-fields]');
        const isBan = () => editor.querySelector('[data-ban-mode="ban"]').checked;
        const jstValue = timestamp => new Date(timestamp + 9 * 60 * 60 * 1000).toISOString().slice(0, 16);
        let confirmed = false;
        const updateFields = () => {
            indefinite.disabled = !isBan();
            expiryFields.disabled = !isBan() || indefinite.checked;
            date.required = time.required = !expiryFields.disabled;
            reason.disabled = !isBan();
            date.min = jstValue(Date.now()).slice(0, 10);
            date.setCustomValidity('');
            time.setCustomValidity('');
        };
        editor.addEventListener('change', updateFields);
        updateFields();
        editor.querySelector('[data-ban-calendar]').addEventListener('click', () => {
            if (typeof date.showPicker === 'function') date.showPicker();
            else date.focus();
        });
        editor.querySelectorAll('[data-ban-days]').forEach(button => button.addEventListener('click', () => {
            const value = jstValue(Date.now() + Number(button.dataset.banDays) * 86400000);
            date.value = value.slice(0, 10);
            time.value = value.slice(11, 16);
            updateFields();
        }));
        function openConfirmation() {
            updateFields();
            if (isBan() && !indefinite.checked && date.value && time.value
                && new Date(`${date.value}T${time.value}:00+09:00`).getTime() <= Date.now())
                time.setCustomValidity('現在より後の解除日時を指定してください。');
            if (!editor.reportValidity()) return;
            const setText = (selector, value) => { dialog.querySelector(selector).textContent = value; };
            setText('[data-ban-confirm-target]', management.dataset.targetMcid ?? '—');
            setText('[data-ban-confirm-uuid]', management.dataset.targetUuid ?? '—');
            setText('[data-ban-confirm-action]', isBan() ? '利用停止にする' : '利用停止を解除する');
            setText('[data-ban-confirm-expiry]', isBan() ? (indefinite.checked ? '無期限' : `${date.value.replaceAll('-', '/')} ${time.value}（日本時間）`) : '—');
            setText('[data-ban-confirm-reason]', isBan() ? (reason.value.trim() || '理由なし') : '—');
            dialog.showModal();
        }
        open.addEventListener('click', openConfirmation);
        editor.addEventListener('submit', event => {
            if (!confirmed) { event.preventDefault(); openConfirmation(); }
        });
        dialog.querySelector('[data-ban-cancel]').addEventListener('click', () => dialog.close());
        dialog.querySelector('[data-ban-submit]').addEventListener('click', () => {
            confirmed = true;
            editor.requestSubmit();
            confirmed = false;
        });
    }
})();
