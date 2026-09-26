const maximumTotal = 9223372036854775807n;

// Prices arrive as integer strings: Number would round balances above 2^53.
export function purchaseEstimate(unitPrice, quantity, maximumQuantity, gold, currency) {
    if (![unitPrice, quantity, maximumQuantity].every(value => /^\d+$/.test(value))) return null;
    const count = BigInt(quantity);
    const price = BigInt(unitPrice);
    if (count < 1n || count > BigInt(maximumQuantity) || price < 1n) return null;
    const total = price * count;
    if (total > maximumTotal) return null;
    const balance = currency.toLowerCase() === 'gold' && /^\d+$/.test(gold ?? '')
        ? BigInt(gold) - total : null;
    return { total, balance };
}

if (typeof document !== 'undefined') {
    for (const form of document.querySelectorAll('[data-market-purchase]')) {
        const quantity = form.elements.PurchaseQuantity;
        const total = form.querySelector('[data-purchase-total]');
        const balance = form.querySelector('[data-purchase-balance]');
        const button = form.querySelector('button[type="submit"]');
        const update = () => {
            const estimate = purchaseEstimate(form.dataset.unitPrice, quantity.value, quantity.max,
                form.dataset.gold, form.dataset.currency);
            button.disabled = !estimate;
            total.textContent = estimate ? `${estimate.total.toLocaleString('ja-JP')} ${form.dataset.currency}` : '—';
            balance.classList.toggle('is-short', estimate?.balance < 0n);
            balance.textContent = !estimate ? '在庫の範囲内で購入数量を入力してください。'
                : estimate.balance === null ? '購入後の残高は確認できません。'
                : estimate.balance < 0n ? `保存済み残高では ${(-estimate.balance).toLocaleString('ja-JP')} Gold 不足しています。`
                : `購入後の見込み残高 ${estimate.balance.toLocaleString('ja-JP')} Gold`;
        };
        quantity.addEventListener('input', update);
        form.addEventListener('submit', event => {
            update();
            if (button.disabled) event.preventDefault();
        });
        update();
    }
}
