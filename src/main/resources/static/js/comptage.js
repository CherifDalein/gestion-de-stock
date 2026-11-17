// Formulaire de comptage de caisse : total du billetage, solde théorique et écart affichés pendant la saisie.
// Les chiffres affichés sont indicatifs : le solde qui fait foi est relu par le serveur, sous verrou, à l'envoi.
// Les textes sont insérés avec textContent, jamais en HTML.
(() => {
    'use strict';

    /** Symbole de la monnaie de la boutique (attribut data-monnaie du <body>, posé par le serveur) ; jamais écrit en dur. */
    function symbole() {
        return (typeof document !== 'undefined' && document.body && document.body.dataset && document.body.dataset.monnaie) || '';
    }

    /** Arrondi HALF_UP à l'unité, comme l'affichage serveur : les calculs restent en centimes. */
    function entierArrondi(absolu) { return (absolu + 50n) / 100n; }

    /** Montant en centimes (BigInt) depuis « 1000 » ou « 1000,5 » ; vide = 0 ; illisible = null. */
    function lire(valeur) {
        const texte = String(valeur ?? '').trim().replace(',', '.');
        if (texte === '') return 0n;
        const match = /^(-?)(\d{1,15})(?:\.(\d{1,2}))?$/.exec(texte);
        if (!match) return null;
        const centimes = BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0'));
        return match[1] ? -centimes : centimes;
    }

    /** « 1 000 000 » (valeur absolue, sans décimales, HALF_UP). */
    function formater(centimes) {
        const absolu = centimes < 0n ? -centimes : centimes;
        return String(entierArrondi(absolu)).replace(/\B(?=(\d{3})+(?!\d))/g, '\u00a0');
    }

    /** Quantité entière positive ou nulle ; vide = 0 ; illisible = null. */
    function quantite(valeur) {
        const texte = String(valeur ?? '').trim();
        if (texte === '') return 0;
        return /^\d{1,7}$/.test(texte) ? Number(texte) : null;
    }

    /** Total du billetage en centimes ; une quantité illisible compte zéro. Les coupures viennent de la page (monnaie de la boutique). */
    function totalBilletage(quantites, coupures) {
        let total = 0n;
        coupures.forEach((coupure, i) => {
            const q = quantite(quantites[i]);
            if (q) total += BigInt(coupure) * BigInt(q) * 100n;
        });
        return total;
    }

    /** Écart = compté − théorique, en centimes. */
    function ecart(compte, theorique) {
        return compte - theorique;
    }

    /** « Aucun écart », « Excédent de 5 000 » ou « Manque de 5 000 » suivis du symbole de la monnaie. */
    function texteEcart(centimes) {
        if (centimes === 0n) return 'Aucun écart : la caisse est conforme';
        return centimes > 0n ? `Excédent de ${formater(centimes)}\u00a0${symbole()}` : `Manque de ${formater(centimes)}\u00a0${symbole()}`;
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
        const COUPURES = champs.map(c => Number(c.dataset.coupure));

        function option() { return compte.options[compte.selectedIndex]; }

        function maj() {
            const choisi = option();
            const especes = !!choisi && choisi.dataset.type === 'ESPECES';
            if (billetage) billetage.hidden = !especes;
            const quantites = champs.map(c => especes ? c.value : '');
            const somme = totalBilletage(quantites, COUPURES);
            const billeteJuste = especes && somme > 0n;
            if (billetage && total) total.textContent = `${formater(somme)}\u00a0${symbole()}`;
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
            // Comptage à l'aveugle : sans data-solde (caissier), aucun solde ni écart n'est calculé.
            const solde = choisi && choisi.dataset.solde !== undefined ? lire(choisi.dataset.solde) : null;
            const compteMontant = lire(montant.value);
            if (theorique) theorique.textContent = solde === null ? '—' : `${solde < 0n ? '− ' : ''}${formater(solde)}\u00a0${symbole()}`;
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

    const api = Object.freeze({ lire, formater, quantite, totalBilletage, ecart, texteEcart });
    if (typeof globalThis !== 'undefined') globalThis.Comptage = api;
    if (typeof document === 'undefined') return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
