(() => {
    'use strict';

    const normaliser = valeur => String(valeur ?? '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr-FR')
        .replace(/œ/g, 'oe').replace(/æ/g, 'ae').trim().replace(/\s+/g, ' ');
    const nombres = new Intl.NumberFormat('fr-FR');
    const libellesStatut = Object.freeze({ reglee: 'Réglée', partielle: 'Partiel', credit: 'À régler' });

    function initialiser() {
        const page = document.getElementById('stockFactures');
        const table = document.getElementById('facturesTable');
        if (!page || !table) return;
        const lignes = Array.from(page.querySelectorAll('tr[data-invoice-row]'));
        if (lignes.length === 0) return;

        const recherche = document.getElementById('facturesSearch');
        const statut = document.getElementById('facturesStatut');
        const taillePage = document.getElementById('facturesPageSize');
        const precedent = document.getElementById('facturesPrevious');
        const suivant = document.getElementById('facturesNext');
        const libellePage = document.getElementById('facturesPageLabel');
        const resultats = document.getElementById('facturesResults');
        const tableWrap = document.getElementById('facturesTableWrap');
        const aucunResultat = document.getElementById('facturesNoResults');
        const statutsAutorises = new Set(['', 'reglee', 'partielle', 'credit']);
        const taillesAutorisees = new Set(['10', '25', '50', 'all']);
        const tailleInitiale = taillesAutorisees.has(taillePage?.value) ? taillePage.value : '25';
        const libelleDocument = page.dataset.documentKind === 'achat' ? 'achat' : 'facture';
        const donnees = lignes.map((ligne, ordre) => ({
            ligne, ordre, date: ligne.dataset.date?.trim() ?? '', statut: ligne.dataset.status ?? '',
            recherche: normaliser([ligne.dataset.reference, ligne.dataset.name, ligne.dataset.date,
                ligne.querySelector('.invoices-date')?.textContent, libellesStatut[ligne.dataset.status]].join(' '))
        })).sort((a, b) => {
            if (!a.date && b.date) return 1;
            if (!b.date && a.date) return -1;
            // Les dates ISO locales se comparent sans conversion de fuseau ; les égalités restent stables.
            return (a.date < b.date ? 1 : a.date > b.date ? -1 : 0) || a.ordre - b.ordre;
        });
        let numeroPage = 1;
        let nombrePages = 1;

        function afficher() {
            const mots = normaliser(recherche?.value).split(' ').filter(Boolean);
            const statutChoisi = statutsAutorises.has(statut?.value) ? statut.value : '';
            const tailleChoisie = taillesAutorisees.has(taillePage?.value) ? taillePage.value : tailleInitiale;
            const filtres = donnees.filter(documentFacture => mots.every(mot => documentFacture.recherche.includes(mot))
                && (!statutChoisi || documentFacture.statut === statutChoisi));
            const taille = tailleChoisie === 'all' ? Math.max(filtres.length, 1) : Number(tailleChoisie);
            nombrePages = Math.max(1, Math.ceil(filtres.length / taille));
            numeroPage = Math.min(Math.max(numeroPage, 1), nombrePages);
            const debut = (numeroPage - 1) * taille;
            const visibles = new Set(filtres.slice(debut, debut + taille));
            const fragment = document.createDocumentFragment();
            // Les liens et les montants rendus par le serveur restent sur leurs nœuds d'origine.
            donnees.forEach(documentFacture => {
                documentFacture.ligne.hidden = !visibles.has(documentFacture);
                fragment.appendChild(documentFacture.ligne);
            });
            table.tBodies[0].appendChild(fragment);
            if (tableWrap) tableWrap.hidden = filtres.length === 0;
            if (aucunResultat) aucunResultat.hidden = filtres.length !== 0;
            if (precedent) precedent.disabled = numeroPage <= 1;
            if (suivant) suivant.disabled = numeroPage >= nombrePages;
            if (libellePage) libellePage.textContent = `Page ${nombres.format(numeroPage)} sur ${nombres.format(nombrePages)}`;
            if (resultats) {
                resultats.textContent = filtres.length === 0 ? `${libelleDocument === 'achat' ? 'Aucun achat' : 'Aucune facture'} ne correspond à ces critères.`
                    : `${nombres.format(filtres.length)} ${libelleDocument}${filtres.length > 1 ? 's' : ''} · `
                    + `Affichage de ${nombres.format(debut + 1)} à ${nombres.format(Math.min(debut + taille, filtres.length))}`;
            }
            // Les cartes financières portent sur la période GET et restent indépendantes des filtres locaux.
        }

        const filtrer = () => { numeroPage = 1; afficher(); };
        recherche?.addEventListener('input', filtrer);
        statut?.addEventListener('change', filtrer);
        taillePage?.addEventListener('change', filtrer);
        precedent?.addEventListener('click', () => {
            if (numeroPage > 1) { numeroPage--; afficher(); }
        });
        suivant?.addEventListener('click', () => {
            if (numeroPage < nombrePages) { numeroPage++; afficher(); }
        });
        page.querySelectorAll('#facturesReset, [data-reset-invoices]').forEach(bouton => {
            bouton.addEventListener('click', () => {
                if (recherche) recherche.value = '';
                if (statut) statut.value = '';
                if (taillePage) taillePage.value = tailleInitiale;
                filtrer();
            });
        });
        const toolbar = document.getElementById('facturesToolbar');
        const pagination = document.getElementById('facturesPagination');
        if (toolbar) toolbar.hidden = false;
        if (pagination) pagination.hidden = false;
        afficher();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
