const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const contexte = vm.createContext({ document: { readyState: 'complete', body: { dataset: { monnaie: 'FCFA' } }, getElementById: () => null } });
vm.runInContext(readFileSync(path.resolve(__dirname, '../../main/resources/static/js/comptage.js'), 'utf8'), contexte);
const C = contexte.Comptage;

test('le total du billetage multiplie chaque coupure par sa quantité', () => {
    // Les coupures viennent de la page (monnaie de la boutique) : ici celles du franc CFA (billets).
    const coupures = [10000, 5000, 2000, 1000, 500];
    const q = ['2', '1', '0', '3', '', '4'];   // 2x10000 + 5000 + 0x2000 + 3x1000 = 28 000
    assert.equal(C.totalBilletage(q, coupures), 2800000n);
    assert.equal(C.totalBilletage([], coupures), 0n);
    assert.equal(C.totalBilletage(['abc', '-1'], coupures), 0n);
    assert.equal(C.totalBilletage(['2', '1'], [20000, 10000]), 5000000n);
});

test('les montants sont formatés sans décimales, avec des espaces insécables', () => {
    assert.equal(C.formater(C.lire('1250000')), '1\u00a0250\u00a0000');
    assert.equal(C.formater(C.lire('1500,5')), '1\u00a0501');   // arrondi HALF_UP à l'affichage
    assert.equal(C.lire('12a'), null);
});

test('l\'écart est compté moins théorique et décrit en clair', () => {
    assert.equal(C.texteEcart(C.ecart(C.lire('100000'), C.lire('100000'))), 'Aucun écart : la caisse est conforme');
    assert.equal(C.texteEcart(C.ecart(C.lire('105000'), C.lire('100000'))), 'Excédent de 5\u00a0000\u00a0FCFA');
    assert.equal(C.texteEcart(C.ecart(C.lire('98500'), C.lire('100000'))), 'Manque de 1\u00a0500\u00a0FCFA');
});

test('une quantité illisible ou trop longue est refusée', () => {
    assert.equal(C.quantite('12'), 12);
    assert.equal(C.quantite(''), 0);
    assert.equal(C.quantite('1.5'), null);
    assert.equal(C.quantite('12345678'), null);
});
