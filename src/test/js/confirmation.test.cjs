const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { creerDocument, el } = require('./faux-dom.cjs');
const Confirmation = require(path.resolve(__dirname, '../../main/resources/static/js/confirmation.js'));

/** Un formulaire d'action destructrice, avec son bouton d'envoi. */
function formulaireSuppression(doc, attributs = {}) {
    const f = doc.createElement('form');
    f.setAttribute('method', 'post');
    f.setAttribute('data-confirmer', '');
    f.setAttribute('data-confirmer-titre', 'Supprimer ce client ?');
    f.setAttribute('data-confirmer-sujet', 'Aminata Camara');
    f.setAttribute('data-confirmer-texte', 'sera supprimé du répertoire.');
    f.setAttribute('data-confirmer-oui', 'Supprimer le client');
    for (const [k, v] of Object.entries(attributs)) f.setAttribute(k, v);
    const bouton = el(doc, 'button', { type: 'submit', texte: 'Supprimer' });
    f.append(bouton);
    doc.body.append(f);
    return { f, bouton };
}

function installer() {
    const doc = creerDocument();
    const env = { confirm: () => { throw new Error('confirm() natif ne doit pas servir'); } };
    Confirmation.installer(doc, env);
    return { doc, env };
}

const dialogue = doc => doc.querySelector('dialog');

test('l’envoi d’un formulaire à confirmer est suspendu et la fenêtre affiche titre, sujet, bouton rouge et « Garder »', () => {
    const { doc } = installer();
    const { f, bouton } = formulaireSuppression(doc);
    const e = f.dispatch('submit', { submitter: bouton });
    assert.equal(e.defaultPrevented, true);
    assert.equal(f.envois.length, 0);
    const d = dialogue(doc);
    assert.equal(d.open, true);
    assert.equal(d.querySelector('h2').textContent, 'Supprimer ce client ?');
    assert.equal(d.querySelector('strong').textContent, 'Aminata Camara');
    assert.match(d.querySelector('p').querySelector('span').textContent, /sera supprimé du répertoire\./);
    const valider = d.querySelector('[data-confirmer-valider]'), garder = d.querySelector('[data-confirmer-garder]');
    assert.equal(valider.textContent, 'Supprimer le client');
    assert.match(valider.className, /btn-danger/);
    assert.equal(garder.textContent, 'Garder');
    assert.equal(doc.activeElement, garder, 'le focus part sur le choix sans danger');
    assert.equal(d.getAttribute('aria-labelledby'), d.querySelector('h2').id);
});

test('« Garder » ferme sans rien envoyer et rend le focus au bouton d’origine', () => {
    const { doc } = installer();
    const { f, bouton } = formulaireSuppression(doc);
    f.dispatch('submit', { submitter: bouton });
    dialogue(doc).querySelector('[data-confirmer-garder]').click();
    assert.equal(dialogue(doc).open, false);
    assert.equal(f.envois.length, 0);
    assert.equal(doc.activeElement, bouton);
});

test('Échap (fermeture du dialogue) annule aussi et rend le focus', () => {
    const { doc } = installer();
    const { f, bouton } = formulaireSuppression(doc);
    f.dispatch('submit', { submitter: bouton });
    dialogue(doc).close();
    assert.equal(f.envois.length, 0);
    assert.equal(doc.activeElement, bouton);
});

test('un clic sur le fond de la fenêtre annule', () => {
    const { doc } = installer();
    const { f, bouton } = formulaireSuppression(doc);
    f.dispatch('submit', { submitter: bouton });
    dialogue(doc).click();
    assert.equal(dialogue(doc).open, false);
    assert.equal(f.envois.length, 0);
});

test('« Supprimer » envoie le bon formulaire, une seule fois, avec son bouton d’envoi', () => {
    const { doc } = installer();
    const premier = formulaireSuppression(doc, { 'data-confirmer-sujet': 'Premier' });
    const second = formulaireSuppression(doc, { 'data-confirmer-sujet': 'Second' });
    second.f.dispatch('submit', { submitter: second.bouton });
    assert.equal(dialogue(doc).querySelector('strong').textContent, 'Second');
    const valider = dialogue(doc).querySelector('[data-confirmer-valider]');
    valider.click();
    valider.click();                                  // double clic : ignoré
    assert.equal(second.f.envois.length, 1);
    assert.equal(second.f.envois[0].soumetteur, second.bouton);
    assert.equal(premier.f.envois.length, 0, 'l’autre formulaire n’est pas touché');
    assert.equal(dialogue(doc).open, false);
    assert.equal(second.f.hasAttribute('data-confirme'), false, 'l’indicateur interne est repris');
    // Un nouvel envoi redemande confirmation.
    const e = second.f.dispatch('submit', { submitter: second.bouton });
    assert.equal(e.defaultPrevented, true);
    assert.equal(dialogue(doc).open, true);
});

test('un formulaire sans data-confirmer part sans fenêtre', () => {
    const { doc } = installer();
    const f = doc.createElement('form');
    doc.body.append(f);
    const e = f.dispatch('submit', {});
    assert.equal(e.defaultPrevented, false);
    assert.equal(dialogue(doc), null);
});

test('quand le bouton d’origine est dans un menu « Plus » refermé, le focus revient au bouton du menu', () => {
    const { doc } = installer();
    const menu = el(doc, 'div', { 'data-plus-menu-pret': '' });
    const toggle = el(doc, 'button', { 'data-plus-toggle': '' });
    const { f, bouton } = formulaireSuppression(doc);
    doc.body.retirer(f);
    menu.append(toggle, el(doc, 'div', { 'data-plus-panneau': '' }, f));
    doc.body.append(menu);
    bouton.visible = false;                            // l'entrée est cachée avec le panneau
    f.dispatch('submit', { submitter: bouton });
    dialogue(doc).querySelector('[data-confirmer-garder]').click();
    assert.equal(doc.activeElement, toggle);
});

test('textes par défaut et texte sans sujet', () => {
    const { doc } = installer();
    const f = doc.createElement('form');
    f.setAttribute('data-confirmer', '');
    doc.body.append(f);
    f.dispatch('submit', {});
    const d = dialogue(doc);
    assert.equal(d.querySelector('h2').textContent, 'Confirmer cette action ?');
    assert.equal(d.querySelector('[data-confirmer-valider]').textContent, 'Confirmer');
    assert.equal(d.querySelector('[data-confirmer-garder]').textContent, 'Garder');
});

test('les textes ne sont jamais posés comme HTML', () => {
    const { doc } = installer();
    const { f, bouton } = formulaireSuppression(doc, { 'data-confirmer-sujet': '<img src=x onerror=alert(1)>' });
    f.dispatch('submit', { submitter: bouton });
    const sujet = dialogue(doc).querySelector('strong');
    assert.equal(sujet.textContent, '<img src=x onerror=alert(1)>');
    assert.equal(sujet.innerHTML, '');
});

test('sans <dialog>, repli sur la confirmation native, et le serveur reste seul juge', () => {
    const doc = creerDocument();
    const vieux = doc.createElement.bind(doc);
    doc.createElement = balise => { const e = vieux(balise); if (balise === 'dialog') e.showModal = undefined; return e; };
    let message = null, reponse = false;
    Confirmation.installer(doc, { confirm: m => { message = m; return reponse; } });
    const { f, bouton } = formulaireSuppression(doc);
    f.dispatch('submit', { submitter: bouton });
    assert.match(message, /Supprimer ce client \?/);
    assert.equal(f.envois.length, 0);
    reponse = true;
    f.dispatch('submit', { submitter: bouton });
    assert.equal(f.envois.length, 1);
});
