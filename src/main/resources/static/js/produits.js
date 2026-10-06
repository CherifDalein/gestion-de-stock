(() => {
    'use strict';

    const normaliser = valeur => String(valeur ?? '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr-FR')
        .replace(/œ/g, 'oe').replace(/æ/g, 'ae').trim().replace(/\s+/g, ' ');
    const collateur = new Intl.Collator('fr-FR', { numeric: true, sensitivity: 'base' });
    const nombres = new Intl.NumberFormat('fr-FR');
    const lireQuantite = valeur => /^\d{1,19}$/.test(String(valeur ?? '')) ? BigInt(valeur) : null;
    const lireMontant = valeur => {
        const match = /^(\d{1,15})(?:\.(\d{1,2}))?$/.exec(String(valeur ?? ''));
        return match ? BigInt(match[1]) * 100n + BigInt((match[2] ?? '').padEnd(2, '0')) : null;
    };
    const afficherMontant = valeur => valeur === null ? 'Non renseigné'
        : `${nombres.format(valeur / 100n)},${String(valeur % 100n).padStart(2, '0')} GNF`;
    const etatStock = quantite => quantite === null ? 'inconnu'
        : quantite === 0n ? 'rupture' : quantite <= 5n ? 'faible' : 'disponible';
    const libellesStock = Object.freeze({
        disponible: 'Disponible', faible: 'Stock faible', rupture: 'Rupture de stock',
        inconnu: 'Stock non renseigné'
    });

    function initialiser() {
        const page = document.getElementById('stockProduits');
        if (!page) return;
        const table = document.getElementById('produitsTable');
        const lignes = Array.from(page.querySelectorAll('tr[data-product-row]'));
        if (!table || lignes.length === 0) return;

        const recherche = document.getElementById('produitsSearch');
        const categorie = document.getElementById('produitsCategory');
        const stock = document.getElementById('produitsStock');
        const taillePage = document.getElementById('produitsPageSize');
        const precedent = document.getElementById('produitsPrevious');
        const suivant = document.getElementById('produitsNext');
        const libellePage = document.getElementById('produitsPageLabel');
        const resultats = document.getElementById('produitsResults');
        const tableWrap = document.getElementById('produitsTableWrap');
        const aucunResultat = document.getElementById('produitsNoResults');
        const boutonsStock = Array.from(page.querySelectorAll('[data-stock-filter]'));
        const boutonsTri = Array.from(page.querySelectorAll('[data-sort]'));
        const trisAutorises = new Set(['reference', 'nom', 'categorie', 'prixAchat', 'prixVente', 'quantite']);
        const stocksAutorises = new Set(['', 'en-stock', 'disponible', 'faible', 'rupture']);
        const taillesAutorisees = new Set(['10', '25', '50', 'all']);
        const tailleInitiale = taillesAutorisees.has(taillePage?.value) ? taillePage.value : '25';
        const donnees = lignes.map((ligne, ordre) => {
            const data = ligne.dataset;
            const quantite = lireQuantite(data.quantity);
            return {
                ligne, ordre, reference: data.reference ?? '', nom: data.name ?? '',
                categorie: data.categoryName || 'Sans catégorie', categorieId: data.categoryId ?? '',
                fournisseur: data.supplier || 'Non défini', quantite,
                prixAchat: lireMontant(data.purchasePrice), prixVente: lireMontant(data.salePrice),
                etat: etatStock(quantite),
                recherche: normaliser([data.name, data.reference, data.categoryName || 'Sans catégorie', data.supplier].join(' '))
            };
        });
        const parLigne = new Map(donnees.map(produit => [produit.ligne, produit]));
        const categories = new Map();
        donnees.forEach(produit => {
            if (produit.categorieId && !categories.has(produit.categorieId)) {
                categories.set(produit.categorieId, produit.categorie);
            }
        });
        if (categorie) {
            Array.from(categorie.options).filter(option => option.value !== '').forEach(option => option.remove());
            Array.from(categories.entries()).sort((a, b) => collateur.compare(a[1], b[1]))
                .forEach(([id, nom]) => {
                    const option = document.createElement('option');
                    option.value = id;
                    option.textContent = nom;
                    categorie.appendChild(option);
                });
            categorie.value = '';
        }
        let numeroPage = 1;
        let nombrePages = 1;
        let cleTri = 'reference';
        let sensTri = 1;

        function comparer(a, b) {
            const valeurA = a[cleTri];
            const valeurB = b[cleTri];
            // Les valeurs absentes restent en fin de liste dans les deux sens de tri.
            if (valeurA === null && valeurB !== null) return 1;
            if (valeurB === null && valeurA !== null) return -1;
            let comparaison = 0;
            if (typeof valeurA === 'bigint' && typeof valeurB === 'bigint') {
                comparaison = valeurA < valeurB ? -1 : valeurA > valeurB ? 1 : 0;
            } else if (valeurA !== null && valeurB !== null) {
                comparaison = collateur.compare(String(valeurA), String(valeurB));
            }
            return comparaison * sensTri || a.ordre - b.ordre;
        }

        function afficher() {
            const mots = normaliser(recherche?.value).split(' ').filter(Boolean);
            const categorieChoisie = categories.has(categorie?.value) ? categorie.value : '';
            const stockChoisi = stocksAutorises.has(stock?.value) ? stock.value : '';
            const tailleChoisie = taillesAutorisees.has(taillePage?.value) ? taillePage.value : tailleInitiale;
            const filtres = donnees.filter(produit =>
                mots.every(mot => produit.recherche.includes(mot))
                && (!categorieChoisie || produit.categorieId === categorieChoisie)
                && (!stockChoisi || (stockChoisi === 'en-stock'
                    ? produit.quantite !== null && produit.quantite > 0n : produit.etat === stockChoisi)))
                .sort(comparer);
            const taille = tailleChoisie === 'all' ? Math.max(filtres.length, 1) : Number(tailleChoisie);
            nombrePages = Math.max(1, Math.ceil(filtres.length / taille));
            numeroPage = Math.min(Math.max(numeroPage, 1), nombrePages);
            const debut = (numeroPage - 1) * taille;
            const visibles = new Set(filtres.slice(debut, debut + taille));
            const fragment = document.createDocumentFragment();
            // Déplacer les nœuds préserve les formulaires, leurs jetons CSRF et leurs écouteurs.
            donnees.slice().sort(comparer).forEach(produit => {
                produit.ligne.hidden = !visibles.has(produit);
                fragment.appendChild(produit.ligne);
            });
            table.tBodies[0].appendChild(fragment);
            if (tableWrap) tableWrap.hidden = filtres.length === 0;
            if (aucunResultat) aucunResultat.hidden = filtres.length !== 0;
            if (precedent) precedent.disabled = numeroPage <= 1;
            if (suivant) suivant.disabled = numeroPage >= nombrePages;
            if (libellePage) libellePage.textContent = `Page ${nombres.format(numeroPage)} sur ${nombres.format(nombrePages)}`;
            if (resultats) {
                resultats.textContent = filtres.length === 0 ? 'Aucun produit ne correspond à ces critères.'
                    : `${nombres.format(filtres.length)} produit${filtres.length > 1 ? 's' : ''} · `
                    + `Affichage de ${nombres.format(debut + 1)} à ${nombres.format(Math.min(debut + taille, filtres.length))}`;
            }
            boutonsStock.forEach(bouton => bouton.setAttribute('aria-pressed', String(bouton.dataset.stockFilter === stockChoisi)));
            boutonsTri.forEach(bouton => {
                const actif = bouton.dataset.sort === cleTri;
                bouton.closest('th')?.setAttribute('aria-sort', actif ? (sensTri === 1 ? 'ascending' : 'descending') : 'none');
                bouton.dataset.direction = actif ? (sensTri === 1 ? 'asc' : 'desc') : '';
            });
        }

        const filtrer = () => { numeroPage = 1; afficher(); };
        recherche?.addEventListener('input', filtrer);
        categorie?.addEventListener('change', filtrer);
        stock?.addEventListener('change', filtrer);
        taillePage?.addEventListener('change', filtrer);
        boutonsStock.forEach(bouton => {
            if (!stocksAutorises.has(bouton.dataset.stockFilter)) return;
            bouton.disabled = false;
            bouton.addEventListener('click', () => {
                if (stock) stock.value = bouton.dataset.stockFilter;
                filtrer();
            });
        });
        boutonsTri.forEach(bouton => {
            if (!trisAutorises.has(bouton.dataset.sort)) return;
            bouton.disabled = false;
            bouton.addEventListener('click', () => {
                const nouvelleCle = bouton.dataset.sort;
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
        page.querySelectorAll('#produitsReset, [data-reset-products]').forEach(bouton => {
            bouton.addEventListener('click', () => {
                if (recherche) recherche.value = '';
                if (categorie) categorie.value = '';
                if (stock) stock.value = '';
                if (taillePage) taillePage.value = tailleInitiale;
                cleTri = 'reference';
                sensTri = 1;
                filtrer();
            });
        });

        const details = document.getElementById('produitDetailsDialog');
        page.querySelectorAll('[data-product-details]').forEach(bouton => {
            if (!details || typeof details.showModal !== 'function') return;
            bouton.hidden = false;
            bouton.addEventListener('click', () => {
                const produit = parLigne.get(bouton.closest('tr[data-product-row]'));
                if (!produit) return;
                const valeurs = {
                    reference: produit.reference || 'Non renseignée', categorie: produit.categorie,
                    quantite: produit.quantite === null ? 'Non renseignée' : nombres.format(produit.quantite),
                    etat: libellesStock[produit.etat], prixVente: afficherMontant(produit.prixVente),
                    prixAchat: afficherMontant(produit.prixAchat), fournisseur: produit.fournisseur
                };
                const titre = document.getElementById('produitDetailsTitle');
                if (titre) titre.textContent = produit.nom;
                details.querySelectorAll('[data-detail]').forEach(champ => {
                    if (Object.hasOwn(valeurs, champ.dataset.detail)) champ.textContent = valeurs[champ.dataset.detail];
                });
                details.showModal();
            });
        });

        const suppression = document.getElementById('produitDeleteDialog');
        const confirmer = document.getElementById('produitDeleteConfirm');
        let formulaireEnAttente = null;
        let suppressionEnvoyee = false;
        const dialogueSuppression = suppression && confirmer && typeof suppression.showModal === 'function';
        page.querySelectorAll('form[data-product-delete]').forEach(formulaire => {
            formulaire.addEventListener('submit', event => {
                if (!dialogueSuppression) {
                    if (!window.confirm('Supprimer ce produit ?')) event.preventDefault();
                    return;
                }
                event.preventDefault();
                if (suppressionEnvoyee || suppression.open) return;
                const produit = parLigne.get(formulaire.closest('tr[data-product-row]'));
                formulaireEnAttente = formulaire;
                const nom = document.getElementById('produitDeleteName');
                if (nom) nom.textContent = produit?.nom ?? 'ce produit';
                confirmer.disabled = false;
                suppression.showModal();
            });
            formulaire.removeAttribute('onsubmit');
        });
        if (dialogueSuppression) {
            confirmer.addEventListener('click', () => {
                const formulaire = formulaireEnAttente;
                if (!formulaire || suppressionEnvoyee || !formulaire.reportValidity()) return;
                suppressionEnvoyee = true;
                confirmer.disabled = true;
                formulaireEnAttente = null;
                suppression.close();
                HTMLFormElement.prototype.submit.call(formulaire);
            });
            suppression.addEventListener('close', () => {
                formulaireEnAttente = null;
                if (!suppressionEnvoyee) confirmer.disabled = false;
            });
        }
        page.querySelectorAll('[data-close-dialog]').forEach(bouton => {
            bouton.addEventListener('click', () => bouton.closest('dialog')?.close());
        });
        const toolbar = document.getElementById('produitsToolbar');
        const pagination = document.getElementById('produitsPagination');
        if (toolbar) toolbar.hidden = false;
        if (pagination) pagination.hidden = false;
        afficher();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
