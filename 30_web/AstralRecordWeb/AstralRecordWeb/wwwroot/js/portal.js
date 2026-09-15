(() => {
    'use strict';
    const menu = document.getElementById('site-menu');
    const toggle = document.querySelector('[data-bs-target="#site-menu"]');
    menu?.addEventListener('show.bs.offcanvas', () => toggle?.setAttribute('aria-expanded', 'true'));
    menu?.addEventListener('hidden.bs.offcanvas', () => toggle?.setAttribute('aria-expanded', 'false'));
    menu?.querySelectorAll('a').forEach(link => link.addEventListener('click', () => {
        window.bootstrap?.Offcanvas.getInstance(menu)?.hide();
    }));

    const dialog = document.getElementById('logout-confirm');
    let logoutTrigger;
    document.querySelectorAll('[data-logout-open]').forEach(link => link.addEventListener('click', event => {
        if (!dialog?.showModal) return; // The /Logout page remains a confirmation without JavaScript.
        event.preventDefault();
        logoutTrigger = link;
        if (menu?.classList.contains('show') || menu?.classList.contains('hiding')) {
            menu.addEventListener('hidden.bs.offcanvas', () => dialog.showModal(), { once: true });
            window.bootstrap?.Offcanvas.getInstance(menu)?.hide();
        } else dialog.showModal();
    }));
    document.querySelector('[data-logout-cancel]')?.addEventListener('click', () => dialog.close());
    dialog?.addEventListener('close', () => {
        (logoutTrigger?.closest('.offcanvas') ? toggle : logoutTrigger)?.focus();
    });

    const motion = matchMedia('(prefers-reduced-motion: reduce)');
    if ('IntersectionObserver' in window && !motion.matches) {
        const observer = new IntersectionObserver(entries => entries.forEach(entry => {
            if (entry.isIntersecting) {
                entry.target.classList.add('ar-revealed');
                observer.unobserve(entry.target);
            }
        }), { threshold: .08 });
        document.querySelectorAll('.ar-feature-card, .ar-world-copy, .ar-discord-panel, .ar-edition-card, .ar-player-card').forEach(element => {
            element.classList.add('ar-reveal-ready');
            observer.observe(element);
        });
        motion.addEventListener('change', () => {
            if (motion.matches) { observer.disconnect(); document.querySelectorAll('.ar-reveal-ready').forEach(e => e.classList.add('ar-revealed')); }
        });
    }
})();
