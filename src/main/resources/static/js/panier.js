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
    // 1. Mise à jour de l'affichage total
    document.getElementById('totalVente').innerText = totalGeneral.toLocaleString();

    // 2. Mise à jour de l'input caché pour l'envoi vers Spring Boot
    document.getElementById('inputTotalTotal').value = totalGeneral;

    // 3. Mise à jour du montant versé (on suggère le total par défaut)
    const verseInput = document.getElementById('montantVerse');
    verseInput.value = totalGeneral;

    calculerReste();
}

function calculerReste() {
    const total = totalGeneral;
    const verse = parseFloat(document.getElementById('montantVerse').value) || 0;
    const reste = total - verse;

    const resteInput = document.getElementById('resteAPayer');
    resteInput.value = reste;

    // Feedback visuel
    if (reste > 0) {
        resteInput.classList.add('text-danger', 'fw-bold');
    } else {
        resteInput.classList.remove('text-danger', 'fw-bold');
    }
}

// Initialisation au chargement
document.addEventListener('DOMContentLoaded', function() {
    const verseInput = document.getElementById('montantVerse');
    if (verseInput) {
        verseInput.addEventListener('input', calculerReste);
    }
});

// Re-indexation avant soumission
document.addEventListener('submit', function(event) {
    if (event.target.id === 'formVente') {
        const rows = document.querySelectorAll('#panierBody tr');
        if (rows.length === 0) {
            event.preventDefault();
            alert("Le panier est vide !");
            return;
        }
        rows.forEach((row, newIndex) => {
            row.querySelector('input[name*=".produit.id"]').name = `lignes[${newIndex}].produit.id`;
            row.querySelector('input[name*=".quantite"]').name = `lignes[${newIndex}].quantite`;
        });
    }
});