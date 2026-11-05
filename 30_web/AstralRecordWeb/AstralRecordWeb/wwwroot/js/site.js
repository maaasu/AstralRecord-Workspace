(() => {
    'use strict';

    document.querySelectorAll('[data-player-avatar]').forEach(image => {
        image.addEventListener('error', () => { image.hidden = true; }, { once: true });
        if (image.complete && image.naturalWidth === 0) image.hidden = true;
    });

    const copyText = async (value) => {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(value);
            return;
        }

        const input = document.createElement('textarea');
        input.value = value;
        input.setAttribute('readonly', '');
        input.style.position = 'fixed';
        input.style.opacity = '0';
        document.body.appendChild(input);
        input.select();

        const copied = document.execCommand('copy');
        input.remove();

        if (!copied) {
            throw new Error('Clipboard copy failed.');
        }
    };

    document.querySelectorAll('[data-copy-server]').forEach((button) => {
        button.hidden = false;
        const defaultLabel = button.querySelector('[data-copy-label]')?.textContent ?? 'コピー';
        const describedByIds = (button.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean);
        const status = describedByIds
            .map((id) => document.getElementById(id))
            .find((element) => element?.matches('[data-copy-status]'));
        let resetTimer;

        button.addEventListener('click', async () => {
            const value = button.getAttribute('data-copy-value');
            if (!value) {
                return;
            }

            window.clearTimeout(resetTimer);

            try {
                await copyText(value);
                button.dataset.copyState = 'done';
                const label = button.querySelector('[data-copy-label]');
                if (label) {
                    label.textContent = 'コピーしました';
                }
                if (status) {
                    status.textContent = `${value} をクリップボードにコピーしました。`;
                }
            } catch {
                button.dataset.copyState = 'failed';
                const label = button.querySelector('[data-copy-label]');
                if (label) label.textContent = 'コピーできません';
                if (status) {
                    status.textContent = `コピーできませんでした。${value} を選択してコピーしてください。`;
                }
            }

            resetTimer = window.setTimeout(() => {
                delete button.dataset.copyState;
                const label = button.querySelector('[data-copy-label]');
                if (label) {
                    label.textContent = defaultLabel;
                }
            }, 2400);
        });
    });
})();
