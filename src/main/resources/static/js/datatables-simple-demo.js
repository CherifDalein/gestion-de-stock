window.addEventListener('DOMContentLoaded', () => {
    const table = document.getElementById('datatablesSimple');
    if (!table) return;
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
    } else {
        table.classList.add('table');
        const wrapper = document.createElement('div');
        wrapper.className = 'table-responsive';
        table.before(wrapper);
        wrapper.append(table);
    }
});
