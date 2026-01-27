let index = 0;
let totalGeneral = 0;

function ajouterAuPanier() {
    const select = document.getElementById('selectProduit');
    const qtyInput = document.getElementById('inputQuantite');

    const pId = select.value;
    const selectedOption = select.options[select.selectedIndex];

    if (!pId || pId === "") return;

    const pNom = selectedOption.getAttribute('data-nom');
    const pPrix = parseFloat(selectedOption.getAttribute('data-prix'));
    const qty = parseInt(qtyInput.value);

    // Extraction du stock
    const textActuel = selectedOption.text;
    const match = textActuel.match(/\((\d+) en stock\)/);
    const stockActuel = match ? parseInt(match[1]) : 0;

    if (qty > stockActuel) {
        alert("Stock insuffisant !");
        return;
    }
    if (qty <= 0) return;

    // Mise à jour visuelle du stock
    const nouveauStock = stockActuel - qty;
    selectedOption.text = `${pNom} (${nouveauStock} en stock)`;

    const sousTotal = pPrix * qty;
    const tbody = document.getElementById('panierBody');

    const row = document.createElement('tr');
    row.id = `row-${index}`;
    // IMPORTANT : On garde le montant pur (sans GNF) dans l'attribut data-montant pour le calcul
    row.innerHTML = `
        <td>${pNom} <input type="hidden" name="lignes[${index}].produit.id" value="${pId}"></td>
        <td>${pPrix.toLocaleString()} GNF</td>
        <td>${qty} <input type="hidden" name="lignes[${index}].quantite" value="${qty}"></td>
        <td class="sous-total-val" data-montant="${sousTotal}">${sousTotal.toLocaleString()} GNF</td>
        <td>
            <button type="button" class="btn btn-danger btn-sm" onclick="supprimerLigne(${index}, ${sousTotal}, '${pId}', ${qty})">
                <i class="fas fa-trash"></i>
            </button>
        </td>
    `;

    tbody.appendChild(row);
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
                const stockActuel = match ? parseInt(match[1]) : 0;
                opt.text = `${pNom} (${stockActuel + qteARendre} en stock)`;
                break;
            }
        }
    }
}

function actualiserAffichageTotal() {
    // On met à jour l'affichage avec GNF, mais on garde totalGeneral en nombre pur
    document.getElementById('totalVente').innerText = totalGeneral.toLocaleString();
}

// Sécurité pour la soumission
document.addEventListener('submit', function(event) {
    if (event.target.id === 'formVente') {
        const rows = document.querySelectorAll('#panierBody tr');

        if (rows.length === 0) {
            event.preventDefault();
            alert("Le panier est vide !");
            return;
        }

        // --- RE-INDEXATION DES LIGNES ---
        rows.forEach((row, newIndex) => {
            // On trouve l'input du produit ID
            const inputId = row.querySelector('input[name*=".produit.id"]');
            if (inputId) inputId.name = `lignes[${newIndex}].produit.id`;

            // On trouve l'input de la quantité
            const inputQty = row.querySelector('input[name*=".quantite"]');
            if (inputQty) inputQty.name = `lignes[${newIndex}].quantite`;
        });
    }
});