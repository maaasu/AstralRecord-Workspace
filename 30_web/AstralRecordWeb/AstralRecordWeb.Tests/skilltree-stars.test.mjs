import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeStarfield } from '../AstralRecordWeb/wwwroot/js/skilltree-stars.mjs';

function harness({ reduced = false, savedPause = false, observers = true, storageAvailable = true } = {}) {
    function events(target = {}) {
        const listeners = new Map();
        return Object.assign(target, {
            addEventListener(name, callback) {
                if (!listeners.has(name)) listeners.set(name, new Set());
                listeners.get(name).add(callback);
            },
            removeEventListener(name, callback) { listeners.get(name)?.delete(callback); },
            emit(name) { for (const callback of listeners.get(name) ?? []) callback(); },
            listenerCount(name) { return listeners.get(name)?.size ?? 0; },
        });
    }
    const frames = new Map(), draws = [];
    let nextFrame = 0;
    const storage = new Map([['astral-motion-paused', String(savedPause)]]);
    const media = events({ matches: reduced });
    const context = {
        arcCalls: 0,
        setTransform(...args) { this.transform = args; },
        clearRect(...args) { draws.push(args); },
        beginPath() {}, arc() { this.arcCalls++; }, fill() {}, fillRect() {},
    };
    const canvas = {
        style: {}, attributes: {},
        setAttribute(name, value) { this.attributes[name] = value; },
        getContext: () => context,
        remove() { container.children = container.children.filter(child => child !== this); },
    };
    const rect = { top: 0, left: 0, right: 1200, bottom: 800, width: 1200, height: 800 };
    const win = events({
        innerWidth: 1200, innerHeight: 900, devicePixelRatio: 3,
        matchMedia: () => media,
        sessionStorage: {
            getItem(key) { if (!storageAvailable) throw new Error('blocked'); return storage.get(key); },
            setItem(key, value) { if (!storageAvailable) throw new Error('blocked'); storage.set(key, value); },
        },
        requestAnimationFrame(callback) { frames.set(++nextFrame, callback); return nextFrame; },
        cancelAnimationFrame(id) { frames.delete(id); },
    });
    const doc = events({ hidden: false, defaultView: win, createElement: () => canvas });
    const container = {
        ownerDocument: doc, children: [], getBoundingClientRect: () => rect,
        appendChild(child) { this.children.push(child); },
    };
    const instances = [];
    if (observers) {
        win.IntersectionObserver = class {
            constructor(callback) { this.callback = callback; instances.push(this); }
            observe(element) { this.element = element; }
            disconnect() { this.disconnected = true; }
            show(value) { this.callback([{ isIntersecting: value }]); }
        };
        win.ResizeObserver = class {
            constructor(callback) { this.callback = callback; instances.push(this); }
            observe(element) { this.element = element; }
            disconnect() { this.disconnected = true; }
            resize() { this.callback(); }
        };
    }
    const controller = initializeStarfield(container);
    return {
        controller, canvas, container, context, doc, draws, frames, instances, media, rect, storage, win,
        step(now) { const queued = [...frames.values()]; frames.clear(); queued.forEach(callback => callback(now)); },
    };
}

test('mounts an inert teal and gold canvas with bounded density and pixel ratio', () => {
    const h = harness();
    assert.deepEqual(h.container.children, [h.canvas]);
    assert.equal(h.canvas.attributes['aria-hidden'], 'true');
    assert.equal(h.canvas.style.pointerEvents, 'none');
    assert.equal(h.canvas.width, 1800);
    assert.equal(h.canvas.height, 1200);
    assert.deepEqual(h.context.transform, [1.5, 0, 0, 1.5, 0, 0]);
    assert.equal(h.frames.size, 1);
    h.step(100);
    assert.match(h.context.fillStyle, /rgba\((137, 246, 225|255, 231, 166),/);
    h.controller.dispose();
});

test('draws at no more than 30 fps and limits stars to 80', () => {
    const h = harness();
    const initialDraws = h.draws.length;
    h.step(100);
    h.step(116);
    h.step(132);
    assert.equal(h.draws.length, initialDraws + 1);
    h.step(150);
    assert.equal(h.draws.length, initialDraws + 2);
    assert.equal(h.context.arcCalls, h.draws.length * 80);
    h.controller.dispose();
});

test('pauses for saved preference, reduced motion, hidden document, offscreen view, and page cache', () => {
    const saved = harness({ savedPause: true });
    assert.equal(saved.frames.size, 0);
    saved.controller.resume();
    assert.equal(saved.storage.get('astral-motion-paused'), 'false');
    assert.equal(saved.frames.size, 1);
    saved.controller.pause();
    assert.equal(saved.storage.get('astral-motion-paused'), 'true');
    assert.equal(saved.frames.size, 0);
    saved.controller.dispose();

    const h = harness({ reduced: true });
    assert.equal(h.frames.size, 0);
    h.media.matches = false; h.media.emit('change');
    assert.equal(h.frames.size, 1);
    h.doc.hidden = true; h.doc.emit('visibilitychange');
    assert.equal(h.frames.size, 0);
    h.doc.hidden = false; h.doc.emit('visibilitychange');
    assert.equal(h.frames.size, 1);
    h.instances[0].show(false);
    assert.equal(h.frames.size, 0);
    h.instances[0].show(true);
    assert.equal(h.frames.size, 1);
    h.win.emit('pagehide');
    assert.equal(h.frames.size, 0);
    h.win.emit('pageshow');
    assert.equal(h.frames.size, 1);
    h.controller.dispose();
});

test('fallback viewport tracking and resize keep animation bounded', () => {
    const h = harness({ observers: false, storageAvailable: false });
    h.rect.top = 950; h.rect.bottom = 1750;
    h.win.emit('scroll');
    assert.equal(h.frames.size, 0);
    h.rect.top = 0; h.rect.bottom = 800;
    h.win.emit('scroll');
    assert.equal(h.frames.size, 1);
    h.rect.width = 3000; h.rect.height = 2000;
    h.win.emit('resize');
    assert.equal(h.canvas.width, 4500);
    assert.equal(h.canvas.height, 3000);
    assert.doesNotThrow(() => h.controller.pause());
    h.controller.dispose();
    assert.equal(h.frames.size, 0);
    assert.equal(h.win.listenerCount('scroll'), 0);
    assert.equal(h.win.listenerCount('resize'), 0);
    assert.equal(h.container.children.length, 0);
});

test('dispose disconnects observers and listeners and cannot restart animation', () => {
    const h = harness();
    h.controller.dispose();
    h.controller.dispose();
    assert.equal(h.frames.size, 0);
    assert.equal(h.instances.every(observer => observer.disconnected), true);
    assert.equal(h.doc.listenerCount('visibilitychange'), 0);
    assert.equal(h.media.listenerCount('change'), 0);
    assert.equal(h.container.children.length, 0);
    h.doc.emit('visibilitychange');
    h.controller.resume();
    assert.equal(h.frames.size, 0);
});
