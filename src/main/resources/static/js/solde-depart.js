// Section « solde de départ » des fiches client et fournisseur : montant formaté pendant la saisie et
// confirmation d'un changement de montant ou de sens. Le serveur reste la seule vraie protection.
// Les textes métier sont toujours insérés avec textContent, jamais en HTML.
(() => {
    'use strict';

    /** Montant en centimes (BigInt) depuis un texte « 1000000 » ou « 1000.5 » ; vide = 0 ; illisible = null. */
    function lire(valeur) {
        const texte = String(valeur ?? '').trim().replace(',', '.');
        if (texte === '') return 0n;
        const match = /^(-?)(\d{1,15})(?:\.(\d{1,2}))?$/.exec(texte);
        if (!match) return null;
        const centimes = BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0'));
        return match[1] ? -centimes : centimes;
    }

    /** « 1 000 000 » ou « 1 000 000,50 » (valeur absolue). */
    function formater(centimes) {
        const absolu = centimes < 0n ? -centimes : centimes;
        const entier = String(absolu / 100n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
        const fraction = absolu % 100n;
        return fraction === 0n ? entier : `${entier},${String(fraction).padStart(2, '0')}`;
    }

    /** Solde avec son sens, sans signe : « 1 000 000 GNF (il me doit) », « 1 000 000 GNF (je lui dois) » ou « 0 GNF ». */
    function signe(centimes) {
        if (centimes === 0n) return '0 GNF';
        return centimes > 0n ? `${formater(centimes)} GNF (il me doit)` : `${formater(centimes)} GNF (je lui dois)`;
    }

    /** Solde signé saisi : le montant, négatif quand le sens est « je lui dois ». */
    function soldeSaisi(montant, sens) {
        const centimes = lire(montant);
        if (centimes === null) return null;
        return sens === 'JE_LUI_DOIS' ? -centimes : centimes;
    }

    function apercu(montant) {
        const centimes = lire(montant);
        return centimes === null || centimes === 0n ? '' : `= ${formater(centimes)} GNF`;
    }

    function confirmation(ancien, nouveau) {
        return `Je confirme le nouveau solde de départ : ${signe(ancien)} → ${signe(nouveau)}`;
    }

    function initialiser() {
        const section = document.getElementById('soldeDepartSection');
        const montant = document.getElementById('inputMontantSoldeDepart');
        const sens = document.getElementById('inputSensSoldeDepart');
        if (!section || !montant || !sens) return;
        const apercuMontant = document.getElementById('soldeDepartApercu');
        const bloc = document.getElementById('soldeDepartConfirmation');
        const texte = document.getElementById('soldeDepartConfirmationTexte');
        const case_ = document.getElementById('inputConfirmationSoldeDepart');
        const ancien = bloc ? lire(section.dataset.ancienSolde) : null;

        function maj() {
            if (apercuMontant) apercuMontant.textContent = apercu(montant.value);
            if (!bloc || !texte || !case_ || ancien === null) return;
            const nouveau = soldeSaisi(montant.value, sens.value);
            const change = nouveau !== null && nouveau !== ancien;
            bloc.hidden = !change;
            case_.required = change;
            if (change) texte.textContent = confirmation(ancien, nouveau);
            else case_.checked = false;
        }

        montant.addEventListener('input', maj);
        sens.addEventListener('change', maj);
        maj();
    }

    const api = Object.freeze({ lire, formater, signe, soldeSaisi, apercu, confirmation });
    if (typeof globalThis !== 'undefined') globalThis.SoldeDepart = api;
    if (typeof document === 'undefined') return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
