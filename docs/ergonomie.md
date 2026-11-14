# Ergonomie (novembre 2026)

Quatre petits modules JavaScript sans dépendance (UMD : chargés par le navigateur, testés par `node --test src/test/js/*.cjs`) et une feuille de style, `static/css/ergonomie.css`. Tous sont chargés par `dashboard.html`. Chacun repose sur du HTML qui fonctionne sans JavaScript : le script ne fait qu'améliorer.

## Menu « Plus » — `static/js/menu-plus.js`

Range les actions secondaires d'une ligne. Garder 2 à 3 actions principales visibles, le reste dans le menu.

```html
<details class="plus-menu-details" data-plus-menu>
  <summary class="btn btn-outline-secondary btn-sm plus-menu-bouton" aria-label="Plus d’actions pour X"><span aria-hidden="true">⋯</span><span>Plus</span></summary>
  <div class="plus-menu-panel" data-plus-panneau>
    <a class="plus-menu-item" href="…">Facture</a>
    <hr class="plus-menu-separateur">
    <form method="post" th:action="…" data-confirmer …><button class="plus-menu-item plus-menu-item--danger">Supprimer</button></form>
  </div>
</details>
```

Sans JavaScript, c'est un `<details>`. Avec JavaScript, il devient un vrai `<button aria-haspopup="menu" aria-expanded>` et le panneau (liens, formulaires et jetons CSRF déplacés, pas copiés) est positionné en `fixed` : il n'est jamais coupé par le défilement d'un tableau. Échap et clic à l'extérieur ferment et rendent le focus au bouton ; flèches, Début et Fin naviguent. Un menu vide est à éviter : entourer le `<details>` du même `sec:authorize` que ses entrées. Les droits ne changent jamais : une action masquée à un rôle reste masquée.

## Confirmation — `static/js/confirmation.js`

Remplace `confirm()`. Sur un `<form>` :

```html
<form method="post" th:action="…" data-confirmer
      data-confirmer-titre="Supprimer ce client ?" th:attr="data-confirmer-sujet=${p.nom}"
      data-confirmer-texte="sera supprimé du répertoire." data-confirmer-oui="Supprimer le client" data-confirmer-non="Garder">
```

Une seule `<dialog>` est créée à la demande ; le bouton de confirmation est rouge ; le focus revient à l'élément d'origine (ou au bouton « Plus » de la ligne). Sans JavaScript, le formulaire part directement et le serveur garde ses contrôles. Textes posés avec `textContent`.

## Filtres repliables — `static/js/filtres-mobiles.js`

```html
<div data-filtres>                    <!-- conteneur -->
  <form|div data-filtres-contenu>     <!-- les champs d'origine -->
     <input type="hidden" data-filtre-libelle="Échues non soldées">   <!-- filtre actif sans champ visible -->
     <a|button data-filtres-effacer>
```

Sous 768 px : bouton « Filtres » avec le nombre de filtres actifs, étiquettes des filtres actifs, « Effacer ». Au-delà, rien ne change. Quand le contenu est dans une grille de bureau, lui donner `display: contents` hors téléphone (voir le journal de caisse et les produits).

## Tri sur téléphone — `static/js/tri-mobile.js`

Pour les tableaux simple-datatables : crée « Trier par » à partir des en-têtes (masqués sur téléphone) et déclenche le même tri que le clic sur l'en-tête. Les tris sont faits dans le navigateur : il n'y a pas de paramètre d'adresse. Les listes clients et fournisseurs ont leur propre `<select>` (`partenaires.js`).

## Autres éléments de `ergonomie.css`

Fiches compactes des partenaires (`solde-badge--doit|dois|avance|solde`), fiches du journal sur téléphone, colonne d'actions collante sur ordinateur, historique de règlement en fiches (`table.historique-cartes` avec `data-label` sur chaque `td`, et `td.historique-action`).

## Mesures

`scrollWidth` des pages principales à 360 et 1280 px : voir le script Playwright décrit dans le rapport (une page est « trop large » quand `scrollWidth` dépasse la largeur de l'écran).
