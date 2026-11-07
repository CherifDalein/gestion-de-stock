const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const contexte = vm.createContext({});
vm.runInContext(readFileSync(path.resolve(__dirname, '../../main/resources/static/js/solde-depart.js'), 'utf8'), contexte);
const S = contexte.SoldeDepart;

test('le montant est formaté avec des séparateurs de milliers', () => {
    assert.equal(S.apercu('1000000'), '= 1 000 000 GNF');
    assert.equal(S.apercu('1500.5'), '= 1 500,50 GNF');
    assert.equal(S.apercu(''), '');
    assert.equal(S.apercu('abc'), '');
});

test('le solde saisi est signé selon le sens', () => {
    assert.equal(S.soldeSaisi('1000', 'IL_ME_DOIT'), 100000n);
    assert.equal(S.soldeSaisi('1000', 'JE_LUI_DOIS'), -100000n);
    assert.equal(S.soldeSaisi('0', 'JE_LUI_DOIS'), 0n);
    assert.equal(S.soldeSaisi('x', 'IL_ME_DOIT'), null);
});

test('le texte de confirmation montre l\'ancien et le nouveau solde avec leur sens', () => {
    const ancien = S.soldeSaisi('1000000', 'JE_LUI_DOIS');
    const nouveau = S.soldeSaisi('10000000', 'JE_LUI_DOIS');
    assert.equal(S.confirmation(ancien, nouveau),
        'Je confirme le nouveau solde de départ : 1 000 000 GNF (je lui dois) → 10 000 000 GNF (je lui dois)');
    assert.equal(S.confirmation(0n, 5000000n), 'Je confirme le nouveau solde de départ : 0 GNF → 50 000 GNF (il me doit)');
});
