(() => {
    'use strict';

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

    const home = document.querySelector('.ar-home');
    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const finePointer = window.matchMedia('(pointer: fine)');

    if (!home || reduceMotion.matches || !finePointer.matches) {
        return;
    }

    let frameId = 0;
    let pointerX = 0;
    let pointerY = 0;

    const updateDepth = () => {
        home.style.setProperty('--pointer-x', `${pointerX.toFixed(2)}px`);
        home.style.setProperty('--pointer-y', `${pointerY.toFixed(2)}px`);
        frameId = 0;
    };

    home.addEventListener('pointermove', (event) => {
        pointerX = ((event.clientX / window.innerWidth) - 0.5) * 22;
        pointerY = ((event.clientY / window.innerHeight) - 0.5) * 18;

        if (!frameId) {
            frameId = window.requestAnimationFrame(updateDepth);
        }
    }, { passive: true });

    home.addEventListener('pointerleave', () => {
        pointerX = 0;
        pointerY = 0;
        if (!frameId) {
            frameId = window.requestAnimationFrame(updateDepth);
        }
    }, { passive: true });
})();
