(() => {
    'use strict';

    const normaliser = valeur => String(valeur ?? '').normalize('NFD')
        .replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('fr-FR')
        .replace(/œ/g, 'oe').replace(/æ/g, 'ae').trim().replace(/\s+/g, ' ');
    const nombres = new Intl.NumberFormat('fr-FR');
    const collateur = new Intl.Collator('fr-FR', { numeric: true, sensitivity: 'base' });
    const libellesSens = Object.freeze({ entree: 'Entrées', sortie: 'Sorties', neutre: 'Sans variation', inconnu: 'Montant non renseigné' });

    // Never convert financial values to Number: all comparisons use exact signed cents.
    function centimes(valeur) {
        const parties = /^(-?)(\d+)(?:\.(\d{1,2}))?$/.exec(String(valeur ?? '').trim());
        if (!parties) return null;
        const montant = BigInt(parties[2]) * 100n + BigInt((parties[3] ?? '').padEnd(2, '0'));
        return parties[1] ? -montant : montant;
    }

    function dateValide(valeur) {
        const parties = /^(\d{4})-(\d{2})-(\d{2})$/.exec(valeur);
        if (!parties) return false;
        const annee = Number(parties[1]);
        const mois = Number(parties[2]);
        const jour = Number(parties[3]);
        const jours = [31, (annee % 4 === 0 && (annee % 100 !== 0 || annee % 400 === 0)) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
        return annee > 0 && mois >= 1 && mois <= 12 && jour >= 1 && jour <= jours[mois - 1];
    }

    function initialiser() {
        const page = document.getElementById('stockCaisse');
        const table = document.getElementById('caisseTable');
        if (!page || !table) return;
        const lignes = Array.from(table.querySelectorAll('tr[data-caisse-row]'));
        const recherche = document.getElementById('caisseSearch');
        const source = document.getElementById('caisseSource');
        const direction = document.getElementById('caisseDirection');
        const dateDu = document.getElementById('caisseDateFrom');
        const dateAu = document.getElementById('caisseDateTo');
        const erreurDate = document.getElementById('caisseDateError');
        const taillePage = document.getElementById('caissePageSize');
        const precedent = document.getElementById('caissePrevious');
        const suivant = document.getElementById('caisseNext');
        const libellePage = document.getElementById('caissePageLabel');
        const resultats = document.getElementById('caisseResults');
        const tableWrap = document.getElementById('caisseTableWrap');
        const aucunResultat = document.getElementById('caisseNoResults');
        const imprimer = document.getElementById('caissePrint');
        const selectionImprimee = document.getElementById('caissePrintSelection');
        const dateImprimee = document.getElementById('caissePrintDate');
        const videImprime = document.getElementById('caissePrintEmpty');
        const boutonsTri = Array.from(page.querySelectorAll('[data-caisse-sort]'));
        const taillesAutorisees = new Set(['10', '25', '50', 'all']);
        const tailleInitiale = taillesAutorisees.has(taillePage?.value) ? taillePage.value : '25';
        const donnees = lignes.map((ligne, ordre) => {
            const date = ligne.dataset.date?.trim() ?? '';
            return {
                ligne, ordre, date, jour: date.slice(0, 10),
                montant: centimes(ligne.dataset.amount), source: ligne.dataset.source?.trim() ?? '',
                direction: ligne.dataset.direction ?? 'inconnu',
                recherche: normaliser([ligne.textContent, date, ligne.dataset.type].join(' '))
            };
        });
        const sources = Array.from(new Set(donnees.map(mouvement => mouvement.source))).sort(collateur.compare);
        const sourcesAutorisees = new Map();
        sources.forEach((valeur, index) => {
            const cle = `source-${index}`;
            sourcesAutorisees.set(cle, valeur);
            if (source) {
                const option = document.createElement('option');
                option.value = cle;
                option.textContent = valeur || 'Non renseignée';
                source.appendChild(option);
            }
        });
        let numeroPage = 1;
        let nombrePages = 1;
        let cleTri = 'date';
        let sensTri = -1;
        let mouvementsFiltres = donnees;
        let selectionDemandee = false;
        let periodeInvalide = false;

        function comparer(a, b) {
            const premier = cleTri === 'montant' ? a.montant : (a.date || null);
            const second = cleTri === 'montant' ? b.montant : (b.date || null);
            // Unknown values always follow dated/known values; ties preserve server order.
            if (premier === null && second !== null) return 1;
            if (second === null && premier !== null) return -1;
            const comparaison = premier === null ? 0 : (premier < second ? -1 : premier > second ? 1 : 0);
            return comparaison * sensTri || a.ordre - b.ordre;
        }

        function criteres() {
            const du = dateDu?.value ?? '';
            const au = dateAu?.value ?? '';
            return {
                mots: normaliser(recherche?.value).split(' ').filter(Boolean),
                source: sourcesAutorisees.has(source?.value) ? sourcesAutorisees.get(source.value) : null,
                direction: Object.hasOwn(libellesSens, direction?.value) ? direction.value : '',
                du, au, invalide: Boolean((du && !dateValide(du)) || (au && !dateValide(au)) || (du && au && du > au))
            };
        }

        function afficher() {
            const filtre = criteres();
            periodeInvalide = filtre.invalide;
            if (erreurDate) erreurDate.hidden = !periodeInvalide;
            [dateDu, dateAu].forEach(champ => champ?.setAttribute('aria-invalid', String(periodeInvalide)));
            if (imprimer) imprimer.disabled = periodeInvalide;
            mouvementsFiltres = donnees.filter(mouvement => !periodeInvalide
                && filtre.mots.every(mot => mouvement.recherche.includes(mot))
                && (filtre.source === null || mouvement.source === filtre.source)
                && (!filtre.direction || mouvement.direction === filtre.direction)
                && ((!filtre.du && !filtre.au) || (dateValide(mouvement.jour)
                    && (!filtre.du || mouvement.jour >= filtre.du) && (!filtre.au || mouvement.jour <= filtre.au)))).sort(comparer);
            const tailleChoisie = taillesAutorisees.has(taillePage?.value) ? taillePage.value : tailleInitiale;
            const taille = tailleChoisie === 'all' ? Math.max(mouvementsFiltres.length, 1) : Number(tailleChoisie);
            nombrePages = Math.max(1, Math.ceil(mouvementsFiltres.length / taille));
            numeroPage = Math.min(Math.max(numeroPage, 1), nombrePages);
            const debut = (numeroPage - 1) * taille;
            const visibles = new Set(mouvementsFiltres.slice(debut, debut + taille));
            const fragment = document.createDocumentFragment();
            // Keep server-rendered cells and exact amounts on their original DOM nodes.
            donnees.slice().sort(comparer).forEach(mouvement => {
                mouvement.ligne.hidden = !visibles.has(mouvement);
                fragment.appendChild(mouvement.ligne);
            });
            table.tBodies[0].appendChild(fragment);
            if (tableWrap) tableWrap.hidden = mouvementsFiltres.length === 0;
            if (aucunResultat) aucunResultat.hidden = mouvementsFiltres.length !== 0 || donnees.length === 0;
            if (precedent) precedent.disabled = numeroPage <= 1;
            if (suivant) suivant.disabled = numeroPage >= nombrePages;
            if (libellePage) libellePage.textContent = `Page ${nombres.format(numeroPage)} sur ${nombres.format(nombrePages)}`;
            if (resultats) {
                resultats.textContent = mouvementsFiltres.length === 0 ? 'Aucun mouvement ne correspond à ces critères.'
                    : `${nombres.format(mouvementsFiltres.length)} mouvement${mouvementsFiltres.length > 1 ? 's' : ''} · `
                    + `Affichage de ${nombres.format(debut + 1)} à ${nombres.format(Math.min(debut + taille, mouvementsFiltres.length))}`;
            }
            boutonsTri.forEach(bouton => {
                const actif = bouton.dataset.caisseSort === cleTri;
                bouton.closest('th')?.setAttribute('aria-sort', actif ? (sensTri === 1 ? 'ascending' : 'descending') : 'none');
                bouton.dataset.direction = actif ? (sensTri === 1 ? 'asc' : 'desc') : '';
                const libelle = bouton.dataset.caisseSort === 'date' ? 'date' : 'montant';
                bouton.setAttribute('aria-label', `Trier par ${libelle}${actif ? (sensTri === 1 ? ', ordre croissant' : ', ordre décroissant') : ''}`);
            });
        }

        const filtrer = () => { numeroPage = 1; afficher(); };
        recherche?.addEventListener('input', filtrer);
        [source, direction, taillePage].forEach(champ => champ?.addEventListener('change', filtrer));
        [dateDu, dateAu].forEach(champ => champ?.addEventListener('input', filtrer));
        boutonsTri.forEach(bouton => {
            if (!['date', 'montant'].includes(bouton.dataset.caisseSort)) return;
            bouton.disabled = donnees.length === 0;
            bouton.addEventListener('click', () => {
                const nouvelleCle = bouton.dataset.caisseSort;
                sensTri = nouvelleCle === cleTri ? -sensTri : (nouvelleCle === 'date' ? -1 : 1);
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
        page.querySelectorAll('#caisseReset, [data-reset-caisse]').forEach(bouton => {
            bouton.addEventListener('click', () => {
                [recherche, source, direction, dateDu, dateAu].forEach(champ => { if (champ) champ.value = ''; });
                if (taillePage) taillePage.value = tailleInitiale;
                cleTri = 'date';
                sensTri = -1;
                filtrer();
            });
        });

        function preparerImpression() {
            const selection = selectionDemandee ? mouvementsFiltres : donnees;
            const inclus = new Set(selection);
            page.dataset.printScope = selectionDemandee ? 'selection' : 'all';
            page.dataset.printEmpty = String(selection.length === 0);
            donnees.forEach(mouvement => { mouvement.ligne.dataset.printIncluded = String(inclus.has(mouvement)); });
            const tri = `Tri : ${cleTri === 'date' ? 'date' : 'montant'}, ${sensTri === 1 ? 'croissant' : 'décroissant'}`;
            const nombre = `${nombres.format(selection.length)} mouvement${selection.length > 1 ? 's' : ''}`;
            const criteresImprimes = [];
            if (selectionDemandee) {
                const filtre = criteres();
                if (recherche?.value.trim()) criteresImprimes.push(`Recherche : ${recherche.value.trim()}`);
                if (filtre.source !== null) criteresImprimes.push(`Source : ${filtre.source || 'Non renseignée'}`);
                if (filtre.direction) criteresImprimes.push(`Sens : ${libellesSens[filtre.direction]}`);
                if (filtre.du) criteresImprimes.push(`Du ${filtre.du.split('-').reverse().join('/')}`);
                if (filtre.au) criteresImprimes.push(`Au ${filtre.au.split('-').reverse().join('/')} (inclus)`);
            }
            if (selectionImprimee) selectionImprimee.textContent = `Sélection : ${criteresImprimes.length ? criteresImprimes.join(' · ') : 'journal complet'} · ${nombre} · ${tri}`;
            if (dateImprimee) {
                // The consultation timestamp is rendered by the server; add the browser's print time separately.
                if (!dateImprimee.dataset.consultation) dateImprimee.dataset.consultation = dateImprimee.textContent;
                dateImprimee.textContent = `${dateImprimee.dataset.consultation} · Impression le ${new Intl.DateTimeFormat('fr-FR', { dateStyle: 'short', timeStyle: 'short' }).format(new Date())}`;
            }
            if (videImprime) videImprime.textContent = selectionDemandee && donnees.length > 0 ? 'Aucun mouvement ne correspond aux filtres sélectionnés.' : 'Aucun mouvement enregistré.';
        }

        imprimer?.addEventListener('click', () => {
            if (periodeInvalide) return;
            selectionDemandee = true;
            preparerImpression();
            window.print();
        });
        window.addEventListener('beforeprint', preparerImpression);
        window.addEventListener('afterprint', () => {
            selectionDemandee = false;
            page.dataset.printScope = 'all';
            page.dataset.printEmpty = String(donnees.length === 0);
            donnees.forEach(mouvement => { delete mouvement.ligne.dataset.printIncluded; });
        });
        window.addEventListener('keydown', event => {
            if ((event.ctrlKey || event.metaKey) && event.key.toLocaleLowerCase('fr-FR') === 'p') {
                selectionDemandee = false;
                preparerImpression();
            }
        });

        const toolbar = document.getElementById('caisseToolbar');
        const pagination = document.getElementById('caissePagination');
        const aideImpression = document.getElementById('caissePrintHelp');
        if (toolbar) toolbar.hidden = donnees.length === 0;
        if (pagination) pagination.hidden = donnees.length === 0;
        if (imprimer) imprimer.hidden = false;
        if (aideImpression) aideImpression.hidden = false;
        afficher();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initialiser);
    else initialiser();
})();
