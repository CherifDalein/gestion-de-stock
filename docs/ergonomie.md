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

## Thème sombre (novembre 2026)

Trois modes : **Automatique** (défaut, suit `prefers-color-scheme` en direct), **Clair** et **Sombre**. Choix dans le menu du compte (radiogroup « Thème », `fragments/theme.html :: selecteur`) et, sur les pages publiques (accueil, connexion, inscription, mot de passe oublié, accès terminé), par le bouton lune/soleil (`fragments/theme.html :: bascule`). Aucune règle métier, aucune migration, rien côté serveur.

### Bootstrap : 5.2.3 gardé, surchargé par `theme.css`

Constat : le CSS de Bootstrap 5.2.3 n'est **pas** chargé par un CDN, il est compilé dans `static/css/styles.css` (SB Admin 7.0.7, 11 000 lignes, avec nos réglages `.datatable-*` fusionnés dans `.table` et `.form-control`). Seul le JS (`bootstrap.bundle`) vient du CDN. Passer à la 5.3.3 aurait voulu dire recompiler ou remplacer `styles.css`, donc revalider chaque écran en clair (risque de régression visible par tous) pour un gain faible : 5.2 exprime presque tous ses composants avec des variables `--bs-*` (carte, alerte, bouton, menu, fenêtre, pagination…), que `theme.css` redéfinit sous `[data-bs-theme="dark"]`. L'attribut `data-bs-theme` est donc notre propre convention, compatible avec une future 5.3. Le JS 5.2.3 (dropdown, modal, collapse) n'est pas touché.

### Fichiers

- `static/js/theme.js` : module UMD (testé par `src/test/js/theme.test.cjs`). Chargé **de façon synchrone dans le `<head>`, avant les feuilles de style** (`fragments/theme.html :: tete`) : il pose `data-bs-theme="light|dark"` et `data-theme-mode="auto|light|dark"` sur `<html>` avant la première peinture, donc pas de flash blanc. L'appli n'a pas de CSP (SecurityConfig ne règle que `frameOptions`) : un fichier a été préféré à un script en ligne pour garder une seule source, testable. Préférence dans `localStorage`, clé `rfc-theme` (`light`, `dark` ; absente = automatique). Toute lecture et écriture est dans un `try/catch` : navigation privée ou stockage refusé = mode automatique, le choix reste appliqué pour la page. Il met aussi à jour `<meta name="theme-color">` (marine en clair, `#16212c` en sombre ; un `<meta … media="(prefers-color-scheme: dark)">` sert sans JavaScript) et suit les autres onglets (évènement `storage`).
- `static/css/theme.css` : chargé sur toutes les pages, juste après `styles.css`. Contient les jetons, le sombre, les réglages Bootstrap, le sélecteur, la bascule et les deux logos.
- `fragments/theme.html` : `tete`, `selecteur`, `bascule`, `logo(classe, largeur, hauteur)`.
- `<meta name="color-scheme" content="light dark">` : champs natifs, barres de défilement et sélecteurs de date suivent le thème.

### Les variables (jetons)

Définis sur `:root` (clair, valeurs historiques) et redéfinis sous `[data-bs-theme="dark"]` dans `theme.css`.

| Jeton | Rôle | Clair | Sombre |
|---|---|---|---|
| `--rfc-bg` | fond de page | `#f4f7f6` | `#0f1720` |
| `--rfc-surface` | cartes, panneaux, barre latérale | `#fff` | `#16212c` |
| `--rfc-surface-2`, `--rfc-surface-3` | panneau discret, puce, en-tête de tableau | `#f8fbf9`, `#f2f6f4` | `#1a2733`, `#202e3b` |
| `--rfc-input-bg` | champs | `#fff` | `#111b25` |
| `--rfc-tint` (`--app-tint`) | fond vert pâle (actif, « Réglée ») | `#eaf5ee` | `#173a2f` |
| `--app-border`, `--rfc-border-strong`, `--rfc-green-border` | bordures | | |
| `--app-ink` | texte | `#152c25` | `#e8eef3` |
| `--rfc-heading` | grands titres (marine) | `#0B2540` | `#e8eef3` |
| `--rfc-ink-2`, `--app-muted` | textes secondaires (contraste AA) | `#52685d`, `#64776e` | `#bccad5`, `#9db0bf` |
| `--rfc-faint` | icônes et accessoires, jamais du texte de lecture | `#759783` | `#8da0b0` |
| `--app-green` | **remplissage** vert (boutons, pastilles), texte blanc dessus | `#15805d` | `#15805d` |
| `--rfc-green-hover` | remplissage vert au survol | `#106448` | `#0f6b4e` |
| `--rfc-green-text` (`--app-green-dark`, `--rfc-link`) | **texte** et icônes verts | `#106448` | `#5fd0a4` |
| `--rfc-danger-*`, `--rfc-warn-*`, `--rfc-info-*` (`-bg`, `-fg`, `-border`, `-solid`) | états : « En retard », « Me doit », info | | |
| `--rfc-on-accent` | texte sur remplissage vert, rouge, bleu | `#fff` | `#fff` |
| `--rfc-shadow-rgb` | couleur des ombres : `rgb(var(--rfc-shadow-rgb) / 10%)` | `21 44 37` | `0 0 0` |
| `--rfc-chart-ventes/resultat/perte` | barres du tableau de bord | | éclaircies |

Deux pièges : le vert de marque se **remplit** (`--app-green`, identique dans les deux thèmes) mais s'**écrit** avec `--rfc-green-text`, qui s'éclaircit en sombre ; ne jamais utiliser `--app-green-dark` ou `--rfc-green-text` comme fond de bouton (c'est du vert clair en sombre : utiliser `--rfc-green-hover`).

### Styler un nouvel écran

1. Aucune couleur en dur (`#fff`, `#152c25`, `rgb(21 44 37 / 3%)`…) pour un fond, un texte, une bordure ou une ombre : prendre un jeton. Fond de carte : `var(--rfc-surface)` ; texte : `var(--app-ink)` ; texte secondaire : `var(--app-muted)` ; bordure : `var(--app-border)` ; ombre : `rgb(var(--rfc-shadow-rgb) / 3%)`.
2. Un état (alerte, badge, ligne en retard) prend la paire `--rfc-<état>-bg` / `--rfc-<état>-fg` : ne jamais mélanger le fond d'un état avec le texte d'un autre. Ne pas faire passer une information par la couleur seule.
3. Les composants Bootstrap (`.card`, `.table`, `.btn-outline-*`, `.alert-*`, `.badge`, `.form-*`, `.dropdown-menu`, `.pagination`…) sont déjà réglés dans `theme.css` : ne rien refaire. Un composant Bootstrap qui écrit une couleur en dur sans variable `--bs-*` se corrige dans `theme.css`, sous `@media not print`, jamais dans la feuille de l'écran.
4. Nouvelle page : charger `theme.css` après `styles.css` et placer `fragments/theme :: tete` dans le `<head>` avant les feuilles ; les pages de la mise en page `dashboard.html` l'ont déjà.
5. Logo : `fragments/theme :: logo('classe', 'largeur', 'hauteur')`. Il sert les variantes `rfc-compta-logo.svg` et `rfc-compta-logo-fond-sombre.svg` (`.logo-clair` / `.logo-sombre`). `theme.css` affiche la bonne selon `data-bs-theme` (donc le choix manuel l'emporte) ; sans JavaScript, `prefers-color-scheme` choisit. Le logo propre à chaque boutique (`identite-boutique`) ne change jamais.
6. Vérifier : captures en sombre et en clair à 375 et 1280 px, puis `scripts/controle-theme-sombre.cjs` (axe-core, règle `color-contrast`, et recherche des blocs clairs oubliés). Les lignes JavaScript qui posent une couleur en ligne (`style="…"`) doivent aussi utiliser `var(--…)`.

### Ce qui reste en clair

- **Les impressions** : tout le sombre de `theme.css` est dans `@media not print`, donc imprimer depuis un écran sombre donne la page claire. Les jetons du `:root` clair s'appliquent toujours à l'impression.
- **Les PDF** (reçus, factures, états, justificatifs, comptage) : gabarits `templates/pdf/*`, qui ne chargent ni `theme.css` ni `theme.js`.
- **Les documents imprimables HTML** (`factures/template_*`, `devis/impression`, `retours/impression*`) : ils ne chargent pas le thème.
- **Le logo de chaque boutique**, les bandes vertes (carte « chiffre d'affaires » du tableau de bord, bande « essai gratuit » de l'accueil, bouton blanc de cette bande) : vert et blanc dans les deux thèmes.

### Limites connues

- Sans JavaScript, l'appli est en clair (sauf le logo, `theme-color` et `color-scheme`, qui suivent le système). Le CSS sombre dépend de l'attribut posé par `theme.js`.
- Les polices d'icônes Font Awesome et simple-datatables viennent toujours du CDN : leurs couleurs sont réglées par `theme.css` (`.datatable-*`).
- Les exports Excel et PDF ne changent pas.
