/*!
 * Adapted from Start Bootstrap - SB Admin v7.0.7 (https://startbootstrap.com/template/sb-admin)
 * Copyright 2013-2023 Start Bootstrap
 * Licensed under MIT (https://github.com/StartBootstrap/startbootstrap-sb-admin/blob/master/LICENSE)
 */
window.addEventListener('DOMContentLoaded', () => {
    document.querySelectorAll('main table:not(#datatablesSimple)').forEach(table => {
        if (table.closest('.table-responsive')) return;
        const wrapper = document.createElement('div');
        wrapper.className = 'table-responsive';
        table.before(wrapper);
        wrapper.append(table);
    });

    const toggle = document.getElementById('sidebarToggle');
    const sidebar = document.getElementById('layoutSidenav_nav');
    if (!toggle || !sidebar) return;

    const desktop = window.matchMedia('(min-width: 992px)');
    const backdrop = document.querySelector('.app-mobile-backdrop');
    const content = document.getElementById('layoutSidenav_content');
    const topActions = document.getElementById('stockTopActions');
    const isOpen = () => desktop.matches !== document.body.classList.contains('sb-sidenav-toggled');

    const synchroniser = () => {
        const open = isOpen();
        const mobileOpen = open && !desktop.matches;
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', open ? 'Masquer le menu' : 'Afficher le menu');
        sidebar.inert = !open;
        sidebar.setAttribute('aria-hidden', String(!open));
        if (content) content.inert = mobileOpen;
        if (topActions) topActions.inert = mobileOpen;
        document.body.classList.toggle('stock-menu-open', mobileOpen);
    };

    const fermer = () => {
        document.body.classList.toggle('sb-sidenav-toggled', desktop.matches);
        synchroniser();
        toggle.focus();
    };

    toggle.addEventListener('click', () => {
        document.body.classList.toggle('sb-sidenav-toggled');
        synchroniser();
    });
    if (backdrop) backdrop.addEventListener('click', fermer);
    sidebar.addEventListener('click', event => {
        if (!desktop.matches && event.target.closest('a[href]')) fermer();
    });
    document.addEventListener('keydown', event => {
        if (desktop.matches || !isOpen()) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            fermer();
        } else if (event.key === 'Tab') {
            const controls = [toggle, ...sidebar.querySelectorAll('a[href], button:not([disabled])')]
                .filter(element => element.getClientRects().length);
            const first = controls[0];
            const last = controls[controls.length - 1];
            if ((event.shiftKey && document.activeElement === first) ||
                (!event.shiftKey && document.activeElement === last) ||
                !controls.includes(document.activeElement)) {
                event.preventDefault();
                (event.shiftKey ? last : first).focus();
            }
        }
    });
    desktop.addEventListener('change', () => {
        const sidebarHadFocus = sidebar.contains(document.activeElement);
        document.body.classList.remove('sb-sidenav-toggled');
        synchroniser();
        if (!desktop.matches && sidebarHadFocus) toggle.focus();
    });
    synchroniser();
});
