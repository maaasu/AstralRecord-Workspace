(() => {
    'use strict';

    const elements = [...document.querySelectorAll('[data-astral-scene]')];
    if (!elements.length) return;

    const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
    const controls = [...document.querySelectorAll('[data-motion-toggle]')];
    const storageKey = 'astral-motion-paused';
    let paused = false;
    try { paused = sessionStorage.getItem(storageKey) === 'true'; } catch { /* Storage is optional. */ }
    let frame = 0;
    let lastTime = 0;
    let elapsed = 0;
    let geometryDirty = true;
    let suspended = false;

    const scenes = elements.map(element => {
        const canvas = element.querySelector('[data-astral-particles]');
        const video = element.querySelector('[data-astral-video]');
        let context = null;
        try { context = canvas?.getContext('2d') ?? null; } catch { /* CSS effects work without canvas. */ }
        return { element, canvas, context, video, videoRequested: false, videoFailed: false, videoRequest: 0, width: 0, height: 0, visible: false, particles: [] };
    });

    const canMove = () => !paused && !reducedMotion.matches && !document.hidden && !suspended;
    const stopFrame = () => {
        if (frame) window.cancelAnimationFrame(frame);
        frame = 0;
        lastTime = 0;
    };

    function syncVideo(scene) {
        const video = scene.video;
        if (!video) return;
        if (!canMove() || !scene.visible) {
            scene.videoRequest++;
            scene.videoRequested = false;
            video.pause();
            return;
        }
        if (scene.videoRequested || scene.videoFailed) return;
        // No source is attached until the scene is visible and motion is allowed.
        if (!video.getAttribute('src') || video.error) {
            video.muted = true;
            video.src = window.innerWidth < 768 ? video.dataset.videoMobile : video.dataset.videoDesktop;
        }
        scene.videoRequested = true;
        const request = ++scene.videoRequest;
        const failed = () => {
            if (request !== scene.videoRequest) return;
            scene.videoRequested = false;
            scene.videoFailed = true;
            video.dataset.videoReady = 'false';
        };
        try { Promise.resolve(video.play()).catch(failed); } catch { failed(); }
    }

    scenes.forEach(scene => {
        scene.video?.addEventListener('playing', () => {
            if (canMove() && scene.visible && !scene.videoFailed) scene.video.dataset.videoReady = 'true';
            else syncVideo(scene);
        });
        scene.video?.addEventListener('error', () => {
            scene.videoFailed = true;
            scene.videoRequested = false;
            scene.video.dataset.videoReady = 'false';
        });
    });

    function updateGeometry() {
        scenes.forEach(scene => {
            const rect = scene.element.getBoundingClientRect();
            scene.visible = rect.bottom > 0 && rect.top < window.innerHeight;
            scene.element.dataset.sceneVisible = String(scene.visible);
            const progress = Math.max(0, Math.min(1, -rect.top / Math.max(1, rect.height)));
            scene.element.style.setProperty('--ar-scene-shift', `${(progress * 65).toFixed(1)}px`);
            scene.element.style.setProperty('--ar-copy-shift', `${(progress * -30).toFixed(1)}px`);
            syncVideo(scene);
            if (!scene.context || (scene.width === rect.width && scene.height === rect.height)) return;

            scene.width = rect.width;
            scene.height = rect.height;
            const ratio = Math.min(window.devicePixelRatio || 1, 1.5);
            scene.canvas.width = Math.round(rect.width * ratio);
            scene.canvas.height = Math.round(rect.height * ratio);
            scene.context.setTransform(ratio, 0, 0, ratio, 0, 0);
            const count = rect.width < 768 ? 42 : 96;
            scene.particles = Array.from({ length: count }, (_, index) => ({
                x: ((index * 73 + 17) % 101) / 101,
                y: ((index * 47 + 31) % 103) / 103,
                speed: 16 + (index % 7) * 5,
                size: index % 6 === 0 ? 3 : 1.3,
                color: index % 3 === 0 ? '137, 246, 225' : '255, 231, 166',
                phase: index * 1.7,
            }));
        });
        geometryDirty = false;
    }

    function draw(scene) {
        const context = scene.context;
        if (!context || !scene.width || !scene.height) return;
        context.clearRect(0, 0, scene.width, scene.height);
        scene.particles.forEach(particle => {
            const x = particle.x * scene.width + Math.sin(elapsed * .25 + particle.phase) * 28;
            const span = scene.height + 40;
            const y = ((particle.y * span - elapsed * particle.speed) % span + span) % span - 20;
            const alpha = .35 + (Math.sin(elapsed * .8 + particle.phase) + 1) * .25;
            context.fillStyle = `rgba(${particle.color}, ${alpha.toFixed(2)})`;
            context.beginPath();
            context.arc(x, y, particle.size, 0, Math.PI * 2);
            context.fill();
            if (particle.size > 2) {
                context.fillStyle = `rgba(255, 238, 193, ${(alpha * .5).toFixed(2)})`;
                context.fillRect(x - 7, y - .5, 14, 1);
                context.fillRect(x - .5, y - 7, 1, 14);
            }
        });
    }

    function tick(now) {
        frame = 0;
        if (!canMove()) return;
        if (geometryDirty) updateGeometry();
        if (!scenes.some(scene => scene.visible)) { lastTime = 0; return; }
        // Cap particle rendering at 30 fps and prevent jumps after tab restoration.
        if (!lastTime || now - lastTime >= 1000 / 30) {
            elapsed += lastTime ? Math.min(now - lastTime, 80) / 1000 : 0;
            lastTime = now;
            scenes.filter(scene => scene.visible).forEach(draw);
        }
        frame = window.requestAnimationFrame(tick);
    }

    function requestFrame() {
        if (canMove() && !frame) frame = window.requestAnimationFrame(tick);
    }

    function updateState() {
        const state = reducedMotion.matches ? 'reduced' : canMove() ? 'running' : 'paused';
        document.body.dataset.motionState = state;
        controls.forEach(button => {
            button.hidden = false;
            button.disabled = reducedMotion.matches;
            const label = button.querySelector('[data-motion-label]');
            if (label) {
                label.textContent = reducedMotion.matches ? '動きを控えて表示中' : paused ? '演出を再生' : '演出を一時停止';
                button.title = label.textContent;
            }
        });
        if (!canMove()) {
            stopFrame();
            scenes.forEach(syncVideo);
            if (reducedMotion.matches) scenes.forEach(scene => {
                scene.element.style.setProperty('--ar-scene-shift', '0px');
                scene.element.style.setProperty('--ar-copy-shift', '0px');
            });
            return;
        }
        geometryDirty = true;
        requestFrame();
    }

    controls.forEach(button => button.addEventListener('click', () => {
        if (reducedMotion.matches) return;
        paused = !paused;
        if (!paused) scenes.forEach(scene => { scene.videoFailed = false; });
        try { sessionStorage.setItem(storageKey, String(paused)); } catch { /* Storage is optional. */ }
        updateState();
    }));

    const revealElements = [...document.querySelectorAll('.ar-journal-heading, .ar-journal-story, .ar-journal-world-copy')];
    if ('IntersectionObserver' in window && !paused && !reducedMotion.matches) {
        const revealObserver = new IntersectionObserver(entries => entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('ar-scene-visible');
                revealObserver.unobserve(entry.target);
            }
        }), { threshold: .12 });
        revealElements.forEach(element => {
            element.classList.add('ar-scene-reveal');
            revealObserver.observe(element);
        });
        reducedMotion.addEventListener('change', () => {
            if (reducedMotion.matches) {
                revealObserver.disconnect();
                revealElements.forEach(element => element.classList.add('ar-scene-visible'));
            }
        });
    }

    window.addEventListener('scroll', () => { geometryDirty = true; requestFrame(); }, { passive: true });
    window.addEventListener('resize', () => { geometryDirty = true; requestFrame(); }, { passive: true });
    document.addEventListener('visibilitychange', updateState);
    reducedMotion.addEventListener('change', updateState);
    window.addEventListener('pagehide', () => { suspended = true; updateState(); });
    window.addEventListener('pageshow', () => { suspended = false; updateState(); });
    updateGeometry();
    document.body.classList.add('ar-motion-ready');
    updateState();
})();
