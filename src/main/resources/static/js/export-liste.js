/*
 * Boutons d'export d'une liste (Excel, PDF) : ils exportent la liste filtrée côté serveur. Les filtres et la recherche que la page
 * applique dans le navigateur sont ajoutés au lien d'export pour que le fichier contienne seulement les lignes encore visibles.
 *
 * - Par défaut (tableau simple-datatables), le texte de la barre de recherche est ajouté sous « q ».
 * - Une page qui filtre avec ses propres champs les déclare sur le lien : data-export-champs="q=#recherche;stock=#etat;source=#src@libelle"
 *   (« paramètre=#identifiant-du-champ » séparés par « ; » ; « @libelle » envoie le texte de l'option choisie au lieu de sa valeur).
 * Tout passe par URLSearchParams : jamais inséré comme HTML. Un champ vide n'ajoute aucun paramètre.
 */
(function (global) {
    'use strict';

    const LONGUEUR_MAXIMALE = 100;

    /** Lien d'export avec les valeurs données (objet nom -> texte) : retire d'abord les paramètres du même nom, n'ajoute pas les vides. */
    function lienAvecParametres(base, valeurs) {
        const adresse = new URL(base, 'http://localhost');
        Object.keys(valeurs || {}).forEach(nom => {
            adresse.searchParams.delete(nom);
            const texte = String(valeurs[nom] == null ? '' : valeurs[nom]).trim();
            if (texte) adresse.searchParams.set(nom, texte.slice(0, LONGUEUR_MAXIMALE));
        });
        return adresse.pathname + adresse.search;
    }

    /** Lien d'export avec la recherche : retire un « q » précédent, ajoute le nouveau s'il n'est pas vide. */
    function lienAvecRecherche(base, recherche) {
        return lienAvecParametres(base, { q: recherche });
    }

    /** Lit « nom=#champ;autre=#champ@libelle » ; les éléments mal formés sont ignorés (seuls les sélecteurs d'identifiant sont admis). */
    function analyserChamps(definition) {
        return String(definition || '').split(';').map(partie => partie.trim()).filter(Boolean).map(partie => {
            const trouve = /^([a-z][a-z0-9_]*)=#([A-Za-z][\w-]*)(@libelle)?$/.exec(partie);
            return trouve ? { nom: trouve[1], identifiant: trouve[2], libelle: Boolean(trouve[3]) } : null;
        }).filter(Boolean);
    }

    /** Valeur actuelle d'un champ déclaré ; « @libelle » : texte de l'option choisie, vide si le choix « tous » (valeur vide) est sélectionné. */
    function valeurDuChamp(champ, document) {
        const element = document.getElementById(champ.identifiant);
        if (!element) return '';
        if (!champ.libelle) return element.value || '';
        if (!element.value) return '';
        const option = element.selectedOptions && element.selectedOptions[0];
        return option ? option.textContent : '';
    }

    function valeursDuLien(lien, document) {
        const champs = analyserChamps(lien.dataset.exportChamps);
        if (champs.length === 0) {
            const recherche = document.querySelector('.datatable-input');
            return { q: recherche ? recherche.value : '' };
        }
        const valeurs = {};
        champs.forEach(champ => { valeurs[champ.nom] = valeurDuChamp(champ, document); });
        return valeurs;
    }

    function installer(document) {
        const liens = Array.from(document.querySelectorAll('a[data-export-liste]'));
        if (liens.length === 0) return;
        liens.forEach(lien => { lien.dataset.exportBase = lien.getAttribute('href'); });
        const mettreAJour = () => {
            liens.forEach(lien => lien.setAttribute('href', lienAvecParametres(lien.dataset.exportBase, valeursDuLien(lien, document))));
        };
        // Les boutons « Réinitialiser » des pages changent les champs sans événement : le lien est aussi recalculé au moment de l'utiliser.
        liens.forEach(lien => ['click', 'mousedown', 'contextmenu', 'focus', 'touchstart'].forEach(nom => lien.addEventListener(nom, mettreAJour)));
        ['input', 'change'].forEach(nom => document.addEventListener(nom, mettreAJour));
        mettreAJour();
    }

    const api = { lienAvecRecherche, lienAvecParametres, analyserChamps, valeurDuChamp, installer };
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else {
        global.ExportListe = api;
        installer(global.document);
    }
})(typeof window !== 'undefined' ? window : globalThis);
