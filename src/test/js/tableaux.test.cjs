const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const T = require(path.resolve(__dirname, '../../main/resources/static/js/datatables-simple-demo.js'));

function element(attrs, enfants) {
    return { attributes: Object.entries(attrs).map(([name, value]) => ({ name, value })), cells: enfants };
}

function tableau() {
    const ligne = (n, total) => element({ 'data-id': String(n) }, [
        element({ class: 'jt-numero', 'data-label': 'Numéro' }),
        element({ class: 'jt-total', 'data-label': 'Total', 'data-montant': total })
    ]);
    return { tBodies: [{ rows: [ligne(1, '100'), ligne(2, '200')] }] };
}

test('les attributs des lignes et des cellules sont relevés dans l’ordre des données', () => {
    const releve = T.releverAttributs(tableau());
    assert.equal(releve.length, 2);
    assert.deepEqual(releve[1].ligne, { 'data-id': '2' });
    assert.deepEqual(releve[1].cellules[1], { class: 'jt-total', 'data-label': 'Total', 'data-montant': '200' });
});

test('chaque ligne rendue retrouve ses attributs d’origine d’après son index de données, même après un tri', () => {
    const rendre = T.rendreLigne(T.releverAttributs(tableau()));
    // simple-datatables rend la 2e ligne des données en premier (tri décroissant) : l’index reçu reste 1.
    const tr = { nodeName: 'TR', attributes: { 'data-index': '1' }, childNodes: [{ nodeName: 'TD' }, { nodeName: 'TD', attributes: {} }] };
    rendre({}, tr, 1);
    assert.equal(tr.attributes['data-id'], '2');
    assert.equal(tr.attributes['data-index'], '1', 'les attributs de la bibliothèque restent');
    assert.equal(tr.childNodes[0].attributes['data-label'], 'Numéro');
    assert.equal(tr.childNodes[1].attributes.class, 'jt-total');
    assert.equal(tr.childNodes[1].attributes['data-montant'], '200');
});

test('une ligne inconnue (ajoutée après coup) est laissée telle quelle', () => {
    const rendre = T.rendreLigne(T.releverAttributs(tableau()));
    const tr = { attributes: { a: 'b' }, childNodes: [] };
    assert.equal(rendre({}, tr, 5), tr);
    assert.deepEqual(tr.attributes, { a: 'b' });
});
