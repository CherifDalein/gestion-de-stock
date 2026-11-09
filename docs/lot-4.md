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

## Limites connues

- Les montants Excel sont des `double` (15 chiffres significatifs) ; un montant de plus de 15 chiffres perdrait ses centimes.
- Le gabarit générique `pdf/tableau` suppose des colonnes de largeur automatique : au-delà de 12 colonnes, passez en paysage
  et réduisez les textes, ou écrivez un gabarit dédié.
- Le logo est celui du service de logos (PNG normalisé) : il est imprimé à 17 mm de haut au plus.
- Le PDF n'est pas PDF/A et n'est pas signé.
- La recherche serveur reproduit celle de l'écran mais pas à la lettre (l'écran cherche dans le texte affiché, y compris « GNF » ;
  l'export cherche numéro, dates, client, vendeur, montants et statut).
