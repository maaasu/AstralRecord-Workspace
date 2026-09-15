const iconCache = new Map();

export function materialId(icon) {
    const normalized = String(icon ?? '').trim().replace(/^minecraft:/i, '').toLowerCase();
    return /^[a-z0-9_]{1,80}$/.test(normalized) ? normalized : null;
}

function imageAvailable(url) {
    return new Promise(resolve => {
        const probe = new Image();
        probe.referrerPolicy = 'no-referrer';
        let complete = false;
        const finish = success => {
            if (complete) return;
            complete = true;
            clearTimeout(timer);
            probe.onload = null; probe.onerror = null;
            if (!success) probe.removeAttribute('src');
            resolve(success);
        };
        const timer = setTimeout(() => finish(false), 5000);
        probe.onload = () => finish(probe.naturalWidth > 0);
        probe.onerror = () => finish(false);
        probe.src = url;
    });
}

export function loadIcon(icon) {
    const id = materialId(icon);
    if (!id) return Promise.resolve(null);
    if (!iconCache.has(id)) {
        const base = 'https://assets.mcasset.cloud/1.21.11/assets/minecraft/textures';
        iconCache.set(id, (async () => {
            for (const url of [`/images/minecraft/${id}.png`, `${base}/item/${id}.png`, `${base}/block/${id}.png`]) {
                if (await imageAvailable(url)) return url;
            }
            return null;
        })());
    }
    return iconCache.get(id);
}
