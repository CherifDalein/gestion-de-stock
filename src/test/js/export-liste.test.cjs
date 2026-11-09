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

const { lienAvecParametres, analyserChamps, valeurDuChamp } = require(path.resolve(__dirname, '../../main/resources/static/js/export-liste.js'));

test('plusieurs paramètres : les vides sont retirés, les autres remplacent les anciens', () => {
    assert.equal(lienAvecParametres('/produits/export/excel?stock=faible&categorie=3', { q: 'riz', categorie: '', stock: 'rupture' }),
        '/produits/export/excel?q=riz&stock=rupture');
    assert.equal(lienAvecParametres('/x?a=1', { b: '   ', c: null }), '/x?a=1');
    assert.equal(lienAvecParametres('/x', { q: 'a&b=c<script>' }), '/x?q=a%26b%3Dc%3Cscript%3E');
});

test('la déclaration des champs n\'accepte que des identifiants', () => {
    assert.deepEqual(analyserChamps('q=#produitsSearch;stock=#produitsStock; source=#caisseSource@libelle'), [
        { nom: 'q', identifiant: 'produitsSearch', libelle: false },
        { nom: 'stock', identifiant: 'produitsStock', libelle: false },
        { nom: 'source', identifiant: 'caisseSource', libelle: true }]);
    assert.deepEqual(analyserChamps('q=body > script;x=.classe;=#sans-nom;stock=#a b'), []);
    assert.deepEqual(analyserChamps(''), []);
    assert.deepEqual(analyserChamps(undefined), []);
});

test('un champ de liste envoie son libellé, ou rien pour « tous »', () => {
    const select = (value, texte) => ({ value, selectedOptions: [{ textContent: texte }] });
    const document = { getElementById: id => ({ src: select('source-2', 'VENTE'), tous: select('', 'Toutes les sources'), texte: { value: 'riz' } }[id] || null) };
    assert.equal(valeurDuChamp({ identifiant: 'src', libelle: false }, { getElementById: () => select('source-2', 'VENTE') }), 'source-2');
    assert.equal(valeurDuChamp({ identifiant: 'src', libelle: true }, document), 'VENTE');
    assert.equal(valeurDuChamp({ identifiant: 'tous', libelle: true }, document), '');
    assert.equal(valeurDuChamp({ identifiant: 'texte', libelle: false }, document), 'riz');
    assert.equal(valeurDuChamp({ identifiant: 'absent', libelle: false }, document), '');
});
