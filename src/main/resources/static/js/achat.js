let index = 0;

function ajouterLigne() {
    const select = document.getElementById('selectProduit');
    const pId = select.value;
    const pNom = select.options[select.selectedIndex].getAttribute('data-nom');
    const prix = document.getElementById('inputPrix').value;
    const qte = document.getElementById('inputQtite').value;

    const prixCentimes = Montants.lire(prix);
    const quantite = Number(qte);
    if (!pId || prix.trim() === '' || qte.trim() === '' ||
        prixCentimes === null || prixCentimes < 0n ||
        !Number.isSafeInteger(quantite) || quantite <= 0 || quantite > 2147483647) {
        afficherMessageProduit('Choisissez un produit, un prix positif ou nul et une quantité entière positive.');
        return;
    }

    const tbody = document.querySelector('#tableLignes tbody');
    const totalLigne = prixCentimes * BigInt(quantite);
    const totalActuel = [...document.querySelectorAll('.ligne-total')]
        .reduce((total, td) => total + BigInt(td.dataset.centimes), 0n);
    if (totalActuel + totalLigne > Montants.maximum) {
        afficherMessageProduit('Le total dépasse le montant maximal autorisé.');
        return;
    }
    const row = document.createElement('tr');
    const nomCellule = row.insertCell();
    const nom = document.createElement('span');
    nom.textContent = pNom;
    nomCellule.appendChild(nom);

    const champs = [
        {cellule: nomCellule, propriete: 'produit.id', valeur: pId, type: 'hidden'},
        {cellule: row.insertCell(), propriete: 'prixAchatUnitaire', valeur: Montants.formater(prixCentimes), type: 'number'},
        {cellule: row.insertCell(), propriete: 'quantite', valeur: quantite, type: 'number'}
    ];
    champs.forEach(champ => {
        const input = document.createElement('input');
        input.type = champ.type;
        input.name = `lignes[${index}].${champ.propriete}`;
        input.value = champ.valeur;
        input.readOnly = true;
        input.step = champ.propriete === 'prixAchatUnitaire' ? '0.01' : '1';
        input.className = 'form-control form-control-sm';
        if (champ.type === 'number') {
            input.setAttribute('aria-label', `${champ.propriete === 'quantite' ? 'Quantité de' : 'Prix unitaire de'} ${pNom}`);
        }
        champ.cellule.appendChild(input);
    });

    const totalCellule = row.insertCell();
    totalCellule.className = 'ligne-total fw-bold';
    totalCellule.dataset.centimes = totalLigne.toString();
    totalCellule.textContent = Montants.formater(totalLigne);
    const actionCellule = row.insertCell();
    actionCellule.className = 'text-center';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-outline-danger btn-sm';
    button.textContent = 'Retirer';
    button.setAttribute('aria-label', `Retirer ${pNom} de l’achat`);
    button.addEventListener('click', () => supprimerLigne(button));
    actionCellule.appendChild(button);
    tbody.appendChild(row);
    afficherMessageProduit();
    index++;
    calculerTotal();

    // Reset et focus pour la ligne suivante
    select.value = "";
    document.getElementById('inputPrix').value = "";
    document.getElementById('inputQtite').value = "";
    select.focus();
}

function supprimerLigne(button) {
    button.closest('tr').remove();
    reindexerLignes();
    calculerTotal();
}

function reindexerLignes() {
    const rows = document.querySelectorAll('#tableLignes tbody tr');
    rows.forEach((row, newIndex) => {
        row.querySelectorAll('input[name]').forEach(input => {
            input.name = input.name.replace(/^lignes\[\d+\]/, `lignes[${newIndex}]`);
        });
    });
    index = rows.length;
}

function calculerTotal() {
    let total = 0n;
    document.querySelectorAll('.ligne-total').forEach(td => {
        total += BigInt(td.dataset.centimes);
    });

    document.getElementById('totalGeneral').value = Montants.formater(total);
    const affichage = document.getElementById('totalAchatAffiche');
    if (affichage) affichage.textContent = Montants.afficher(total);

    // Suggestion : Par défaut on met le montant versé égal au total
    const vInput = document.getElementById('montantVerse');
    if (vInput.dataset.saisieManuelle !== 'true') {
        vInput.value = Montants.formater(total);
    }

    synchroniserPanier();
    calculerReste();
}

function synchroniserPanier() {
    const nombre = document.querySelectorAll('#tableLignes tbody tr').length;
    const vide = document.getElementById('achatVide');
    const table = document.getElementById('achatTable');
    const compteur = document.getElementById('achatCompteur');
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
    if (document.querySelectorAll('#tableLignes tbody tr').length === 0) {
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
    const total = Montants.lire(document.getElementById('totalGeneral').value) ?? 0n;
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

    // Style visuel si dette
    if (reste > 0n) {
        resteInput.classList.add('text-danger');
    } else {
        resteInput.classList.remove('text-danger');
    }
}

document.addEventListener('DOMContentLoaded', function() {
    // Remplissage auto du prix quand on change de produit
    const selectProduit = document.getElementById('selectProduit');
    if (selectProduit) {
        selectProduit.addEventListener('change', function() {
            const prix = this.options[this.selectedIndex].getAttribute('data-prix');
            document.getElementById('inputPrix').value = prix || "";
        });
    }

    // Calcul du reste quand on modifie le montant versé manuellement
    const verseInput = document.getElementById('montantVerse');
    if (verseInput) {
        if (verseInput.value !== '') verseInput.dataset.saisieManuelle = 'true';
        verseInput.addEventListener('input', () => {
            verseInput.dataset.saisieManuelle = 'true';
            calculerReste();
        });
        calculerTotal();
    }
    ['selectProduit', 'inputPrix', 'inputQtite'].forEach(id => {
        document.getElementById(id)?.addEventListener('input', () => afficherMessageProduit());
    });
});

document.addEventListener('submit', function(event) {
    if (event.target.id !== 'achatForm') return;
    if (document.querySelectorAll('#tableLignes tbody tr').length === 0) {
        event.preventDefault();
        afficherMessageProduit('Ajoutez au moins un produit avant de valider l’achat.');
        document.getElementById('selectProduit')?.focus();
        return;
    }
    reindexerLignes();
});
