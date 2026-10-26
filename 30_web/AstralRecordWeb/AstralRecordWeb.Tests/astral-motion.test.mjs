import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { setImmediate as nextTurn } from 'node:timers/promises';

const source = readFileSync(new URL('../AstralRecordWeb/wwwroot/js/astral-motion.js', import.meta.url), 'utf8');

function harness({ reduced = false, savedPause = false, canvasAvailable = true, storageAvailable = true, width = 1200, playResult = () => Promise.resolve() } = {}) {
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
    const video = {
        ...events(), dataset: { videoDesktop: '/media/desktop.mp4', videoMobile: '/media/mobile.mp4' },
        _src: '', muted: false, paused: true, playCalls: 0, sourceWrites: 0, error: null,
        get src() { return this._src; },
        set src(value) { this._src = value; this.sourceWrites++; this.error = null; },
        getAttribute() { return this.src; },
        play() { this.playCalls++; this.paused = false; return playResult(); },
        pause() { this.paused = true; },
    };
    const rect = { top: 0, bottom: 800, width, height: 800 };
    const properties = new Map();
    const scene = {
        dataset: {}, style: { setProperty: (key, value) => properties.set(key, value) },
        getBoundingClientRect: () => rect, querySelector: selector => selector === '[data-astral-video]' ? video : canvas,
    };
    const label = { textContent: '' };
    const button = { ...events(), hidden: true, disabled: false, querySelector: () => label };
    const body = { dataset: {}, classList: { add() {} } };
    const document = {
        ...events(), body, hidden: false,
        querySelectorAll: selector => selector === '[data-astral-scene]' ? [scene] : selector === '[data-motion-toggle]' ? [button] : [],
    };
    const window = {
        ...events(), innerHeight: 900, innerWidth: width, devicePixelRatio: 3, matchMedia: () => media,
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
        window, document, media, button, label, body, scene, rect, frames, storage, canvas, video, properties,
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

test('video starts muted with the appropriate asset and waits for playback before showing', () => {
    const h = harness();
    assert.equal(h.video.src, '/media/desktop.mp4');
    assert.equal(h.video.muted, true);
    assert.equal(h.video.playCalls, 1);
    assert.notEqual(h.video.dataset.videoReady, 'true');
    h.video.emit('playing');
    assert.equal(h.video.dataset.videoReady, 'true');
    h.step(100);
    h.step(200);
    assert.equal(h.video.playCalls, 1, 'animation frames do not repeatedly call play');
    assert.equal(harness({ width: 390 }).video.src, '/media/mobile.mp4');
});

test('reduced motion and saved pause never load a video until motion is allowed', () => {
    const h = harness({ reduced: true });
    assert.equal(h.video.src, '');
    assert.equal(h.video.playCalls, 0);
    h.media.matches = false;
    h.media.emit('change');
    h.step(100);
    assert.equal(h.video.playCalls, 1);
    const paused = harness({ savedPause: true });
    assert.equal(paused.video.src, '');
    paused.button.emit('click');
    paused.step(100);
    assert.equal(paused.video.playCalls, 1);
});

test('pause, reduced motion, hidden tabs and offscreen scenes also pause native video', () => {
    const h = harness();
    h.button.emit('click');
    assert.equal(h.video.paused, true);
    h.button.emit('click');
    h.step(100);
    assert.equal(h.video.paused, false);
    h.rect.top = -900;
    h.rect.bottom = -100;
    h.window.emit('scroll');
    h.step(200);
    assert.equal(h.video.paused, true);
    h.rect.top = 0;
    h.rect.bottom = 800;
    h.window.emit('scroll');
    h.step(300);
    assert.equal(h.video.paused, false);
    h.document.hidden = true;
    h.document.emit('visibilitychange');
    assert.equal(h.video.paused, true);
    h.document.hidden = false;
    h.document.emit('visibilitychange');
    h.step(400);
    h.media.matches = true;
    h.media.emit('change');
    assert.equal(h.video.paused, true);
    h.video.paused = false;
    h.video.emit('playing');
    assert.equal(h.video.paused, true, 'a late playing event cannot bypass reduced motion');
});

test('autoplay rejection falls back without retrying on every scroll; explicit resume retries', async () => {
    const h = harness({ playResult: () => Promise.reject(new Error('Autoplay blocked')) });
    await nextTurn();
    assert.equal(h.video.dataset.videoReady, 'false');
    h.window.emit('scroll');
    h.step(100);
    assert.equal(h.video.playCalls, 1);
    h.button.emit('click');
    h.button.emit('click');
    h.step(200);
    assert.equal(h.video.playCalls, 2);
    await nextTurn();
});

test('a stale rejected play request cannot invalidate a newer resume', async () => {
    let rejectFirst;
    let attempt = 0;
    const h = harness({ playResult: () => ++attempt === 1 ? new Promise((_, reject) => { rejectFirst = reject; }) : Promise.resolve() });
    h.button.emit('click');
    h.button.emit('click');
    h.step(100);
    h.video.emit('playing');
    rejectFirst(new Error('Aborted by pause'));
    await nextTurn();
    assert.equal(h.video.dataset.videoReady, 'true');
    assert.equal(h.video.playCalls, 2);
});

test('an explicit resume reloads a video that previously had a media error', () => {
    const h = harness();
    h.video.error = { code: 2 };
    h.video.emit('error');
    assert.equal(h.video.dataset.videoReady, 'false');
    h.window.emit('scroll');
    h.step(100);
    assert.equal(h.video.sourceWrites, 1);
    h.button.emit('click');
    h.button.emit('click');
    h.step(200);
    assert.equal(h.video.sourceWrites, 2);
    assert.equal(h.video.error, null);
    h.video.emit('playing');
    assert.equal(h.video.dataset.videoReady, 'true');
});
