// Rapprochement bancaire (étape 3c) : somme de la sélection d'un pointage, onglets mobiles, choix du mode de montant à l'import.
// Les chiffres affichés sont indicatifs : le serveur revérifie tout (somme exacte, sans écart toléré) à l'envoi.
// Les textes sont insérés avec textContent, jamais en HTML.
(() => {
    'use strict';

    /** Montant en centimes (BigInt) depuis « 1000 », « -1000.5 » ou « 250.50 » ; vide = 0 ; illisible = null. */
    function lire(valeur) {
        const texte = String(valeur ?? '').trim().replace(',', '.');
        if (texte === '') return 0n;
        const match = /^(-?)(\d{1,15})(?:\.(\d{1,2}))?$/.exec(texte);
        if (!match) return null;
        const centimes = BigInt(match[2]) * 100n + BigInt((match[3] || '').padEnd(2, '0'));
        return match[1] ? -centimes : centimes;
    }

    /** « 1 000 000 », « − 1 000,50 » : espaces entre les milliers, virgule décimale, décimales seulement si non nulles. */
    function formater(centimes) {
        const absolu = centimes < 0n ? -centimes : centimes;
        const entier = String(absolu / 100n).replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
        const fraction = absolu % 100n;
        const texte = fraction === 0n ? entier : `${entier},${String(fraction).padStart(2, '0')}`;
        return centimes < 0n ? `− ${texte}` : texte;
    }

    /** Somme en centimes d'une liste de montants ; un montant illisible compte zéro. */
    function somme(montants) {
        return montants.reduce((total, m) => total + (lire(m) ?? 0n), 0n);
    }

    /**
     * Verdict affiché sous la sélection : {@code etat} vaut « vide » (il manque un côté), « egal » (les sommes sont égales : on peut associer) ou
     * « ecart » (elles diffèrent : le serveur refusera).
     */
    function verdict(lignes, mouvements) {
        if (lignes.length === 0 || mouvements.length === 0) return { etat: 'vide', texte: 'Choisissez au moins une ligne et un mouvement.' };
        const ecart = somme(lignes) - somme(mouvements);
        if (ecart === 0n) return { etat: 'egal', texte: 'Les sommes sont égales : vous pouvez associer.' };
        return { etat: 'ecart', texte: `Écart de ${formater(ecart)} GNF : aucun écart n'est toléré.` };
    }

    function initialiser() {
        // Choix du compte d'un nouveau relevé : recharge la page pour proposer les dates et le solde d'ouverture.
        document.querySelectorAll('select[data-recharger]').forEach(select => {
            select.addEventListener('change', () => {
                if (select.value) window.location.assign(`${select.dataset.recharger}?compte=${encodeURIComponent(select.value)}`);
            });
        });
        // Confirmation avant une suppression.
        document.querySelectorAll('[data-confirmer]').forEach(bouton => {
            bouton.addEventListener('click', evenement => { if (!window.confirm(bouton.dataset.confirmer)) evenement.preventDefault(); });
        });
        // Import : seuls les champs du mode de montant choisi sont montrés.
        const modes = document.querySelectorAll('input[name="modeMontant"]');
        if (modes.length) {
            const maj = () => {
                const choisi = Array.from(modes).find(m => m.checked)?.value ?? 'SIGNE';
                document.querySelectorAll('[data-mode]').forEach(champ => { champ.hidden = champ.dataset.mode !== choisi; });
            };
            modes.forEach(m => m.addEventListener('change', maj));
            maj();
        }
        // Pointage : somme de la sélection et verdict.
        const barre = document.getElementById('barreAssocier');
        if (barre) {
            const cases = () => Array.from(document.querySelectorAll('input[form="formRapprocher"]:checked'));
            const maj = () => {
                const lignes = cases().filter(c => c.name === 'lignes').map(c => c.dataset.montant);
                const mouvements = cases().filter(c => c.name === 'mouvements').map(c => c.dataset.montant);
                const v = verdict(lignes, mouvements);
                document.getElementById('sommeLignes').textContent = formater(somme(lignes));
                document.getElementById('sommeMouvements').textContent = formater(somme(mouvements));
                const texte = document.getElementById('verdictSommes');
                texte.textContent = v.texte;
                texte.dataset.etat = v.etat;
                document.getElementById('boutonAssocier').disabled = v.etat !== 'egal';
            };
            document.querySelectorAll('input[form="formRapprocher"]').forEach(c => c.addEventListener('change', maj));
            maj();
        }
        // Mobile : deux onglets (relevé, appli) au lieu de deux sections empilées.
        const onglets = document.querySelector('.rap-onglets-mobile');
        if (onglets) {
            const boutons = Array.from(onglets.querySelectorAll('button[data-cible]'));
            const mobile = typeof globalThis.matchMedia === 'function' ? globalThis.matchMedia('(max-width: 575.98px)') : null;
            let courant = boutons.length ? boutons[0].dataset.cible : null;
            // Les deux colonnes restent côte à côte hors mobile : on ne masque une colonne que sur un petit écran.
            const montrer = cible => {
                courant = cible;
                boutons.forEach(b => b.setAttribute('aria-selected', String(b.dataset.cible === cible)));
                boutons.forEach(b => { const colonne = document.getElementById(b.dataset.cible); if (colonne) colonne.hidden = !!mobile && mobile.matches && b.dataset.cible !== cible; });
            };
            if (mobile && mobile.addEventListener) mobile.addEventListener('change', () => { if (courant) montrer(courant); });
            onglets.hidden = false;
            document.documentElement.classList.add('rap-js');
            boutons.forEach(b => b.addEventListener('click', () => montrer(b.dataset.cible)));
            if (boutons.length) montrer(boutons[0].dataset.cible);
        }
    }

    const api = Object.freeze({ lire, formater, somme, verdict });
    if (typeof globalThis !== 'undefined') globalThis.Rapprochement = api;
    if (typeof document === 'undefined') return;
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
