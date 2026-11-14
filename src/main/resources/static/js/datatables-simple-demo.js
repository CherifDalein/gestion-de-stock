/*
 * Tableaux des journaux (ventes, achats, devis, retours) pris en charge par simple-datatables.
 *
 * simple-datatables reconstruit les lignes à partir de ses données : sans précaution, les attributs posés par le serveur
 * sur les <tr> et les <td> (classes de colonnes jt-*, data-label des fiches sur téléphone, data-* des filtres) disparaissent
 * au premier affichage, puis à chaque tri, recherche ou changement de page. On les relève avant la prise en charge et on
 * les remet sur chaque ligne rendue (option rowRender, l'index reçu est celui de la ligne dans les données d'origine).
 */
(function (racine, fabrique) {
    'use strict';
    const api = fabrique();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else {
        racine.StockTableaux = api;
        if (racine.addEventListener) racine.addEventListener('DOMContentLoaded', () => api.installer(racine.document, racine));
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    function attributs(element) {
        const resultat = {};
        Array.from(element.attributes || []).forEach(a => { resultat[a.name] = a.value; });
        return resultat;
    }

    /** Relève les attributs de chaque ligne et de chaque cellule du corps du tableau, dans l'ordre des données. */
    function releverAttributs(table) {
        const corps = table.tBodies && table.tBodies[0];
        return corps ? Array.from(corps.rows).map(tr => ({ ligne: attributs(tr), cellules: Array.from(tr.cells).map(attributs) })) : [];
    }

    /** rowRender pour simple-datatables : remet les attributs d'origine ; ceux posés par la bibliothèque restent prioritaires. */
    function rendreLigne(releve) {
        return (donnees, tr, index) => {
            const origine = releve[index];
            if (!origine || !tr) return tr;
            tr.attributes = Object.assign({}, origine.ligne, tr.attributes || {});
            (tr.childNodes || []).forEach((td, colonne) => {
                const attributsCellule = origine.cellules[colonne];
                if (attributsCellule && td) td.attributes = Object.assign({}, attributsCellule, td.attributes || {});
            });
            return tr;
        };
    }

    function installer(doc, fenetre) {
        const table = doc.getElementById('datatablesSimple');
        if (!table) return null;
        const journalRegion = table.closest('.journal-table-wrap');
        if (fenetre.simpleDatatables && fenetre.simpleDatatables.DataTable) {
            // Exposé pour le « Trier par » du téléphone (tri-mobile.js), qui déclenche le même tri que l'en-tête.
            fenetre.stockDataTable = new fenetre.simpleDatatables.DataTable(table, {
                rowRender: rendreLigne(releverAttributs(table)),
                labels: {
                    placeholder: 'Rechercher dans la liste…',
                    searchTitle: 'Rechercher dans le tableau',
                    perPage: 'lignes par page',
                    pageTitle: 'Page {page}',
                    noRows: 'Aucun élément à afficher.',
                    noResults: 'Aucun résultat pour cette recherche.',
                    info: 'Affichage de {start} à {end} sur {rows} lignes'
                }
            });
            // DataTables owns the scroll container; keep its controls outside that region.
            const scroll = journalRegion && journalRegion.querySelector('.datatable-container');
            if (scroll) {
                ['role', 'tabindex', 'aria-label', 'aria-describedby'].forEach(attribute => {
                    if (journalRegion.hasAttribute(attribute)) {
                        scroll.setAttribute(attribute, journalRegion.getAttribute(attribute));
                        journalRegion.removeAttribute(attribute);
                    }
                });
                journalRegion.classList.remove('table-responsive');
            }
            // tri-mobile.js s'installe au chargement du document, avant cette prise en charge : on le relance ici.
            if (fenetre.StockTriMobile) fenetre.StockTriMobile.installer(doc, fenetre);
            return fenetre.stockDataTable;
        }
        table.classList.add('table');
        if (table.closest('.table-responsive')) return null;
        const wrapper = doc.createElement('div');
        wrapper.className = 'table-responsive';
        table.before(wrapper);
        wrapper.append(table);
        return null;
    }

    return { releverAttributs, rendreLigne, installer };
});
