(() => {
    const form = document.querySelector('[data-donation-form]');
    if (!form) return;
    const entries = form.querySelector('[data-donation-entries]');
    const add = form.querySelector('[data-add-entry]');
    function renumber() {
        const rows = entries.querySelectorAll('[data-donation-entry]');
        rows.forEach((row, index) => {
            row.querySelector('legend').textContent = `明細 ${index + 1}`;
            row.querySelectorAll('[name]').forEach(input => input.name = input.name.replace(/Entries\[\d+\]/, `Entries[${index}]`));
            ['method', 'amount', 'value'].forEach(prefix => {
                row.querySelector(`[id^="${prefix}-"]`).id = `${prefix}-${index}`;
                row.querySelector(`[for^="${prefix}-"]`).htmlFor = `${prefix}-${index}`;
            });
            row.querySelector('[data-remove-entry]').disabled = rows.length === 1;
        });
        add.disabled = rows.length >= 10;
    }
    add.addEventListener('click', () => {
        if (entries.children.length >= 10) return;
        const row = entries.firstElementChild.cloneNode(true);
        row.querySelectorAll('input').forEach(input => input.value = '');
        entries.append(row);
        renumber();
        row.querySelector('select').focus();
    });
    entries.addEventListener('click', event => {
        const button = event.target.closest('[data-remove-entry]');
        if (button && entries.children.length > 1) { button.closest('[data-donation-entry]').remove(); renumber(); }
    });
    renumber();
})();
