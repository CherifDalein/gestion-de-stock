// Formulaire de retour d'achat (retour au fournisseur) : montant calculé en direct, en centimes entiers (BigInt).
// Le serveur recalcule tout et reste seul juge ; les textes sont toujours insérés avec textContent.
// Retour R, reste à payer avant le retour D : la part qui réduit la dette envers le fournisseur est min(R, D), l'excédent
// E = R − min(R, D) (déjà payé) est remboursé par le fournisseur ou gardé en avoir chez lui.
(function() {
const RetourAchat = Object.freeze({
    calculer(lignes, reste) {
        let montant = 0n;
        for (const ligne of lignes) montant += ligne.prix * BigInt(ligne.quantite);
        const impute = montant < reste ? montant : reste;
        return {montant, impute, excedent: montant - impute};
    }
});
if (typeof globalThis !== 'undefined') globalThis.RetourAchat = RetourAchat;

if (typeof document !== 'undefined') {
    document.addEventListener('DOMContentLoaded', function() {
        const formulaire = document.getElementById('formRetourAchat');
        if (!formulaire || typeof Montants === 'undefined') return;
        const reste = Montants.lire(formulaire.dataset.reste ?? '0') ?? 0n;
        const avecFournisseur = formulaire.dataset.avecFournisseur === 'true';
        const rangees = Array.from(formulaire.querySelectorAll('tr[data-ligne-retour]'));
        const texte = (id, valeur) => { const element = document.getElementById(id); if (element) element.textContent = valeur; };

        function quantiteSaisie(rangee) {
            const champ = rangee.querySelector('.retour-quantite');
            if (!champ || champ.disabled || !/^\d{1,9}$/.test(champ.value.trim())) return 0;
            const maximum = Number(rangee.dataset.max);
            return Math.min(Number(champ.value.trim()), maximum);
        }

        function mettreAJour() {
            const lignes = [];
            for (const rangee of rangees) {
                const prix = Montants.lire(rangee.dataset.prix ?? '0') ?? 0n;
                const quantite = quantiteSaisie(rangee);
                lignes.push({prix, quantite});
                const cellule = rangee.querySelector('[data-montant-ligne]');
                if (cellule) cellule.textContent = `${Montants.afficher(prix * BigInt(quantite))} GNF`;
            }
            const {montant, impute, excedent} = RetourAchat.calculer(lignes, reste);
            texte('retourMontant', Montants.afficher(montant));
            texte('retourImpute', Montants.afficher(impute));
            texte('retourExcedent', Montants.afficher(excedent));
            let explication;
            if (montant === 0n) {
                explication = 'Saisissez une quantité pour voir le détail.';
            } else if (excedent === 0n) {
                explication = `Vous devez encore ${Montants.afficher(reste)} GNF au fournisseur : ce retour réduit votre dette de ${Montants.afficher(impute)} GNF. Aucun argent n’est rendu.`;
            } else if (impute === 0n) {
                explication = `Vous ne devez plus rien au fournisseur : les ${Montants.afficher(excedent)} GNF déjà payés sont à récupérer en argent ou à garder en avoir chez lui.`;
            } else {
                explication = `${Montants.afficher(impute)} GNF réduisent votre dette envers le fournisseur (sans argent) ; les ${Montants.afficher(excedent)} GNF déjà payés sont à récupérer en argent ou à garder en avoir chez lui.`;
            }
            texte('retourExplication', explication);

            // Le choix n'est demandé (et envoyé) que s'il reste un excédent : sinon ses champs sont désactivés.
            const choix = document.getElementById('retourChoix');
            const argent = document.getElementById('modeArgent');
            const avoir = document.getElementById('modeAvoir');
            const bloc = document.getElementById('retourCompteBloc');
            const compte = document.getElementById('comptePaiement');
            const avec = excedent > 0n;
            if (choix) choix.hidden = !avec;
            if (argent) argent.disabled = !avec;
            if (avoir) avoir.disabled = !avec || !avecFournisseur;
            const indisponible = document.getElementById('avoirIndisponible');
            if (indisponible) indisponible.hidden = avecFournisseur;
            if (avec && !avecFournisseur && argent) argent.checked = true;
            const argentChoisi = avec && argent && argent.checked;
            if (bloc) bloc.hidden = !argentChoisi;
            if (compte) compte.disabled = !argentChoisi;
        }

        formulaire.addEventListener('input', mettreAJour);
        formulaire.addEventListener('change', mettreAJour);
        formulaire.addEventListener('submit', function(event) {
            if (rangees.every(rangee => quantiteSaisie(rangee) === 0)) {
                event.preventDefault();
                texte('retourExplication', 'Indiquez au moins une quantité à retourner.');
                return;
            }
            // Un seul clic enregistre un seul retour : le bouton est bloqué dès l'envoi.
            const bouton = document.getElementById('boutonRetour');
            if (bouton) setTimeout(() => { bouton.disabled = true; }, 0);
        });
        mettreAJour();
    });
}
})();
