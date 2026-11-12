// Formulaire de comptage de caisse : total du billetage, solde théorique et écart affichés pendant la saisie.
// Les chiffres affichés sont indicatifs : le solde qui fait foi est relu par le serveur, sous verrou, à l'envoi.
// Les textes sont insérés avec textContent, jamais en HTML.
(() => {
    'use strict';

    const COUPURES = [20000, 10000, 5000, 2000, 1000, 500, 100, 50, 25, 10, 5, 1];

    /** Montant en centimes (BigInt) depuis « 1000 » ou « 1000,5 » ; vide = 0 ; illisible = null. */
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

    /** Quantité entière positive ou nulle ; vide = 0 ; illisible = null. */
    function quantite(valeur) {
        const texte = String(valeur ?? '').trim();
        if (texte === '') return 0;
        return /^\d{1,7}$/.test(texte) ? Number(texte) : null;
    }

    /** Total du billetage en centimes ; une quantité illisible compte zéro. */
    function totalBilletage(quantites) {
        let total = 0n;
        COUPURES.forEach((coupure, i) => {
            const q = quantite(quantites[i]);
            if (q) total += BigInt(coupure) * BigInt(q) * 100n;
        });
        return total;
    }

    /** Écart = compté − théorique, en centimes. */
    function ecart(compte, theorique) {
        return compte - theorique;
    }

    /** « Aucun écart », « Excédent de 5 000 GNF » ou « Manque de 5 000 GNF ». */
    function texteEcart(centimes) {
        if (centimes === 0n) return 'Aucun écart : la caisse est conforme';
        return centimes > 0n ? `Excédent de ${formater(centimes)} GNF` : `Manque de ${formater(centimes)} GNF`;
    }

    function initialiser() {
        const formulaire = document.getElementById('formComptage');
        if (!formulaire) return;
        const compte = document.getElementById('comptageCompte');
        const montant = document.getElementById('comptageMontant');
        const theorique = document.getElementById('comptageTheorique');
        const ecartTexte = document.getElementById('comptageEcart');
        const billetage = document.getElementById('comptageBilletage');
        const total = document.getElementById('comptageTotalBilletage');
        const champs = Array.from(formulaire.querySelectorAll('[data-coupure]'));

        function option() { return compte.options[compte.selectedIndex]; }

        function maj() {
            const choisi = option();
            const especes = !!choisi && choisi.dataset.type === 'ESPECES';
            if (billetage) billetage.hidden = !especes;
            const quantites = champs.map(c => especes ? c.value : '');
            const somme = totalBilletage(quantites);
            const billeteJuste = especes && somme > 0n;
            if (billetage && total) total.textContent = `${formater(somme)} GNF`;
            champs.forEach((c, i) => {
                const sousTotal = document.getElementById(`comptageLigne${i}`);
                const q = quantite(c.value);
                if (sousTotal) sousTotal.textContent = q ? `${formater(BigInt(COUPURES[i]) * BigInt(q) * 100n)}` : '0';
            });
            if (billeteJuste) {
                montant.value = String(somme / 100n);
                montant.readOnly = true;
            } else {
                montant.readOnly = false;
            }
            const solde = choisi ? lire(choisi.dataset.solde) : null;
            const compteMontant = lire(montant.value);
            if (theorique) theorique.textContent = solde === null ? '—' : `${solde < 0n ? '− ' : ''}${formater(solde)} GNF`;
            if (ecartTexte) {
                if (solde === null || compteMontant === null || montant.value.trim() === '') {
                    ecartTexte.textContent = 'Saisissez le montant compté pour voir l\'écart';
                    ecartTexte.dataset.etat = '';
                } else {
                    const e = ecart(compteMontant, solde);
                    ecartTexte.textContent = texteEcart(e);
                    ecartTexte.dataset.etat = e === 0n ? 'conforme' : e > 0n ? 'excedent' : 'manque';
                }
            }
        }

        compte.addEventListener('change', maj);
        montant.addEventListener('input', maj);
        champs.forEach(c => c.addEventListener('input', maj));
        maj();
    }

    const api = Object.freeze({ COUPURES, lire, formater, quantite, totalBilletage, ecart, texteEcart });
    if (typeof globalThis !== 'undefined') globalThis.Comptage = api;
    if (typeof document === 'undefined') return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
