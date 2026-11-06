import { loadIcon, loadImage, materialId } from './minecraft-icons.mjs';

function headTextureUrl(encoded) {
    // Match the API's GeyserIconTextureValidator limit.
    if (!encoded || encoded.length > 16_384) return null;
    try {
        const url = JSON.parse(atob(encoded))?.textures?.SKIN?.url;
        const match = /^https?:\/\/textures\.minecraft\.net\/texture\/([0-9a-f]{1,64})$/i.exec(url ?? '');
        return match ? `https://textures.minecraft.net/texture/${match[1]}` : null;
    } catch {
        return null;
    }
}

async function renderHead(url) {
    const skin = await loadImage(url);
    if (!skin) return null;

    const { naturalWidth: width, naturalHeight: height } = skin;
    if (width < 64 || width > 1024 || width % 64 !== 0 || (height !== width && height !== width / 2)) return null;

    const size = width / 8;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    const context = canvas.getContext('2d');
    if (!context) return null;

    context.drawImage(skin, size, size, size, size, 0, 0, size, size);
    context.drawImage(skin, size * 5, size, size, size, 0, 0, size, size);
    return canvas;
}

async function showItemIcon(icon) {
    const { material, packTexture, headTexture } = icon.dataset;
    const image = icon.querySelector('img');
    const fallback = icon.querySelector('[data-item-icon-fallback]');

    let url = packTexture && await loadImage(packTexture) ? packTexture : null;
    if (!url && materialId(material) === 'player_head') {
        const skinUrl = headTextureUrl(headTexture);
        const head = skinUrl && await renderHead(skinUrl);
        if (head) {
            image.replaceWith(head);
            fallback.hidden = true;
            return;
        }
    }

    url ??= await loadIcon(material);
    if (url) {
        image.src = url;
        image.hidden = false;
        fallback.hidden = true;
    }
}

const icons = document.querySelectorAll('[data-item-icon]');
if ('IntersectionObserver' in window) {
    const observer = new IntersectionObserver(entries => {
        for (const entry of entries) {
            if (entry.isIntersecting) {
                observer.unobserve(entry.target);
                void showItemIcon(entry.target);
            }
        }
    }, { rootMargin: '200px' });
    icons.forEach(icon => observer.observe(icon));
} else {
    icons.forEach(icon => void showItemIcon(icon));
}
