/*
 * Fenêtre de confirmation aux couleurs de l'application, pour les actions destructrices (supprimer, annuler…).
 * Elle remplace window.confirm() sur un <dialog> unique, créé à la première utilisation.
 *
 * Usage : sur le <form method="post"> de l'action, poser data-confirmer et les textes :
 *   <form method="post" th:action="…" data-confirmer
 *         data-confirmer-titre="Supprimer ce client ?"            titre de la fenêtre
 *         data-confirmer-sujet="Aminata Camara"                   ce qui sera supprimé ou annulé (en gras)
 *         data-confirmer-texte="sera supprimé du répertoire."     la suite de la phrase (optionnel)
 *         data-confirmer-oui="Supprimer"                          bouton rouge (« Confirmer » par défaut)
 *         data-confirmer-non="Garder">                            bouton neutre (« Garder » par défaut)
 *
 * - Annuler / Échap / clic sur le fond : rien n'est envoyé, le focus revient au bouton d'origine (ou au bouton « Plus »
 *   de sa ligne quand l'entrée du menu a disparu).
 * - Confirmer : le formulaire d'origine est envoyé (requestSubmit : validation du navigateur et bouton d'envoi conservés).
 *   Un second clic pendant l'envoi est ignoré.
 * - Sans JavaScript, le formulaire part directement : le serveur garde tous ses contrôles. Si le navigateur ne connaît pas
 *   <dialog>, la confirmation native window.confirm() sert de repli.
 * Tous les textes sont posés avec textContent : jamais insérés comme HTML.
 */
(function (racine, fabrique) {
    'use strict';
    const api = fabrique();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else {
        racine.StockConfirmation = api;
        if (racine.document) api.installer(racine.document, racine);
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    let sequence = 0;

    function lire(formulaire, nom, defaut) {
        const valeur = formulaire.getAttribute('data-confirmer-' + nom);
        return valeur && valeur.trim() ? valeur.trim() : defaut;
    }

    function visible(element) {
        if (!element) return false;
        if (typeof element.getClientRects === 'function') return element.getClientRects().length > 0;
        return true;
    }

    /** Élément qui reprend le focus à la fermeture : l'origine si elle est visible, sinon le bouton « Plus » de sa ligne. */
    function pointDeRetour(origine, formulaire) {
        if (visible(origine)) return origine;
        const menu = (origine && origine.closest ? origine.closest('[data-plus-menu-pret]') : null)
            || (formulaire.closest ? formulaire.closest('[data-plus-menu-pret]') : null);
        const bouton = menu ? menu.querySelector('[data-plus-toggle]') : null;
        return bouton || origine || null;
    }

    function construire(doc) {
        const id = 'confirmation-' + (++sequence);
        const dialogue = doc.createElement('dialog');
        dialogue.className = 'confirm-dialog';
        dialogue.setAttribute('aria-labelledby', id + '-titre');
        dialogue.setAttribute('aria-describedby', id + '-texte');
        const corps = doc.createElement('div');
        corps.className = 'confirm-dialog-corps';
        const icone = doc.createElement('span');
        icone.className = 'confirm-dialog-icone';
        icone.setAttribute('aria-hidden', 'true');
        icone.textContent = '!';
        const titre = doc.createElement('h2');
        titre.id = id + '-titre';
        titre.className = 'confirm-dialog-titre';
        const texte = doc.createElement('p');
        texte.id = id + '-texte';
        texte.className = 'confirm-dialog-texte';
        const sujet = doc.createElement('strong');
        sujet.className = 'confirm-dialog-sujet';
        const suite = doc.createElement('span');
        suite.className = 'confirm-dialog-suite';
        texte.append(sujet, suite);
        const actions = doc.createElement('div');
        actions.className = 'confirm-dialog-actions';
        const garder = doc.createElement('button');
        garder.setAttribute('type', 'button');
        garder.className = 'btn btn-outline-secondary';
        garder.setAttribute('data-confirmer-garder', '');
        const valider = doc.createElement('button');
        valider.setAttribute('type', 'button');
        valider.className = 'btn btn-danger';
        valider.setAttribute('data-confirmer-valider', '');
        actions.append(garder, valider);
        corps.append(icone, titre, texte, actions);
        dialogue.append(corps);
        doc.body.append(dialogue);
        return { dialogue, titre, sujet, suite, garder, valider };
    }

    function installer(doc, env) {
        if (doc.__stockConfirmation) return doc.__stockConfirmation;
        const fenetre = env || doc.defaultView || {};
        let ui = null;
        let enAttente = null;     // { formulaire, soumetteur, retour }

        function fermerSansEnvoi() {
            const retour = enAttente ? enAttente.retour : null;
            enAttente = null;
            if (retour && typeof retour.focus === 'function') retour.focus();
        }

        /** Envoie le formulaire en sautant la confirmation, une seule fois (l'indicateur est repris dans tous les cas). */
        function envoyer(formulaire, soumetteur) {
            formulaire.setAttribute('data-confirme', '');
            try {
                if (typeof formulaire.requestSubmit === 'function') formulaire.requestSubmit(soumetteur || undefined);
                else formulaire.submit();
            } finally { formulaire.removeAttribute('data-confirme'); }
        }

        function demander(formulaire, soumetteur) {
            if (!ui) {
                ui = construire(doc);
                ui.garder.addEventListener('click', () => ui.dialogue.close());
                ui.dialogue.addEventListener('close', fermerSansEnvoi);
                ui.dialogue.addEventListener('cancel', () => { /* Échap : « close » suit et rend le focus */ });
                ui.dialogue.addEventListener('click', e => { if (e.target === ui.dialogue) ui.dialogue.close(); });
                ui.valider.addEventListener('click', () => {
                    const cours = enAttente;
                    if (!cours) return;
                    enAttente = null;       // un second clic pendant l'envoi est ignoré
                    ui.dialogue.close();    // « close » : le focus ne revient pas à l'origine, la page change
                    envoyer(cours.formulaire, cours.soumetteur);
                });
            }
            ui.titre.textContent = lire(formulaire, 'titre', 'Confirmer cette action ?');
            ui.sujet.textContent = lire(formulaire, 'sujet', '');
            const suite = lire(formulaire, 'texte', '');
            ui.suite.textContent = (ui.sujet.textContent && suite ? ' ' : '') + suite;
            ui.garder.textContent = lire(formulaire, 'non', 'Garder');
            ui.valider.textContent = lire(formulaire, 'oui', 'Confirmer');
            enAttente = { formulaire, soumetteur: soumetteur || null, retour: pointDeRetour(soumetteur || null, formulaire) };
            ui.dialogue.showModal();
            ui.garder.focus();
        }

        doc.addEventListener('submit', evenement => {
            const formulaire = evenement.target;
            if (!formulaire || !formulaire.hasAttribute || !formulaire.hasAttribute('data-confirmer')) return;
            if (formulaire.hasAttribute('data-confirme')) return;   // envoi issu de la fenêtre de confirmation
            evenement.preventDefault();
            const soumetteur = evenement.submitter || null;
            const peutDialogue = typeof doc.createElement('dialog').showModal === 'function';
            if (!peutDialogue) {
                const message = [lire(formulaire, 'titre', 'Confirmer cette action ?'), lire(formulaire, 'sujet', '') + ' ' + lire(formulaire, 'texte', '')].join('\n').trim();
                if (fenetre.confirm && fenetre.confirm(message)) envoyer(formulaire, soumetteur);
                return;
            }
            if (ui && ui.dialogue.open) return;
            demander(formulaire, soumetteur);
        });
        doc.__stockConfirmation = { demander };
        return doc.__stockConfirmation;
    }

    return { installer, pointDeRetour };
});
