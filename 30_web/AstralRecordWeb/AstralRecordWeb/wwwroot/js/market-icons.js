import { loadIcon } from './minecraft-icons.mjs';

for (const image of document.querySelectorAll('[data-market-icon]')) {
    loadIcon(image.dataset.marketIcon).then(url => {
        if (!url) return;
        image.src = url;
        image.hidden = false;
        const fallback = image.parentElement.querySelector('[data-market-icon-fallback]');
        if (fallback) fallback.hidden = true;
    });
}

// Omit unused ranges rather than sending hundreds of empty query parameters.
for (const form of document.querySelectorAll('[data-market-search]')) {
    form.addEventListener('formdata', event => {
        for (const [key, value] of [...event.formData.entries()]) {
            if (typeof value === 'string' && !value.trim()) event.formData.delete(key);
        }
    });
}
