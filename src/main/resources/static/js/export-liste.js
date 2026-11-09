/*
 * Boutons d'export d'une liste (Excel, PDF) : ils exportent la liste filtrée côté serveur. Quand la liste a une barre de
 * recherche (simple-datatables), le texte tapé est ajouté au lien sous le paramètre « q » pour que le fichier contienne
 * seulement les lignes encore visibles. Le texte passe par URLSearchParams : jamais inséré comme HTML.
 */
(function (global) {
    'use strict';

    /** Lien d'export avec la recherche : retire un « q » précédent, ajoute le nouveau s'il n'est pas vide. */
    function lienAvecRecherche(base, recherche) {
        const adresse = new URL(base, 'http://localhost');
        adresse.searchParams.delete('q');
        const texte = (recherche || '').trim();
        if (texte) adresse.searchParams.set('q', texte.slice(0, 100));
        return adresse.pathname + adresse.search;
    }

    function installer(document) {
        const liens = Array.from(document.querySelectorAll('a[data-export-liste]'));
        if (liens.length === 0) return;
        liens.forEach(lien => { lien.dataset.exportBase = lien.getAttribute('href'); });
        const mettreAJour = () => {
            const champ = document.querySelector('.datatable-input');
            liens.forEach(lien => lien.setAttribute('href', lienAvecRecherche(lien.dataset.exportBase, champ ? champ.value : '')));
        };
        document.addEventListener('input', evenement => {
            if (evenement.target && evenement.target.classList && evenement.target.classList.contains('datatable-input')) mettreAJour();
        });
    }

    const api = { lienAvecRecherche, installer };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else {
        global.ExportListe = api;
        installer(global.document);
    }
})(typeof window !== 'undefined' ? window : globalThis);
