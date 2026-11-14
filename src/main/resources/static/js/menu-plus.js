/*
 * Menu « Plus » des lignes de liste : regroupe les actions secondaires (et, séparées en rouge, les actions destructrices).
 *
 * Balisage serveur (utilisable sans JavaScript, grâce à <details>) :
 *   <details class="plus-menu-details" data-plus-menu>
 *     <summary class="btn btn-outline-secondary btn-sm app-row-action plus-menu-bouton" aria-label="Plus d’actions pour X">
 *       <span class="plus-menu-icone" aria-hidden="true">⋯</span><span>Plus</span></summary>
 *     <div class="plus-menu-panel" data-plus-panneau> …liens, formulaires, boutons… </div>
 *   </details>
 *
 * Avec JavaScript, chaque <details data-plus-menu> est remplacé par un vrai <button aria-haspopup="menu" aria-expanded>
 * suivi du panneau. Les liens, formulaires et jetons CSRF du panneau sont déplacés tels quels, sans copie.
 *  - Ouverture au clic, à Entrée/Espace, ou à la flèche bas ; flèches haut/bas, Début et Fin parcourent les entrées.
 *  - Fermeture : Échap (le focus revient au bouton), clic en dehors, Tab hors du menu, choix d'une entrée.
 *  - Le panneau est positionné en « fixed » à partir du bouton : le défilement du tableau ne le coupe pas ; il passe au-dessus
 *    du bouton s'il manque de place en dessous.
 * Tous les écouteurs sont délégués au document : ils survivent aux réécritures du tableau par simple-datatables.
 */
(function (racine, fabrique) {
    'use strict';
    const api = fabrique();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else {
        racine.StockMenuPlus = api;
        if (racine.document) api.installer(racine.document);
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    const MARGE = 8;
    const ENTREES = 'a[href], button';
    let sequence = 0;

    function parties(menu) {
        return { bouton: menu.querySelector('[data-plus-toggle]'), panneau: menu.querySelector('[data-plus-panneau]') };
    }

    function estOuvert(menu) { return menu.hasAttribute('data-ouvert'); }

    function entrees(menu) {
        const { panneau } = parties(menu);
        return panneau ? Array.from(panneau.querySelectorAll(ENTREES)).filter(e => !e.disabled) : [];
    }

    /** Remplace un <details data-plus-menu> par un bouton de menu accessible. Retourne la nouvelle racine, ou null. */
    function ameliorer(details, doc) {
        if (!details.parentNode) return null;
        const resume = details.querySelector('summary');
        const panneau = details.querySelector('[data-plus-panneau]');
        if (!resume || !panneau) return null;
        const id = panneau.id || ('plus-menu-' + (++sequence));
        const bouton = doc.createElement('button');
        bouton.setAttribute('type', 'button');
        bouton.className = resume.className;
        bouton.innerHTML = resume.innerHTML;
        bouton.setAttribute('data-plus-toggle', '');
        bouton.setAttribute('aria-haspopup', 'menu');
        bouton.setAttribute('aria-expanded', 'false');
        bouton.setAttribute('aria-controls', id);
        const libelle = resume.getAttribute('aria-label');
        if (libelle) bouton.setAttribute('aria-label', libelle);
        if (resume.getAttribute('title')) bouton.setAttribute('title', resume.getAttribute('title'));
        panneau.id = id;
        panneau.setAttribute('role', 'menu');
        panneau.hidden = true;
        panneau.style.position = 'fixed';
        Array.from(panneau.querySelectorAll(ENTREES)).forEach(e => e.setAttribute('role', 'menuitem'));
        const menu = doc.createElement('div');
        menu.className = 'plus-menu';
        menu.setAttribute('data-plus-menu-pret', '');
        menu.append(bouton, panneau);
        details.replaceWith(menu);
        return menu;
    }

    function initialiser(doc) {
        return Array.from(doc.querySelectorAll('details[data-plus-menu]')).map(d => ameliorer(d, doc)).filter(Boolean);
    }

    /** Place le panneau en « fixed » près du bouton, dans la fenêtre : au-dessus du bouton s'il manque de place dessous. */
    function positionner(menu, vue) {
        const { bouton, panneau } = parties(menu);
        if (!bouton || !panneau) return;
        // Le panneau sort du flux avant toute mesure : resté dans la cellule, il agrandirait la ligne et déplacerait le bouton.
        const style = panneau.style;
        style.position = 'fixed';
        style.maxHeight = '';
        style.left = '0px';
        style.top = '0px';
        const r = bouton.getBoundingClientRect();
        const largeur = panneau.offsetWidth;
        const hauteur = panneau.offsetHeight;
        const vw = vue.innerWidth;
        const vh = vue.innerHeight;
        const gauche = Math.min(Math.max(r.right - largeur, MARGE), Math.max(vw - largeur - MARGE, MARGE));
        const dessous = vh - r.bottom - MARGE;
        const dessus = r.top - MARGE;
        let haut;
        if (hauteur <= dessous) haut = r.bottom + 4;
        else if (hauteur <= dessus) haut = r.top - hauteur - 4;
        else if (dessous >= dessus) { haut = r.bottom + 4; style.maxHeight = Math.max(dessous - 4, 120) + 'px'; }
        else { const h = Math.max(dessus - 4, 120); style.maxHeight = h + 'px'; haut = Math.max(r.top - 4 - Math.min(hauteur, h), MARGE); }
        style.left = Math.round(gauche) + 'px';
        style.top = Math.round(haut) + 'px';
    }

    function fermer(menu, { rendreFocus = false } = {}) {
        const { bouton, panneau } = parties(menu);
        if (!panneau) return;
        panneau.hidden = true;
        menu.removeAttribute('data-ouvert');
        if (bouton) {
            bouton.setAttribute('aria-expanded', 'false');
            if (rendreFocus) bouton.focus();
        }
    }

    function fermerTous(doc, sauf, options) {
        Array.from(doc.querySelectorAll('[data-plus-menu-pret]')).forEach(m => { if (m !== sauf && estOuvert(m)) fermer(m, options); });
    }

    function ouvrir(menu, doc, { focusPremier = false } = {}) {
        const { bouton, panneau } = parties(menu);
        if (!bouton || !panneau) return;
        fermerTous(doc, menu);
        panneau.hidden = false;
        menu.setAttribute('data-ouvert', '');
        bouton.setAttribute('aria-expanded', 'true');
        positionner(menu, doc.defaultView);
        if (focusPremier) { const premiere = entrees(menu)[0]; if (premiere) premiere.focus(); }
    }

    function deplacer(menu, cible, touche) {
        const liste = entrees(menu);
        if (!liste.length) return;
        const courant = liste.indexOf(cible);
        let suivant;
        if (touche === 'Home') suivant = 0;
        else if (touche === 'End') suivant = liste.length - 1;
        else if (touche === 'ArrowDown') suivant = courant < 0 ? 0 : (courant + 1) % liste.length;
        else suivant = courant <= 0 ? liste.length - 1 : courant - 1;
        liste[suivant].focus();
    }

    function installer(doc) {
        initialiser(doc);
        if (doc.__stockMenuPlus) return;
        doc.__stockMenuPlus = true;
        const vue = doc.defaultView;

        doc.addEventListener('click', evenement => {
            const cible = evenement.target;
            if (!cible || !cible.closest) return;
            const bascule = cible.closest('[data-plus-toggle]');
            if (bascule) {
                const menu = bascule.closest('[data-plus-menu-pret]');
                if (!menu) return;
                if (estOuvert(menu)) fermer(menu); else ouvrir(menu, doc);
                return;
            }
            const dansMenu = cible.closest('[data-plus-menu-pret]');
            if (dansMenu && cible.closest(ENTREES) && cible.closest('[data-plus-panneau]')) {
                fermer(dansMenu, { rendreFocus: true });
                return;
            }
            const actif = doc.activeElement;
            Array.from(doc.querySelectorAll('[data-plus-menu-pret]')).forEach(menu => {
                if (!estOuvert(menu) || menu === dansMenu) return;
                // Le focus ne revient au bouton que s'il n'a pas été donné à un autre champ par ce même clic.
                const focusPerdu = !actif || actif === doc.body || menu.contains(actif);
                fermer(menu, { rendreFocus: focusPerdu });
            });
        });

        doc.addEventListener('keydown', evenement => {
            const cible = evenement.target;
            if (!cible || !cible.closest) return;
            const menu = cible.closest('[data-plus-menu-pret]');
            const touche = evenement.key;
            if (touche === 'Escape' || touche === 'Esc') {
                const ouvert = menu && estOuvert(menu) ? menu : Array.from(doc.querySelectorAll('[data-plus-menu-pret]')).find(estOuvert);
                if (!ouvert) return;
                evenement.preventDefault();
                fermer(ouvert, { rendreFocus: true });
                return;
            }
            if (!menu) return;
            if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(touche)) {
                evenement.preventDefault();
                if (!estOuvert(menu)) {
                    if (touche === 'ArrowDown' || touche === 'ArrowUp') ouvrir(menu, doc, { focusPremier: true });
                    return;
                }
                deplacer(menu, cible, touche);
            }
        });

        doc.addEventListener('focusout', evenement => {
            const menu = evenement.target && evenement.target.closest ? evenement.target.closest('[data-plus-menu-pret]') : null;
            if (!menu || !estOuvert(menu)) return;
            const vers = evenement.relatedTarget;
            if (!vers || menu.contains(vers)) return;
            fermer(menu);
        });

        const repositionner = () => {
            Array.from(doc.querySelectorAll('[data-plus-menu-pret]')).filter(estOuvert).forEach(menu => {
                const r = parties(menu).bouton.getBoundingClientRect();
                if (r.bottom < 0 || r.top > vue.innerHeight) fermer(menu);
                else positionner(menu, vue);
            });
        };
        if (vue) {
            vue.addEventListener('scroll', repositionner, true);
            vue.addEventListener('resize', repositionner);
        }
        if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', () => initialiser(doc));
    }

    return { ameliorer, initialiser, installer, ouvrir, fermer, positionner, estOuvert, entrees };
});
