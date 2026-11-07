const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

// Sans élément #stockProduits, le script n'initialise rien et n'expose que ses fonctions pures.
const contexte = vm.createContext({ document: { readyState: 'complete', getElementById: () => null }, Intl, BigInt });
vm.runInContext(readFileSync(path.resolve(__dirname, '../../main/resources/static/js/produits.js'), 'utf8'), contexte);
const P = contexte.ProduitsCalculs;

test('une marge signée est lue en centimes', () => {
    assert.equal(P.lireMontantSigne('40000.00'), 4000000n);
    assert.equal(P.lireMontantSigne('-5000.50'), -500050n);
    assert.equal(P.lireMontantSigne(''), null);
    assert.equal(P.lireMontantSigne(undefined), null);
    assert.equal(P.lireMontantSigne('abc'), null);
});

test('la marge s\'affiche avec son taux, ou un tiret quand elle n\'existe pas', () => {
    const espace = texte => texte.replace(/ | /g, ' ');
    assert.equal(espace(P.afficherMarge(4000000n, '26.7')), '40 000,00 GNF (26,7 %)');
    assert.equal(espace(P.afficherMarge(-500050n, '-3.3')), '-5 000,50 GNF (-3,3 %)');
    assert.equal(P.afficherMarge(null, ''), '—');
    assert.equal(espace(P.afficherMarge(4000000n, '')), '40 000,00 GNF');
});

test('le tri par marge peut comparer des montants négatifs', () => {
    const marges = ['10.00', '-5.00', '0.00'].map(P.lireMontantSigne).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
    assert.deepEqual(marges, [-500n, 0n, 1000n]);
});
