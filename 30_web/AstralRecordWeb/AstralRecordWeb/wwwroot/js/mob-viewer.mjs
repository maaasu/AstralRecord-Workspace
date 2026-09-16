const MOB_ENTITY_TYPES = new Set([
    'BLAZE', 'BREEZE', 'CAMEL', 'CAVE_SPIDER', 'CHICKEN', 'COD', 'COW', 'CREEPER',
    'DOLPHIN', 'DONKEY', 'DROWNED', 'ELDER_GUARDIAN', 'ENDERMAN', 'ENDERMITE',
    'ENDER_DRAGON', 'EVOKER', 'FOX', 'FROG', 'GHAST', 'GIANT', 'GLOW_SQUID',
    'GOAT', 'GUARDIAN', 'HOGLIN', 'HORSE', 'HUSK', 'ILLUSIONER', 'IRON_GOLEM',
    'LLAMA', 'MAGMA_CUBE', 'MOOSHROOM', 'MULE', 'OCELOT', 'PANDA', 'PARROT', 'PHANTOM',
    'PIG', 'PIGLIN', 'PIGLIN_BRUTE', 'PILLAGER', 'POLAR_BEAR', 'PUFFERFISH', 'RABBIT',
    'RAVAGER', 'SALMON', 'SHEEP', 'SHULKER', 'SILVERFISH', 'SKELETON', 'SKELETON_HORSE',
    'SLIME', 'SNIFFER', 'SNOW_GOLEM', 'SPIDER', 'SQUID', 'STRAY', 'STRIDER', 'TADPOLE',
    'TROPICAL_FISH', 'TURTLE', 'VEX', 'VILLAGER', 'VINDICATOR', 'WANDERING_TRADER',
    'WARDEN', 'WITCH', 'WITHER', 'WITHER_SKELETON', 'WOLF', 'ZOGLIN', 'ZOMBIE',
    'ZOMBIE_HORSE', 'ZOMBIE_VILLAGER', 'ZOMBIFIED_PIGLIN'
]);

const ENTITY_SLUGS = new Map([
    ['CAVE_SPIDER', 'cave_spider'], ['DROWNED', 'drowned'], ['ELDER_GUARDIAN', 'elder_guardian'],
    ['ENDER_DRAGON', 'ender_dragon'], ['HUSK', 'husk'], ['MAGMA_CUBE', 'magma_cube'],
    ['MOOSHROOM', 'mooshroom'], ['PIGLIN_BRUTE', 'piglin_brute'], ['SKELETON_HORSE', 'skeleton_horse'],
    ['SNOW_GOLEM', 'snow_golem'], ['WITHER_SKELETON', 'wither_skeleton'],
    ['ZOMBIE_HORSE', 'zombie_horse'], ['ZOMBIE_VILLAGER', 'zombie_villager'],
    ['ZOMBIFIED_PIGLIN', 'zombified_piglin']
]);

export function mobEntityType(value) {
    const type = String(value ?? '').trim().toUpperCase();
    return MOB_ENTITY_TYPES.has(type) ? type : null;
}

export function mobImagePath(entityType) {
    const type = mobEntityType(entityType);
    if (!type) return null;
    return `/images/mobs/${ENTITY_SLUGS.get(type) ?? type.toLowerCase()}.png`;
}

function fallback(target) {
    target.dataset.mobVisualState = 'unavailable';
    const existing = target.querySelector('[data-mob-visual-fallback]');
    if (existing) {
        existing.hidden = false;
        return;
    }

    const label = document.createElement('span');
    label.className = target.hasAttribute('data-mob-thumbnail') ? 'mob-thumbnail__fallback' : 'mob-viewer__fallback';
    label.dataset.mobVisualFallback = '';
    label.textContent = '外見画像なし';
    target.append(label);
}

function render(target, imageClass) {
    if (target.dataset.mobVisualState) return;
    const src = mobImagePath(target.dataset.entityType);
    if (!src) {
        fallback(target);
        return;
    }

    const image = document.createElement('img');
    image.className = imageClass;
    image.src = src;
    image.alt = target.dataset.mobName ? `${target.dataset.mobName}の外見` : 'Minecraft バニラモブの外見';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.referrerPolicy = 'no-referrer';
    image.addEventListener('load', () => { target.dataset.mobVisualState = 'loaded'; }, { once: true });
    image.addEventListener('error', () => {
        image.remove();
        fallback(target);
    }, { once: true });
    target.dataset.mobVisualState = 'loading';
    target.append(image);
}

function observe(selector, imageClass) {
    const targets = [...document.querySelectorAll(selector)];
    if (!('IntersectionObserver' in window)) {
        targets.forEach(target => render(target, imageClass));
        return;
    }

    const observer = new IntersectionObserver(entries => {
        for (const entry of entries) {
            if (!entry.isIntersecting) continue;
            render(entry.target, imageClass);
            observer.unobserve(entry.target);
        }
    }, { rootMargin: '240px 0px' });
    targets.forEach(target => observer.observe(target));
}

export function installMobViewers() {
    observe('[data-mob-viewer]', 'mob-viewer__image');
    observe('[data-mob-thumbnail]', 'mob-thumbnail__image');
}

if (typeof document !== 'undefined') installMobViewers();
