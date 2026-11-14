const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { creerDocument, el } = require('./faux-dom.cjs');
const MenuPlus = require(path.resolve(__dirname, '../../main/resources/static/js/menu-plus.js'));

/** Une ligne de liste avec son menu « Plus » tel que le serveur le rend (details + summary + panneau). */
function ligne(doc, { entrees = 3 } = {}) {
    const lien = el(doc, 'a', { href: '/factures/client/1', texte: 'Factures' });
    const formulaire = doc.createElement('form');
    formulaire.setAttribute('method', 'post');
    const supprimer = el(doc, 'button', { type: 'submit', texte: 'Supprimer' });
    formulaire.append(supprimer);
    const autre = el(doc, 'a', { href: '/etats/releve/client/1', texte: 'Relevé' });
    const panneau = el(doc, 'div', { class: 'plus-menu-panel', 'data-plus-panneau': '' }, lien, autre, formulaire);
    const resume = el(doc, 'summary', { class: 'btn btn-sm plus-menu-bouton', 'aria-label': 'Plus d’actions pour Aminata', html: '<span aria-hidden="true">⋯</span><span>Plus</span>' });
    const details = el(doc, 'details', { class: 'plus-menu-details', 'data-plus-menu': '' }, resume, panneau);
    const cellule = el(doc, 'td', {}, details);
    doc.body.append(cellule);
    return { cellule, details, panneau, lien, autre, formulaire, supprimer };
}

function installer(options) {
    const doc = creerDocument(options);
    const l = ligne(doc);
    MenuPlus.installer(doc);
    const menu = l.cellule.querySelector('[data-plus-menu-pret]');
    const bouton = menu.querySelector('[data-plus-toggle]');
    return { doc, l, menu, bouton };
}

test('le details devient un vrai bouton de menu, sans copier le panneau', () => {
    const { l, menu, bouton, doc } = installer();
    assert.equal(l.cellule.querySelector('details'), null, 'le details est remplacé');
    assert.equal(bouton.tagName, 'BUTTON');
    assert.equal(bouton.getAttribute('type'), 'button');
    assert.equal(bouton.getAttribute('aria-haspopup'), 'menu');
    assert.equal(bouton.getAttribute('aria-expanded'), 'false');
    assert.equal(bouton.getAttribute('aria-label'), 'Plus d’actions pour Aminata');
    assert.equal(bouton.getAttribute('aria-controls'), l.panneau.id);
    assert.match(bouton.innerHTML, /Plus/);
    assert.equal(menu.querySelector('[data-plus-panneau]'), l.panneau, 'le même panneau (liens, formulaires, jetons) est déplacé');
    assert.equal(l.panneau.hidden, true);
    assert.equal(l.panneau.getAttribute('role'), 'menu');
    assert.equal(l.lien.getAttribute('role'), 'menuitem');
    assert.equal(l.supprimer.getAttribute('role'), 'menuitem');
    // Idempotent : une seconde initialisation ne refait rien.
    assert.equal(MenuPlus.initialiser(doc).length, 0);
});

test('le clic ouvre le menu, place le panneau en fixed dans la fenêtre, et un second clic le ferme', () => {
    const { menu, bouton, l } = installer();
    bouton.click();
    assert.equal(bouton.getAttribute('aria-expanded'), 'true');
    assert.equal(l.panneau.hidden, false);
    assert.equal(l.panneau.style.position, 'fixed');
    const gauche = parseInt(l.panneau.style.left, 10), haut = parseInt(l.panneau.style.top, 10);
    assert.ok(gauche >= 8 && gauche + 220 <= 1280 - 8, 'dans la largeur de la fenêtre');
    assert.equal(gauche, 600 - 220, 'aligné sur le bord droit du bouton');
    assert.equal(haut, 144 + 4, 'sous le bouton');
    bouton.click();
    assert.equal(bouton.getAttribute('aria-expanded'), 'false');
    assert.equal(l.panneau.hidden, true);
    assert.equal(MenuPlus.estOuvert(menu), false);
});

test('le panneau passe au-dessus du bouton quand il manque de place en dessous, et reste dans la fenêtre à droite', () => {
    const { doc, bouton, l } = installer({ largeur: 360, hauteur: 780 });
    bouton.rect = { top: 700, bottom: 744, left: 300, right: 350 };
    bouton.click();
    assert.equal(parseInt(l.panneau.style.top, 10), 700 - 160 - 4, 'au-dessus');
    assert.equal(parseInt(l.panneau.style.left, 10), 350 - 220);
    bouton.rect = { top: 50, bottom: 94, left: 4, right: 40 };
    bouton.click(); bouton.click();
    assert.equal(parseInt(l.panneau.style.left, 10), 8, 'jamais coupé à gauche');
    assert.equal(doc.activeElement !== null, true);
});

test('Échap ferme le menu et rend le focus au bouton', () => {
    const { doc, bouton, l } = installer();
    bouton.click();
    l.lien.focus();
    const e = l.lien.dispatch('keydown', { key: 'Escape' });
    assert.equal(e.defaultPrevented, true);
    assert.equal(l.panneau.hidden, true);
    assert.equal(bouton.getAttribute('aria-expanded'), 'false');
    assert.equal(doc.activeElement, bouton);
});

test('un clic en dehors ferme le menu et le focus revient au bouton s’il était resté dans le menu', () => {
    const { doc, bouton, l } = installer();
    const dehors = el(doc, 'p', { texte: 'ailleurs' });
    doc.body.append(dehors);
    bouton.click();
    l.lien.focus();
    doc.activeElement = doc.body;            // un clic sur une zone non focalisable fait perdre le focus
    dehors.click();
    assert.equal(l.panneau.hidden, true);
    assert.equal(doc.activeElement, bouton);
});

test('un clic sur un autre champ ferme le menu sans lui voler le focus', () => {
    const { doc, bouton, l } = installer();
    const champ = el(doc, 'input', { type: 'text' });
    doc.body.append(champ);
    bouton.click();
    champ.focus();                           // mousedown a déjà donné le focus au champ
    champ.click();
    assert.equal(l.panneau.hidden, true);
    assert.equal(doc.activeElement, champ);
});

test('un clic dans le panneau, sur une entrée, ferme le menu', () => {
    const { bouton, l, doc } = installer();
    bouton.click();
    l.supprimer.click();
    assert.equal(l.panneau.hidden, true);
    assert.equal(doc.activeElement, bouton);
});

test('au clavier : flèche bas ouvre et va à la première entrée, les flèches parcourent en boucle, Début et Fin sautent', () => {
    const { doc, bouton, l } = installer();
    bouton.focus();
    let e = bouton.dispatch('keydown', { key: 'ArrowDown' });
    assert.equal(e.defaultPrevented, true);
    assert.equal(l.panneau.hidden, false);
    assert.equal(doc.activeElement, l.lien);
    l.lien.dispatch('keydown', { key: 'ArrowDown' });
    assert.equal(doc.activeElement, l.autre);
    l.autre.dispatch('keydown', { key: 'ArrowDown' });
    assert.equal(doc.activeElement, l.supprimer);
    l.supprimer.dispatch('keydown', { key: 'ArrowDown' });
    assert.equal(doc.activeElement, l.lien, 'retour au début');
    l.lien.dispatch('keydown', { key: 'ArrowUp' });
    assert.equal(doc.activeElement, l.supprimer, 'retour à la fin en remontant');
    l.supprimer.dispatch('keydown', { key: 'Home' });
    assert.equal(doc.activeElement, l.lien);
    l.lien.dispatch('keydown', { key: 'End' });
    assert.equal(doc.activeElement, l.supprimer);
});

test('Tab hors du menu le ferme ; Tab d’une entrée à l’autre le laisse ouvert', () => {
    const { doc, bouton, l, menu } = installer();
    const suivant = el(doc, 'a', { href: '/x' });
    doc.body.append(suivant);
    bouton.click();
    l.lien.dispatch('focusout', { relatedTarget: l.autre });
    assert.equal(MenuPlus.estOuvert(menu), true);
    l.supprimer.dispatch('focusout', { relatedTarget: suivant });
    assert.equal(MenuPlus.estOuvert(menu), false);
    assert.equal(l.panneau.hidden, true);
});

test('un seul menu est ouvert à la fois', () => {
    const doc = creerDocument();
    const a = ligne(doc), b = ligne(doc);
    MenuPlus.installer(doc);
    const [menuA, menuB] = doc.querySelectorAll('[data-plus-menu-pret]');
    menuA.querySelector('[data-plus-toggle]').click();
    menuB.querySelector('[data-plus-toggle]').click();
    assert.equal(MenuPlus.estOuvert(menuA), false);
    assert.equal(MenuPlus.estOuvert(menuB), true);
    assert.equal(a.panneau.hidden, true);
    assert.equal(b.panneau.hidden, false);
});

test('le défilement referme un menu dont le bouton a quitté la fenêtre et repositionne les autres', () => {
    const { doc, bouton, menu, l } = installer();
    bouton.click();
    bouton.rect = { top: 300, bottom: 344, left: 500, right: 600 };
    doc.defaultView.dispatch('scroll');
    assert.equal(parseInt(l.panneau.style.top, 10), 348, 'suit le bouton');
    bouton.rect = { top: -80, bottom: -36, left: 500, right: 600 };
    doc.defaultView.dispatch('scroll');
    assert.equal(MenuPlus.estOuvert(menu), false);
});
