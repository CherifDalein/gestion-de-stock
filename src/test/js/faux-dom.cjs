/*
 * Mini DOM pour tester les modules d'interface sous node, sans jsdom : juste ce que menu-plus.js et confirmation.js utilisent.
 * Sélecteurs gérés : listes séparées par des virgules de « balise.classe[attribut][attribut=valeur] ».
 * Ce fichier ne contient aucun test (node --test le charge sans rien exécuter).
 */
'use strict';

function analyser(selecteur) {
    return selecteur.split(',').map(s => s.trim()).filter(Boolean).map(composant => {
        const m = /^([a-zA-Z0-9-]*)((?:\.[\w-]+|\[[^\]]+\])*)$/.exec(composant);
        if (!m) throw new Error('Sélecteur non géré : ' + composant);
        const contraintes = [];
        for (const c of m[2].match(/\.[\w-]+|\[[^\]]+\]/g) || []) {
            if (c[0] === '.') contraintes.push({ classe: c.slice(1) });
            else {
                const [nom, valeur] = c.slice(1, -1).split('=');
                contraintes.push({ attribut: nom, valeur: valeur === undefined ? undefined : valeur.replace(/^["']|["']$/g, '') });
            }
        }
        return { balise: m[1].toLowerCase(), contraintes };
    });
}

class Element {
    constructor(doc, balise) {
        this.ownerDocument = doc; this.tagName = balise.toUpperCase(); this.parentNode = null; this.enfants = [];
        this.attributs = new Map(); this.ecouteurs = {}; this.style = {}; this.className = ''; this.id = '';
        this.textContent = ''; this.innerHTML = ''; this.disabled = false; this._hidden = false;
        this.visible = true; this.rect = { top: 100, bottom: 144, left: 500, right: 600 }; this.taille = { largeur: 220, hauteur: 160 };
        this.focusJournal = [];
    }
    get hidden() { return this._hidden; }
    set hidden(v) { this._hidden = !!v; if (v) this.attributs.set('hidden', ''); else this.attributs.delete('hidden'); }
    get offsetWidth() { return this.taille.largeur; }
    get offsetHeight() { return this.taille.hauteur; }
    getBoundingClientRect() { return this.rect; }
    getClientRects() { return this.visible && !this.estCache() ? [this.rect] : []; }
    estCache() { for (let n = this; n; n = n.parentNode) if (n._hidden) return true; return false; }
    get children() { return this.enfants; }
    setAttribute(nom, valeur) { this.attributs.set(nom, String(valeur)); if (nom === 'id') this.id = String(valeur); if (nom === 'hidden') this._hidden = true; }
    getAttribute(nom) {
        if (nom === 'class') return this.className || null;
        if (nom === 'id') return this.id || null;
        return this.attributs.has(nom) ? this.attributs.get(nom) : null;
    }
    hasAttribute(nom) { return nom === 'hidden' ? this._hidden : this.attributs.has(nom) || (nom === 'id' && !!this.id); }
    removeAttribute(nom) { this.attributs.delete(nom); if (nom === 'hidden') this._hidden = false; }
    append(...noeuds) { noeuds.forEach(n => { if (n.parentNode) n.parentNode.retirer(n); n.parentNode = this; this.enfants.push(n); }); }
    retirer(n) { this.enfants = this.enfants.filter(e => e !== n); n.parentNode = null; }
    replaceWith(autre) {
        const parent = this.parentNode; const i = parent.enfants.indexOf(this);
        if (autre.parentNode) autre.parentNode.retirer(autre);
        parent.enfants[i] = autre; autre.parentNode = parent; this.parentNode = null;
    }
    contains(n) { for (let c = n; c; c = c.parentNode) if (c === this) return true; return false; }
    correspond(sel) {
        const attribut = (nom) => nom === 'class' ? this.className : this.getAttribute(nom);
        return analyser(sel).some(({ balise, contraintes }) => (!balise || this.tagName.toLowerCase() === balise) && contraintes.every(c => {
            if (c.classe) return (' ' + this.className + ' ').includes(' ' + c.classe + ' ');
            const v = this.hasAttribute(c.attribut) ? (attribut(c.attribut) ?? '') : null;
            return c.valeur === undefined ? v !== null : v === c.valeur;
        }));
    }
    closest(sel) { for (let n = this; n && n.correspond; n = n.parentNode) if (n.correspond(sel)) return n; return null; }
    querySelectorAll(sel) {
        const r = [];
        const parcourir = n => n.enfants.forEach(e => { if (e.correspond(sel)) r.push(e); parcourir(e); });
        parcourir(this);
        return r;
    }
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
    addEventListener(type, f) { (this.ecouteurs[type] = this.ecouteurs[type] || []).push(f); }
    /** Déclenche l'évènement sur l'élément puis le remonte jusqu'au document (comme le DOM). */
    dispatch(type, proprietes = {}) {
        const e = { type, target: this, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() {}, ...proprietes };
        for (let n = this; n; n = n.parentNode || (n.ownerDocument !== n ? n.ownerDocument : null)) {
            (n.ecouteurs && n.ecouteurs[type] || []).forEach(f => f(e));
            if (n === this.ownerDocument) break;
        }
        return e;
    }
    focus() { this.ownerDocument.activeElement = this; this.ownerDocument.journalFocus.push(this); }
    click() { return this.dispatch('click'); }
}

class Dialogue extends Element {
    constructor(doc) { super(doc, 'dialog'); this.open = false; }
    showModal() { this.open = true; }
    close() { if (!this.open) return; this.open = false; this.dispatch('close'); }
}

class Formulaire extends Element {
    constructor(doc) { super(doc, 'form'); this.envois = []; }
    requestSubmit(soumetteur) {
        const e = this.dispatch('submit', { submitter: soumetteur || null });
        if (!e.defaultPrevented) this.envois.push({ soumetteur: soumetteur || null });
    }
    submit() { this.envois.push({ direct: true }); }
}

function creerDocument({ largeur = 1280, hauteur = 900 } = {}) {
    const doc = new Element(null, '#document');
    doc.ownerDocument = doc;
    doc.createElement = balise => balise === 'dialog' ? new Dialogue(doc) : balise === 'form' ? new Formulaire(doc) : new Element(doc, balise);
    doc.body = doc.createElement('body'); doc.append(doc.body);
    doc.activeElement = doc.body; doc.journalFocus = []; doc.readyState = 'complete';
    const ecouteursFenetre = {};
    doc.defaultView = { innerWidth: largeur, innerHeight: hauteur, ecouteurs: ecouteursFenetre,
        addEventListener(type, f) { (ecouteursFenetre[type] = ecouteursFenetre[type] || []).push(f); } };
    doc.defaultView.dispatch = (type) => (ecouteursFenetre[type] || []).forEach(f => f({ type }));
    return doc;
}

/** Fabrique un élément avec attributs, classes et enfants. */
function el(doc, balise, attributs = {}, ...enfants) {
    const e = doc.createElement(balise);
    for (const [k, v] of Object.entries(attributs)) {
        if (k === 'class') e.className = v; else if (k === 'texte') e.textContent = v; else if (k === 'html') e.innerHTML = v; else e.setAttribute(k, v);
    }
    e.append(...enfants);
    return e;
}

module.exports = { creerDocument, el, Element };
