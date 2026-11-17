const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const contexte = vm.createContext({ document: { readyState: 'complete', body: { dataset: { monnaie: 'FCFA' } }, getElementById: () => null, querySelectorAll: () => [], querySelector: () => null, addEventListener() {} } });
vm.runInContext(readFileSync(path.resolve(__dirname, '../../main/resources/static/js/rapprochement.js'), 'utf8'), contexte);
const R = contexte.Rapprochement;

test('les montants se lisent avec une virgule ou un point, signés', () => {
    assert.equal(R.lire('250.50'), 25050n);
    assert.equal(R.lire('-1200000,5'), -120000050n);
    assert.equal(R.lire(''), 0n);
    assert.equal(R.lire('12a'), null);
    assert.equal(R.lire('1.234'), null);
});

test('les montants s\'affichent sans décimales, avec des espaces insécables, le signe moins en toutes lettres', () => {
    assert.equal(R.formater(120000050n), '1\u00a0200\u00a0001');
    assert.equal(R.formater(-150000n), '− 1\u00a0500');
    assert.equal(R.formater(0n), '0');
});

test('la somme d\'une sélection additionne des montants signés', () => {
    assert.equal(R.somme(['100000', '-30000.25', 'abc']), 6999975n);
    assert.equal(R.somme([]), 0n);
});

test('le verdict exige des deux côtés et des sommes exactement égales', () => {
    assert.equal(R.verdict([], ['100']).etat, 'vide');
    assert.equal(R.verdict(['100'], []).etat, 'vide');
    assert.equal(R.verdict(['-50000', '-10000'], ['-60000']).etat, 'egal');
    const ecart = R.verdict(['60000'], ['59999.99']);
    assert.equal(ecart.etat, 'ecart');
    assert.match(ecart.texte, /Écart inférieur à 1\u00a0FCFA/);
});
