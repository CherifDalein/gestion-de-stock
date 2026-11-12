# Lot 4 — états et exports

Le lot 4 permet d'exporter les listes, les états, les justificatifs et les reçus en
**PDF** (vrais fichiers, identiques partout, à télécharger ou à partager) et en
**Excel** (.xlsx). Décisions du gérant :

- **Droits.** Le gérant (ADMIN) exporte et imprime tout. Le caissier (CAISSIER) exporte
  seulement les listes qu'il voit déjà, **sans coûts, marges ni trésorerie**, et avec
  exactement les mêmes restrictions de données qu'à l'écran.
- **En-tête.** Nom, logo et coordonnées de la **boutique**, jamais le nom RFC-COMPTA à leur place.

## Socle d'export

Ce chapitre est le guide des tâches qui ajoutent un export. Tout le code du socle est dans
`org.example.stock.export` ; l'exemple de référence est l'export de la liste des ventes
(`VenteExportService`, `VenteExportController`, voir [le pilote](#pilote--la-liste-des-ventes)).

### Dépendances et polices

| Dépendance | Version | Rôle |
|---|---|---|
| `io.github.openhtmltopdf:openhtmltopdf-pdfbox` | 1.1.110 | met en page du XHTML/CSS et l'écrit en PDF (PDFBox 3.0.8 inclus, fournit `PDFTextStripper` aux tests) |
| `org.apache.poi:poi-ooxml` | 5.5.1 | écrit les .xlsx (Spring Boot ne gère pas POI : version explicite dans `build.gradle`) |

POI 6.0.0 existe mais n'a pas été essayé : la 5.5.1 est la dernière 5.x et suffit.

Polices : `src/main/resources/polices/DejaVuSans.ttf` et `DejaVuSans-Bold.ttf` (licence libre
Bitstream Vera / domaine public, texte dans `LICENCE-DejaVu.txt`). Elles sont **embarquées**
(sous-ensemble) dans chaque PDF : accents, « œ », espace insécable, « ’ », « € » et « — »
s'impriment partout de la même façon, jamais en carré vide. Le gabarit de base les nomme
`font-family: 'DejaVu Sans'`. Un texte dans une police différente (autre `font-family`) retomberait
sur une police de base qui ne couvre pas tous les accents : **ne changez pas la famille**.
Pour un caractère hors DejaVu (alphabet non latin), il faudrait ajouter une police.

### Ajouter un export en quelques lignes

Une liste se décrit **une seule fois** dans un `TableauExport`, qui alimente l'Excel ET le PDF :

```java
TableauExport t = TableauExport.titre("Clients")                      // titre (feuille Excel : nomFeuille(...))
        .filtres("Solde supérieur à 0 · Période du " + Textes.date(du) + " au " + Textes.date(au))
        .colonnes(Colonne.texteCourt("Code"), Colonne.texte("Nom"), Colonne.montant("Solde"), Colonne.date("Échéance"))
        .ligne("C-001", "Awa Diallo", new BigDecimal("125000"), LocalDate.of(2026, 12, 1))   // une valeur par colonne
        .totaux("Total", null, new BigDecimal("125000"), null);                              // facultatif
```

Dans le contrôleur (lecture seule, `GET`) :

```java
@GetMapping("/clients/export/excel")
public ResponseEntity<byte[]> excel() {
    TableauExport t = clientsExport.preparer(...);            // service : droits + lignes + colonnes
    return TelechargementExport.excel("clients_" + LocalDate.now() + ".xlsx", excel.ecrire(t, contextes.courant()));
}
@GetMapping("/clients/export/pdf")
public ResponseEntity<byte[]> pdf() {
    return TelechargementExport.pdf("clients_" + LocalDate.now() + ".pdf", pdf.rendreTableau(clientsExport.preparer(...), FormatPapier.A4_PAYSAGE));
}
```

avec `ExportExcelService excel`, `DocumentPdfService pdf` et `ContexteDocumentService contextes` injectés.
Côté écran, deux liens `<a class="btn btn-outline-success">Excel</a>` et `btn-outline-danger` PDF
(icônes `fa-file-excel`, `fa-file-pdf`), comme dans `templates/ventes/liste.html`.

### Les classes

| Classe | Rôle |
|---|---|
| `Colonne`, `TypeColonne` | colonne typée : `texte`, `texteCourt` (jamais de retour à la ligne dans le PDF : numéros, statuts), `entier`, `montant` (GNF), `date`, `dateHeure`, `pourcentage` |
| `TableauExport` | titre, filtres, notes, colonnes, lignes, totaux. Refuse une ligne de mauvaise largeur ; une cellule de mauvais type est refusée à l'écriture (`IllegalArgumentException` nommant la colonne) |
| `ExportExcelService.ecrire(tableau, contexte)` | classeur .xlsx (`byte[]`) |
| `DocumentPdfService.rendreTableau(tableau[, format])` | PDF d'un `TableauExport` avec le gabarit `pdf/tableau` |
| `DocumentPdfService.rendre("pdf/mon-gabarit", variables, format)` | PDF d'un gabarit à vous (reçu, justificatif, état mis en forme) |
| `ContexteDocumentService.courant()` | boutique du compte connecté (nom, adresse, téléphones, email, logo), éditeur, heure |
| `FormatPapier` | `A4_PORTRAIT` (défaut), `A4_PAYSAGE`, `TICKET_80MM` ou `FormatPapier.ticket(hauteurMm)` |
| `TelechargementExport` | réponse de téléchargement, nom de fichier sûr (`nomFichier("ventes", du, au, "xlsx")`) |
| `DroitsExport.estGerant()` | décision de droits, lue dans le compte connecté |
| `LimiteExport` | limite de lignes (20 000), `ExportTropVolumineuxException` |
| `Textes` | formats communs (`montant`, `entier`, `date`, `dateHeure`, `pourcentage`) |

Valeurs attendues par type : `TEXTE` n'importe quel objet (`toString()`), `ENTIER` un nombre,
`MONTANT` un `BigDecimal` (ou un nombre), `DATE` un `LocalDate`, `DATE_HEURE` un `LocalDateTime`,
`POURCENTAGE` un nombre **en pourcents** (12,5 pour 12,5 %). Une cellule vide est `null`.
Dans la ligne de totaux, une cellule texte (« Total ») est acceptée dans n'importe quelle colonne.

Un seul accès base pour les deux formats : `ContexteDocument c = contextes.courant();` puis
`excel.ecrire(t, c)` et `pdf.rendreTableau(t, c, format)`.

### Droits : la règle à appliquer

1. **La sécurité d'accès est déjà posée** par `SecurityConfig` : une route `/ventes/export/...` est couverte par
   « `/ventes/**` » (gérant et caissier) ; une route sous `/achats/**`, `/caisse/**`, `/etats/**`… est réservée au gérant.
   Placez vos routes d'export **sous le préfixe de l'écran qu'elles reproduisent** : elles héritent de ses droits.
2. **Mêmes données que l'écran** : l'export appelle la **même méthode de service** que l'écran, avec les mêmes
   filtres (la liste des ventes passe par `VenteService.rechercher(FiltreVentes)` des deux côtés).
3. **Le caissier n'a ni coût, ni marge, ni trésorerie.** Décidez dans le service avec `DroitsExport.estGerant()` et
   **ne calculez même pas** les colonnes réservées pour un caissier (`VenteExportService` ne lit pas les marges). Un
   paramètre du navigateur n'élargit jamais les droits.
4. **Boutique** : toujours celle du compte connecté (`BoutiqueService`), jamais un paramètre. Les services existants le font.
5. **Lecture seule** : des `GET` sans effet de bord (pas de numéro consommé, pas de mouvement).
6. **Paramètres invalides = 400** (période incohérente, valeur multiple, vendeur inconnu) : un export ne doit jamais
   porter silencieusement sur une autre liste que celle de l'écran.

Pour une page réservée au gérant, ne rien ajouter : la règle de sécurité refuse déjà le caissier (403).

### Téléchargement

`TelechargementExport.excel(...)` et `.pdf(...)` posent `Content-Type` exact
(`application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `application/pdf`),
`Content-Disposition: attachment; filename="…"` (nom ASCII : accents retirés, `œ` devient `oe`, tout caractère hors
lettres, chiffres, `.`, `-`, `_` devient `_`, pas de séparateur de chemin ni de retour à la ligne), `Content-Length`,
`Cache-Control: no-store`, `Pragma: no-cache`, `Expires: 0` et `X-Content-Type-Options: nosniff` : ces documents contiennent
des données de la boutique, ni le navigateur ni un intermédiaire ne doit les garder. Nom conseillé :
`TelechargementExport.nomFichier("ventes", du, au, "xlsx")` → `ventes_2026-11-01_2026-11-30.xlsx` ; avec une seule date
(en général celle d'édition) `ventes_2026-11-09.xlsx`.

Quand un export est refusé pour sa taille, le contrôleur renvoie à la liste avec `redirectAttributes.addFlashAttribute("error", message)` ;
la page affiche `${error}` (voir `ventes/liste.html`).

### Volume

`LimiteExport` fixe **20 000 lignes** (propriété `stock.export.limite-lignes`). Le service lit au plus `limite + 1` lignes
(`VenteService.rechercher(filtre, max)`), puis `limite.verifier(lignes.size())` lève `ExportTropVolumineuxException`
(« Cet export dépasse 20 000 lignes. Réduisez la période ou ajoutez un filtre, puis recommencez. »). Même si l'écran
est paginé, l'export porte sur **toutes** les lignes du filtre jusqu'à cette limite. Lisez toujours avec une limite : jamais de
`findAll()` sans borne pour un export.

### Excel : ce que fait le socle

- **En tête de feuille** : nom de la boutique (gras, 14 pt), titre, filtres, notes, « Édité le … par … · Montants en GNF », ligne vide.
  Puis la ligne d'en-têtes (gras, fond gris), **figée** avec toutes les lignes au-dessus, avec **filtre automatique** ; les
  données ; la ligne de totaux (gras, filet), hors du filtre automatique. Titres répétés à l'impression, mise à l'échelle sur
  une page de large, paysage au-delà de 6 colonnes.
- **Vrais nombres et vraies dates** : montants, entiers et pourcentages sont numériques (somme, tri et graphiques fonctionnent) ;
  dates et dates-heures sont des dates Excel (`dd/mm/yyyy`, `dd/mm/yyyy hh:mm`).
- **Format** : montant `#,##0` quand la valeur n'a pas de décimales, `#,##0.00` sinon (comme l'écran) ; Excel affiche le
  séparateur de milliers de la langue de l'utilisateur (espace en français). Pourcentage `0%`, `0.0%` ou `0.00%` selon la valeur.
  Les montants sont écrits en `double` : exacts jusqu'à 15 chiffres significatifs, bien au-delà des montants en GNF.
- **Largeurs** calculées sur le contenu (8 à 60 caractères), sans bibliothèque graphique ; nom de feuille corrigé
  (`WorkbookUtil.createSafeSheetName` : 31 caractères, sans `/ \ ? * [ ] :`).
- **Injection de formules (CSV/Excel injection)** : tout texte écrit — cellule, titre, filtre, nom de boutique,
  en-tête, totaux — est une cellule de **type texte** ; s'il commence par `=`, `+`, `-`, `@`, une tabulation ou un retour chariot
  (`ExportExcelService.estDangereux`), la cellule reçoit en plus le style « préfixe apostrophe » : le texte reste littéral, tel que
  saisi, et s'affiche sans apostrophe parasite. Aucune cellule de formule n'est jamais créée. Si une tâche ajoute un jour un
  export CSV, elle devra préfixer elle-même une apostrophe aux mêmes textes.

### PDF : gabarits XHTML et pièges

Le PDF est produit en deux temps : Thymeleaf rend un gabarit de `templates/pdf/` en texte, puis openhtmltopdf
**analyse ce texte comme du XML** et le met en page. Conséquences :

- **XHTML valide et strict** : toute balise fermée (`<br />`, `<img ... />`, `<meta ... />`), attributs entre guillemets,
  un seul élément racine, imbrication correcte. Une erreur donne `Génération du PDF impossible : le gabarit n'est probablement
  pas du XHTML valide` (cause : message de l'analyseur XML avec numéro de ligne).
- **Pas d'entités nommées** (`&nbsp;`, `&eacute;`, `&euro;`) : écrire les caractères directement (UTF-8) ou en numérique
  (`&#160;`). Seules `&amp; &lt; &gt; &quot; &apos;` existent. `th:text` échappe correctement les textes saisis.
- **Pas de JavaScript, pas de Bootstrap, pas de CDN** : le CSS est écrit dans le gabarit (`<style>`), CSS 2.1 + `@page`.
  Pas de flexbox avancé ni de grille CSS : alignez avec des `<table>` (colonnes) ou des `float` simples. Les variables CSS
  (`var(--x)`), `calc()` complexe, `gap` et `position: sticky` ne sont pas gérés.
- **Aucune ressource externe** : le résolveur d'URI du service **refuse tout sauf `data:`**. Les images passent en
  `data:image/png;base64,…` (le logo est déjà ainsi dans `doc.logoDataUri`). Pas de `<link href>` ni d'`<img src="http…">`.
- **Pas de lien `@{/…}`** dans un gabarit PDF : le rendu se fait hors requête web. Pas de `sec:authorize` non plus.
- **Caractères de contrôle** (U+0000 à U+001F hors tabulation et retours) : retirés automatiquement du XHTML produit,
  ils feraient échouer l'analyse XML.
- **Polices** : gardez `font-family: 'DejaVu Sans'` (hérité du gabarit de base) ; les autres familles retombent sur une police
  sans accents complets. Le gras est bien embarqué (`font-weight: bold`) ; l'italique n'est pas fourni.
- **Pagination** : un tableau long doit porter `-fs-table-paginate: paginate` (déjà dans `table.tableau`) pour répéter son `<thead>` ;
  `tr { page-break-inside: avoid }` garde les lignes entières. Les montants se mettent en `white-space: nowrap`.
- **Pied de page** « Page X / Y » et nom de la boutique : règle `@page` générée par le service (`doc.piedCss()` protège le nom
  pour une chaîne CSS) ; vous n'avez rien à écrire. Il n'y a pas de pied pour un ticket.
- **Unités** : `mm` et `pt` (`px` est converti à 96 dpi) ; `cm` ou `mm` pour les marges.

**Gabarit enfant** : un gabarit à vous ressemble à `templates/pdf/tableau.html` : il déclare le fragment du corps et laisse
`pdf/base` fournir l'en-tête de boutique, le CSS de base et la page.

```html
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:th="http://www.thymeleaf.org" th:replace="~{pdf/base :: page(~{::div#contenu})}">
<body>
<div id="contenu">
    <h1 th:text="${titre}">Titre</h1>
    <p th:text="${recu.numero}">numéro</p>
    ...
</div>
</body>
</html>
```

Variables disponibles dans tout gabarit : `doc` (`ContexteDocument` : `nomBoutique`, `coordonnees()`, `aUnLogo()`, `logoDataUri`,
`editeur`, `editeLeTexte()`), `format` (`FormatPapier`), `titre` si vous le passez, et vos propres variables.
Classes CSS fournies : `h1`, `.filtres`, `.note`, `table.tableau` (+ `.dense`, `.num` aligné à droite, `.nw` sans retour,
`tr.pair`, `tr.totaux`). `pdf/base :: entete` donne l'en-tête de boutique seul (un reçu peut le reprendre sans la page).

**Ticket 80 mm** : `FormatPapier.TICKET_80MM` (80 × 297 mm, marges 3 mm, pas de pied) ou `FormatPapier.ticket(hauteurMm)`.
L'en-tête de boutique devient centré. Le gabarit générique `pdf/tableau` n'est PAS fait pour 80 mm (trop de colonnes) :
un reçu a son propre gabarit en une ou deux colonnes.

### Vérification visuelle

`SocleExportTests.genererLaDemonstration` et `VenteExportTests.genererLaDemonstrationDuPilote` écrivent des PDF et des
Excel de démonstration quand la variable d'environnement `EXPORT_DEMO_DIR` est définie :

```sh
EXPORT_DEMO_DIR=/tmp/demo ./gradlew test --tests '*SocleExportTests' --tests '*VenteExportTests'
pdftoppm -png -r 80 /tmp/demo/demo-paysage-60-lignes.pdf /tmp/demo/page   # puis regarder les PNG
pdffonts /tmp/demo/demo-paysage-60-lignes.pdf                              # les polices doivent être « emb yes »
```

## Pilote : la liste des ventes

Sur `/ventes`, les boutons **Excel** et **PDF** (à côté de « Effectuer une vente ») exportent exactement la liste affichée.

| Élément | Détail |
|---|---|
| Routes | `GET /ventes/export/excel` et `GET /ventes/export/pdf` (`VenteExportController`) ; droits de « `/ventes/**` » : gérant et caissier, 302 vers la connexion sans compte, 405 pour tout autre verbe |
| Filtres | `filtre=echues`, `vendeur=<id>` (gérant seulement, ignoré pour un caissier comme à l'écran), `du=AAAA-MM-JJ`, `au=AAAA-MM-JJ` (bornes incluses), `q=<recherche>` (100 caractères au plus) |
| Définition commune | `FiltreVentes` + `VenteService.rechercher(...)` servent l'écran ET l'export ; tri identique (récentes d'abord ; échues : échéance la plus ancienne d'abord) |
| Période à l'écran | deux champs « Du » et « Au (inclus) » ajoutés au formulaire de filtre (visibles du gérant et du caissier) ; période invalide = ignorée avec avertissement à l'écran, **400** pour l'export |
| Recherche | la barre de recherche du tableau (simple-datatables) est côté navigateur : `static/js/export-liste.js` ajoute son texte au lien d'export sous `q` (via `URLSearchParams`, jamais en HTML). Le serveur garde les ventes dont **chaque mot** figure dans le texte de la ligne (numéro, dates, client, vendeur, montants, statut), sans tenir compte des accents ni de la casse |
| Format PDF | A4 paysage (11 ou 12 colonnes) |
| Nom du fichier | `ventes_<du>_<au>.xlsx|pdf` avec une période, sinon `ventes_<date du jour>.xlsx|pdf` |
| En-tête | boutique du compte, titre « Journal des ventes », filtres appliqués (« Période du … au … · Vendeur : … · Ventes échues non soldées · Recherche : « … » » ou « Toutes les ventes ») |

**Colonnes.** Numéro, Date (date et heure), Client (« Client de passage » sans client), Vendeur (« Non renseigné »), Total, Retours,
Versé, Avance utilisée, Reste, Statut (« Réglée », « À régler », « À régler (en retard) »), Échéance, puis **Bénéfice brut pour le gérant seulement**.

**Totaux.** Dernière ligne : nombre de ventes, puis Σ total, retours, versé, avance utilisée, reste et bénéfice brut.

**Bénéfice brut (gérant).** Σ (prix unitaire − coût figé) × quantité des lignes de la vente, **moins** la marge des marchandises
retournées sur cette vente (même définition que le tableau de bord et l'état « Ventes par vendeur »). Une ligne sans coût connu
compte pour 0 ; leur nombre est signalé sous le tableau. Calcul par `VenteExportRepository` (deux requêtes agrégées, par paquets de
1 000 identifiants). Pour un caissier, ces requêtes ne sont **pas exécutées** : ni colonne, ni note, ni valeur dans le fichier.

**Volume.** Au-delà de la limite, la requête est refusée **avant** de fabriquer le fichier : retour à `/ventes` (mêmes filtres)
avec l'alerte « Cet export dépasse 20 000 lignes… ».

### Vérifications

```sh
./gradlew test --tests '*SocleExportTests'
./gradlew test --tests '*VenteExportTests'
node --test src/test/js/export-liste.test.cjs
```

`SocleExportTests` (sans base) : PDF qui commence par `%PDF`, nom de boutique, accents, « œ », « € », « — » dans le texte extrait
par `PDFTextStripper`, polices **toutes embarquées**, 60 lignes sur plusieurs pages avec en-tête répété et « Page X / Y », logo
présent ou absent, ticket de 80 mm, textes saisis échappés, caractères de contrôle ; Excel : montants et dates numériques, formats,
totaux, en-têtes gras figés, filtre automatique, **neutralisation de toutes les formes de formule**, nom de feuille ; noms de fichier sûrs.

`VenteExportTests` (deux boutiques, gérant, caissier, gérant de l'autre boutique) : chaque nombre comparé au calcul à la main
(bénéfice brut avec retour et coût absent, totaux avec centimes) ; huit combinaisons de filtres où la liste de l'écran, l'Excel et le PDF
contiennent **les mêmes ventes** ; recherche sans accents ; en-têtes de téléchargement ; le caissier ne reçoit ni coût ni bénéfice
(en-têtes, valeurs, texte du PDF) ; l'autre boutique est exclue ; dépassement de limite ; paramètres invalides ; accès sans connexion ;
lecture seule.

## Exports des listes

Chaque liste ci-dessous a ses boutons **Excel** et **PDF** (`btn-outline-success` / `btn-outline-danger`, à côté du bouton d'action de la page),
construits comme ceux du pilote des ventes : l'export contient **exactement les lignes de la liste affichée** (mêmes filtres, même recherche),
de la boutique du compte connecté, avec les droits de l'écran. Toutes les routes sont des `GET` en lecture seule, placées sous le préfixe de la
page (elles héritent de sa règle de sécurité), et un paramètre invalide, répété ou trop long donne **400**.

| Liste | Routes (`.../excel`, `.../pdf`) | Droits | Colonnes | Filtres respectés | Totaux |
|---|---|---|---|---|---|
| Achats | `/achats/export` | gérant ; caissier 403 | Numéro, Date, Fournisseur, Saisi par, Total, Retours, Versé, Avance utilisée, Reste, Statut, Échéance (11, paysage) | `filtre=echues`, `q` | nombre d'achats, Σ total, retours, versé, avance, reste |
| Produits | `/produits/export` | gérant **et** caissier (règle ajoutée dans `SecurityConfig`) | Référence, Produit, Catégorie, Nature, Unité, Quantité, Seuil d'alerte, État du stock, Prix de vente (9, paysage). **Gérant seulement** : Fournisseur, Coût moyen, Dernier prix d'achat, Marge unitaire, Taux de marge, Valeur du stock (15, paysage) | `categorie=<id>`, `stock=en-stock\|disponible\|faible\|rupture\|a-surveiller\|service`, `q` | nombre de produits ; gérant : Σ valeur du stock |
| Clients | `/clients/export` | gérant et caissier, comme l'écran (solde et avance sont visibles du caissier) | Nom, Téléphone, Email, Adresse, Me doit, Je dois, Avance disponible, Dont solde de départ (8, paysage) | `contact=telephone\|email\|sans-contact`, `q` | nombre de fiches, Σ de chaque montant |
| Fournisseurs | `/fournisseurs/export` | gérant ; caissier 403 | mêmes colonnes que les clients | `contact`, `q` | idem |
| Devis | `/devis/export` | gérant et caissier | Numéro, Date, Client, Auteur, Total, Valable jusqu'au, État (7, portrait) | `etat=en-cours\|expire\|transforme\|annule`, `q` | nombre de devis, Σ total |
| Retours | `/retours/export` | gérant ; caissier 403 | Numéro, Type, Date, Document d'origine, Client / fournisseur, Montant, Remboursé, Avoir (8, paysage) | `type=vente\|achat`, `q` | nombre de retours, Σ montant, remboursé, avoir |
| Journal de trésorerie | `/caisse/export` | gérant ; caissier 403 (comme `/caisse/journal`) | Date et heure, Compte, Sens, Montant (signé), Source, Motif, Utilisateur, Annulation (8, paysage) | `compte=CAISSE\|ORANGE_MONEY\|BANQUE`, `source=<libellé affiché>`, `sens=entree\|sortie\|neutre\|inconnu`, `du`, `au` (jours inclus), `q` | nombre de mouvements, Σ montant (net) ; en notes : entrées et sorties de la sélection, soldes par compte, solde total et indicateurs du jour de l'écran |
| Catégories | `/categories/export` | gérant ; caissier 403 | Nom, Nombre de produits (2, portrait) | `q` | nombre de catégories, Σ produits |

### Règles communes

- **Format du papier** : A4 **paysage au-delà de 7 colonnes**, portrait sinon (`LivraisonExport.formatPour`, appliqué par tous les exports de cette tâche).
- **Nom du fichier** : `<liste>_<date du jour>.xlsx|pdf` ; le journal de trésorerie avec une période : `tresorerie_<du>_<au>.xlsx|pdf`.
- **Volume** : lecture de `limite + 1` lignes au plus, **filtres de base de données compris dans la limite** (statut échu, état d'un devis, type de retour, compte, source,
  sens et période du journal). Les filtres calculés ou textuels (recherche `q`, état du stock, catégorie et coordonnées des répertoires, qui n'existent que dans la
  page) sont appliqués après la lecture : une liste de plus de 20 000 lignes avant ces filtres est refusée, jamais tronquée. Retour à la liste avec l'alerte
  « Cet export dépasse 20 000 lignes… » ; le filtre revient quand la page le lit dans son adresse (`/achats?filtre=echues`, `/devis?etat=…`, `/retours?type=…`,
  `/produits?stock=…`, `/clients?contact=…`).
- **Tri** : celui des écrans à l'ouverture de la page (achats et devis du plus récent au plus ancien, retours idem, journal par date décroissante, échus par échéance,
  produits par référence, répertoires et catégories par nom, avec comparaison « naturelle » `REF-2` avant `REF-10`). Le tri choisi dans le navigateur n'est pas transmis.
- **Recherche** : la barre de la page est côté navigateur ; `export-liste.js` ajoute son texte sous `q`, le serveur garde les lignes dont **chaque mot** figure dans le texte
  de la ligne, sans accents ni casse (`RechercheListe`). Pour les répertoires, un mot de chiffres retrouve aussi un téléphone écrit autrement ; pour le journal, la recherche
  porte aussi sur la mention d'annulation. La recherche des produits couvre le fournisseur **pour le gérant seulement**, comme l'écran.
- **Le caissier n'a ni coût, ni marge, ni trésorerie** : pour les produits, `ProduitExportService` décide avec `DroitsExport.estGerant()` et ne lit ni le coût moyen, ni le dernier prix
  d'achat, ni le fournisseur ; aucun paramètre n'élargit ce droit. Les listes réservées au gérant le revérifient dans leur service (`DroitsExport.exigerGerant()`, 403) en plus de la
  règle de sécurité de leur route.

### Ce que les exports ajoutent au socle (génériques)

| Ajout | Rôle |
|---|---|
| `LivraisonExport` | fabrique la réponse Excel ou PDF d'un `TableauExport` (nom de fichier, paysage au-delà de 7 colonnes, retour à la liste si trop volumineux) : un contrôleur d'export tient en quelques lignes |
| `RechercheListe` | recherche sans accents ni casse (reprise du pilote, utilisée aussi par `VenteExportService`), tri naturel |
| `ParametresListe` | lecture stricte des paramètres (`q`, choix, identifiant, date, période) : 400 sur toute valeur invalide |
| `DroitsExport.exigerGerant()` | seconde barrière des listes réservées au gérant |
| `export-liste.js` | accepte `data-export-champs="q=#champ;stock=#autre;source=#liste@libelle"` pour les pages qui filtrent avec leurs propres champs (produits, répertoires, journal, catégories) ; le lien est recalculé au clic (les boutons « Réinitialiser » de ces pages changent les champs sans événement). Sans cet attribut, comportement du pilote (barre de recherche simple-datatables) |
| `pdf/base.html` | les **intitulés** de colonnes numériques peuvent passer à la ligne (valeurs toujours sur une ligne) : un tableau de 15 colonnes tient sur une page paysage |

### Détails par liste

- **Achats** : statut « Réglé », « À régler » ou « À régler (en retard) » ; « Saisi par » vaut « Non renseigné » sans compte. Le libellé « Versé » est celui de l'écran.
- **Produits** : « Nature » vaut « Article » ou « Service » ; quantité et seuil sont vides pour un service ; une fiche dont les prix ne sont pas définis n'a ni prix, ni coût, ni marge
  (« À définir » à l'écran). Valeur du stock = quantité × coût moyen pondéré (articles aux prix définis). Le taux de marge est un vrai pourcentage Excel (`15,6 %`).
- **Clients et fournisseurs** : « Me doit » est ce que la personne doit à la boutique, « Je dois » ce que la boutique lui doit (solde net de l'écran, avance déduite) ; « Avance
  disponible » et « Dont solde de départ » sont ceux de l'écran. Les cellules à zéro sont vides.
- **Devis** : l'état est celui calculé de l'écran (« Expiré » = en cours dont la validité est passée). L'auteur est ajouté à ce que l'écran montre.
- **Retours** : retours de vente et d'achat mêlés, du plus récent au plus ancien ; la boutique et le gérant sont vérifiés aussi par `RetourVenteService` et `RetourAchatService`.
- **Journal de trésorerie** : « Sens » vaut Entrée, Sortie, Sans variation ou Non renseigné (« (correction) » pour une correction d'entrée) ; le montant est signé ; « Annulation » porte
  « Annulé le … · motif : … » sur un mouvement annulé et « Annulation du mouvement du … » sur l'annulation ; le motif d'une dépense ou d'un transfert est suivi de sa référence
  (« Dépense #12 »). Les soldes (par compte et total) et les indicateurs de la journée sont ceux de l'écran, **indépendants des filtres** comme à l'écran ; `source` est le libellé
  affiché dans la liste déroulante (« Solde initial », « VENTE », « Non renseignée »…).
- **Catégories** : la page ne propose qu'une recherche (identifiant ou nom).

### Vérifications des listes

```sh
./gradlew test --tests '*ListesCommercialesExportTests' --tests '*ListesCatalogueExportTests' --tests '*TresorerieExportTests' --tests '*ListeExportOutilsTests'
node --test src/test/js/export-liste.test.cjs
EXPORT_DEMO_DIR=/tmp/demo ./gradlew test --tests '*ListesCatalogueExportTests'   # PDF de démonstration des produits (gérant et caissier), clients, fournisseurs, catégories
```

Chaque classe a deux boutiques, un gérant et un caissier, et compare les nombres au calcul à la main : Excel ouvert avec POI (nombres et dates réels, totaux), PDF qui commence par `%PDF`
et contient le nom de la boutique et des lignes attendues, les filtres comparés à la liste de l'écran (modèle du contrôleur), l'autre boutique exclue, 403 pour un rôle non autorisé,
aucune formule dans les cellules, dépassement de limite, paramètres invalides, accès sans connexion, présence des boutons et de leurs champs de filtre sur la page. Le caissier n'a, dans
les produits, aucun coût, marge, fournisseur ni valeur de stock (en-têtes, nombres, texte du PDF, recherche) et reçoit 403 sur le journal de trésorerie.

## Limites connues

- Les montants Excel sont des `double` (15 chiffres significatifs) ; un montant de plus de 15 chiffres perdrait ses centimes.
- Le gabarit générique `pdf/tableau` suppose des colonnes de largeur automatique : au-delà de 12 colonnes, passez en paysage
  et réduisez les textes, ou écrivez un gabarit dédié.
- Le logo est celui du service de logos (PNG normalisé) : il est imprimé à 17 mm de haut au plus.
- Le PDF n'est pas PDF/A et n'est pas signé.
- Le tri choisi dans le navigateur (colonnes cliquées) n'est pas transmis aux exports des listes : ils suivent l'ordre d'ouverture de la page.
- La recherche serveur reproduit celle de l'écran mais pas à la lettre (l'écran cherche dans le texte affiché, y compris « GNF » ;
  l'export cherche numéro, dates, client, vendeur, montants et statut).

## États du gérant

Huit états, réservés au gérant (`ADMIN`), sous le menu **États** (`/etats`). Chacun s'affiche à l'écran et se télécharge en **PDF** et en **Excel**
par le socle d'export (même boutique, mêmes chiffres dans les trois formes). Le caissier reçoit **403** sur toutes les adresses `/etats/**`
(règle de `SecurityConfig` + contrôle `DroitsEtats.exigerGerant()` dans chaque service) ; un visiteur sans compte est renvoyé vers la connexion.

| État | Page | Export (`/export/pdf`, `/export/excel`) | Paramètre |
|---|---|---|---|
| Bilan de la période | `/etats/bilan` | oui | période `du` / `au` |
| État des créances | `/etats/creances` | oui | `date` de référence |
| État des dettes | `/etats/dettes` | oui | `date` de référence |
| Relevé des créances d'un client | `/etats/releve/client/{id}` | oui | période |
| Relevé des dettes d'un fournisseur | `/etats/releve/fournisseur/{id}` | oui | période |
| État du stock | `/etats/stock` | oui | aucun (jour de l'édition) |
| Liste des dépenses | `/etats/depenses` | oui | période |
| Liste des encaissements | `/etats/encaissements` | oui | période |
| Ventes par vendeur | `/etats/ventes-par-vendeur` (page existante) | oui (nouveau) | période |

Les relevés s'ouvrent depuis les listes des clients et des fournisseurs (lien « Relevé des créances » / « Relevé des dettes », gérant seulement).
Sans paramètre, la période est le **mois courant** (de l'horloge `recoveryClock`, comme `EcheanceService.aujourdHui()`). Les bornes sont celles du
tableau de bord : `[du à 0 h, au + 1 jour à 0 h)`, le jour `au` est donc inclus. Années acceptées : 2000 à 9998 ; `au` ne peut précéder `du`.
**Période invalide** : la page s'affiche avec un avertissement (mois courant) et jamais de 500 ; l'export répond **400** ; un format autre que
`pdf` ou `excel` répond 404 ; un partenaire d'une autre boutique répond 404.

### Ce qui est réutilisé, ce qui est propre aux états

Aucun chiffre commun avec le tableau de bord n'est recalculé : les états **appellent** les mêmes méthodes, donc donnent exactement les mêmes
nombres (un test compare les objets entiers, égalité de `record`).

| Chiffre | Source |
|---|---|
| Chiffre d'affaires net, bénéfice brut, dépenses, bénéfice net | `TableauDeBordService.indicateursDeLaPeriode` (même méthode privée que `indicateursDuMois`) |
| Créances, dettes, en retard, avances, position nette | `TableauDeBordService.creancesEtDettes()` → `SoldePartenaireService` |
| Valeur du stock | `TableauDeBordService.valeurStock()` |
| Ventes par vendeur | `TableauDeBordService.ventesParVendeur` |
| Détail des créances et des dettes | `SoldePartenaireService` (soldes, reste du solde de départ, avance) + `EtatsRepository` (factures non soldées) |
| Trésorerie, encaissements, listes, relevés | `EtatsRepository` (nouveau, lecture seule, toutes les requêtes filtrées par boutique) |

Quand un état montre un total qui existe aussi ailleurs, il le **rapproche** et refuse de s'éditer en cas d'écart (message « relancez l'état ») :
valeur du stock, total des dépenses, total des créances/dettes, avances, retards. Ce n'est jamais un chiffre arrondi pour « faire tomber juste ».

### Définitions exactes

**Bilan de la période** (5 tableaux ; un onglet Excel chacun).
- *Ventes de la période* : somme des totaux des ventes dont la date est dans la période. *Retours de vente* : somme des retours à **leur** date.
  *Chiffre d'affaires net* = ventes − retours.
- *Coût des ventes* = chiffre d'affaires net − bénéfice brut : Σ coût moyen figé à la vente × quantité, net du coût des marchandises retournées.
  *Bénéfice brut* = Σ (prix − coût figé) × quantité des ventes − marge des retours. Une ligne sans coût connu est comptée à 0 (signalée par une note).
- *Dépenses* = opérations de trésorerie de type DEPENSE de la période (annulées ou non : une dépense ne s'annule pas dans l'application).
  Les achats de marchandises et les transferts n'en sont pas. *Bénéfice net* = bénéfice brut − dépenses (peut être négatif).
- *Trésorerie par compte* (caisse, Orange Money, banque), lue dans le journal `mouvement_caisse` : **début** = somme des mouvements avant le début de
  la période (un mouvement sans date compte comme antérieur) ; **fonds de départ** saisis dans la période ; **encaissements** (montants positifs) et
  **décaissements** (négatifs) hors fonds, transferts et annulations ; **annulations de règlements** (net, signées) ; **transferts** (net, somme nulle
  sur les trois comptes) ; **fin = début + fonds + encaissements + décaissements + annulations + transferts**. Pour une période qui va jusqu'au jour de
  l'édition, la fin égale `CaisseService.getSoldeCompte` (testé). Une dépense annulée par écriture inverse voit son annulation dans la colonne
  *annulations*, pas dans les décaissements.
- *Par nature* : ventes payées à la création, règlements de ventes, règlements de soldes de départ des clients, remboursements reçus des fournisseurs
  (encaissements) ; achats payés à la création, règlements d'achats, règlements de soldes de départ des fournisseurs, remboursements aux clients,
  dépenses (décaissements).
- *Créances, dettes, avances* : **situation du jour de l'édition**, identique au tableau de bord (voir limites).
- *Valeur du stock* : **valeur au jour de l'édition** (quantité × coût moyen actuel, services exclus), libellé comme tel dans le titre, le tableau
  et la note. La valeur à la fin de la période n'est **pas** affichée : le coût moyen n'est pas conservé jour par jour, la reconstituer serait inventer.
- Depuis l'étape 3b1 (financements), le bilan montre aussi les apports et retraits des associés, les emprunts reçus et prêts accordés, les
  remboursements de capital, les intérêts et charges financières, les produits financiers et les autres produits et charges. Le **bénéfice net**
  inclut les produits et charges financiers (intérêts d'emprunt, frais de dossier, intérêts de prêt, produits et charges divers) mais **ni le
  capital des emprunts ni les apports et retraits** : ceux-ci modifient seulement la trésorerie (variation de trésorerie). Voir
  [Financements](lot-3.md#financements-et-flux-hors-ventes-et-achats).

**État des créances / des dettes** (date de référence ; par défaut le jour). Pour chaque client (fournisseur) : les factures dont le reste est positif
(numéro, date, échéance, **total effectif** = total − retours, **versé**, **avance utilisée**, **reste** = max(0, total effectif − versé − avance utilisée),
jours de retard), le **reste du solde de départ** (départ − règlements nets des annulations), l'**avance disponible** (en négatif) et le total du partenaire.
Une ligne **« Ventes sans client »** (« Achats sans fournisseur ») regroupe les factures sans partenaire. Tri : montant en retard décroissant, puis reste
décroissant, puis plus ancienne échéance, puis nom. Une échéance est en retard quand elle est **strictement antérieure** à la date de référence ; les jours
de retard sont l'écart en jours. Le total (hors avances) est celui de `SoldePartenaireService` ; le récapitulatif donne restes des factures, restes des
soldes de départ, total, dont en retard, avances et solde net (créances − avances des clients ; dettes − avances chez les fournisseurs).

**Relevé d'un partenaire** (période). Le livre reprend, pour ce partenaire seulement : solde de départ de la fiche, factures, versements à la création,
règlements, annulations de règlement, retours (réduisent la créance ou la dette), remboursements de retour (argent rendu ou reçu), avoirs (ligne sans
mouvement d'argent, la part gardée reste en avance) et règlements du solde de départ. Solde positif : le client doit (la boutique doit, pour un fournisseur) ;
négatif : avance. Les opérations antérieures à la période sont reportées en une ligne, les opérations postérieures ne sont pas listées (une note le dit).
Le solde final de toutes les opérations est **comparé à la fiche** (`soldeNet`) : une note « Concordance » ou « ATTENTION » l'indique ; les tests exigent
« Concordance » sur tout le jeu de données.

**État du stock** : par catégorie, pour chaque produit en stock (services exclus) : quantité, unité, coût moyen, valeur = quantité × coût moyen, seuil
d'alerte, état (Disponible, Stock faible quand quantité ≤ seuil, Rupture à 0) ; total par catégorie et total général (égal à `valeurStock()`).

**Liste des dépenses** : date, compte, motif (l'application n'a pas de catégorie de dépense), utilisateur, montant, statut ; total, puis total et nombre par
compte. Une dépense portant une écriture inverse est signalée « Annulée le … » et reste comptée, comme au tableau de bord.

**Liste des encaissements** : tous les mouvements positifs de la période hors fonds de départ, transferts et annulations, avec compte, source
(paiement à la vente, règlement de vente, règlement de solde de départ d'un client, remboursement d'un fournisseur), document, client ou fournisseur,
utilisateur et statut « Annulé le … (motif) ». Un deuxième tableau liste les **annulations d'encaissements** (en négatif) ; le troisième donne, par compte,
encaissements, annulations et net. Les annulations de décaissements (règlement d'achat, dépense…) ne sont pas des encaissements : une note les signale.

**Ventes par vendeur** : page existante inchangée, avec les boutons PDF et Excel ; le total est le chiffre d'affaires net du bilan.

### Limites

- **Historique** : l'application ne conserve ni les restes à payer ni le coût moyen jour par jour. Créances, dettes, avances et valeur du stock sont donc
  ceux du **jour de l'édition** ; la date de l'état des créances et des dettes ne sert qu'à décider des retards. Le bilan d'une période passée affiche
  ces chiffres avec la mention de leur date.
- Pas de catégories de dépense ; une dépense ne s'annule pas (le drapeau d'annulation est défensif).
- Les excédents de retour gardés en avoir n'apparaissent pas dans la trésorerie (aucun mouvement d'argent).
- Volume : 20 000 lignes par état (`LimiteExport`) ; au-delà, l'export est refusé avec un message.
- Les montants Excel sont des nombres (15 chiffres significatifs), les PDF reprennent le format du socle (milliers séparés par une espace insécable).

### Extensions du socle (ajouts, rien de retiré)

`TableauExport.sousTotal(...)` (ligne mise en valeur dans les trois formes), `ExportExcelService.ecrire(List<TableauExport>, …)` (un onglet par tableau,
noms uniques), `DocumentPdfService.rendreEtat(...)` (gabarit `pdf/etat`, plusieurs tableaux sur le même document) et le style `.section-etat` de `pdf/base`.

### Vérifications

```bash
./gradlew test --no-daemon --tests '*EtatsGerantTests'   # jeu de données fixe, calculs à la main, PDF, Excel, droits, deux boutiques
service mariadb start && ./gradlew etatsMariaDbTest --no-daemon   # requêtes des états sur MariaDB jetable
ETATS_SORTIE=/chemin/dossier ./gradlew test --no-daemon --tests '*EtatsGerantTests.ecrit*'   # écrit les PDF pour la revue visuelle
pdftoppm -png -r 70 bilan.pdf bilan                      # puis ouvrir les images
```

`EtatsGerantTests` construit avec les vrais services deux boutiques : fonds de départ, ventes comptant, à crédit, avec avance utilisée et surplus, règlement
puis annulation, retours en argent, en avoir et imputés, achats, retours d'achat, dépenses, transfert, règlements de soldes de départ, une vente et un achat
anciens sans partenaire, opérations hors période (mai et juillet). Chaque total est commenté avec son calcul. Résultats de référence, période du 01/06 au 30/06/2026 :
chiffre d'affaires net 166 000, bénéfice brut 96 000, dépenses 142 000, bénéfice net −46 000, trésorerie 1 080 000 → 1 039 000, créances 185 000, dettes 129 000,
valeur du stock 946 000.

## Justificatifs de solde

Un justificatif de solde est un **PDF officiel** de la boutique (A4 portrait, en-tête de la boutique, polices embarquées) qui atteste un solde à une date, pour le
remettre à un client, à un fournisseur ou à un contrôleur. **Réservé au gérant** : le caissier reçoit **403** (règle `/etats/**` de `SecurityConfig`, revérifiée
par `JustificatifSoldeService`), un visiteur sans compte est renvoyé vers la connexion, un client ou un fournisseur d'une autre boutique donne **404**, un
paramètre absent, répété ou invalide donne **400**. Tout est en lecture seule (`GET`), aucune table, aucun compteur, aucune modification de schéma.

| Document | Route | Paramètre | Contenu |
|---|---|---|---|
| Solde d'un client | `GET /etats/justificatifs/client/pdf?id=` | `id` du client | identité, solde dû ou avance, détail, montant en chiffres et en lettres, signature et cachet, « Bon pour accord » |
| Solde d'un fournisseur | `GET /etats/justificatifs/fournisseur/pdf?id=` | `id` du fournisseur | idem, sens de la dette inversé |
| Solde de trésorerie | `GET /etats/justificatifs/tresorerie/pdf[?date=AAAA-MM-JJ]` | `date` d'arrêté (défaut : aujourd'hui) | Caisse (espèces), Orange Money, Banque, total, montant en lettres, signatures |
| Page | `GET /etats/justificatifs` | | les trois types ; entrée « Justificatifs de solde » dans la page d'accueil des états |

**Boutons.** Un bouton « Justificatif de solde » (gérant seulement, `sec:authorize`) figure sur la ligne de chaque client (`/clients`) et de chaque fournisseur
(`/fournisseurs`), à côté du relevé. Le caissier ne le voit pas.

### Contenu

- **Tous** : en-tête de la boutique, titre, **numéro du document**, **date d'édition**, **date d'arrêté**, mention « Arrêté à la somme de … », zone de signature et de cachet.
- **Client ou fournisseur** : identité (nom, téléphone, email, adresse quand ils existent) ; solde (« Le client doit à la boutique la somme de », « La boutique détient une
  avance au nom du client, d'un montant de », « La boutique doit au fournisseur… », « La boutique a une avance chez le fournisseur… », ou « Solde nul ») en chiffres et en
  lettres ; détail : **solde de départ restant à régler** (avec son échéance), **factures non soldées** (numéro, date, échéance, reste), total dû, **avance disponible**
  (en déduction) ; zone « Bon pour accord » pour le partenaire.
- **Trésorerie** : solde de chaque compte, total, mention « Solde calculé par l'application ; à rapprocher du comptage physique. », zone de signature de la boutique et visa facultatif d'un contrôleur.

### Même solde que la fiche, le relevé, le journal et le bilan

Aucun calcul n'est réécrit :

- **Partenaire** : le solde est `SoldePartenaire.soldeNet()` de `SoldePartenaireService` (fiche, tableau de bord, états), le reste du solde de départ et l'avance sont ceux de la fiche. Le
  justificatif suit la convention des relevés : positif = le client doit à la boutique (la boutique doit au fournisseur), négatif = avance ; solde = reste du solde de départ + restes des
  factures − avance. Les factures non soldées viennent d'une requête de même condition que le reste à payer ; si leur somme ne redonne pas le solde de la fiche, le document est refusé
  (retour à la page avec le message, jamais un document qui ne s'additionne pas).
- **Trésorerie** : aujourd'hui, `CaisseService.getSoldeCompte` (le solde du journal) ; un jour passé, la somme des mouvements jusqu'à la fin de ce jour (`EtatsRepository.soldesAvant`, la requête
  de « trésorerie à la fin de la période » du bilan ; un mouvement sans date compte comme antérieur).

### Dates d'arrêté

| Document | Dates possibles | Pourquoi |
|---|---|---|
| Trésorerie | **n'importe quel jour passé, ou aujourd'hui** (fin de journée, 23 h 59 comprise) ; une date future ou antérieure à 2000 donne 400 | le solde se déduit exactement des mouvements datés ; testé jour par jour contre la somme du journal et contre le bilan |
| Client, fournisseur | **le jour de l'édition seulement**, écrit dans le document | l'application ne conserve ni les restes à payer ni les avances jour par jour |

Une date passée pour un partenaire n'est **pas proposée** : le relevé permettrait de reconstituer le total d'un compte, mais pas le détail par facture (restes, avances utilisées) à cette date,
et un chiffre non exact sur un document officiel est pire qu'une limite affichée. Le document le dit : « la date d'arrêté est donc la date d'édition ».

### Numéro du document et nom du fichier

`JS-AAAAMMJJ-<type><identifiant>` : `JS-20261109-C12` (client 12), `JS-20261109-F7` (fournisseur 7), `JS-20261109-T3` (trésorerie de la boutique 3). `AAAAMMJJ` est la date d'arrêté ; le numéro est
recalculé, donc **stable** (même partenaire, même date, même numéro) et sans compteur. Il identifie le partenaire et le jour, pas le montant : deux éditions le même jour après une opération
portent le même numéro, la date et l'heure d'édition de l'en-tête les distinguent. Le fichier s'appelle `<numéro>.pdf`.

### Montant en lettres

`org.example.stock.export.MontantEnLettres` (réutilisable par les reçus) : `enLettres(long)` et `francsGuineens(BigDecimal)`. Graphie traditionnelle, comme « cent vingt-cinq mille francs guinéens » :
« vingt et un », « soixante et onze », « quatre-vingts » mais « quatre-vingt-un », « quatre-vingt-onze » ; « cent » et « quatre-vingt » prennent un « s » seulement quand ils terminent le nombre ou précèdent
« million » et « milliard » (« deux cents », « deux cent mille », « deux cents millions ») ; « mille » invariable et jamais « un mille » ; « un million », « deux millions » ; une somme ronde en millions prend
« de » (« deux millions de francs guinéens ») ; « zéro franc guinéen », « un franc guinéen » ; centimes éventuels (« … et cinquante centimes ») ; négatif précédé de « moins ». Limite : moins de mille milliards.

### Limites

- Le justificatif d'un **autre partenaire** (banque, associé, personnel, divers) existe depuis l'étape 3b1 : numéro `JS-AAAAMMJJ-P<id>`, solde du jour, montant en lettres et signatures (voir [Financements](lot-3.md#financements-et-flux-hors-ventes-et-achats)).
- Pas de signature électronique ni de PDF/A : le document est à signer et à cacheter à la main.
- Le solde d'un partenaire est celui du moment de l'édition ; si une opération est saisie ensuite, le justificatif déjà remis ne change pas, il faut en éditer un nouveau.

### Vérifications

```bash
./gradlew test --no-daemon --tests '*MontantEnLettresTests' --tests '*JustificatifsSoldeTests'
JUSTIFICATIFS_SORTIE=/chemin/dossier ./gradlew test --no-daemon --tests '*JustificatifsSoldeTests'   # écrit les six PDF pour la revue visuelle
pdftoppm -png -r 70 client-boubacar-dette.pdf client-boubacar-dette                                   # puis ouvrir les images
```

`MontantEnLettresTests` : les cas difficiles (0, 1, 21, 71, 80, 81, 91, 100, 200, 201, 1 000, 1 001, 1 000 000, 2 000 000, 1 250 300, 999 999 999 et d'autres) et un contrôle d'orthographe sur les 200 000 premiers nombres.
`JustificatifsSoldeTests` (jeu de données des états, deux boutiques) : solde égal à celui de la fiche et du relevé pour trois clients et trois fournisseurs (solde de départ, avance, avoir, retour imputé, annulation de règlement) ;
trésorerie égale au journal chaque jour du 01/05 au 15/07, au bilan et à `getSoldeCompte` ; PDF (`%PDF`, chiffres et lettres, mentions, signatures) ; caissier 403, autre boutique 404, paramètres invalides 400, lecture seule, numéro stable, boutons réservés au gérant.

## Reçus

Reçus PDF imprimables d'une vente et d'un règlement, au format choisi par la boutique. Code : paquet `org.example.stock.recu`
(`RecuController`, `RecuService`, `RecuPdf`), gabarits `templates/pdf/recu-vente.html` et `recu-reglement.html`, styles `resources/pdf/recu-*.css`.

### Contenu

- **Reçu de vente** : nom, logo (A4 et ticket ; le logo de plus de 100 Kio est omis du ticket), adresse et contacts de la boutique, numéro de facture, date, vendeur,
  client, lignes (désignation, quantité avec unité, prix unitaire, total), total, retours et total net s'il y en a, versé, avance utilisée, surplus, reste à payer,
  compte de paiement, mention de clôture sobre. Jamais de coût, de marge ni de prix d'achat.
- **Reçu de règlement** (encaissement d'une vente, paiement d'un achat, règlement d'un solde de départ client ou fournisseur) : montant, compte, date, document concerné,
  **reste après ce règlement** (recalculé à la date du règlement : un règlement ultérieur ou un retour postérieur ne le change pas), auteur. Un règlement annulé ensuite porte un
  cadre « ANNULÉ le … » avec le motif et l'auteur de l'annulation, et le montant est barré.

### Formats

- **A4** : style de la facture (en-tête du socle d'export, tableau des lignes, totaux), une page.
- **Ticket 80 mm** : page de 80 mm de large (zone imprimable 74 mm), police 8 pt au minimum, aucune colonne coupée, désignations longues passées à la ligne (un mot sans espace de plus de
  16 caractères est coupé tous les 12 caractères). La hauteur est ajustée au contenu : le gabarit est rendu une première fois sur une page très haute, le bas du texte est mesuré
  avec PDFBox, puis il est rendu sur une page juste assez haute (`RecuPdf`).

### Réglages

Page **Paramètres de la boutique** (gérant) : « Format des reçus » (A4 ou ticket 80 mm) et « Proposer l'impression du reçu après chaque opération » (oui ou non). Les champs sont dans la
liste blanche du formulaire ; un champ inconnu ou une valeur inconnue donne 400. Un envoi sans ces champs conserve les réglages. Valeurs par défaut : A4, bouton proposé (voir la
[migration du lot 4](migrations/lot-4.md)).

### Parcours

Après une vente ou un règlement, si le réglage le permet, le bandeau « Opération enregistrée » propose « Imprimer le reçu » : le PDF s'ouvre dans un nouvel onglet, jamais
automatiquement (attribut flash `recuUrl`, fragment `fragments/recu :: bandeau`). Le reçu reste accessible plus tard, quel que soit le réglage : bouton « Reçu » dans la liste des
ventes, lien « Reçu » dans l'historique de chaque règlement.

| Adresse | Reçu | Droits |
|---|---|---|
| `/ventes/recu/{id}` | vente | gérant, caissier |
| `/ventes/regler/{id}/recu/{mouvement}` | règlement d'une vente | gérant, caissier |
| `/clients/solde/regler/{id}/recu/{mouvement}` | règlement du solde de départ d'un client | gérant, caissier |
| `/achats/regler/{id}/recu/{mouvement}` | paiement d'un achat | gérant |
| `/fournisseurs/solde/regler/{id}/recu/{mouvement}` | règlement du solde de départ d'un fournisseur | gérant |

Chaque adresse est sous le préfixe de la page qu'elle prolonge, donc sous sa règle de `SecurityConfig` (aucune règle ajoutée) : 403 pour un rôle non autorisé, redirection vers la
connexion sans compte, **404** pour un document ou un mouvement d'une autre boutique, d'un autre document, ou pour une annulation (qui n'est pas un règlement). Réponse en
`Content-Disposition: inline`, `Cache-Control: no-store`.

### Envoyer le reçu (WhatsApp)

À côté de « Imprimer le reçu » (bandeau) et de chaque « Reçu » (liste des ventes, historiques de règlement), un bouton **Envoyer** partage le PDF par le **menu de partage natif de
l'appareil** (WhatsApp, e-mail, Drive, etc.) avec l'API Web Share de niveau 2 (partage de fichier). Aucune route ni règle de sécurité n'est ajoutée : le bouton utilise la **même adresse**
que le reçu, donc les mêmes droits (vente et solde client : gérant et caissier ; achat et solde fournisseur : gérant seul ; un rôle sans accès ne voit pas la page, donc pas le bouton).

**Fonctionnement** (`static/js/recu-partage.js`, fragment `fragments/recu :: envoyer`)

- Le bouton est rendu avec l'attribut `hidden`. Le script ne le montre que si `navigator.canShare` accepte un `File` PDF de test ; sinon (navigateur incompatible, JavaScript désactivé)
  il reste caché et l'impression reste disponible.
- Au clic : téléchargement du reçu (même origine, même session), contrôle de la réponse (`ok` et `Content-Type: application/pdf`), création d'un `File` nommé d'après le
  `Content-Disposition` (`recu-vente_FV-000012.pdf`, `recu-reglement-vente_…pdf`, etc. : nom déjà nettoyé par le serveur, nettoyé de nouveau par le script), puis
  `navigator.share({ files, title, text })` avec un texte court : « Reçu FV-000012 — Boutique Étoile ».
- Pendant le téléchargement : bouton désactivé, `aria-busy`, libellé « Préparation… » ; un double clic ne lance qu'un téléchargement.
- Annulation dans le menu de partage : silencieuse, le bouton revient à l'état initial.
- Réponse qui n'est pas un PDF (session expirée : page de connexion), 403, 404, erreur réseau ou de partage : message lisible sous le bouton (zone `aria-live`), jamais d'`alert()`.
- Les textes viennent d'attributs `data-recu-*` échappés par Thymeleaf et sont posés avec `textContent` : rien n'est inséré comme HTML.

**Compatibilité** : Chrome sur Android, Safari sur iPhone, Safari sur macOS, Chrome et Edge sur Windows (partage de fichier pris en charge). Sur les autres navigateurs (Firefox, notamment),
le bouton reste caché.

**À savoir**

- **HTTPS obligatoire en production** : le partage n'existe que dans un contexte sécurisé (`localhost` est accepté pour les essais).
- **Aucune donnée n'est envoyée à un tiers par l'application** : ni lien public, ni jeton, ni API WhatsApp Business. C'est l'utilisateur qui choisit l'application et le contact dans le menu
  de son appareil ; le fichier ne quitte l'appareil que par ce choix.
- **Second clic possible sur Safari** : Safari refuse le partage si le geste de l'utilisateur est perdu pendant le téléchargement (`NotAllowedError`). Le reçu est alors gardé en mémoire, le bouton
  devient « Partager maintenant » et le clic suivant ouvre le menu aussitôt, sans nouveau téléchargement.

### Gabarits : le piège du style

Un `<style>` placé dans le corps du gabarit n'est pas appliqué et son texte s'imprime : les styles des reçus sont dans `resources/pdf/<gabarit>.css`, injectés dans l'en-tête par
`pdf/base.html` (variable `cssGabarit`, une seule ligne ajoutée au socle).

### Vérifications

```bash
./gradlew test --no-daemon --tests '*RecuTests' --tests '*RecuParametresTests'
node --test src/test/js/recu-partage.test.cjs                                   # bouton « Envoyer » (faux navigator et fetch)
RECU_DEMO_DIR=/chemin/dossier ./gradlew test --no-daemon --tests '*RecuTests'   # écrit les PDF pour la revue visuelle
pdftoppm -png -r 110 vente-ticket.pdf vente-ticket                              # puis ouvrir les images
```

`RecuTests` crée deux boutiques avec les vrais services et vérifie : contenu et totaux de chaque type de reçu en A4 et en ticket, reste historique, mention d'annulation, absence de
coût et de marge, largeur de 80 mm (PDFBox), aucun caractère hors de la page, police d'au moins 8 pt, texte complet extrait (désignation longue, mot sans espace), droits du gérant et du
caissier, 404 d'une autre boutique, bandeau présent ou absent selon le réglage, boutons « Reçu » de la liste et de l'historique. `RecuParametresTests` couvre l'enregistrement des réglages,
la liste blanche et les valeurs invalides.
