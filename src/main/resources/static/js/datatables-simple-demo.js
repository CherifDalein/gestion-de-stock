window.addEventListener('DOMContentLoaded', () => {
    const table = document.getElementById('datatablesSimple');
    if (!table) return;
    const journalRegion = table.closest('.journal-table-wrap');
    if (window.simpleDatatables?.DataTable) {
        new window.simpleDatatables.DataTable(table, {
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
        const scroll = journalRegion?.querySelector('.datatable-container');
        if (scroll) {
            ['role', 'tabindex', 'aria-label', 'aria-describedby'].forEach(attribute => {
                if (journalRegion.hasAttribute(attribute)) {
                    scroll.setAttribute(attribute, journalRegion.getAttribute(attribute));
                    journalRegion.removeAttribute(attribute);
                }
            });
            journalRegion.classList.remove('table-responsive');
        }
    } else {
        table.classList.add('table');
        if (table.closest('.table-responsive')) return;
        const wrapper = document.createElement('div');
        wrapper.className = 'table-responsive';
        table.before(wrapper);
        wrapper.append(table);
    }
});
