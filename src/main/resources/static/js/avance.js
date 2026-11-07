// Avance disponible du partenaire choisi (client sur la vente, fournisseur sur l'achat).
// L'information vient de GET {url}/{id}/avance ; le serveur recalcule l'avance sous verrou à l'enregistrement.
document.addEventListener('DOMContentLoaded', function() {
    const select = document.querySelector('select[data-avance-url]');
    if (!select || typeof definirAvanceDisponible !== 'function') return;
    let demande = 0;
    const charger = async () => {
        const numero = ++demande;
        if (!select.value) {
            definirAvanceDisponible(0n);
            return;
        }
        try {
            const reponse = await fetch(`${select.dataset.avanceUrl}/${encodeURIComponent(select.value)}/avance`,
                {headers: {'Accept': 'application/json'}, credentials: 'same-origin'});
            if (!reponse.ok) throw new Error('avance indisponible');
            const avance = Montants.lire((await reponse.json()).avance);
            // Une réponse tardive d'un partenaire précédemment choisi est ignorée.
            if (numero === demande) definirAvanceDisponible(avance ?? 0n);
        } catch (erreur) {
            if (numero === demande) definirAvanceDisponible(0n);
        }
    };
    select.addEventListener('change', charger);
    charger();
});
