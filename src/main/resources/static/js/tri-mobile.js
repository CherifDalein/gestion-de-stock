/*
 * « Trier par » sur téléphone pour les tableaux triés par un clic sur leur en-tête (simple-datatables).
 * Sur téléphone, les en-têtes sont masqués (le tableau devient des fiches) : ce <select> prend le relais et déclenche
 * exactement le même tri que le clic sur l'en-tête (colonne et sens), sans paramètre d'adresse : ces tris se font dans le navigateur.
 *
 * Le <select> n'est créé que si le tableau a bien été pris en charge par simple-datatables (window.stockDataTable, posé par
 * datatables-simple-demo.js) : sans cela (réseau coupé, pas de JavaScript) il n'y aurait rien à piloter.
 * Les listes clients et fournisseurs ont leur propre tri (partenaires.js) piloté par le même principe.
 */
(function (racine, fabrique) {
    'use strict';
    const api = fabrique();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else {
        racine.StockTriMobile = api;
        if (racine.document) {
            const lancer = () => api.installer(racine.document, racine);
            if (racine.document.readyState === 'loading') racine.document.addEventListener('DOMContentLoaded', lancer); else lancer();
        }
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    function texte(th) { return String(th.textContent || '').replace(/\s+/g, ' ').trim(); }

    /** Options { valeur, libelle, index, sens } : chaque colonne triable en croissant puis en décroissant. */
    function options(entetes) {
        const liste = [];
        entetes.forEach((th, index) => {
            if (th.hasAttribute && (th.hasAttribute('data-no-tri') || (th.className || '').includes('journal-colonne-actions'))) return;
            const nom = texte(th);
            if (!nom) return;
            liste.push({ valeur: `${index}:asc`, libelle: `${nom} (croissant)`, index, sens: 'asc' });
            liste.push({ valeur: `${index}:desc`, libelle: `${nom} (décroissant)`, index, sens: 'desc' });
        });
        return liste;
    }

    /** Trie la colonne comme le ferait un clic sur son en-tête. Retourne la méthode employée (pour les tests). */
    function trier(tableDonnees, entetes, index, sens) {
        const colonnes = tableDonnees.columns;
        if (colonnes && typeof colonnes.sort === 'function') { colonnes.sort(index, sens); return 'columns.sort'; }
        // Repli : le clic sur le déclencheur de tri de l'en-tête (un clic de plus inverse le sens).
        const th = entetes[index];
        const declencheur = th && th.querySelector ? th.querySelector('a, button') : null;
        if (!declencheur) return null;
        declencheur.click();
        if (sens === 'desc' && th.getAttribute('aria-sort') !== 'descending') declencheur.click();
        return 'clic';
    }

    function installer(doc, env) {
        const fenetre = env || doc.defaultView || {};
        const tableau = doc.getElementById('datatablesSimple');
        const instance = fenetre.stockDataTable;
        if (!tableau || !instance || tableau.hasAttribute('data-tri-mobile-pret')) return null;
        const thead = tableau.querySelector('thead');
        const entetes = thead ? Array.from(thead.querySelectorAll('th')) : [];
        const liste = options(entetes);
        if (!liste.length) return null;
        tableau.setAttribute('data-tri-mobile-pret', '');
        const bloc = doc.createElement('div');
        bloc.className = 'tri-mobile';
        const etiquette = doc.createElement('label');
        etiquette.className = 'form-label';
        etiquette.setAttribute('for', 'triMobile');
        etiquette.textContent = 'Trier par';
        const select = doc.createElement('select');
        select.id = 'triMobile';
        select.className = 'form-select';
        const vide = doc.createElement('option');
        vide.value = '';
        vide.textContent = 'Ordre de la liste';
        select.append(vide);
        liste.forEach(o => { const option = doc.createElement('option'); option.value = o.valeur; option.textContent = o.libelle; select.append(option); });
        select.addEventListener('change', () => {
            const choisie = liste.find(o => o.valeur === select.value);
            if (choisie) trier(instance, entetes, choisie.index, choisie.sens);
        });
        bloc.append(etiquette, select);
        const zone = tableau.closest('.journal-table-wrap') || tableau.parentNode;
        zone.parentNode.insertBefore(bloc, zone);
        return { bloc, select, liste };
    }

    return { options, trier, installer };
});
