let index = 0;

function ajouterLigne() {
    const select = document.getElementById('selectProduit');
    const pId = select.value;
    const pNom = select.options[select.selectedIndex].getAttribute('data-nom');
    const prix = document.getElementById('inputPrix').value;
    const qte = document.getElementById('inputQtite').value;

    const prixNombre = Number(prix);
    const quantite = Number(qte);
    if (!pId || prix.trim() === '' || qte.trim() === '' ||
        !Number.isFinite(prixNombre) || prixNombre < 0 ||
        !Number.isSafeInteger(quantite) || quantite <= 0) {
        alert("Veuillez remplir correctement tous les champs.");
        return;
    }

    const tbody = document.querySelector('#tableLignes tbody');
    const totalLigne = prixNombre * quantite;
    const row = document.createElement('tr');
    const nomCellule = row.insertCell();
    const nom = document.createElement('span');
    nom.textContent = pNom;
    nomCellule.appendChild(nom);

    const champs = [
        {cellule: nomCellule, propriete: 'produit.id', valeur: pId, type: 'hidden'},
        {cellule: row.insertCell(), propriete: 'prixAchatUnitaire', valeur: prixNombre, type: 'number'},
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
        champ.cellule.appendChild(input);
    });

    const totalCellule = row.insertCell();
    totalCellule.className = 'ligne-total fw-bold';
    totalCellule.dataset.montant = totalLigne;
    totalCellule.textContent = totalLigne.toFixed(2);
    const actionCellule = row.insertCell();
    actionCellule.className = 'text-center';
    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'btn btn-outline-danger btn-sm';
    button.setAttribute('aria-label', 'Supprimer la ligne');
    const icone = document.createElement('i');
    icone.className = 'fas fa-trash';
    button.appendChild(icone);
    button.addEventListener('click', () => supprimerLigne(button));
    actionCellule.appendChild(button);
    tbody.appendChild(row);
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
    let total = 0;
    document.querySelectorAll('.ligne-total').forEach(td => {
        total += Number(td.dataset.montant);
    });

    document.getElementById('totalGeneral').value = total.toFixed(2);

    // Suggestion : Par défaut on met le montant versé égal au total
    const vInput = document.getElementById('montantVerse');
    if (vInput.dataset.saisieManuelle !== 'true') {
        vInput.value = total.toFixed(2);
    }

    calculerReste();
}

function calculerReste() {
    const total = parseFloat(document.getElementById('totalGeneral').value) || 0;
    const verse = parseFloat(document.getElementById('montantVerse').value) || 0;
    const reste = total - verse;

    const resteInput = document.getElementById('resteAPayer');
    resteInput.value = reste.toFixed(2);

    // Style visuel si dette
    if (reste > 0) {
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
    }
});

document.addEventListener('submit', function(event) {
    if (event.target.id !== 'achatForm') return;
    if (document.querySelectorAll('#tableLignes tbody tr').length === 0) {
        event.preventDefault();
        alert("L'achat doit contenir au moins un produit.");
        return;
    }
    reindexerLignes();
});
