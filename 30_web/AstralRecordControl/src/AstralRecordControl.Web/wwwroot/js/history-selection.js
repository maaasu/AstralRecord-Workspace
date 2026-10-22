(function () {
    'use strict';
    const form = document.getElementById('history-selection');
    if (!form) return;
    const all = document.getElementById('select-all-history');
    const choices = Array.from(form.querySelectorAll('input[name="workflowIds"]:not(:disabled)'));
    const count = document.getElementById('history-selection-count');
    function update() {
        const selected = choices.filter(input => input.checked).length;
        count.textContent = `${selected} 件選択`;
        all.checked = choices.length > 0 && selected === choices.length;
        all.indeterminate = selected > 0 && selected < choices.length;
        all.disabled = choices.length === 0;
    }
    all.addEventListener('change', () => { choices.forEach(input => { input.checked = all.checked; }); update(); });
    choices.forEach(input => input.addEventListener('change', update));
    update();
})();
