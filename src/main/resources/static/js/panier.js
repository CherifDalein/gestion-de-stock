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

    if (qty <= 0) {
        alert("La quantité doit être supérieure à 0");
        return;
    }

    const sousTotal = pPrix * qty;
    const tbody = document.getElementById('panierBody');

    const row = document.createElement('tr');
    row.id = `row-${index}`;
    row.innerHTML = `
        <td>${pNom} <input type="hidden" name="lignes[${index}].produit.id" value="${pId}"></td>
        <td>${pPrix.toLocaleString()} GNF</td>
        <td>${qty} <input type="hidden" name="lignes[${index}].quantite" value="${qty}"></td>
        <td class="sous-total">${sousTotal.toLocaleString()} GNF</td>
        <td>
            <button type="button" class="btn btn-danger btn-sm" onclick="supprimerLigne(${index}, ${sousTotal})">
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

function supprimerLigne(idx, montant) {
    const row = document.getElementById('row-' + idx);
    if (row) {
        row.remove();
        totalGeneral -= montant;
        actualiserAffichageTotal();
    }
}

function actualiserAffichageTotal() {
    document.getElementById('totalVente').innerText = totalGeneral.toLocaleString();
}

document.addEventListener('DOMContentLoaded', function() {
    const form = document.getElementById('formVente');
    if (form) {
        form.addEventListener('submit', function(event) {
            const panierBody = document.getElementById('panierBody');
            if (!panierBody || panierBody.children.length === 0) {
                event.preventDefault();
                alert("Votre panier est vide ! Ajoutez au moins un produit avant de valider.");
            }
        });
    }
});