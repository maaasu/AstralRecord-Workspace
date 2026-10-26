import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';

const source = readFileSync(new URL('../AstralRecordWeb/wwwroot/js/astral-motion.js', import.meta.url), 'utf8');

function harness({ reduced = false, savedPause = false, canvasAvailable = true, storageAvailable = true } = {}) {
    const events = () => {
        const listeners = new Map();
        return {
            addEventListener(name, callback) {
                if (!listeners.has(name)) listeners.set(name, []);
                listeners.get(name).push(callback);
            },
            emit(name) { for (const callback of listeners.get(name) ?? []) callback(); },
        };
    };
    const frames = new Map();
    let nextFrame = 0, draws = 0;
    const media = { ...events(), matches: reduced };
    const context = {
        setTransform() {}, clearRect() { draws++; }, beginPath() {}, arc() {}, fill() {}, fillRect() {},
    };
    const canvas = { getContext() { if (!canvasAvailable) throw new Error('Canvas unavailable'); return context; } };
    const rect = { top: 0, bottom: 800, width: 1200, height: 800 };
    const properties = new Map();
    const scene = {
        dataset: {}, style: { setProperty: (key, value) => properties.set(key, value) },
        getBoundingClientRect: () => rect, querySelector: () => canvas,
    };
    const label = { textContent: '' };
    const button = { ...events(), hidden: true, disabled: false, querySelector: () => label };
    const body = { dataset: {}, classList: { add() {} } };
    const document = {
        ...events(), body, hidden: false,
        querySelectorAll: selector => selector === '[data-astral-scene]' ? [scene] : selector === '[data-motion-toggle]' ? [button] : [],
    };
    const window = {
        ...events(), innerHeight: 900, devicePixelRatio: 3, matchMedia: () => media,
        requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
        cancelAnimationFrame: id => frames.delete(id),
    };
    const storage = new Map([['astral-motion-paused', String(savedPause)]]);
    const sessionStorage = {
        getItem(key) { if (!storageAvailable) throw new Error('Storage blocked'); return storage.get(key); },
        setItem(key, value) { if (!storageAvailable) throw new Error('Storage blocked'); storage.set(key, value); },
    };
    runInNewContext(source, { document, window, sessionStorage });
    return {
        window, document, media, button, label, body, scene, rect, frames, storage, canvas, properties,
        get draws() { return draws; },
        step(now) { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach(callback => callback(now)); },
    };
}

test('pause cancels particle frames and survives navigation via session preference', () => {
    const h = harness();
    h.step(100);
    assert.equal(h.draws, 1);
    assert.equal(h.canvas.width, 1800, 'pixel density is capped');
    h.button.emit('click');
    assert.equal(h.body.dataset.motionState, 'paused');
    assert.equal(h.frames.size, 0);
    assert.equal(h.label.textContent, '演出を再生');
    assert.equal(h.storage.get('astral-motion-paused'), 'true');
    const nextPage = harness({ savedPause: true });
    assert.equal(nextPage.frames.size, 0);
    nextPage.button.emit('click');
    assert.equal(nextPage.body.dataset.motionState, 'running');
    assert.equal(nextPage.frames.size, 1);
});

test('reduced motion prevents animation and responds immediately to preference changes', () => {
    const h = harness({ reduced: true });
    assert.equal(h.body.dataset.motionState, 'reduced');
    assert.equal(h.frames.size, 0);
    assert.equal(h.button.disabled, true);
    assert.equal(h.properties.get('--ar-scene-shift'), '0px');
    h.button.emit('click');
    assert.equal(h.frames.size, 0);
    h.media.matches = false;
    h.media.emit('change');
    assert.equal(h.frames.size, 1);
    assert.equal(h.button.disabled, false);
    h.media.matches = true;
    h.media.emit('change');
    assert.equal(h.frames.size, 0);
});

test('hidden tabs and page cache suspension stop frames and restore only when visible', () => {
    const h = harness();
    h.document.hidden = true;
    h.document.emit('visibilitychange');
    assert.equal(h.frames.size, 0);
    h.window.emit('scroll');
    assert.equal(h.frames.size, 0);
    h.document.hidden = false;
    h.document.emit('visibilitychange');
    assert.equal(h.frames.size, 1);
    h.window.emit('pagehide');
    assert.equal(h.frames.size, 0);
    h.window.emit('pageshow');
    assert.equal(h.frames.size, 1);
});

test('offscreen scenes stop requesting frames and scrolling back resumes them', () => {
    const h = harness();
    h.rect.top = -900;
    h.rect.bottom = -100;
    h.window.emit('scroll');
    h.step(100);
    assert.equal(h.frames.size, 0);
    assert.equal(h.draws, 0);
    assert.equal(h.scene.dataset.sceneVisible, 'false');
    h.rect.top = 0;
    h.rect.bottom = 800;
    h.window.emit('scroll');
    h.step(200);
    assert.equal(h.draws, 1);
    assert.equal(h.frames.size, 1);
});

test('canvas and storage failures preserve controls and CSS effects', () => {
    const h = harness({ canvasAvailable: false, storageAvailable: false });
    h.step(100);
    assert.equal(h.body.dataset.motionState, 'running');
    assert.equal(h.button.hidden, false);
    h.button.emit('click');
    assert.equal(h.frames.size, 0);
    assert.equal(h.body.dataset.motionState, 'paused');
});
