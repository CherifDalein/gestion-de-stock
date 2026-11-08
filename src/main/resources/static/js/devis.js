// Panier d'un devis : produits, quantités entières et prix unitaires saisis. Les montants sont en centimes entiers ;
// le serveur recalcule tout et reste seul juge. Les textes métier sont toujours insérés avec textContent.
let indexLigne = 0;
let totalDevis = 0n;

function afficherMessageDevis(message = '') {
    const feedback = document.getElementById('produitFeedback');
    if (!feedback) return;
    feedback.textContent = message;
    feedback.hidden = message === '';
}

function synchroniserDevis() {
    const nombre = document.getElementById('panierBody')?.children.length ?? 0;
    const vide = document.getElementById('panierVide');
    const table = document.getElementById('panierTable');
    const compteur = document.getElementById('panierCompteur');
    if (vide) vide.hidden = nombre > 0;
    if (table) table.hidden = nombre === 0;
    if (compteur) compteur.textContent = `${nombre} ligne${nombre > 1 ? 's' : ''}`;
    const total = document.getElementById('totalDevis');
    if (total) total.textContent = Montants.afficher(totalDevis);
}

// Le prix de vente du produit est proposé dès qu'il est choisi ; il reste modifiable.
function proposerPrix() {
    const select = document.getElementById('selectProduit');
    const prix = document.getElementById('inputPrix');
    const option = select.options[select.selectedIndex];
    const valeur = option && option.value ? Montants.lire(option.getAttribute('data-prix') ?? '') : null;
    prix.value = valeur === null ? '' : Montants.formater(valeur);
}

function ajouterLigneDevis(produitId, nom, quantite, prixCentimes) {
    const sousTotal = prixCentimes * BigInt(quantite);
    if (totalDevis + sousTotal > Montants.maximum) {
        afficherMessageDevis('Le total dépasse le montant maximal autorisé.');
        return false;
    }
    const ligne = document.createElement('tr');
    const nomCellule = ligne.insertCell();
    const nomTexte = document.createElement('span');
    nomTexte.textContent = nom;
    nomCellule.appendChild(nomTexte);
    const produitInput = document.createElement('input');
    produitInput.type = 'hidden';
    produitInput.name = `lignes[${indexLigne}].produit.id`;
    produitInput.value = produitId;
    nomCellule.appendChild(produitInput);

    const prixCellule = ligne.insertCell();
    prixCellule.className = 'text-end';
    const prixTexte = document.createElement('span');
    prixTexte.textContent = `${Montants.afficher(prixCentimes)} GNF`;
    prixCellule.appendChild(prixTexte);
    const prixInput = document.createElement('input');
    prixInput.type = 'hidden';
    prixInput.name = `lignes[${indexLigne}].prixUnitaire`;
    prixInput.value = Montants.formater(prixCentimes);
    prixCellule.appendChild(prixInput);

    const quantiteCellule = ligne.insertCell();
    quantiteCellule.className = 'text-end';
    const quantiteTexte = document.createElement('span');
    quantiteTexte.textContent = String(quantite);
    quantiteCellule.appendChild(quantiteTexte);
    const quantiteInput = document.createElement('input');
    quantiteInput.type = 'hidden';
    quantiteInput.name = `lignes[${indexLigne}].quantite`;
    quantiteInput.value = String(quantite);
    quantiteCellule.appendChild(quantiteInput);

    const sousTotalCellule = ligne.insertCell();
    sousTotalCellule.className = 'text-end';
    sousTotalCellule.textContent = `${Montants.afficher(sousTotal)} GNF`;

    const bouton = document.createElement('button');
    bouton.type = 'button';
    bouton.className = 'btn btn-outline-danger btn-sm';
    bouton.textContent = 'Retirer';
    bouton.setAttribute('aria-label', `Retirer ${nom} du devis`);
    bouton.addEventListener('click', () => {
        ligne.remove();
        totalDevis -= sousTotal;
        synchroniserDevis();
    });
    const actionCellule = ligne.insertCell();
    actionCellule.className = 'text-end';
    actionCellule.appendChild(bouton);

    document.getElementById('panierBody').appendChild(ligne);
    totalDevis += sousTotal;
    indexLigne++;
    synchroniserDevis();
    return true;
}

function ajouterAuDevis() {
    const select = document.getElementById('selectProduit');
    const option = select.options[select.selectedIndex];
    if (!option || !option.value) {
        afficherMessageDevis('Choisissez un produit à ajouter.');
        return;
    }
    if (option.disabled || option.getAttribute('data-prix-definis') !== 'true') {
        afficherMessageDevis('Les prix de ce produit sont à définir lors d’un achat avant de le proposer dans un devis.');
        return;
    }
    const quantite = Number(document.getElementById('inputQuantite').value);
    const prix = Montants.lire(document.getElementById('inputPrix').value);
    if (!Number.isSafeInteger(quantite) || quantite <= 0 || quantite > 2147483647) {
        afficherMessageDevis('Saisissez une quantité entière positive.');
        return;
    }
    if (prix === null || prix < 0n) {
        afficherMessageDevis('Saisissez un prix unitaire positif ou nul avec au maximum deux décimales.');
        return;
    }
    if (ajouterLigneDevis(option.value, option.getAttribute('data-nom'), quantite, prix)) {
        afficherMessageDevis();
        document.getElementById('inputQuantite').value = 1;
        document.getElementById('inputPrix').value = '';
        select.value = '';
    }
}

// Reconstitue une ligne envoyée après un refus du serveur (le serveur revérifie tout).
function chargerLigneDevis(produitId, quantite, prix) {
    const select = document.getElementById('selectProduit');
    const option = Array.from(select.options).find(o => o.value === String(produitId));
    const centimes = prix === null || prix === undefined ? null : Montants.lire(prix);
    if (!option || option.disabled || centimes === null || !Number.isSafeInteger(Number(quantite)) || Number(quantite) <= 0) return;
    ajouterLigneDevis(option.value, option.getAttribute('data-nom'), Number(quantite), centimes);
}

document.addEventListener('DOMContentLoaded', function() {
    document.getElementById('boutonAjouterLigne')?.addEventListener('click', ajouterAuDevis);
    document.getElementById('selectProduit')?.addEventListener('change', proposerPrix);
    ['selectProduit', 'inputQuantite', 'inputPrix'].forEach(id => {
        document.getElementById(id)?.addEventListener('input', () => afficherMessageDevis());
    });
    synchroniserDevis();
});

document.addEventListener('submit', function(event) {
    if (event.target.id !== 'formDevis') return;
    const lignes = document.querySelectorAll('#panierBody tr');
    if (lignes.length === 0) {
        event.preventDefault();
        afficherMessageDevis('Ajoutez au moins une ligne avant de créer le devis.');
        document.getElementById('selectProduit')?.focus();
        return;
    }
    lignes.forEach((ligne, nouvelIndex) => {
        ligne.querySelector('input[name*=".produit.id"]').name = `lignes[${nouvelIndex}].produit.id`;
        ligne.querySelector('input[name*=".prixUnitaire"]').name = `lignes[${nouvelIndex}].prixUnitaire`;
        ligne.querySelector('input[name*=".quantite"]').name = `lignes[${nouvelIndex}].quantite`;
    });
    // Un seul clic crée un seul devis : le bouton est bloqué dès l'envoi.
    const bouton = document.getElementById('boutonCreerDevis');
    if (bouton) setTimeout(() => { bouton.disabled = true; }, 0);
});

