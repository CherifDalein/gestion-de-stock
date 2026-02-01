let index = 0;

function ajouterLigne() {
    const select = document.getElementById('selectProduit');
    const pId = select.value;
    const pNom = select.options[select.selectedIndex].getAttribute('data-nom');
    const prix = document.getElementById('inputPrix').value;
    const qte = document.getElementById('inputQtite').value;

    if (!pId || !prix || !qte || qte <= 0) {
        alert("Veuillez remplir correctement tous les champs.");
        return;
    }

    const tbody = document.querySelector('#tableLignes tbody');
    const totalLigne = parseFloat(prix) * parseFloat(qte);

    const row = `
        <tr>
            <td>
                ${pNom}
                <input type="hidden" name="lignes[${index}].produit.id" value="${pId}">
            </td>
            <td>
                <input type="number" name="lignes[${index}].prixAchatUnitaire" value="${prix}" class="form-control form-control-sm" readonly>
            </td>
            <td>
                <input type="number" name="lignes[${index}].quantite" value="${qte}" class="form-control form-control-sm" readonly>
            </td>
            <td class="ligne-total fw-bold">${totalLigne.toFixed(2)}</td>
            <td class="text-center">
                <button type="button" class="btn btn-outline-danger btn-sm" onclick="supprimerLigne(this)">
                    <i class="fas fa-trash"></i>
                </button>
            </td>
        </tr>
    `;

    tbody.insertAdjacentHTML('beforeend', row);
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
    calculerTotal();
}

function calculerTotal() {
    let total = 0;
    document.querySelectorAll('.ligne-total').forEach(td => {
        total += parseFloat(td.innerText);
    });

    document.getElementById('totalGeneral').value = total.toFixed(2);

    // Suggestion : Par défaut on met le montant versé égal au total
    const vInput = document.getElementById('montantVerse');
    if (vInput.value === "" || parseFloat(vInput.value) === 0) {
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
        verseInput.addEventListener('input', calculerReste);
    }
});