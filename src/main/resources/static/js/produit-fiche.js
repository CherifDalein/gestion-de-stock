(() => {
    'use strict';

    // Affiche les champs propres à la nature choisie. Les champs masqués sont désactivés :
    // ils ne sont ni validés par le navigateur ni envoyés au serveur.
    function initialiser() {
        const formulaire = document.querySelector('form[data-fiche-produit]');
        if (!formulaire) return;
        const choix = Array.from(formulaire.querySelectorAll('input[data-nature-choice]'));
        const panneaux = Array.from(formulaire.querySelectorAll('[data-nature-panel]'));
        if (choix.length === 0 || panneaux.length === 0) return;

        function appliquer() {
            const nature = choix.find(bouton => bouton.checked)?.value ?? 'STOCK';
            panneaux.forEach(panneau => {
                const actif = panneau.dataset.naturePanel === nature;
                panneau.hidden = !actif;
                panneau.querySelectorAll('input, select, textarea').forEach(champ => { champ.disabled = !actif; });
            });
        }

        choix.forEach(bouton => bouton.addEventListener('change', appliquer));
        appliquer();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
