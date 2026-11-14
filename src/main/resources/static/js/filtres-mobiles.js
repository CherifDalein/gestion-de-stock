/*
 * Filtres repliables sur téléphone (moins de 768 px). Sur ordinateur, rien ne change.
 *
 * Balisage :
 *   <div data-filtres>                                   conteneur
 *     <form|div data-filtres-contenu> …champs… </…>      ce qui se replie : les champs d'origine, déplacés nulle part
 *       <input type="hidden" data-filtre-libelle="Échues non soldées">   filtre « actif » sans champ visible
 *       <a|button data-filtres-effacer href="…">         (optionnel) « Effacer » du formulaire : un lien est suivi, un bouton est cliqué
 *   </div>
 *
 * Sur téléphone, le contenu est replié derrière un bouton « Filtres » (aria-expanded) ; quand des filtres sont actifs le bouton
 * affiche leur nombre, ils sont rappelés en petites étiquettes (« Du : 01/06/2026 ») et un lien « Effacer » apparaît.
 * Un champ est actif quand sa valeur diffère de celle de sa première option (select) ou n'est pas vide (autres champs).
 * Un champ marqué data-filtre-initial est présélectionné par le serveur (le compte du journal de trésorerie) : sa valeur au
 * chargement de la page est sa valeur par défaut, elle n'est pas comptée comme un filtre choisi.
 * Sans JavaScript, les filtres restent affichés. Les textes sont posés avec textContent.
 */
(function (racine, fabrique) {
    'use strict';
    const api = fabrique();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else {
        racine.StockFiltresMobiles = api;
        if (racine.document) api.installer(racine.document, racine);
    }
})(typeof window !== 'undefined' ? window : globalThis, function () {
    'use strict';

    const MOBILE = '(max-width: 767.98px)';
    let sequence = 0;
    /** Valeur au chargement des champs marqués data-filtre-initial (relevée la première fois que le champ est lu). */
    const initiales = new Map();

    function valeurInitiale(champ) {
        if (!initiales.has(champ)) initiales.set(champ, champ.value);
        return initiales.get(champ);
    }

    /** 2026-06-01 devient 01/06/2026 ; toute autre valeur est rendue telle quelle. */
    function formaterValeur(valeur) {
        const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(valeur);
        return m ? `${m[3]}/${m[2]}/${m[1]}` : valeur;
    }

    /**
     * Étiquettes des filtres actifs, à partir de descripteurs { libelle, valeur, defaut, texte }.
     * valeur/defaut : valeurs brutes ; texte : texte affiché de la valeur (option choisie) ; libelle : nom du champ.
     */
    function etiquettes(champs) {
        return champs.filter(c => String(c.valeur ?? '').trim() !== String(c.defaut ?? '').trim() && String(c.valeur ?? '').trim() !== '')
            .map(c => {
                const texte = formaterValeur(String(c.texte ?? c.valeur).trim());
                return c.libelle ? `${c.libelle} : ${texte}` : texte;
            });
    }

    function libelleDe(champ, contenu) {
        const id = champ.id;
        const etiquette = id ? Array.from(contenu.querySelectorAll('label')).find(l => l.getAttribute('for') === id) : null;
        const brut = etiquette ? etiquette.textContent : (champ.getAttribute('aria-label') || '');
        return String(brut).replace(/\s+/g, ' ').trim();
    }

    /** Descripteurs des champs visibles du contenu, plus les filtres « sans champ » (data-filtre-libelle). */
    function lireChamps(contenu) {
        const decrits = [];
        Array.from(contenu.querySelectorAll('select, input')).forEach(champ => {
            const type = (champ.getAttribute('type') || '').toLowerCase();
            const libelleFiltre = champ.getAttribute('data-filtre-libelle');
            if (libelleFiltre) { decrits.push({ libelle: '', valeur: 'oui', defaut: '', texte: libelleFiltre }); return; }
            if (['hidden', 'submit', 'button', 'reset', 'checkbox', 'radio'].includes(type)) return;
            if (champ.tagName.toLowerCase() === 'select') {
                const options = Array.from(champ.options || []);
                const choisie = options.find(o => o.value === champ.value) || null;
                const defaut = champ.hasAttribute('data-filtre-initial') ? valeurInitiale(champ) : (options.length ? options[0].value : '');
                decrits.push({ libelle: libelleDe(champ, contenu), valeur: champ.value, defaut,
                    texte: choisie ? choisie.textContent : champ.value });
            } else decrits.push({ libelle: libelleDe(champ, contenu), valeur: champ.value, defaut: '', texte: champ.value });
        });
        return decrits;
    }

    function monter(conteneur, doc, env) {
        const contenu = conteneur.querySelector('[data-filtres-contenu]');
        if (!contenu || conteneur.hasAttribute('data-filtres-pret')) return null;
        conteneur.setAttribute('data-filtres-pret', '');
        const id = 'filtres-' + (++sequence);
        const barre = doc.createElement('div');
        barre.className = 'filtres-barre';
        const bouton = doc.createElement('button');
        bouton.setAttribute('type', 'button');
        bouton.className = 'btn btn-outline-secondary filtres-bouton';
        bouton.setAttribute('aria-expanded', 'false');
        bouton.setAttribute('aria-controls', id);
        const libelle = doc.createElement('span');
        libelle.textContent = 'Filtres';
        const compteur = doc.createElement('span');
        compteur.className = 'filtres-compteur';
        compteur.hidden = true;
        const icone = doc.createElement('i');
        icone.className = 'fas fa-sliders';
        icone.setAttribute('aria-hidden', 'true');
        bouton.append(icone, libelle, compteur);
        const effacer = doc.createElement('a');
        effacer.className = 'btn btn-link filtres-effacer';
        effacer.textContent = 'Effacer';
        effacer.hidden = true;
        barre.append(bouton, effacer);
        const liste = doc.createElement('ul');
        liste.className = 'filtres-etiquettes';
        liste.hidden = true;
        contenu.id = contenu.id || id;
        bouton.setAttribute('aria-controls', contenu.id);
        const parent = contenu.parentNode;
        parent.insertBefore(barre, contenu);
        parent.insertBefore(liste, contenu);

        const mobile = env.matchMedia ? env.matchMedia(MOBILE) : { matches: false, addEventListener() {} };
        let ouvert = false;
        let cachePar = false;   // vrai quand c'est ce module qui a replié le contenu : sur ordinateur, on ne montre que ce qu'on a caché
        const lienEffacer = contenu.querySelector('[data-filtres-effacer]');

        function appliquerVisibilite() {
            if (mobile.matches) { contenu.hidden = !ouvert; cachePar = !ouvert; }
            else if (cachePar) { contenu.hidden = false; cachePar = false; }
            bouton.setAttribute('aria-expanded', String(mobile.matches && ouvert));
        }

        function rafraichir() {
            const actifs = etiquettes(lireChamps(contenu));
            compteur.textContent = String(actifs.length);
            compteur.hidden = actifs.length === 0;
            bouton.setAttribute('aria-label', actifs.length ? `Filtres, ${actifs.length} actif${actifs.length > 1 ? 's' : ''}` : 'Filtres');
            while (liste.firstChild) liste.removeChild(liste.firstChild);
            actifs.forEach(texte => { const li = doc.createElement('li'); li.textContent = texte; liste.append(li); });
            liste.hidden = actifs.length === 0;
            effacer.hidden = actifs.length === 0;
            const href = lienEffacer ? lienEffacer.getAttribute('href') : null;
            if (href) effacer.setAttribute('href', href); else effacer.removeAttribute('href');
            effacer.setAttribute('role', href ? 'link' : 'button');
            if (!href) effacer.setAttribute('tabindex', '0');
        }

        bouton.addEventListener('click', () => { ouvert = !ouvert; appliquerVisibilite(); });
        effacer.addEventListener('click', evenement => {
            if (effacer.getAttribute('href')) return;          // un lien : la navigation fait le travail
            evenement.preventDefault();
            if (lienEffacer && typeof lienEffacer.click === 'function') lienEffacer.click();
            rafraichir();
        });
        ['input', 'change', 'click'].forEach(type => contenu.addEventListener(type, () => {
            if (typeof env.setTimeout === 'function') env.setTimeout(rafraichir, 0); else rafraichir();
        }));
        if (mobile.addEventListener) mobile.addEventListener('change', appliquerVisibilite);
        rafraichir();
        appliquerVisibilite();
        return { barre, bouton, compteur, liste, effacer, contenu, rafraichir, appliquerVisibilite };
    }

    function installer(doc, env) {
        const fenetre = env || doc.defaultView || {};
        return Array.from(doc.querySelectorAll('[data-filtres]')).map(c => monter(c, doc, fenetre)).filter(Boolean);
    }

    return { etiquettes, formaterValeur, lireChamps, monter, installer };
});
