const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { creerDocument, el } = require('./faux-dom.cjs');
const T = require(path.resolve(__dirname, '../../main/resources/static/js/tri-mobile.js'));

function tableau(doc) {
    const ths = ['Numéro', 'Date', 'Total', 'Actions'].map((nom, i) => {
        const th = el(doc, 'th', { texte: nom, class: nom === 'Actions' ? 'journal-colonne-actions' : '' });
        th.append(el(doc, 'a', { href: '#', class: 'datatable-sorter' }));
        return th;
    });
    const table = el(doc, 'table', { id: 'datatablesSimple' }, el(doc, 'thead', {}, el(doc, 'tr', {}, ...ths)));
    const zone = el(doc, 'div', { class: 'journal-table-wrap' }, table);
    const carte = el(doc, 'div', { class: 'card-body' }, zone);
    doc.body.append(carte);
    return { table, ths, carte, zone };
}

test('une option croissante et une décroissante par colonne triable, sans la colonne des actions', () => {
    const doc = creerDocument();
    const { ths } = tableau(doc);
    const o = T.options(ths);
    assert.deepEqual(o.map(x => x.libelle), ['Numéro (croissant)', 'Numéro (décroissant)', 'Date (croissant)', 'Date (décroissant)', 'Total (croissant)', 'Total (décroissant)']);
    assert.deepEqual(o.map(x => [x.index, x.sens]).slice(2, 4), [[1, 'asc'], [1, 'desc']]);
});

test('le choix déclenche le même tri que l’en-tête : colonne et sens', () => {
    const doc = creerDocument();
    const { carte } = tableau(doc);
    const appels = [];
    const env = { stockDataTable: { columns: { sort: (i, s) => appels.push([i, s]) } } };
    const m = T.installer(doc, env);
    assert.ok(m, 'le select est créé');
    assert.equal(carte.querySelector('select'), m.select);
    assert.equal(m.select.parentNode.enfants[0].textContent, 'Trier par');
    m.select.value = '2:desc';
    m.select.dispatch('change');
    m.select.value = '0:asc';
    m.select.dispatch('change');
    m.select.value = '';
    m.select.dispatch('change');
    assert.deepEqual(appels, [[2, 'desc'], [0, 'asc']]);
});

test('sans instance simple-datatables, aucun select n’est ajouté', () => {
    const doc = creerDocument();
    const { carte } = tableau(doc);
    assert.equal(T.installer(doc, {}), null);
    assert.equal(carte.querySelector('select'), null);
});

test('repli : clic sur le déclencheur de l’en-tête, deux fois pour le sens décroissant', () => {
    const doc = creerDocument();
    const { ths } = tableau(doc);
    let clics = 0;
    ths[1].querySelector('a').addEventListener('click', () => { clics++; });
    assert.equal(T.trier({ columns: {} }, ths, 1, 'asc'), 'clic');
    assert.equal(clics, 1);
    assert.equal(T.trier({ columns: {} }, ths, 1, 'desc'), 'clic');
    assert.equal(clics, 3);
});
