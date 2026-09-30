const storageKey = 'astralrecord:skilltree-preferences:v1';
export function normalizePreferences(value) {
    return {
        sound: typeof value?.sound === 'boolean' ? value.sound : true,
        volume: Number.isFinite(value?.volume) ? Math.round(Math.max(0, Math.min(100, value.volume))) : 35,
        directClick: value?.directClick === true,
    };
}
export function loadPreferences() {
    try { return normalizePreferences(JSON.parse(localStorage.getItem(storageKey))); }
    catch { return normalizePreferences(); }
}
export function savePreferences(value) {
    try { localStorage.setItem(storageKey, JSON.stringify(normalizePreferences(value))); }
    catch { /* The controls still work when browser storage is unavailable. */ }
}

// Short, locally synthesized cues: no downloads and no audio before a user gesture.
export function createTreeSound(preferences, AudioContextType = globalThis.AudioContext ?? globalThis.webkitAudioContext) {
    let context, master, lastPlayed = -Infinity;
    const cues = { unlock: [660, 880], relock: [440, 330], undo: [520, 390], accepted: [620, 780], applied: [660, 880, 1100], error: [220, 185] };
    const update = () => {
        if (master) master.gain.setValueAtTime(preferences.sound ? preferences.volume / 100 * .16 : 0, context.currentTime);
    };
    const arm = () => {
        if (!preferences.sound || !preferences.volume || !AudioContextType) return;
        try {
            if (!context) { context = new AudioContextType(); master = context.createGain(); master.connect(context.destination); update(); }
            if (context.state === 'suspended') context.resume().catch(() => {});
        } catch { /* Audio support must never affect editing. */ }
    };
    const play = name => {
        if (!preferences.sound || !preferences.volume || context?.state !== 'running' || !cues[name]) return;
        const now = context.currentTime;
        if (now - lastPlayed < .045) return;
        lastPlayed = now;
        try {
            cues[name].forEach((frequency, index) => {
                const oscillator = context.createOscillator(), envelope = context.createGain();
                const start = now + index * .075;
                oscillator.type = 'sine'; oscillator.frequency.value = frequency;
                envelope.gain.setValueAtTime(0, start);
                envelope.gain.linearRampToValueAtTime(1, start + .008);
                envelope.gain.exponentialRampToValueAtTime(.001, start + .14);
                oscillator.connect(envelope); envelope.connect(master);
                oscillator.onended = () => { oscillator.disconnect(); envelope.disconnect(); };
                oscillator.start(start); oscillator.stop(start + .15);
            });
        } catch { /* A disabled audio device must not interrupt a change. */ }
    };
    return { arm, play, update };
}
