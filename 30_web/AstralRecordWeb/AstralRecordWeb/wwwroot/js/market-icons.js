import './item-icons.js';

// Omit unused ranges rather than sending hundreds of empty query parameters.
for (const form of document.querySelectorAll('[data-market-search]')) {
    form.addEventListener('formdata', event => {
        for (const [key, value] of [...event.formData.entries()]) {
            if (typeof value === 'string' && !value.trim()) event.formData.delete(key);
        }
    });
}

// Compact filters leave the listing visible on small screens.
const compactFilters = window.matchMedia('(max-width: 991px)');
for (const disclosure of document.querySelectorAll('[data-market-filters]')) {
    const updateDisclosure = () => { disclosure.open = !compactFilters.matches; };
    compactFilters.addEventListener('change', updateDisclosure);
    updateDisclosure();
}
