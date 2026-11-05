(() => {
    'use strict';
    const dialog = document.getElementById('action-confirm');
    if (!dialog?.showModal || !HTMLFormElement.prototype.requestSubmit) return;
    const title = document.getElementById('action-confirm-title');
    const message = document.getElementById('action-confirm-message');
    const details = dialog.querySelector('[data-confirm-details]');
    const accept = dialog.querySelector('[data-confirm-accept]');
    let pending;
    let approved;

    // Capture before page-specific handlers lock or disable a submitting form.
    document.addEventListener('submit', event => {
        const form = event.target;
        const submitter = event.submitter;
        if (approved?.form === form && approved.submitter === submitter) {
            approved = undefined;
            return;
        }
        const source = submitter?.hasAttribute('data-confirm-title') ? submitter : form;
        if (!source.hasAttribute('data-confirm-title')) return;
        event.preventDefault();
        event.stopImmediatePropagation();
        if (pending || !form.reportValidity()) return;

        title.textContent = source.dataset.confirmTitle;
        message.textContent = source.dataset.confirmMessage ?? '';
        accept.textContent = source.dataset.confirmAccept ?? '確定する';
        details.replaceChildren();
        form.querySelectorAll('[data-confirm-label]').forEach(element => {
            const term = document.createElement('dt');
            const value = document.createElement('dd');
            term.textContent = element.dataset.confirmLabel;
            value.textContent = element.matches('select')
                ? element.selectedOptions[0]?.textContent ?? ''
                : element.matches('input, textarea') ? element.value : element.textContent;
            details.append(term, value);
        });
        pending = { form, submitter };
        dialog.showModal();
    }, true);

    dialog.querySelector('[data-confirm-cancel]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
        const cancelled = pending;
        pending = undefined;
        cancelled?.submitter?.focus();
    });
    accept.addEventListener('click', () => {
        if (!pending) return;
        const action = pending;
        pending = undefined;
        dialog.close();
        if (!action.form.isConnected) return;
        approved = action;
        action.form.requestSubmit(action.submitter ?? undefined);
        approved = undefined;
    });
})();
