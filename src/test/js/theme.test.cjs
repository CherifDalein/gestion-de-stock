const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const Theme = require(path.resolve(__dirname, '../../main/resources/static/js/theme.js'));

/** Faux élément : attributs, et quelques méthodes du DOM utilisées par le module. */
function element(attributs = {}) {
    const attrs = new Map(Object.entries(attributs));
    return {
        attrs, checked: false, value: attributs.value,
        getAttribute: n => (attrs.has(n) ? attrs.get(n) : null),
        setAttribute(n, v) { attrs.set(n, String(v)); },
        removeAttribute(n) { attrs.delete(n); },
        hasAttribute: n => attrs.has(n),
    };
}

/** Faux document + fenêtre. options : stockage (objet, ou 'interdit'), sombre (réglage du système). */
function monde({ stockage = {}, sombre = false, cles = [] } = {}) {
    const html = element();
    const metas = [element({ name: 'theme-color', content: '#0B2540' }), element({ name: 'theme-color', content: '#16212c', media: '(prefers-color-scheme: dark)' })];
    const radios = ['auto', 'light', 'dark'].map(v => Object.assign(element({ 'data-theme-choix': '', value: v }), { value: v }));
    const bascule = element({ 'data-theme-bascule': '' });
    const ecouteursDoc = {}, ecouteursMedia = [], ecouteursFenetre = {};
    const doc = {
        documentElement: html, readyState: 'complete',
        querySelectorAll(sel) {
            if (sel === 'meta[name="theme-color"]') return metas;
            if (sel === '[data-theme-choix]') return radios;
            if (sel === '[data-theme-bascule]') return [bascule];
            return [];
        },
        addEventListener(type, f) { (ecouteursDoc[type] = ecouteursDoc[type] || []).push(f); },
    };
    let systemeSombre = sombre;
    const donnees = stockage === 'interdit' ? null : { ...stockage };
    const fenetre = {
        matchMedia: () => ({ get matches() { return systemeSombre; }, addEventListener: (t, f) => ecouteursMedia.push(f) }),
        addEventListener: (t, f) => { (ecouteursFenetre[t] = ecouteursFenetre[t] || []).push(f); },
        get localStorage() {
            if (donnees === null) throw new Error('SecurityError : stockage interdit');
            return {
                getItem: k => (k in donnees ? donnees[k] : null),
                setItem: (k, v) => { donnees[k] = String(v); },
                removeItem: k => { delete donnees[k]; },
            };
        },
    };
    return { fenetre, doc, html, metas, radios, bascule, donnees,
        changerSysteme(v) { systemeSombre = v; ecouteursMedia.forEach(f => f({ matches: v })); },
        emettre(type, evt) { (ecouteursDoc[type] || []).forEach(f => f(evt)); },
        emettreFenetre(type, evt) { (ecouteursFenetre[type] || []).forEach(f => f(evt)); } };
}

test('la clé de stockage est rfc-theme et les modes sont auto, light, dark', () => {
    assert.equal(Theme.CLE, 'rfc-theme');
    assert.deepEqual(Theme.MODES, ['auto', 'light', 'dark']);
});

test('lire : valeur enregistrée, valeur inconnue ou absente', () => {
    assert.equal(Theme.lire(monde({ stockage: { 'rfc-theme': 'dark' } }).fenetre), 'dark');
    assert.equal(Theme.lire(monde({ stockage: { 'rfc-theme': 'light' } }).fenetre), 'light');
    assert.equal(Theme.lire(monde({ stockage: { 'rfc-theme': 'violet' } }).fenetre), 'auto');
    assert.equal(Theme.lire(monde().fenetre), 'auto');
});

test('ecrire : mémorise clair et sombre, efface la clé pour auto', () => {
    const m = monde();
    assert.equal(Theme.ecrire(m.fenetre, 'dark'), true);
    assert.equal(m.donnees['rfc-theme'], 'dark');
    assert.equal(Theme.ecrire(m.fenetre, 'light'), true);
    assert.equal(m.donnees['rfc-theme'], 'light');
    assert.equal(Theme.ecrire(m.fenetre, 'auto'), true);
    assert.equal('rfc-theme' in m.donnees, false);
    Theme.ecrire(m.fenetre, 'nimporte-quoi');
    assert.equal('rfc-theme' in m.donnees, false, 'une valeur inconnue vaut auto');
});

test('stockage interdit (navigation privée) : lecture et écriture sans erreur, mode automatique', () => {
    const m = monde({ stockage: 'interdit', sombre: true });
    assert.doesNotThrow(() => Theme.lire(m.fenetre));
    assert.equal(Theme.lire(m.fenetre), 'auto');
    assert.equal(Theme.ecrire(m.fenetre, 'dark'), false);
    assert.doesNotThrow(() => Theme.demarrer(m.fenetre, m.doc));
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark', 'automatique : suit le système');
    assert.doesNotThrow(() => Theme.choisir(m.fenetre, m.doc, 'light'));
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light', 'le choix s\'applique pour la page même sans mémoire');
});

test('fenêtre sans localStorage du tout', () => {
    assert.equal(Theme.lire({}), 'auto');
    assert.equal(Theme.ecrire({}, 'dark'), false);
    assert.equal(Theme.systemeSombre({}), false);
});

test('resoudre : le mode manuel l\'emporte, auto suit le système', () => {
    assert.equal(Theme.resoudre('auto', true), 'dark');
    assert.equal(Theme.resoudre('auto', false), 'light');
    assert.equal(Theme.resoudre('light', true), 'light');
    assert.equal(Theme.resoudre('dark', false), 'dark');
    assert.equal(Theme.resoudre('?', true), 'dark');
});

test('appliquer pose data-bs-theme, data-theme-mode, theme-color et coche la bonne radio', () => {
    const m = monde({ sombre: false });
    assert.equal(Theme.appliquer(m.fenetre, m.doc, 'dark'), 'dark');
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark');
    assert.equal(m.html.getAttribute('data-theme-mode'), 'dark');
    for (const meta of m.metas) {
        assert.equal(meta.getAttribute('content'), Theme.COULEURS.dark);
        assert.equal(meta.hasAttribute('media'), false, 'le choix manuel remplace le média du balisage');
    }
    assert.deepEqual(m.radios.map(r => r.checked), [false, false, true]);
    assert.equal(m.bascule.getAttribute('aria-pressed'), 'true');
    assert.equal(m.bascule.getAttribute('aria-label'), 'Passer au thème clair');
    Theme.appliquer(m.fenetre, m.doc, 'light');
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
    assert.equal(m.metas[0].getAttribute('content'), Theme.COULEURS.light);
    assert.equal(m.bascule.getAttribute('aria-label'), 'Passer au thème sombre');
    assert.deepEqual(m.radios.map(r => r.checked), [false, true, false]);
});

test('démarrage : préférence enregistrée prioritaire sur le réglage du système', () => {
    const m = monde({ stockage: { 'rfc-theme': 'light' }, sombre: true });
    Theme.demarrer(m.fenetre, m.doc);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
    const n = monde({ stockage: { 'rfc-theme': 'dark' }, sombre: false });
    Theme.demarrer(n.fenetre, n.doc);
    assert.equal(n.html.getAttribute('data-bs-theme'), 'dark');
});

test('démarrage en automatique : suit prefers-color-scheme, y compris quand il change', () => {
    const m = monde({ sombre: false });
    Theme.demarrer(m.fenetre, m.doc);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
    assert.equal(m.html.getAttribute('data-theme-mode'), 'auto');
    m.changerSysteme(true);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark');
    m.changerSysteme(false);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
});

test('un choix manuel ignore ensuite les changements du système', () => {
    const m = monde({ stockage: { 'rfc-theme': 'light' }, sombre: false });
    Theme.demarrer(m.fenetre, m.doc);
    m.changerSysteme(true);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
});

test('une radio cochée applique et mémorise ; auto efface la clé', () => {
    const m = monde({ sombre: true });
    Theme.demarrer(m.fenetre, m.doc);
    const sombre = m.radios[2]; sombre.checked = true;
    m.emettre('change', { target: sombre });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark');
    assert.equal(m.donnees['rfc-theme'], 'dark');
    const clair = m.radios[1]; clair.checked = true;
    m.emettre('change', { target: clair });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
    assert.equal(m.donnees['rfc-theme'], 'light');
    const auto = m.radios[0]; auto.checked = true;
    m.emettre('change', { target: auto });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark', 'auto : retour au système (sombre ici)');
    assert.equal('rfc-theme' in m.donnees, false);
});

test('la bascule lune/soleil passe de clair à sombre et inversement, et mémorise', () => {
    const m = monde({ sombre: false });
    Theme.demarrer(m.fenetre, m.doc);
    let empeche = false;
    const evt = { target: { closest: sel => (sel === '[data-theme-bascule]' ? m.bascule : null) }, preventDefault() { empeche = true; } };
    m.emettre('click', evt);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark');
    assert.equal(m.donnees['rfc-theme'], 'dark');
    assert.equal(empeche, true);
    m.emettre('click', evt);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
    assert.equal(m.donnees['rfc-theme'], 'light');
});

test('un clic ailleurs que sur la bascule ne change rien', () => {
    const m = monde();
    Theme.demarrer(m.fenetre, m.doc);
    m.emettre('click', { target: { closest: () => null }, preventDefault() { throw new Error('ne doit pas être appelé'); } });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
});

test('un autre onglet change la préférence : celui-ci suit', () => {
    const m = monde({ stockage: {} });
    Theme.demarrer(m.fenetre, m.doc);
    m.donnees['rfc-theme'] = 'dark';
    m.emettreFenetre('storage', { key: 'rfc-theme' });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark');
    m.donnees['rfc-theme'] = 'light';
    m.emettreFenetre('storage', { key: 'autre-cle' });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark', 'une autre clé est ignorée');
});

test('document en cours de chargement : sélecteurs reliés à DOMContentLoaded, thème posé tout de suite', () => {
    const m = monde({ stockage: { 'rfc-theme': 'dark' } });
    m.doc.readyState = 'loading';
    Theme.demarrer(m.fenetre, m.doc);
    assert.equal(m.html.getAttribute('data-bs-theme'), 'dark', 'pas de flash : posé avant le DOM');
    assert.deepEqual(m.radios.map(r => r.checked), [false, false, true], 'les radios existantes sont déjà synchronisées');
    m.emettre('DOMContentLoaded', {});
    const clair = m.radios[1]; clair.checked = true;
    m.emettre('change', { target: clair });
    assert.equal(m.html.getAttribute('data-bs-theme'), 'light');
});

test('theme.js ne touche ni au réseau ni aux cookies : aucune référence', () => {
    const source = require('node:fs').readFileSync(path.resolve(__dirname, '../../main/resources/static/js/theme.js'), 'utf8');
    assert.doesNotMatch(source, /fetch\(|XMLHttpRequest|document\.cookie|sendBeacon/);
});
