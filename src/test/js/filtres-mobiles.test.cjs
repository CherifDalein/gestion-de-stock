const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { creerDocument, el } = require('./faux-dom.cjs');
const F = require(path.resolve(__dirname, '../../main/resources/static/js/filtres-mobiles.js'));

test('étiquettes : seuls les filtres qui diffèrent de leur valeur par défaut sont rappelés, les dates en jj/mm/aaaa', () => {
    const e = F.etiquettes([
        { libelle: 'Saisi par', valeur: '', defaut: '', texte: 'Tous les comptes' },
        { libelle: 'Compte', valeur: '3', defaut: '', texte: 'Caisse' },
        { libelle: 'Du', valeur: '2026-06-01', defaut: '' },
        { libelle: 'Au (inclus)', valeur: '  ', defaut: '' },
        { libelle: '', valeur: 'oui', defaut: '', texte: 'Échues non soldées' }
    ]);
    assert.deepEqual(e, ['Compte : Caisse', 'Du : 01/06/2026', 'Échues non soldées']);
    assert.deepEqual(F.etiquettes([]), []);
});

/** Un bloc de filtres tel que sur la liste des ventes : un select, deux dates, un filtre « sans champ » et un lien Effacer. */
function bloc(doc, { echues = true, vendeur = '', du = '2026-06-01', au = '' } = {}) {
    const select = el(doc, 'select', { id: 'filtreVendeur', name: 'vendeur' });
    select.options = [{ value: '', textContent: 'Tous les comptes' }, { value: '7', textContent: 'Awa Gérante' }];
    select.value = vendeur;
    const champDu = el(doc, 'input', { id: 'filtreDu', type: 'date', name: 'du' }); champDu.value = du;
    const champAu = el(doc, 'input', { id: 'filtreAu', type: 'date', name: 'au' }); champAu.value = au;
    const enfants = [
        el(doc, 'label', { for: 'filtreVendeur', texte: 'Saisi par' }), select,
        el(doc, 'label', { for: 'filtreDu', texte: 'Du' }), champDu,
        el(doc, 'label', { for: 'filtreAu', texte: 'Au (inclus)' }), champAu
    ];
    if (echues) enfants.push(el(doc, 'input', { type: 'hidden', name: 'filtre', 'data-filtre-libelle': 'Échues non soldées' }));
    enfants.push(el(doc, 'a', { href: '/ventes?filtre=echues', 'data-filtres-effacer': '', texte: 'Effacer les filtres' }));
    const contenu = el(doc, 'form', { 'data-filtres-contenu': '' }, ...enfants);
    const conteneur = el(doc, 'div', { 'data-filtres': '' }, contenu);
    doc.body.append(conteneur);
    return { conteneur, contenu, select, champDu };
}

function environnement(mobile) {
    const ecouteurs = [];
    const mq = { matches: mobile, addEventListener: (t, f) => ecouteurs.push(f) };
    return { matchMedia: () => mq, setTimeout: f => f(), basculer(valeur) { mq.matches = valeur; ecouteurs.forEach(f => f()); } };
}

test('sur téléphone, les filtres sont repliés derrière un bouton qui affiche leur nombre et leurs étiquettes', () => {
    const doc = creerDocument();
    const b = bloc(doc);
    const [m] = F.installer(doc, environnement(true));
    assert.equal(b.contenu.hidden, true, 'replié');
    assert.equal(m.bouton.getAttribute('aria-expanded'), 'false');
    assert.equal(m.compteur.textContent, '2');
    assert.equal(m.compteur.hidden, false);
    assert.equal(m.bouton.getAttribute('aria-label'), 'Filtres, 2 actifs');
    assert.deepEqual(m.liste.enfants.map(li => li.textContent), ['Du : 01/06/2026', 'Échues non soldées']);
    assert.equal(m.effacer.hidden, false);
    assert.equal(m.effacer.getAttribute('href'), '/ventes?filtre=echues', 'Effacer reprend le lien du formulaire');
    // Le bouton déplie puis replie.
    m.bouton.click();
    assert.equal(b.contenu.hidden, false);
    assert.equal(m.bouton.getAttribute('aria-expanded'), 'true');
    m.bouton.click();
    assert.equal(b.contenu.hidden, true);
});

test('sans filtre actif : pas de compteur, pas d’étiquette, pas d’« Effacer »', () => {
    const doc = creerDocument();
    bloc(doc, { echues: false, du: '' });
    const [m] = F.installer(doc, environnement(true));
    assert.equal(m.compteur.hidden, true);
    assert.equal(m.liste.hidden, true);
    assert.equal(m.effacer.hidden, true);
    assert.equal(m.bouton.getAttribute('aria-label'), 'Filtres');
});

test('sur ordinateur rien ne change : le contenu reste affiché ; un passage au téléphone puis retour le remet', () => {
    const doc = creerDocument();
    const b = bloc(doc);
    const env = environnement(false);
    const [m] = F.installer(doc, env);
    assert.equal(b.contenu.hidden, false);
    env.basculer(true);
    assert.equal(b.contenu.hidden, true);
    env.basculer(false);
    assert.equal(b.contenu.hidden, false);
    assert.ok(m.barre, 'la barre existe mais le CSS ne l’affiche que sur téléphone');
});

test('un contenu déjà caché par une autre page (journal) n’est pas montré par erreur sur ordinateur', () => {
    const doc = creerDocument();
    const b = bloc(doc);
    b.contenu.hidden = true;
    F.installer(doc, environnement(false));
    assert.equal(b.contenu.hidden, true);
});

test('le compteur suit les changements de champs (filtres appliqués sans rechargement)', () => {
    const doc = creerDocument();
    const b = bloc(doc, { echues: false, du: '' });
    const [m] = F.installer(doc, environnement(true));
    assert.equal(m.compteur.hidden, true);
    b.select.value = '7';
    b.select.dispatch('change');
    assert.equal(m.compteur.textContent, '1');
    assert.deepEqual(m.liste.enfants.map(li => li.textContent), ['Saisi par : Awa Gérante']);
});

test('« Effacer » sans lien clique le bouton de réinitialisation existant', () => {
    const doc = creerDocument();
    const champ = el(doc, 'input', { id: 'q', type: 'search' }); champ.value = 'bissap';
    const reset = el(doc, 'button', { type: 'button', 'data-filtres-effacer': '', texte: 'Réinitialiser' });
    let clics = 0;
    reset.addEventListener('click', () => { clics++; champ.value = ''; });
    const contenu = el(doc, 'div', { 'data-filtres-contenu': '' }, el(doc, 'label', { for: 'q', texte: 'Rechercher' }), champ, reset);
    doc.body.append(el(doc, 'div', { 'data-filtres': '' }, contenu));
    const [m] = F.installer(doc, environnement(true));
    assert.equal(m.compteur.textContent, '1');
    assert.equal(m.effacer.getAttribute('href'), null);
    m.effacer.click();
    assert.equal(clics, 1);
    assert.equal(m.compteur.hidden, true);
});

test('installer deux fois ne monte rien de plus', () => {
    const doc = creerDocument();
    bloc(doc);
    assert.equal(F.installer(doc, environnement(true)).length, 1);
    assert.equal(F.installer(doc, environnement(true)).length, 0);
});
