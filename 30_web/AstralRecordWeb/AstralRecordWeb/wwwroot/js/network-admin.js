(() => {
    'use strict';

    const form = document.querySelector('[data-network-admin]');
    if (form) {

    const target = () => form.querySelector('[data-user-target]')?.value ?? '';
    const listFor = (value) => {
        if (value === 'authority') return form.querySelector('[data-user-list="authority"]');
        const [kind, channelIndex] = value.split(':');
        return form.querySelector(`[data-user-list="${kind}"][data-channel-index="${channelIndex}"]`);
    };
    const inputName = (list, index) => {
        const kind = list.dataset.userList;
        if (kind === 'authority') return `Input.AuthorityUsers[${index}]`;
        return `Input.Channels[${list.dataset.channelIndex}].${kind === 'debug' ? 'DebugUsers' : 'WhitelistUsers'}[${index}]`;
    };
    const refreshNames = (list) => list.querySelectorAll('input[type="hidden"]').forEach((input, index) => {
        input.name = inputName(list, index);
    });
    const remove = (button) => {
        const list = button.closest('[data-user-list]');
        button.closest('li')?.remove();
        if (list) refreshNames(list);
    };

    form.addEventListener('click', (event) => {
        const removeButton = event.target.closest('[data-remove-network-user]');
        if (removeButton) {
            remove(removeButton);
            return;
        }
        const addButton = event.target.closest('[data-add-network-user]');
        if (!addButton) return;
        const list = listFor(target());
        const userUuid = addButton.dataset.userUuid;
        if (!list || !userUuid || list.querySelector(`[data-user-uuid="${userUuid}"]`)) return;
        const item = document.createElement('li');
        item.dataset.userUuid = userUuid;
        const text = document.createElement('span');
        const name = document.createElement('strong');
        name.textContent = addButton.dataset.mcid ?? '登録済みプレイヤー';
        const uuid = document.createElement('small');
        uuid.textContent = userUuid;
        text.append(name, uuid);
        const input = document.createElement('input');
        input.type = 'hidden';
        input.value = userUuid;
        const removeButton = document.createElement('button');
        removeButton.type = 'button';
        removeButton.className = 'btn ar-btn-outline';
        removeButton.dataset.removeNetworkUser = '';
        removeButton.textContent = '外す';
        item.append(text, input, removeButton);
        list.append(item);
        refreshNames(list);
    });
    }

    const banManagement = document.querySelector('[data-ban-management]');
    if (!banManagement) return;
    const banEditor = banManagement.querySelector('[data-ban-editor]');
    const banDialog = banManagement.querySelector('[data-ban-confirm]');
    const banOpen = banManagement.querySelector('[data-ban-open]');
    if (!banEditor || !banDialog || !banOpen) return;
    const banMode = () => banEditor.querySelector('[data-ban-mode="ban"]')?.checked === true;
    const field = (selector) => banEditor.querySelector(selector);
    const setText = (selector, value) => {
        const output = banDialog.querySelector(selector);
        if (output) output.textContent = value;
    };

    const updateBanFields = () => {
        const banned = banMode();
        const indefinite = field('[data-ban-indefinite]');
        const expiry = field('[data-ban-expiry]');
        const reason = field('[data-ban-reason]');
        if (indefinite) indefinite.disabled = !banned;
        if (expiry) expiry.disabled = !banned || indefinite?.checked === true;
        if (reason) reason.disabled = !banned;
    };
    banEditor.addEventListener('change', updateBanFields);
    updateBanFields();
    banOpen.addEventListener('click', () => {
        const banned = banMode();
        const indefinite = field('[data-ban-indefinite]')?.checked === true;
        const expiry = field('[data-ban-expiry]')?.value;
        const reason = field('[data-ban-reason]')?.value?.trim();
        setText('[data-ban-confirm-target]', banManagement.dataset.targetMcid ?? '—');
        setText('[data-ban-confirm-uuid]', banManagement.dataset.targetUuid ?? '—');
        setText('[data-ban-confirm-action]', banned ? '利用停止にする' : '利用停止を解除する');
        setText('[data-ban-confirm-expiry]', banned ? (indefinite ? '無期限' : (expiry || '未指定')) : '—');
        setText('[data-ban-confirm-reason]', banned ? (reason || '理由なし') : '—');
        banDialog.showModal();
    });
    banDialog.querySelector('[data-ban-cancel]')?.addEventListener('click', () => banDialog.close());
    banDialog.querySelector('[data-ban-submit]')?.addEventListener('click', () => banEditor.requestSubmit());
})();
