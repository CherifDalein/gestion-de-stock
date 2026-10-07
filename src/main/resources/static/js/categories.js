(() => {
    'use strict';

    const normaliser = valeur => String(valeur ?? '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr-FR')
        .replace(/œ/g, 'oe').replace(/æ/g, 'ae').trim().replace(/\s+/g, ' ');
    const collateur = new Intl.Collator('fr-FR', { numeric: true, sensitivity: 'base' });
    const nombres = new Intl.NumberFormat('fr-FR');

    function initialiser() {
        const page = document.getElementById('stockCategories');
        if (!page) return;
        const table = document.getElementById('categoriesTable');
        const lignes = Array.from(page.querySelectorAll('tr[data-category-row]'));
        if (!table || lignes.length === 0) return;

        const recherche = document.getElementById('categoriesSearch');
        const tri = document.getElementById('categoriesSort');
        const taillePage = document.getElementById('categoriesPageSize');
        const precedent = document.getElementById('categoriesPrevious');
        const suivant = document.getElementById('categoriesNext');
        const libellePage = document.getElementById('categoriesPageLabel');
        const resultats = document.getElementById('categoriesResults');
        const tableWrap = document.getElementById('categoriesTableWrap');
        const aucunResultat = document.getElementById('categoriesNoResults');
        const boutonsTri = Array.from(page.querySelectorAll('[data-category-sort]'));
        const taillesAutorisees = new Set(['10', '25', '50', 'all']);
        const donnees = lignes.map((ligne, ordre) => ({
            ligne, ordre, id: ligne.dataset.id ?? '', nom: ligne.dataset.name ?? '',
            recherche: normaliser(`${ligne.dataset.id ?? ''} ${ligne.dataset.name ?? ''}`)
        }));
        let numeroPage = 1;
        let nombrePages = 1;
        let cleTri = 'nom';
        let sensTri = 1;

        function comparer(a, b) {
            return collateur.compare(a[cleTri], b[cleTri]) * sensTri || a.ordre - b.ordre;
        }

        function afficher() {
            const mots = normaliser(recherche?.value).split(' ').filter(Boolean);
            const filtres = donnees.filter(categorie => mots.every(mot => categorie.recherche.includes(mot))).sort(comparer);
            const tailleChoisie = taillesAutorisees.has(taillePage?.value) ? taillePage.value : '25';
            const taille = tailleChoisie === 'all' ? Math.max(filtres.length, 1) : Number(tailleChoisie);
            nombrePages = Math.max(1, Math.ceil(filtres.length / taille));
            numeroPage = Math.min(Math.max(numeroPage, 1), nombrePages);
            const debut = (numeroPage - 1) * taille;
            const visibles = new Set(filtres.slice(debut, debut + taille));
            const fragment = document.createDocumentFragment();
            // Conserver les nœuds maintient les formulaires et leurs jetons CSRF.
            donnees.slice().sort(comparer).forEach(categorie => {
                categorie.ligne.hidden = !visibles.has(categorie);
                fragment.appendChild(categorie.ligne);
            });
            table.tBodies[0].appendChild(fragment);
            if (tableWrap) tableWrap.hidden = filtres.length === 0;
            if (aucunResultat) aucunResultat.hidden = filtres.length !== 0;
            if (precedent) precedent.disabled = numeroPage <= 1;
            if (suivant) suivant.disabled = numeroPage >= nombrePages;
            if (libellePage) libellePage.textContent = `Page ${nombres.format(numeroPage)} sur ${nombres.format(nombrePages)}`;
            if (resultats) resultats.textContent = filtres.length === 0 ? 'Aucune catégorie ne correspond à cette recherche.'
                : `${nombres.format(filtres.length)} catégorie${filtres.length > 1 ? 's' : ''} · `
                    + `Affichage de ${nombres.format(debut + 1)} à ${nombres.format(Math.min(debut + taille, filtres.length))}`;
            boutonsTri.forEach(bouton => {
                const actif = bouton.dataset.categorySort === cleTri;
                bouton.closest('th')?.setAttribute('aria-sort', actif ? (sensTri === 1 ? 'ascending' : 'descending') : 'none');
                bouton.dataset.direction = actif ? (sensTri === 1 ? 'asc' : 'desc') : '';
            });
            if (tri) tri.value = `${cleTri}-${sensTri === 1 ? 'asc' : 'desc'}`;
        }

        const filtrer = () => { numeroPage = 1; afficher(); };
        recherche?.addEventListener('input', filtrer);
        taillePage?.addEventListener('change', filtrer);
        tri?.addEventListener('change', () => {
            if (!['nom-asc', 'nom-desc', 'id-asc', 'id-desc'].includes(tri.value)) return;
            [cleTri] = tri.value.split('-');
            sensTri = tri.value.endsWith('-asc') ? 1 : -1;
            filtrer();
        });
        boutonsTri.forEach(bouton => {
            if (!['id', 'nom'].includes(bouton.dataset.categorySort)) return;
            bouton.disabled = false;
            bouton.addEventListener('click', () => {
                const nouvelleCle = bouton.dataset.categorySort;
                sensTri = nouvelleCle === cleTri ? -sensTri : 1;
                cleTri = nouvelleCle;
                filtrer();
            });
        });
        precedent?.addEventListener('click', () => {
            if (numeroPage > 1) { numeroPage--; afficher(); }
        });
        suivant?.addEventListener('click', () => {
            if (numeroPage < nombrePages) { numeroPage++; afficher(); }
        });
        page.querySelectorAll('#categoriesReset, [data-reset-categories]').forEach(bouton => {
            bouton.addEventListener('click', () => {
                if (recherche) recherche.value = '';
                if (taillePage) taillePage.value = '25';
                cleTri = 'nom';
                sensTri = 1;
                filtrer();
            });
        });

        const suppression = document.getElementById('categoryDeleteDialog');
        const confirmer = document.getElementById('categoryDeleteConfirm');
        const annuler = document.getElementById('categoryDeleteCancel');
        const nom = document.getElementById('categoryDeleteName');
        const dialogueDisponible = suppression && confirmer && annuler && typeof suppression.showModal === 'function';
        let formulaireEnAttente = null;
        let declencheur = null;
        let suppressionEnvoyee = false;
        page.querySelectorAll('form[data-category-delete]').forEach(formulaire => {
            formulaire.addEventListener('submit', event => {
                if (suppressionEnvoyee) {
                    event.preventDefault();
                    return;
                }
                const nomCategorie = formulaire.dataset.categoryName || 'cette catégorie';
                if (!dialogueDisponible) {
                    if (!window.confirm(`Supprimer la catégorie « ${nomCategorie} » ?`)) event.preventDefault();
                    else suppressionEnvoyee = true;
                    return;
                }
                event.preventDefault();
                if (suppression.open) return;
                formulaireEnAttente = formulaire;
                declencheur = event.submitter || formulaire.querySelector('button[type="submit"]');
                if (nom) nom.textContent = nomCategorie;
                confirmer.disabled = false;
                suppression.showModal();
                annuler.focus();
            });
            formulaire.removeAttribute('onsubmit');
        });
        if (dialogueDisponible) {
            annuler.addEventListener('click', () => suppression.close());
            confirmer.addEventListener('click', () => {
                const formulaire = formulaireEnAttente;
                if (!formulaire || suppressionEnvoyee || !formulaire.reportValidity()) return;
                suppressionEnvoyee = true;
                confirmer.disabled = true;
                suppression.close();
                HTMLFormElement.prototype.submit.call(formulaire);
            });
            // Le dialogue natif gère Échap et enferme le focus pendant la confirmation.
            suppression.addEventListener('close', () => {
                formulaireEnAttente = null;
                if (!suppressionEnvoyee) confirmer.disabled = false;
                if (declencheur?.isConnected) declencheur.focus();
                declencheur = null;
            });
        }
        const toolbar = document.getElementById('categoriesToolbar');
        const pagination = document.getElementById('categoriesPagination');
        if (toolbar) toolbar.hidden = false;
        if (pagination) pagination.hidden = false;
        afficher();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
