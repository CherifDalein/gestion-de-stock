const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const scripts = path.resolve(__dirname, '../../main/resources/static/js');

// Sans DOM : seul le calcul pur est exposé. Le serveur reste seul juge du montant.
function charger() {
    const contexte = vm.createContext({});
    for (const fichier of ['montants.js', 'retour-vente.js']) vm.runInContext(readFileSync(path.join(scripts, fichier), 'utf8'), contexte);
    return contexte.RetourVente;
}

test('le retour est imputé sur la dette, puis le reste est rendu ou mis en avoir', () => {
    const { calculer } = charger();
    // Vente payée : aucune dette, tout le retour est à rendre (30 000 GNF = 3 000 000 centimes).
    assert.deepEqual({ ...calculer([{ prix: 1000000n, quantite: 3 }], 0n) }, { montant: 3000000n, impute: 0n, excedent: 3000000n });
    // Dette 80 000 : le retour de 30 000 est entièrement imputé, sans argent.
    assert.deepEqual({ ...calculer([{ prix: 1000000n, quantite: 3 }], 8000000n) }, { montant: 3000000n, impute: 3000000n, excedent: 0n });
    // Dette 20 000 : 20 000 imputés, 30 000 d'excédent sur un retour de 50 000.
    assert.deepEqual({ ...calculer([{ prix: 1000000n, quantite: 5 }], 2000000n) }, { montant: 5000000n, impute: 2000000n, excedent: 3000000n });
});

test('plusieurs lignes et quantités nulles', () => {
    const { calculer } = charger();
    const resultat = calculer([{ prix: 250050n, quantite: 2 }, { prix: 999n, quantite: 0 }, { prix: 100n, quantite: 3 }], 0n);
    assert.equal(resultat.montant, 500400n);
    assert.equal(calculer([], 500n).montant, 0n);
    assert.equal(calculer([], 500n).excedent, 0n);
});
