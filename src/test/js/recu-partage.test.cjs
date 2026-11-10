const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const P = require(path.resolve(__dirname, '../../main/resources/static/js/recu-partage.js'));

/** Faux élément : juste ce que le script utilise. */
function element(dataset = {}) {
    const attributs = {};
    const ecouteurs = {};
    const el = {
        dataset, hidden: dataset.cache !== 'non', disabled: false, textContent: '', className: '', attributs, suivant: null,
        setAttribute(nom, valeur) { attributs[nom] = valeur; },
        removeAttribute(nom) { delete attributs[nom]; },
        addEventListener(nom, f) { ecouteurs[nom] = f; },
        after(autre) { el.suivant = autre; },
        querySelector(sel) { return sel === '[data-recu-libelle]' ? el.libelle : null; },
        async cliquer() { return ecouteurs.click(); }
    };
    el.libelle = { textContent: 'Envoyer' };
    return el;
}

function faussePage(boutons) {
    return { querySelectorAll: () => boutons, createElement: () => element({ cache: 'non' }) };
}

class FauxFile {
    constructor(parties, nom, options) { this.parties = parties; this.name = nom; this.type = options.type; this.size = parties.reduce((t, p) => t + (p.size ?? p.length ?? 0), 0); }
}

function fauxBlob(taille = 120) { return { size: taille }; }

function reponsePdf(options = {}) {
    const entetes = { 'Content-Type': 'application/pdf', 'Content-Disposition': 'inline; filename="recu-vente_FV-000012.pdf"', ...(options.entetes || {}) };
    return {
        ok: options.ok ?? true, status: options.status ?? 200,
        headers: { get: nom => entetes[nom] ?? null },
        blob: async () => fauxBlob(options.taille ?? 120)
    };
}

/** Environnement simulé : journal des appels à fetch et à share. */
function environnement({ canShare = () => true, share, fetch } = {}) {
    const journal = { fetch: [], share: [] };
    const env = {
        File: FauxFile,
        navigator: {
            canShare,
            share: async donnees => { journal.share.push(donnees); if (share) return share(donnees, journal.share.length); }
        },
        fetch: async (url, options) => { journal.fetch.push({ url, options }); return fetch ? fetch(url) : reponsePdf(); }
    };
    return { env, journal };
}

function bouton() {
    return element({ recuUrl: '/ventes/recu/12', recuTitre: 'Reçu FV-000012', recuBoutique: 'Boutique Étoile' });
}

function erreur(nom) { const e = new Error(nom); e.name = nom; return e; }

test('le bouton reste caché sans navigator.canShare', () => {
    const b = bouton();
    const { env } = environnement();
    delete env.navigator.canShare;
    assert.equal(P.installer(faussePage([b]), env).length, 0);
    assert.equal(b.hidden, true);
});

test('le bouton reste caché sans navigator.share ni navigateur', () => {
    const b = bouton();
    const { env } = environnement();
    delete env.navigator.share;
    P.installer(faussePage([b]), env);
    assert.equal(b.hidden, true);
    P.installer(faussePage([b]), { File: FauxFile });
    assert.equal(b.hidden, true);
});

test('le bouton reste caché si canShare refuse un fichier PDF, ou lève une erreur', () => {
    const refus = bouton(), erreurCanShare = bouton();
    P.installer(faussePage([refus]), environnement({ canShare: () => false }).env);
    P.installer(faussePage([erreurCanShare]), environnement({ canShare: () => { throw new TypeError('x'); } }).env);
    assert.equal(refus.hidden, true);
    assert.equal(erreurCanShare.hidden, true);
});

test('le bouton est montré quand canShare accepte un File PDF de test', () => {
    const b = bouton();
    const vus = [];
    const { env } = environnement({ canShare: donnees => { vus.push(donnees.files[0]); return true; } });
    P.installer(faussePage([b]), env);
    assert.equal(b.hidden, false);
    assert.equal(vus[0].type, 'application/pdf');
    assert.match(vus[0].name, /\.pdf$/);
});

test('le clic télécharge le reçu puis le partage avec le bon nom, le bon type et le texte court', async () => {
    const b = bouton();
    const { env, journal } = environnement();
    P.installer(faussePage([b]), env);
    const etatsPendant = [];
    const partageOrigine = env.navigator.share;
    env.navigator.share = async d => { etatsPendant.push([b.disabled, b.attributs['aria-busy']]); return partageOrigine(d); };
    await b.cliquer();
    assert.equal(journal.fetch.length, 1);
    assert.equal(journal.fetch[0].url, '/ventes/recu/12');
    assert.equal(journal.fetch[0].options.credentials, 'same-origin');
    assert.equal(journal.share.length, 1);
    const envoi = journal.share[0];
    assert.equal(envoi.files.length, 1);
    assert.equal(envoi.files[0].name, 'recu-vente_FV-000012.pdf');
    assert.equal(envoi.files[0].type, 'application/pdf');
    assert.ok(envoi.files[0].size > 0);
    assert.equal(envoi.title, 'Reçu FV-000012');
    assert.equal(envoi.text, 'Reçu FV-000012 — Boutique Étoile');
    // Pendant le partage : bouton désactivé et occupé ; ensuite : état initial.
    assert.deepEqual(etatsPendant[0], [true, 'true']);
    assert.equal(b.disabled, false);
    assert.equal(b.attributs['aria-busy'], undefined);
    assert.equal(b.libelle.textContent, 'Envoyer');
});

test('pendant le téléchargement : bouton désactivé, aria-busy et libellé « Préparation… »', async () => {
    const b = bouton();
    let terminer;
    const { env } = environnement({ fetch: () => new Promise(r => { terminer = () => r(reponsePdf()); }) });
    P.installer(faussePage([b]), env);
    const clic = b.cliquer();
    assert.equal(b.disabled, true);
    assert.equal(b.attributs['aria-busy'], 'true');
    assert.equal(b.libelle.textContent, 'Préparation…');
    terminer();
    await clic;
    assert.equal(b.libelle.textContent, 'Envoyer');
});

test('un double clic ne lance qu\'un seul téléchargement', async () => {
    const b = bouton();
    let terminer;
    const { env, journal } = environnement({ fetch: () => new Promise(r => { terminer = () => r(reponsePdf()); }) });
    P.installer(faussePage([b]), env);
    const premier = b.cliquer();
    const second = b.cliquer();
    terminer();
    await Promise.all([premier, second]);
    assert.equal(journal.fetch.length, 1);
    assert.equal(journal.share.length, 1);
});

test('NotAllowedError après le téléchargement : « Partager maintenant », puis partage sans nouveau fetch', async () => {
    const b = bouton();
    const { env, journal } = environnement({ share: (d, n) => { if (n === 1) throw erreur('NotAllowedError'); } });
    const [{ message }] = P.installer(faussePage([b]), env);
    await b.cliquer();
    assert.equal(b.libelle.textContent, 'Partager maintenant');
    assert.equal(b.disabled, false);
    assert.equal(message.textContent, '');
    assert.equal(journal.fetch.length, 1);
    const premierFichier = journal.share[0].files[0];
    await b.cliquer();
    assert.equal(journal.fetch.length, 1, 'aucun nouveau téléchargement');
    assert.equal(journal.share.length, 2);
    assert.equal(journal.share[1].files[0], premierFichier);
    assert.equal(b.libelle.textContent, 'Envoyer');
});

test('au second clic, le partage se déclenche avant tout await (geste conservé)', async () => {
    const b = bouton();
    const { env, journal } = environnement({ share: (d, n) => { if (n === 1) throw erreur('NotAllowedError'); } });
    P.installer(faussePage([b]), env);
    await b.cliquer();
    const clic = b.cliquer();
    assert.equal(journal.share.length, 2, 'share() appelé de façon synchrone');
    await clic;
});

test('NotAllowedError au second clic : message, retour à l\'état initial', async () => {
    const b = bouton();
    const { env } = environnement({ share: () => { throw erreur('NotAllowedError'); } });
    const [{ message }] = P.installer(faussePage([b]), env);
    await b.cliquer();
    await b.cliquer();
    assert.equal(b.libelle.textContent, 'Envoyer');
    assert.equal(message.textContent, P.MESSAGES.partage);
});

test('AbortError (annulation) : silencieux, bouton remis à l\'état initial', async () => {
    const b = bouton();
    const { env } = environnement({ share: () => { throw erreur('AbortError'); } });
    const [{ message }] = P.installer(faussePage([b]), env);
    await b.cliquer();
    assert.equal(message.textContent, '');
    assert.equal(message.hidden, true);
    assert.equal(b.libelle.textContent, 'Envoyer');
    assert.equal(b.disabled, false);
});

test('autre erreur de partage : message lisible, pas de « Partager maintenant »', async () => {
    const b = bouton();
    const { env } = environnement({ share: () => { throw erreur('DataError'); } });
    const [{ message }] = P.installer(faussePage([b]), env);
    await b.cliquer();
    assert.equal(message.textContent, P.MESSAGES.partage);
    assert.equal(message.hidden, false);
    assert.equal(message.attributs['aria-live'], 'polite');
    assert.equal(b.libelle.textContent, 'Envoyer');
});

test('réponse HTML (page de connexion) : message de session, aucun partage', async () => {
    const b = bouton();
    const { env, journal } = environnement({ fetch: () => reponsePdf({ entetes: { 'Content-Type': 'text/html;charset=UTF-8' } }) });
    const [{ message }] = P.installer(faussePage([b]), env);
    await b.cliquer();
    assert.equal(journal.share.length, 0);
    assert.equal(message.textContent, P.MESSAGES.session);
    assert.equal(b.libelle.textContent, 'Envoyer');
    assert.equal(b.disabled, false);
});

test('404, 403 et 500 : message adapté, aucun partage, nouvelle tentative possible', async () => {
    for (const [status, attendu] of [[404, P.MESSAGES.introuvable], [403, P.MESSAGES.acces], [500, P.MESSAGES.preparation]]) {
        const b = bouton();
        const { env, journal } = environnement({ fetch: () => reponsePdf({ ok: false, status }) });
        const [{ message }] = P.installer(faussePage([b]), env);
        await b.cliquer();
        assert.equal(journal.share.length, 0);
        assert.equal(message.textContent, attendu);
        await b.cliquer();
        assert.equal(journal.fetch.length, 2);
    }
});

test('échec réseau ou PDF vide : message, aucun partage', async () => {
    const reseau = bouton(), vide = bouton();
    const a = environnement({ fetch: () => { throw new TypeError('Failed to fetch'); } });
    const [{ message: m1 }] = P.installer(faussePage([reseau]), a.env);
    await reseau.cliquer();
    assert.equal(m1.textContent, P.MESSAGES.reseau);
    const c = environnement({ fetch: () => reponsePdf({ taille: 0 }) });
    const [{ message: m2 }] = P.installer(faussePage([vide]), c.env);
    await vide.cliquer();
    assert.equal(m2.textContent, P.MESSAGES.preparation);
    assert.equal(a.journal.share.length + c.journal.share.length, 0);
});

test('une adresse qui n\'est pas relative au site est refusée sans requête', async () => {
    const b = element({ recuUrl: 'https://exemple.test/recu' });
    const { env, journal } = environnement();
    P.installer(faussePage([b]), env);
    await b.cliquer();
    assert.equal(journal.fetch.length, 0);
});

test('le nom de fichier vient du Content-Disposition, nettoyé', () => {
    assert.equal(P.nomDeFichier('inline; filename="recu-vente_FV-000012.pdf"'), 'recu-vente_FV-000012.pdf');
    assert.equal(P.nomDeFichier('inline; filename=recu-paiement-achat_FA-7.pdf'), 'recu-paiement-achat_FA-7.pdf');
    assert.equal(P.nomDeFichier('inline; filename*=UTF-8\'\'re%C3%A7u%20FV-1.pdf'), 'recu_FV-1.pdf');
    assert.equal(P.nomDeFichier('inline; filename="../../etc/passwd"'), 'etc_passwd.pdf');
    assert.equal(P.nomDeFichier('inline; filename="recu"'), 'recu.pdf');
    assert.equal(P.nomDeFichier(null), 'recu.pdf');
    assert.equal(P.nomDeFichier(''), 'recu.pdf');
});
