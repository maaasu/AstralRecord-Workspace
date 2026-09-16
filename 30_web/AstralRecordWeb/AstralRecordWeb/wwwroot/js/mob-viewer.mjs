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
    'ZOMBIE_HORSE', 'ZOMBIE_VILLAGER', 'ZOMBIFIED_PIGLIN', 'PARCHED'
]);

const MOB_IMAGE_URLS = new Map([
    ['BLAZE', '/images/mobs/blaze.png'], ['BREEZE', '/images/mobs/breeze.png'],
    ['CAMEL', '/images/mobs/camel.png'], ['ENDER_DRAGON', '/images/mobs/ender_dragon.png'],
    ['EVOKER', '/images/mobs/evoker.png'], ['HUSK', '/images/mobs/husk.png'],
    ['IRON_GOLEM', '/images/mobs/iron_golem.png'], ['PARCHED', '/images/mobs/parched.png'],
    ['PARROT', '/images/mobs/parrot.png'], ['PIG', '/images/mobs/pig.png'],
    ['PIGLIN', '/images/mobs/piglin.png'], ['SHEEP', '/images/mobs/sheep.png'],
    ['SKELETON', '/images/mobs/skeleton.png'], ['SPIDER', '/images/mobs/spider.png'],
    ['TURTLE', '/images/mobs/turtle.png'], ['WITCH', '/images/mobs/witch.png'],
    ['WITHER_SKELETON', '/images/mobs/wither_skeleton.png'], ['WOLF', '/images/mobs/wolf.png'],
    ['ZOMBIE', '/images/mobs/zombie.png']
]);

export function mobEntityType(value) {
    const type = String(value ?? '').trim().toUpperCase();
    return MOB_ENTITY_TYPES.has(type) ? type : null;
}

export function mobImagePath(entityType) {
    const type = mobEntityType(entityType);
    return type ? MOB_IMAGE_URLS.get(type) ?? null : null;
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
    image.alt = target.dataset.mobName ? `${target.dataset.mobName}の外見` : 'Minecraft バニラモブの外見';
    image.loading = 'lazy';
    image.decoding = 'async';
    image.referrerPolicy = 'no-referrer';
    image.addEventListener('load', () => {
        target.dataset.mobVisualState = 'loaded';
        const fallbackLabel = target.querySelector('[data-mob-visual-fallback]');
        if (fallbackLabel) fallbackLabel.hidden = true;
    }, { once: true });
    image.addEventListener('error', () => {
        image.remove();
        fallback(target);
    }, { once: true });
    target.dataset.mobVisualState = 'loading';
    target.append(image);
    image.src = src;
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
