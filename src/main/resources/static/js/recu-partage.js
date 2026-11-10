/*
 * Bouton « Envoyer » d'un reçu : partage le PDF par le menu de partage natif de l'appareil (WhatsApp, e-mail, etc.) avec
 * l'API Web Share de niveau 2 (partage de fichier). Rien n'est envoyé à un tiers par l'application elle-même.
 *
 * - Les boutons sont rendus avec l'attribut « hidden » : ils ne sont montrés que si le navigateur sait partager un fichier PDF
 *   (navigator.canShare). Sinon (ou sans JavaScript) ils restent cachés et l'impression reste disponible.
 * - Au clic : téléchargement du reçu (même adresse que « Imprimer », même session), contrôle du type PDF, puis navigator.share().
 * - Safari refuse share() quand le téléchargement a fait perdre le geste de l'utilisateur (NotAllowedError) : le fichier est gardé en
 *   mémoire, le bouton devient « Partager maintenant » et le clic suivant partage aussitôt, sans nouveau téléchargement.
 * - Annulation (AbortError) : silencieuse. Autre échec : message lisible près du bouton (zone aria-live), jamais d'alert().
 * Tous les textes sont posés avec textContent : jamais insérés comme HTML.
 */
(function (global) {
    'use strict';

    const MESSAGES = {
        reseau: 'Connexion impossible. Vérifiez votre réseau puis réessayez.',
        session: 'Votre session a peut-être expiré. Reconnectez-vous puis réessayez.',
        acces: 'Vous n\'avez pas accès à ce reçu.',
        introuvable: 'Ce reçu est introuvable.',
        preparation: 'Le reçu n\'a pas pu être préparé. Réessayez ou imprimez-le.',
        appareil: 'Cet appareil ne peut pas partager ce reçu. Imprimez-le à la place.',
        partage: 'Le reçu n\'a pas pu être envoyé. Réessayez ou imprimez-le.'
    };

    /** Erreur dont le message est destiné à l'utilisateur. */
    function erreurLisible(message) {
        const e = new Error(message);
        e.lisible = true;
        return e;
    }

    /** Vrai si l'appareil sait partager un fichier PDF (un petit fichier de test est soumis à canShare). */
    function peutPartager(env) {
        const nav = env.navigator;
        if (!nav || typeof nav.canShare !== 'function' || typeof nav.share !== 'function' || typeof env.File !== 'function') return false;
        try {
            return nav.canShare({ files: [new env.File(['%PDF-1.4'], 'recu.pdf', { type: 'application/pdf' })] }) === true;
        } catch (e) {
            return false;
        }
    }

    /** Nom de fichier sûr tiré de l'en-tête Content-Disposition (ex. recu-vente_FV-000012.pdf), « recu.pdf » à défaut. */
    function nomDeFichier(contentDisposition) {
        let brut = '';
        const texte = String(contentDisposition || '');
        const etendu = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(texte);
        const simple = /filename\s*=\s*"?([^";]+)"?/.exec(texte);
        if (etendu) {
            try { brut = decodeURIComponent(etendu[1].trim()); } catch (e) { brut = ''; }
        }
        if (!brut && simple) brut = simple[1].trim();
        let propre = brut.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^A-Za-z0-9._-]/g, '_').replace(/_{2,}/g, '_').replace(/^[._-]+/, '');
        if (propre.length > 100) propre = propre.slice(0, 100);
        if (!propre) propre = 'recu';
        return /\.pdf$/i.test(propre) ? propre : propre.replace(/\.+$/, '') + '.pdf';
    }

    /** Télécharge le reçu et en fait un File PDF ; lève une erreur lisible sinon. */
    async function chargerRecu(url, env) {
        if (typeof url !== 'string' || !url.startsWith('/') || url.startsWith('//')) throw erreurLisible(MESSAGES.preparation);
        let reponse;
        try {
            reponse = await env.fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/pdf' } });
        } catch (e) {
            throw erreurLisible(MESSAGES.reseau);
        }
        if (!reponse.ok) {
            if (reponse.status === 401 || reponse.status === 403) throw erreurLisible(MESSAGES.acces);
            if (reponse.status === 404) throw erreurLisible(MESSAGES.introuvable);
            throw erreurLisible(MESSAGES.preparation);
        }
        const type = String(reponse.headers.get('Content-Type') || '').toLowerCase();
        // Une session expirée renvoie la page de connexion (200, text/html) : ce n'est pas un reçu.
        if (!type.startsWith('application/pdf')) throw erreurLisible(MESSAGES.session);
        const contenu = await reponse.blob();
        if (!contenu || contenu.size === 0) throw erreurLisible(MESSAGES.preparation);
        const nom = nomDeFichier(reponse.headers.get('Content-Disposition'));
        return new env.File([contenu], nom, { type: 'application/pdf' });
    }

    /** Branche un bouton. `ui` : { libelle, message } (éléments) ; renvoie le gestionnaire de clic (utile aux tests). */
    function brancher(bouton, ui, env) {
        const libelleInitial = ui.libelle.textContent;
        let fichier = null;
        let occupe = false;

        const afficherLibelle = texte => { ui.libelle.textContent = texte; };
        const afficherMessage = texte => { ui.message.textContent = texte; ui.message.hidden = !texte; };
        const attente = actif => {
            bouton.disabled = actif;
            if (actif) bouton.setAttribute('aria-busy', 'true'); else bouton.removeAttribute('aria-busy');
        };
        const repos = () => { fichier = null; attente(false); afficherLibelle(libelleInitial); };

        async function partager(depuisPreparation) {
            const titre = bouton.dataset.recuTitre || 'Reçu';
            const boutique = bouton.dataset.recuBoutique;
            const donnees = { files: [fichier], title: titre, text: boutique ? titre + ' — ' + boutique : titre };
            try {
                if (!env.navigator.canShare(donnees)) throw erreurLisible(MESSAGES.appareil);
                await env.navigator.share(donnees);
                repos();
            } catch (e) {
                const nom = e && e.name;
                if (nom === 'AbortError') {
                    repos();
                } else if (nom === 'NotAllowedError' && depuisPreparation) {
                    // Le geste de l'utilisateur a été perdu pendant le téléchargement : un second clic suffira.
                    attente(false);
                    afficherLibelle('Partager maintenant');
                } else {
                    repos();
                    afficherMessage(e && e.lisible ? e.message : MESSAGES.partage);
                }
            }
        }

        return async function clic() {
            if (occupe) return;
            occupe = true;
            try {
                afficherMessage('');
                if (fichier) {
                    // Partage immédiat : aucun await avant share(), pour garder l'activation transitoire.
                    attente(true);
                    await partager(false);
                    return;
                }
                attente(true);
                afficherLibelle('Préparation…');
                try {
                    fichier = await chargerRecu(bouton.dataset.recuUrl, env);
                } catch (e) {
                    repos();
                    afficherMessage(e && e.lisible ? e.message : MESSAGES.preparation);
                    return;
                }
                await partager(true);
            } finally {
                occupe = false;
            }
        };
    }

    /** Montre et branche les boutons de la page si l'appareil peut partager un PDF. */
    function installer(document, env) {
        if (!document || !peutPartager(env)) return [];
        const boutons = Array.from(document.querySelectorAll('[data-recu-partage]'));
        return boutons.map(bouton => {
            const libelle = bouton.querySelector('[data-recu-libelle]') || bouton;
            const message = document.createElement('span');
            message.className = 'recu-partage-message small text-danger';
            message.setAttribute('role', 'status');
            message.setAttribute('aria-live', 'polite');
            message.hidden = true;
            bouton.after(message);
            const clic = brancher(bouton, { libelle, message }, env);
            bouton.addEventListener('click', clic);
            bouton.hidden = false;
            return { bouton, message, clic };
        });
    }

    const api = { peutPartager, nomDeFichier, chargerRecu, brancher, installer, MESSAGES };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else {
        global.RecuPartage = api;
        installer(global.document, { navigator: global.navigator, fetch: typeof global.fetch === 'function' ? global.fetch.bind(global) : undefined, File: global.File });
    }
})(typeof window !== 'undefined' ? window : globalThis);
