let index = 0;
let totalGeneral = 0n;

function ajouterAuPanier() {
    const select = document.getElementById('selectProduit');
    const qtyInput = document.getElementById('inputQuantite');

    const pId = select.value;
    const selectedOption = select.options[select.selectedIndex];

    if (!pId || pId === "") {
        afficherMessageProduit('Choisissez un produit à ajouter.');
        return;
    }

    const pNom = selectedOption.getAttribute('data-nom');
    const pPrix = Montants.lire(selectedOption.getAttribute('data-prix'));
    const qty = Number(qtyInput.value);
    if (!Number.isSafeInteger(qty) || qty <= 0 || qty > 2147483647 || pPrix === null || pPrix < 0n) {
        afficherMessageProduit('Vérifiez le prix du produit et saisissez une quantité entière positive.');
        return;
    }

    // Extraction du stock
    const textActuel = selectedOption.text;
    const match = textActuel.match(/\((\d+) en stock\)/);
    const stockActuel = match ? BigInt(match[1]) : 0n;

    if (BigInt(qty) > stockActuel) {
        afficherMessageProduit('Stock insuffisant pour cette quantité.');
        return;
    }
    if (qty <= 0) return;

    // Mise à jour visuelle du stock
    const nouveauStock = stockActuel - BigInt(qty);

    const sousTotal = pPrix * BigInt(qty);
    if (totalGeneral + sousTotal > Montants.maximum) {
        afficherMessageProduit('Le total dépasse le montant maximal autorisé.');
        return;
    }
    selectedOption.text = `${pNom} (${nouveauStock} en stock)`;
    const tbody = document.getElementById('panierBody');

    const row = document.createElement('tr');
    row.id = `row-${index}`;
    const nomCellule = row.insertCell();
    const nom = document.createElement('span');
    nom.textContent = pNom;
    nomCellule.appendChild(nom);
    const produitInput = document.createElement('input');
    produitInput.type = 'hidden';
    produitInput.name = `lignes[${index}].produit.id`;
    produitInput.value = pId;
    nomCellule.appendChild(produitInput);

    row.insertCell().textContent = `${Montants.afficher(pPrix)} GNF`;
    const quantiteCellule = row.insertCell();
    const quantiteTexte = document.createElement('span');
    quantiteTexte.textContent = qty;
    quantiteCellule.appendChild(quantiteTexte);
    const quantiteInput = document.createElement('input');
    quantiteInput.type = 'hidden';
    quantiteInput.name = `lignes[${index}].quantite`;
    quantiteInput.value = qty;
    quantiteCellule.appendChild(quantiteInput);

    const totalCellule = row.insertCell();
    totalCellule.className = 'sous-total-val';
    totalCellule.dataset.centimes = sousTotal.toString();
    totalCellule.textContent = `${Montants.afficher(sousTotal)} GNF`;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-outline-danger btn-sm';
    button.textContent = 'Retirer';
    button.setAttribute('aria-label', `Retirer ${pNom} du panier`);
    const rowIndex = index;
    button.addEventListener('click', () => supprimerLigne(rowIndex, sousTotal, pId, qty));
    row.insertCell().appendChild(button);

    tbody.appendChild(row);
    afficherMessageProduit();
    totalGeneral += sousTotal;
    actualiserAffichageTotal();

    index++;
    qtyInput.value = 1;
    select.value = "";
}

function supprimerLigne(idx, montant, pId, qteARendre) {
    const row = document.getElementById('row-' + idx);
    if (row) {
        row.remove();
        totalGeneral -= montant;
        actualiserAffichageTotal();

        const select = document.getElementById('selectProduit');
        for (let i = 0; i < select.options.length; i++) {
            if (select.options[i].value == pId) {
                const opt = select.options[i];
                const pNom = opt.getAttribute('data-nom');
                const match = opt.text.match(/\((\d+) en stock\)/);
                const stockActuel = match ? BigInt(match[1]) : 0n;
                opt.text = `${pNom} (${stockActuel + BigInt(qteARendre)} en stock)`;
                break;
            }
        }
    }
}

function actualiserAffichageTotal() {
    // 1. Mise à jour de l'affichage total
    document.getElementById('totalVente').innerText = Montants.afficher(totalGeneral);

    // 2. Mise à jour de l'input caché pour l'envoi vers Spring Boot
    document.getElementById('inputTotalTotal').value = Montants.formater(totalGeneral);

    // 3. Mise à jour du montant versé (on suggère le total par défaut)
    const verseInput = document.getElementById('montantVerse');
    if (verseInput.dataset.saisieManuelle !== 'true') {
        verseInput.value = Montants.formater(totalGeneral);
    }

    synchroniserPanier();
    calculerReste();
}

function synchroniserPanier() {
    const nombre = document.getElementById('panierBody')?.children.length ?? 0;
    const vide = document.getElementById('panierVide');
    const table = document.getElementById('panierTable');
    const compteur = document.getElementById('panierCompteur');
    if (vide) vide.hidden = nombre > 0;
    if (table) table.hidden = nombre === 0;
    if (compteur) compteur.textContent = `${nombre} ligne${nombre > 1 ? 's' : ''}`;
}

function afficherMessageProduit(message = '') {
    const feedback = document.getElementById('produitFeedback');
    if (feedback) {
        feedback.textContent = message;
        feedback.hidden = message === '';
    } else if (message) {
        alert(message);
    }
}

function actualiserStatutReglement(total, verse) {
    const statut = document.getElementById('reglementStatut');
    if (!statut) return;
    let etat;
    let message;
    if (document.getElementById('panierBody').children.length === 0) {
        etat = 'vide';
        message = 'Ajoutez des produits pour calculer le règlement.';
    } else if (verse === null || verse < 0n) {
        etat = 'invalide';
        message = 'Saisissez un montant versé positif ou nul avec au maximum deux décimales.';
    } else if (verse > total) {
        etat = 'depassement';
        message = 'Le montant versé dépasse le total.';
    } else if (verse === 0n && total > 0n) {
        etat = 'credit';
        message = 'Paiement à crédit';
    } else if (verse < total) {
        etat = 'partiel';
        message = 'Paiement partiel';
    } else {
        etat = 'complet';
        message = 'Paiement intégral';
    }
    statut.dataset.etat = etat;
    statut.textContent = message;
}

function calculerReste() {
    const total = totalGeneral;
    const verseInput = document.getElementById('montantVerse');
    const verse = verseInput.value === '' ? 0n : Montants.lire(verseInput.value);
    verseInput.setCustomValidity(verse === null || verse < 0n ? 'Saisissez un montant positif ou nul avec au maximum deux décimales.' : '');
    actualiserStatutReglement(total, verse);
    if (verse === null) {
        document.getElementById('resteAPayer').value = '';
        return;
    }
    const reste = total - verse;

    const resteInput = document.getElementById('resteAPayer');
    resteInput.value = Montants.formater(reste);

    // Feedback visuel
    if (reste > 0n) {
        resteInput.classList.add('text-danger', 'fw-bold');
    } else {
        resteInput.classList.remove('text-danger', 'fw-bold');
    }
}

// Initialisation au chargement
document.addEventListener('DOMContentLoaded', function() {
    const verseInput = document.getElementById('montantVerse');
    if (verseInput) {
        if (verseInput.value !== '') verseInput.dataset.saisieManuelle = 'true';
        verseInput.addEventListener('input', () => {
            verseInput.dataset.saisieManuelle = 'true';
            calculerReste();
        });
        actualiserAffichageTotal();
    }
    ['selectProduit', 'inputQuantite'].forEach(id => {
        document.getElementById(id)?.addEventListener('input', () => afficherMessageProduit());
    });
});

// Re-indexation avant soumission
document.addEventListener('submit', function(event) {
    if (event.target.id === 'formVente') {
        const rows = document.querySelectorAll('#panierBody tr');
        if (rows.length === 0) {
            event.preventDefault();
            afficherMessageProduit('Ajoutez au moins un produit avant de valider la vente.');
            document.getElementById('selectProduit')?.focus();
            return;
        }
        rows.forEach((row, newIndex) => {
            row.querySelector('input[name*=".produit.id"]').name = `lignes[${newIndex}].produit.id`;
            row.querySelector('input[name*=".quantite"]').name = `lignes[${newIndex}].quantite`;
        });
    }
});
