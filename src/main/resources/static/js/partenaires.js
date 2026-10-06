(() => {
    'use strict';

    const normaliser = valeur => String(valeur ?? '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr-FR')
        .replace(/œ/g, 'oe').replace(/æ/g, 'ae').trim().replace(/\s+/g, ' ');
    const chiffresTelephone = valeur => String(valeur ?? '').replace(/\D/g, '');
    const collateur = new Intl.Collator('fr-FR', { numeric: true, sensitivity: 'base' });
    const nombres = new Intl.NumberFormat('fr-FR');

    function initialiser() {
        const page = document.getElementById('stockPartenaires');
        if (!page) return;
        const table = document.getElementById('partenairesTable');
        const lignes = Array.from(page.querySelectorAll('tr[data-partner-row]'));
        if (!table || lignes.length === 0) return;

        const recherche = document.getElementById('partenairesSearch');
        const contact = document.getElementById('partenairesContact');
        const taillePage = document.getElementById('partenairesPageSize');
        const precedent = document.getElementById('partenairesPrevious');
        const suivant = document.getElementById('partenairesNext');
        const libellePage = document.getElementById('partenairesPageLabel');
        const resultats = document.getElementById('partenairesResults');
        const tableWrap = document.getElementById('partenairesTableWrap');
        const aucunResultat = document.getElementById('partenairesNoResults');
        const boutonsContact = Array.from(page.querySelectorAll('[data-contact-filter]'));
        const boutonsTri = Array.from(page.querySelectorAll('[data-partner-sort]'));
        const trisAutorises = new Set(['nom', 'telephone', 'email', 'adresse']);
        const contactsAutorises = new Set(['', 'telephone', 'email', 'sans-contact']);
        const taillesAutorisees = new Set(['10', '25', '50', 'all']);
        const tailleInitiale = taillesAutorisees.has(taillePage?.value) ? taillePage.value : '25';
        const libellePartenaire = page.dataset.partnerLabel || (page.dataset.partnerType === 'fournisseur' ? 'fournisseur' : 'client');
        const donnees = lignes.map((ligne, ordre) => {
            const data = ligne.dataset;
            const telephone = data.telephone ?? '';
            const email = data.email ?? '';
            return {
                ligne, ordre, nom: data.name ?? '', telephone, email, adresse: data.adresse ?? '',
                avecTelephone: telephone.trim().length > 0, avecEmail: email.trim().length > 0,
                telephoneChiffres: chiffresTelephone(telephone),
                recherche: normaliser([data.name, telephone, email, data.adresse].join(' '))
            };
        });
        const parLigne = new Map(donnees.map(partenaire => [partenaire.ligne, partenaire]));
        let numeroPage = 1;
        let nombrePages = 1;
        let cleTri = 'nom';
        let sensTri = 1;

        function comparer(a, b) {
            const valeurA = a[cleTri].trim();
            const valeurB = b[cleTri].trim();
            // Les champs vides restent en fin de liste dans les deux sens de tri.
            if (!valeurA && valeurB) return 1;
            if (!valeurB && valeurA) return -1;
            return collateur.compare(valeurA, valeurB) * sensTri || a.ordre - b.ordre;
        }

        function correspondRecherche(partenaire, mots) {
            return mots.every(mot => partenaire.recherche.includes(mot)
                || (/^[+\d().\/-]+$/.test(mot) && /\d/.test(mot)
                    && partenaire.telephoneChiffres.includes(chiffresTelephone(mot))));
        }

        function afficher() {
            const mots = normaliser(recherche?.value).split(' ').filter(Boolean);
            const contactChoisi = contactsAutorises.has(contact?.value) ? contact.value : '';
            const tailleChoisie = taillesAutorisees.has(taillePage?.value) ? taillePage.value : tailleInitiale;
            const filtres = donnees.filter(partenaire => correspondRecherche(partenaire, mots)
                && (!contactChoisi || (contactChoisi === 'telephone' ? partenaire.avecTelephone
                    : contactChoisi === 'email' ? partenaire.avecEmail
                        : !partenaire.avecTelephone && !partenaire.avecEmail))).sort(comparer);
            const taille = tailleChoisie === 'all' ? Math.max(filtres.length, 1) : Number(tailleChoisie);
            nombrePages = Math.max(1, Math.ceil(filtres.length / taille));
            numeroPage = Math.min(Math.max(numeroPage, 1), nombrePages);
            const debut = (numeroPage - 1) * taille;
            const visibles = new Set(filtres.slice(debut, debut + taille));
            const fragment = document.createDocumentFragment();
            // Déplacer les nœuds conserve les formulaires, les jetons CSRF et les écouteurs.
            donnees.slice().sort(comparer).forEach(partenaire => {
                partenaire.ligne.hidden = !visibles.has(partenaire);
                fragment.appendChild(partenaire.ligne);
            });
            table.tBodies[0].appendChild(fragment);
            if (tableWrap) tableWrap.hidden = filtres.length === 0;
            if (aucunResultat) aucunResultat.hidden = filtres.length !== 0;
            if (precedent) precedent.disabled = numeroPage <= 1;
            if (suivant) suivant.disabled = numeroPage >= nombrePages;
            if (libellePage) libellePage.textContent = `Page ${nombres.format(numeroPage)} sur ${nombres.format(nombrePages)}`;
            if (resultats) {
                resultats.textContent = filtres.length === 0 ? `Aucun ${libellePartenaire} ne correspond à ces critères.`
                    : `${nombres.format(filtres.length)} ${libellePartenaire}${filtres.length > 1 ? 's' : ''} · `
                    + `Affichage de ${nombres.format(debut + 1)} à ${nombres.format(Math.min(debut + taille, filtres.length))}`;
            }
            boutonsContact.forEach(bouton => bouton.setAttribute('aria-pressed', String(bouton.dataset.contactFilter === contactChoisi)));
            boutonsTri.forEach(bouton => {
                const actif = bouton.dataset.partnerSort === cleTri;
                bouton.closest('th')?.setAttribute('aria-sort', actif ? (sensTri === 1 ? 'ascending' : 'descending') : 'none');
                bouton.dataset.direction = actif ? (sensTri === 1 ? 'asc' : 'desc') : '';
            });
        }

        const filtrer = () => { numeroPage = 1; afficher(); };
        recherche?.addEventListener('input', filtrer);
        contact?.addEventListener('change', filtrer);
        taillePage?.addEventListener('change', filtrer);
        boutonsContact.forEach(bouton => {
            if (!contactsAutorises.has(bouton.dataset.contactFilter)) return;
            bouton.disabled = false;
            bouton.addEventListener('click', () => {
                if (contact) contact.value = bouton.dataset.contactFilter;
                filtrer();
            });
        });
        boutonsTri.forEach(bouton => {
            if (!trisAutorises.has(bouton.dataset.partnerSort)) return;
            bouton.disabled = false;
            bouton.addEventListener('click', () => {
                const nouvelleCle = bouton.dataset.partnerSort;
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
        page.querySelectorAll('#partenairesReset, [data-reset-partners]').forEach(bouton => {
            bouton.addEventListener('click', () => {
                if (recherche) recherche.value = '';
                if (contact) contact.value = '';
                if (taillePage) taillePage.value = tailleInitiale;
                cleTri = 'nom';
                sensTri = 1;
                filtrer();
            });
        });

        const details = document.getElementById('partenaireDetailsDialog');
        page.querySelectorAll('[data-partner-details]').forEach(bouton => {
            if (!details || typeof details.showModal !== 'function') return;
            bouton.hidden = false;
            bouton.addEventListener('click', () => {
                const partenaire = parLigne.get(bouton.closest('tr[data-partner-row]'));
                if (!partenaire || details.open) return;
                const titre = document.getElementById('partenaireDetailsTitle');
                if (titre) titre.textContent = partenaire.nom;
                details.querySelectorAll('[data-partner-detail]').forEach(champ => {
                    const cle = champ.dataset.partnerDetail;
                    if (['telephone', 'email', 'adresse'].includes(cle)) {
                        champ.textContent = partenaire[cle].trim() ? partenaire[cle] : 'Non renseigné';
                    }
                });
                details.showModal();
            });
        });

        const suppression = document.getElementById('partenaireDeleteDialog');
        const confirmer = document.getElementById('partenaireDeleteConfirm');
        let formulaireEnAttente = null;
        let suppressionEnvoyee = false;
        const dialogueSuppression = suppression && confirmer && typeof suppression.showModal === 'function';
        page.querySelectorAll('form[data-partner-delete]').forEach(formulaire => {
            formulaire.addEventListener('submit', event => {
                if (suppressionEnvoyee) {
                    event.preventDefault();
                    return;
                }
                const partenaire = parLigne.get(formulaire.closest('tr[data-partner-row]'));
                if (!dialogueSuppression) {
                    if (!window.confirm(`Supprimer ${partenaire?.nom || `ce ${libellePartenaire}`} ?`)) event.preventDefault();
                    else suppressionEnvoyee = true;
                    return;
                }
                event.preventDefault();
                if (suppression.open) return;
                formulaireEnAttente = formulaire;
                const nom = document.getElementById('partenaireDeleteName');
                if (nom) nom.textContent = partenaire?.nom || `ce ${libellePartenaire}`;
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
        page.querySelectorAll('[data-close-partner-dialog]').forEach(bouton => {
            bouton.addEventListener('click', () => bouton.closest('dialog')?.close());
        });
        const toolbar = document.getElementById('partenairesToolbar');
        const pagination = document.getElementById('partenairesPagination');
        if (toolbar) toolbar.hidden = false;
        if (pagination) pagination.hidden = false;
        afficher();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
