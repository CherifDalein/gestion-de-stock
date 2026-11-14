/*
 * Thème clair / sombre / automatique.
 *
 * Chargé de façon SYNCHRONE dans le <head>, avant les feuilles de style (fragment fragments/theme.html) : la page est donc
 * peinte avec le bon thème du premier coup, sans flash blanc. Au même moment il pose data-bs-theme="light|dark" sur <html>.
 * Ensuite, quand le document est prêt, il relie les sélecteurs de thème (radios [data-theme-choix], boutons [data-theme-bascule]).
 *
 * - Préférence : « auto » (défaut, suit prefers-color-scheme en direct), « light » ou « dark ».
 * - Mémorisation : localStorage, clé « rfc-theme ». Toute lecture ou écriture est protégée : navigation privée, stockage refusé
 *   ou plein => l'application reste en mode automatique, sans erreur. Rien n'est envoyé au serveur.
 * - Aucune dépendance : le module reçoit son environnement (fenêtre, document) et se teste sous node.
 */
(function (racine, fabrique) {
    if (typeof module === 'object' && module.exports) module.exports = fabrique();
    else {
        const Theme = fabrique();
        racine.RfcTheme = Theme;
        Theme.demarrer(racine, racine.document);
    }
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';

    const CLE = 'rfc-theme';
    const MODES = ['auto', 'light', 'dark'];
    const COULEURS = { light: '#0B2540', dark: '#16212c' };   // theme-color : barre du navigateur Android

    /** Mode valide, ou « auto » pour toute autre valeur (stockage corrompu ou modifié à la main). */
    function normaliser(valeur) { return MODES.indexOf(valeur) >= 0 ? valeur : 'auto'; }

    /** Lit la préférence. Stockage absent, interdit ou en erreur => « auto ». */
    function lire(fenetre) {
        try { return normaliser(fenetre.localStorage.getItem(CLE)); } catch (e) { return 'auto'; }
    }

    /** Écrit la préférence. Renvoie false (sans erreur) si le stockage refuse. « auto » efface la clé. */
    function ecrire(fenetre, mode) {
        try {
            const m = normaliser(mode);
            if (m === 'auto') fenetre.localStorage.removeItem(CLE); else fenetre.localStorage.setItem(CLE, m);
            return true;
        } catch (e) { return false; }
    }

    /** Le téléphone ou l'ordinateur est-il réglé en sombre ? */
    function systemeSombre(fenetre) {
        try { return !!(fenetre.matchMedia && fenetre.matchMedia('(prefers-color-scheme: dark)').matches); } catch (e) { return false; }
    }

    /** Thème effectif (« light » ou « dark ») pour un mode et le réglage du système. */
    function resoudre(mode, sombreSysteme) {
        const m = normaliser(mode);
        if (m === 'auto') return sombreSysteme ? 'dark' : 'light';
        return m;
    }

    /** Pose data-bs-theme et data-theme-mode sur <html>, met à jour theme-color et les sélecteurs. Renvoie le thème effectif. */
    function appliquer(fenetre, doc, mode) {
        const m = normaliser(mode);
        const effectif = resoudre(m, systemeSombre(fenetre));
        const html = doc.documentElement;
        html.setAttribute('data-bs-theme', effectif);
        html.setAttribute('data-theme-mode', m);
        if (doc.querySelectorAll) {
            // Le média (prefers-color-scheme) du balisage sert sans JavaScript ; ici le choix manuel l'emporte.
            doc.querySelectorAll('meta[name="theme-color"]').forEach(function (meta) {
                meta.removeAttribute('media');
                meta.setAttribute('content', COULEURS[effectif]);
            });
            synchroniserControles(doc, m, effectif);
        }
        return effectif;
    }

    /** Coche la bonne radio, marque le bon bouton et met à jour l'étiquette de la bascule lune/soleil. */
    function synchroniserControles(doc, mode, effectif) {
        doc.querySelectorAll('[data-theme-choix]').forEach(function (champ) {
            const actif = champ.value === mode;
            champ.checked = actif;
        });
        doc.querySelectorAll('[data-theme-bascule]').forEach(function (bouton) {
            const versSombre = effectif !== 'dark';
            bouton.setAttribute('aria-label', versSombre ? 'Passer au thème sombre' : 'Passer au thème clair');
            bouton.setAttribute('title', versSombre ? 'Thème sombre' : 'Thème clair');
            bouton.setAttribute('aria-pressed', String(effectif === 'dark'));
        });
    }

    /** Choix de l'utilisateur : mémorise (si possible) et applique tout de suite. */
    function choisir(fenetre, doc, mode) {
        ecrire(fenetre, mode);
        return appliquer(fenetre, doc, mode);
    }

    /** Bouton lune/soleil des pages publiques : bascule entre clair et sombre selon ce qui est affiché. */
    function basculer(fenetre, doc) {
        const actuel = doc.documentElement.getAttribute('data-bs-theme') === 'dark' ? 'dark' : 'light';
        return choisir(fenetre, doc, actuel === 'dark' ? 'light' : 'dark');
    }

    /** Applique la préférence enregistrée, suit le système en mode automatique, relie les sélecteurs. */
    function demarrer(fenetre, doc) {
        if (!fenetre || !doc || !doc.documentElement) return;
        appliquer(fenetre, doc, lire(fenetre));

        let requete = null;
        try { requete = fenetre.matchMedia && fenetre.matchMedia('(prefers-color-scheme: dark)'); } catch (e) { requete = null; }
        const surChangementSysteme = function () {
            if (doc.documentElement.getAttribute('data-theme-mode') === 'auto') appliquer(fenetre, doc, 'auto');
        };
        if (requete) {
            if (requete.addEventListener) requete.addEventListener('change', surChangementSysteme);
            else if (requete.addListener) requete.addListener(surChangementSysteme);
        }
        // Un autre onglet change la préférence : celui-ci suit.
        if (fenetre.addEventListener) fenetre.addEventListener('storage', function (evt) {
            if (evt && evt.key === CLE) appliquer(fenetre, doc, lire(fenetre));
        });

        const relier = function () {
            appliquer(fenetre, doc, lire(fenetre));   // les sélecteurs existent maintenant
            doc.addEventListener('change', function (evt) {
                const cible = evt.target;
                if (cible && cible.hasAttribute && cible.hasAttribute('data-theme-choix') && cible.checked) choisir(fenetre, doc, cible.value);
            });
            doc.addEventListener('click', function (evt) {
                const bouton = evt.target && evt.target.closest ? evt.target.closest('[data-theme-bascule]') : null;
                if (bouton) { evt.preventDefault(); basculer(fenetre, doc); }
            });
        };
        if (doc.readyState === 'loading') doc.addEventListener('DOMContentLoaded', relier); else relier();
    }

    return { CLE, MODES, COULEURS, normaliser, lire, ecrire, systemeSombre, resoudre, appliquer, choisir, basculer, demarrer };
});
