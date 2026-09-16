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
    ['BLAZE', 'https://minecraft.wiki/images/thumb/Blaze_JE2.gif/150px-Blaze_JE2.gif?29f6f'],
    ['BREEZE', 'https://minecraft.wiki/images/thumb/Breeze_BE1_JE2.webp/150px-Breeze_BE1_JE2.webp?87e5c'],
    ['CAMEL', 'https://minecraft.wiki/images/thumb/Camel_Idle.gif/200px-Camel_Idle.gif?72667'],
    ['ENDER_DRAGON', 'https://minecraft.wiki/images/thumb/Ender_Dragon.gif/250px-Ender_Dragon.gif?fca41'],
    ['EVOKER', 'https://minecraft.wiki/images/thumb/Evoker_JE3.png/100px-Evoker_JE3.png?54321'],
    ['HUSK', 'https://minecraft.wiki/images/thumb/Husk_JE4_BE2.png/150px-Husk_JE4_BE2.png?a6767'],
    ['IRON_GOLEM', 'https://minecraft.wiki/images/thumb/Iron_Golem_JE2_BE2.png/150px-Iron_Golem_JE2_BE2.png?2cd73'],
    ['PARCHED', 'https://minecraft.wiki/images/thumb/Parched_JE2.png/100px-Parched_JE2.png?70598'],
    ['PARROT', 'https://minecraft.wiki/images/thumb/Red_Parrot_JE1_BE1.png/150px-Red_Parrot_JE1_BE1.png?90904'],
    ['PIG', 'https://minecraft.wiki/images/thumb/Temperate_Pig_JE4_BE2.png/150px-Temperate_Pig_JE4_BE2.png?c550c'],
    ['PIGLIN', 'https://minecraft.wiki/images/thumb/Piglin_JE1.png/150px-Piglin_JE1.png?a498e'],
    ['SHEEP', 'https://minecraft.wiki/images/thumb/White_Sheep_JE5.png/150px-White_Sheep_JE5.png?a5000'],
    ['SKELETON', 'https://minecraft.wiki/images/thumb/Skeleton_JE6_BE4.png/100px-Skeleton_JE6_BE4.png?85786'],
    ['SPIDER', 'https://minecraft.wiki/images/thumb/Spider_JE5_BE4.png/212px-Spider_JE5_BE4.png?b090e'],
    ['TURTLE', 'https://minecraft.wiki/images/thumb/Turtle_JE3_BE1.png/200px-Turtle_JE3_BE1.png?373f9'],
    ['WITCH', 'https://minecraft.wiki/images/thumb/Witch_JE3.png/100px-Witch_JE3.png?75460'],
    ['WITHER_SKELETON', 'https://minecraft.wiki/images/thumb/Wither_Skeleton_JE4_BE3.png/150px-Wither_Skeleton_JE4_BE3.png?9c107'],
    ['WOLF', 'https://minecraft.wiki/images/thumb/Wolf_JE2_BE2.png/150px-Wolf_JE2_BE2.png?ee46e'],
    ['ZOMBIE', 'https://minecraft.wiki/images/thumb/Zombie_JE5_BE2.png/150px-Zombie_JE5_BE2.png?d709c']
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
