// A quiet, decorative starfield for skill trees. The host should be positioned
// and place its interactive content above this absolutely positioned canvas.
const pauseKey = 'astral-skilltree-motion-paused';
const frameInterval = 1000 / 30;
const pixelBudget = 2_250_000;

export function initializeStarfield(container) {
    if (!container?.ownerDocument) throw new TypeError('A DOM container is required');
    const doc = container.ownerDocument;
    const win = doc.defaultView;
    const canvas = doc.createElement('canvas');
    canvas.setAttribute('aria-hidden', 'true');
    canvas.style.position = 'absolute';
    canvas.style.inset = '0';
    canvas.style.width = '100%';
    canvas.style.height = '100%';
    canvas.style.pointerEvents = 'none';
    canvas.style.zIndex = '0';
    container.appendChild(canvas);

    let context;
    try { context = canvas.getContext('2d'); } catch { /* Canvas is optional. */ }
    if (!context) {
        canvas.remove();
        return { pause() {}, resume() {}, dispose() {} };
    }

    const motion = win.matchMedia?.('(prefers-reduced-motion: reduce)');
    let paused = false;
    try { paused = win.sessionStorage?.getItem(pauseKey) === 'true'; } catch { /* Storage is optional. */ }
    let disposed = false;
    let suspended = false;
    let visible = false;
    let width = 0;
    let height = 0;
    let stars = [];
    let frame = 0;
    let lastDraw = 0;
    let elapsed = 0;

    const canAnimate = () => !disposed && !suspended && !paused && !motion?.matches && !doc.hidden && visible && width > 0 && height > 0;
    const cancelFrame = () => {
        if (frame) win.cancelAnimationFrame(frame);
        frame = 0;
        lastDraw = 0;
    };
    const requestFrame = () => {
        if (canAnimate() && !frame) frame = win.requestAnimationFrame(tick);
    };

    function draw() {
        context.clearRect(0, 0, width, height);
        for (const star of stars) {
            const x = star.x * width + Math.sin(elapsed * .22 + star.phase) * 11;
            const span = height + 24;
            const y = ((star.y * span - elapsed * star.speed) % span + span) % span - 12;
            const alpha = .2 + (Math.sin(elapsed * .7 + star.phase) + 1) * .15;
            context.fillStyle = `rgba(${star.color}, ${alpha.toFixed(2)})`;
            context.beginPath();
            context.arc(x, y, star.size, 0, Math.PI * 2);
            context.fill();
            if (star.size > 2) {
                context.fillStyle = `rgba(${star.color}, ${(alpha * .35).toFixed(2)})`;
                context.fillRect(x - 5, y - .5, 10, 1);
                context.fillRect(x - .5, y - 5, 1, 10);
            }
        }
    }

    function tick(now) {
        frame = 0;
        if (!canAnimate()) return;
        if (!lastDraw || now - lastDraw >= frameInterval) {
            elapsed += lastDraw ? Math.min(now - lastDraw, 80) / 1000 : 0;
            lastDraw = now;
            draw();
        }
        requestFrame();
    }

    function resize() {
        if (disposed) return;
        const rect = container.getBoundingClientRect();
        const nextWidth = Math.max(0, rect.width);
        const nextHeight = Math.max(0, rect.height);
        if (nextWidth === width && nextHeight === height) return;
        width = nextWidth;
        height = nextHeight;
        // Large displays need a bounded backing store as well as bounded stars.
        const ratio = Math.min(Math.max(win.devicePixelRatio || 1, 1), 1.5, Math.sqrt(pixelBudget / Math.max(1, width * height)));
        canvas.width = Math.floor(width * ratio);
        canvas.height = Math.floor(height * ratio);
        context.setTransform(ratio, 0, 0, ratio, 0, 0);
        const count = Math.min(80, Math.max(16, Math.round(width * height / 9000)));
        stars = Array.from({ length: count }, (_, index) => ({
            x: ((index * 73 + 17) % 101) / 101,
            y: ((index * 47 + 31) % 103) / 103,
            speed: 6 + (index % 7) * 2,
            size: index % 9 === 0 ? 2.3 : index % 3 === 0 ? 1.5 : .9,
            color: index % 3 === 0 ? '255, 231, 166' : '137, 246, 225',
            phase: index * 1.7,
        }));
        if (visible && !doc.hidden) draw();
        sync();
    }

    function sync() {
        if (canAnimate()) requestFrame();
        else cancelFrame();
    }

    const onVisibility = () => sync();
    const onPageHide = () => { suspended = true; sync(); };
    const onPageShow = () => { suspended = false; sync(); };
    const onScroll = () => {
        const rect = container.getBoundingClientRect();
        visible = rect.bottom > 0 && rect.top < win.innerHeight && rect.right > 0 && rect.left < win.innerWidth;
        sync();
    };
    doc.addEventListener('visibilitychange', onVisibility);
    win.addEventListener('pagehide', onPageHide);
    win.addEventListener('pageshow', onPageShow);
    motion?.addEventListener?.('change', onVisibility);
    if (!motion?.addEventListener) motion?.addListener?.(onVisibility);

    let intersectionObserver;
    if (win.IntersectionObserver) {
        intersectionObserver = new win.IntersectionObserver(entries => {
            visible = entries.some(entry => entry.isIntersecting);
            sync();
        });
        intersectionObserver.observe(container);
    } else {
        win.addEventListener('scroll', onScroll, { passive: true });
    }
    let resizeObserver;
    if (win.ResizeObserver) {
        resizeObserver = new win.ResizeObserver(resize);
        resizeObserver.observe(container);
    } else {
        win.addEventListener('resize', resize);
    }
    onScroll();
    resize();

    return {
        pause(value = true) {
            if (disposed) return;
            paused = Boolean(value);
            try { win.sessionStorage?.setItem(pauseKey, String(paused)); } catch { /* Storage is optional. */ }
            sync();
        },
        resume() { this.pause(false); },
        dispose() {
            if (disposed) return;
            disposed = true;
            cancelFrame();
            intersectionObserver?.disconnect();
            resizeObserver?.disconnect();
            win.removeEventListener('scroll', onScroll);
            win.removeEventListener('resize', resize);
            win.removeEventListener('pagehide', onPageHide);
            win.removeEventListener('pageshow', onPageShow);
            doc.removeEventListener('visibilitychange', onVisibility);
            motion?.removeEventListener?.('change', onVisibility);
            if (!motion?.removeEventListener) motion?.removeListener?.(onVisibility);
            canvas.remove();
        },
    };
}
