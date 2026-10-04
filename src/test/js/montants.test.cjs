const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const scripts = path.resolve(__dirname, '../../main/resources/static/js');

function environnement(script) {
    class Element {
        constructor() { this.children = []; this.dataset = {}; this.value = ''; this.className = ''; this.classList = { add() {}, remove() {} }; }
        appendChild(child) { child.parent = this; this.children.push(child); }
        insertCell() { const cell = new Element(); this.appendChild(cell); return cell; }
        addEventListener() {}
        setAttribute() {}
        setCustomValidity(value) { this.validationMessage = value; }
        focus() {}
        remove() { this.parent.children.splice(this.parent.children.indexOf(this), 1); }
        querySelectorAll() {
            return this.children.flatMap(child => [child, ...child.querySelectorAll()]).filter(child => child.name);
        }
    }
    const elements = Object.fromEntries(['inputPrix', 'inputQtite', 'inputQuantite', 'totalGeneral', 'montantVerse',
        'resteAPayer', 'totalVente', 'inputTotalTotal', 'panierBody'].map(id => [id, new Element()]));
    const body = new Element();
    const option = { value: '1', text: 'Produit (10 en stock)',
        getAttribute: name => ({ 'data-nom': 'Produit', 'data-prix': option.prix })[name], prix: '0.10' };
    elements.selectProduit = new Element(); elements.selectProduit.options = [option]; elements.selectProduit.selectedIndex = 0;
    const alerts = [];
    const document = {
        addEventListener() {},
        createElement: () => new Element(),
        getElementById: id => elements[id] || elements.panierBody.children.find(row => row.id === id),
        querySelector: () => body,
        querySelectorAll: selector => selector === '.ligne-total'
            ? body.children.flatMap(row => row.children).filter(cell => cell.className.includes('ligne-total'))
            : body.children
    };
    const context = vm.createContext({ document, alert: message => alerts.push(message) });
    vm.runInContext(readFileSync(path.join(scripts, 'montants.js'), 'utf8'), context);
    if (script) vm.runInContext(readFileSync(path.join(scripts, script), 'utf8'), context);
    return { context, elements, body, option, alerts };
}

test('les montants conservent les centimes au-delà de la précision de Number', () => {
    const { context } = environnement();
    assert.equal(vm.runInContext("Montants.formater(Montants.lire('0.10') + Montants.lire('0.20'))", context), '0.30');
    assert.equal(vm.runInContext("Montants.formater(Montants.lire('999999999999999.99'))", context), '999999999999999.99');
    assert.equal(vm.runInContext("Montants.afficher(Montants.lire('-1000000.01'))", context), '-1 000 000.01');
    for (const invalid of ['NaN', 'Infinity', '1.001', '1000000000000000', '', 'abc']) {
        assert.equal(vm.runInContext(`Montants.lire(${JSON.stringify(invalid)})`, context), null);
    }
});

test('le panier achat calcule le total et le reste sans arrondi', () => {
    const { context, elements, body } = environnement('achat.js');
    for (const [prix, quantite] of [['0.10', '3'], ['0.20', '1']]) {
        elements.selectProduit.value = '1'; elements.inputPrix.value = prix; elements.inputQtite.value = quantite;
        vm.runInContext('ajouterLigne()', context);
    }
    assert.equal(elements.totalGeneral.value, '0.50');
    elements.montantVerse.value = '0.10'; elements.montantVerse.dataset.saisieManuelle = 'true';
    vm.runInContext('calculerReste()', context);
    assert.equal(elements.resteAPayer.value, '0.40');
    body.children[0].remove(); vm.runInContext('reindexerLignes(); calculerTotal()', context);
    assert.equal(elements.totalGeneral.value, '0.20');
    assert.equal(elements.resteAPayer.value, '0.10');
    assert.equal(body.children[0].querySelectorAll()[0].name, 'lignes[0].produit.id');
});

test('le panier vente conserve le versement manuel après suppression', () => {
    const { context, elements, option } = environnement('panier.js');
    for (const quantite of ['3', '2']) {
        elements.selectProduit.value = '1'; elements.inputQuantite.value = quantite;
        vm.runInContext('ajouterAuPanier()', context);
    }
    assert.equal(elements.totalVente.innerText, '0.50');
    elements.montantVerse.value = '0.10'; elements.montantVerse.dataset.saisieManuelle = 'true';
    vm.runInContext('calculerReste(); supprimerLigne(0, 30n, "1", 3)', context);
    assert.equal(elements.inputTotalTotal.value, '0.20');
    assert.equal(elements.resteAPayer.value, '0.10');
    assert.equal(option.text, 'Produit (8 en stock)');
});

test('le total maximal conserve .99 et un dépassement ne change pas le panier ou le stock', () => {
    for (const script of ['achat.js', 'panier.js']) {
        const env = environnement(script); const { context, elements, option, alerts } = env;
        elements.selectProduit.value = '1'; elements.inputPrix.value = '999999999999999.99';
        option.prix = '999999999999999.99'; elements.inputQtite.value = '1'; elements.inputQuantite.value = '1';
        vm.runInContext(script === 'achat.js' ? 'ajouterLigne()' : 'ajouterAuPanier()', context);
        assert.equal((script === 'achat.js' ? elements.totalGeneral : elements.inputTotalTotal).value, '999999999999999.99');
        elements.selectProduit.value = '1'; elements.inputPrix.value = '0.01'; option.prix = '0.01';
        elements.inputQtite.value = '1'; elements.inputQuantite.value = '1';
        const stock = option.text;
        vm.runInContext(script === 'achat.js' ? 'ajouterLigne()' : 'ajouterAuPanier()', context);
        assert.equal(alerts.length, 1); assert.equal(option.text, stock);
        assert.equal((script === 'achat.js' ? env.body : elements.panierBody).children.length, 1);
    }
});
