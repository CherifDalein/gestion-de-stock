const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { lienAvecRecherche } = require(path.resolve(__dirname, '../../main/resources/static/js/export-liste.js'));

test('sans recherche, le lien garde ses filtres et n\'a pas de q', () => {
    assert.equal(lienAvecRecherche('/ventes/export/excel?filtre=echues&vendeur=3', ''), '/ventes/export/excel?filtre=echues&vendeur=3');
    assert.equal(lienAvecRecherche('/ventes/export/pdf', '   '), '/ventes/export/pdf');
});

test('la recherche est ajoutée et encodée, jamais interprétée', () => {
    assert.equal(lienAvecRecherche('/ventes/export/pdf?du=2026-11-01', 'Awa & <b>x</b>'), '/ventes/export/pdf?du=2026-11-01&q=Awa+%26+%3Cb%3Ex%3C%2Fb%3E');
});

test('une recherche précédente est remplacée et la longueur est bornée', () => {
    assert.equal(lienAvecRecherche('/ventes/export/excel?q=ancien', 'nouveau'), '/ventes/export/excel?q=nouveau');
    assert.equal(lienAvecRecherche('/x', 'a'.repeat(300)).length, '/x?q='.length + 100);
});
