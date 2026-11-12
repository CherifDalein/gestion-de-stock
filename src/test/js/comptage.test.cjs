const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const contexte = vm.createContext({});
vm.runInContext(readFileSync(path.resolve(__dirname, '../../main/resources/static/js/comptage.js'), 'utf8'), contexte);
const C = contexte.Comptage;

test('les coupures sont celles du franc guinéen, de la plus grande à la plus petite', () => {
    assert.deepEqual(Array.from(C.COUPURES), [20000, 10000, 5000, 2000, 1000, 500, 100]);
});

test('le total du billetage multiplie chaque coupure par sa quantité', () => {
    const q = ['2', '1', '0', '3', '', '4'];   // 2x20000 + 10000 + 3x2000 + 4x500 = 58 000
    assert.equal(C.totalBilletage(q), 5800000n);
    assert.equal(C.totalBilletage([]), 0n);
    assert.equal(C.totalBilletage(['abc', '-1']), 0n);
});

test('les montants sont formatés avec des espaces et une virgule', () => {
    assert.equal(C.formater(C.lire('1250000')), '1 250 000');
    assert.equal(C.formater(C.lire('1500,5')), '1 500,50');
    assert.equal(C.lire('12a'), null);
});

test('l\'écart est compté moins théorique et décrit en clair', () => {
    assert.equal(C.texteEcart(C.ecart(C.lire('100000'), C.lire('100000'))), 'Aucun écart : la caisse est conforme');
    assert.equal(C.texteEcart(C.ecart(C.lire('105000'), C.lire('100000'))), 'Excédent de 5 000 GNF');
    assert.equal(C.texteEcart(C.ecart(C.lire('98500'), C.lire('100000'))), 'Manque de 1 500 GNF');
});

test('une quantité illisible ou trop longue est refusée', () => {
    assert.equal(C.quantite('12'), 12);
    assert.equal(C.quantite(''), 0);
    assert.equal(C.quantite('1.5'), null);
    assert.equal(C.quantite('12345678'), null);
});
