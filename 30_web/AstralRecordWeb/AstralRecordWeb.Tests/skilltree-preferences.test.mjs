import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizePreferences, loadPreferences, savePreferences, createTreeSound } from '../AstralRecordWeb/wwwroot/js/skilltree-preferences.mjs';

test('preferences default safely and clamp corrupted volume', () => {
    assert.deepEqual(normalizePreferences(null), { sound: true, volume: 35, directClick: false });
    assert.deepEqual(normalizePreferences({ sound: false, volume: 500, directClick: true }), { sound: false, volume: 100, directClick: true });
    assert.equal(normalizePreferences({ volume: -5 }).volume, 0);
    assert.equal(normalizePreferences({ volume: 'loud' }).volume, 35);
});

test('settings survive reload and blocked storage does not stop editing', () => {
    const descriptor = Object.getOwnPropertyDescriptor(globalThis, 'localStorage');
    let saved = '';
    try {
        Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: () => saved, setItem: (_, value) => { saved = value; } } });
        savePreferences({ sound: false, volume: 70, directClick: true });
        assert.deepEqual(loadPreferences(), { sound: false, volume: 70, directClick: true });
        saved = 'broken json'; assert.equal(loadPreferences().volume, 35);
        Object.defineProperty(globalThis, 'localStorage', { configurable: true, get: () => { throw new Error('disabled'); } });
        assert.doesNotThrow(() => savePreferences({}));
        assert.equal(loadPreferences().sound, true);
    } finally {
        if (descriptor) Object.defineProperty(globalThis, 'localStorage', descriptor); else delete globalThis.localStorage;
    }
});

test('audio is gesture initialized, muted at zero/OFF, bounded and disconnects finished cues', () => {
    let created = 0, tones = 0, disconnected = 0, context;
    const gains = [], oscillators = [];
    class FakeAudioContext {
        constructor() { created++; context = this; this.state = 'running'; this.currentTime = 1; }
        createGain() { const gain = { value: 0, setValueAtTime(value) { this.value = value; }, linearRampToValueAtTime() {}, exponentialRampToValueAtTime() {} }; gains.push(gain); return { gain, connect() {}, disconnect() { disconnected++; } }; }
        createOscillator() { const oscillator = { frequency: {}, connect() {}, disconnect() { disconnected++; }, start() { tones++; }, stop() {} }; oscillators.push(oscillator); return oscillator; }
    }
    const preferences = normalizePreferences(), sound = createTreeSound(preferences, FakeAudioContext);
    sound.play('unlock'); assert.equal(created, 0);
    sound.arm(); sound.play('unlock'); assert.equal(created, 1); assert.equal(tones, 2);
    sound.play('unlock'); assert.equal(tones, 2);
    for (const oscillator of oscillators) oscillator.onended();
    assert.equal(disconnected, 4);
    preferences.sound = false; sound.update(); context.currentTime++;
    sound.play('applied'); assert.equal(tones, 2); assert.equal(gains[0].value, 0);
    preferences.sound = true; preferences.volume = 0; sound.update(); sound.play('unlock'); assert.equal(tones, 2);
    preferences.volume = 100; sound.update(); sound.play('applied'); assert.equal(tones, 5); assert.equal(gains[0].value, .16);
});

test('unsupported or unavailable audio devices never interrupt the operation', () => {
    assert.doesNotThrow(() => { const sound = createTreeSound(normalizePreferences(), null); sound.arm(); sound.play('applied'); });
    assert.doesNotThrow(() => { const sound = createTreeSound(normalizePreferences(), class { constructor() { throw new Error('device'); } }); sound.arm(); sound.play('unlock'); });
});
