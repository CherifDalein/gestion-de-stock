# Lot 1 — règles de gestion

Ce document décrit les règles métier livrées avec le lot 1. Le schéma de base
correspondant est décrit dans [la migration du lot 1](migrations/lot-1.md).

## Seuil d'alerte, unités et services

### Nature d'une fiche

Chaque fiche du catalogue est soit un **article en stock** (`STOCK`, valeur des
fiches existantes), soit un **service** (`SERVICE`) : livraison, pose,
réparation, découpe…

- La nature se choisit à la création et **ne peut plus être changée ensuite**.
  Le formulaire de modification l'affiche en lecture seule et refuse le champ
  `nature` (réponse 400). Une fiche à transformer doit être recréée.
- Un article en stock démarre à zéro, sans prix (« prix à définir ») : ses prix
  et ses quantités arrivent par un achat ou par le stock initial du démarrage.
- Un service a ses prix définis dès la création (`prixDefinis = true`) : un
  prix de vente obligatoire (0 permis) et un **coût fixe** facultatif, 0 par
  défaut. Ce coût est enregistré dans `cout_moyen` ; `prix_achat` reçoit la même
  valeur, pour l'affichage du catalogue. Les deux restent modifiables sur la
  fiche du service. Le coût d'un article en stock ne se saisit jamais sur la
  fiche : il vient des achats.

### Vente d'un service

Un service se vend comme un article, dans le même panier, au prix de sa fiche :

- aucune vérification de quantité, aucune quantité déduite ;
- aucun verrou sur la fiche : seuls les articles en stock du panier sont
  verrouillés. Le service est lu dans la boutique courante ; un service d'une
  autre boutique est introuvable (404), comme un article ;
- aucun mouvement de stock.

Un service est **refusé**, avec un message qui le nomme, dans :

- un achat et une modification d'achat (le formulaire d'achat ne le propose pas) ;
- un stock initial du démarrage de la boutique (le formulaire n'est pas proposé) ;
- un ajustement d'inventaire (déjà désactivé pour tous les produits).

Sa quantité reste à 0 et s'affiche « — ».

### Seuil d'alerte

- Chaque article en stock a son propre seuil, un entier ≥ 0, **5 par défaut**
  (valeur des fiches existantes). Il se saisit sur la fiche.
- **Rupture** : quantité 0. **Stock faible** : 0 < quantité ≤ seuil.
  **Disponible** : quantité > seuil. Avec un seuil de 0, un article n'est jamais
  « faible » : il passe directement de disponible à rupture.
- L'état est calculé par le serveur (`Produit.etatStock()`) et les compteurs du
  catalogue par `ProduitService.ResumeCatalogue`. Le seuil fixe de 5, codé
  auparavant dans la liste et dans `produits.js`, a disparu.
- Les services n'entrent ni dans « En stock », ni dans « Stock faible », ni dans
  « En rupture ». Ils restent comptés dans « Tous les produits ». Aucune valeur
  de stock n'est calculée aujourd'hui ; une future valeur du stock devra elle
  aussi exclure les services.

### Unités

- L'unité est facultative : pièce, kg, sac, carton, bidon, paquet, litre,
  mètre… proposées par une liste, ou tout texte libre de **30 caractères au
  plus**. Les espaces superflus sont retirés ; une unité vide est enregistrée
  comme absente. Aucune unité n'est inventée pour les fiches existantes.
- L'unité sert **seulement à l'affichage**. Les quantités restent des nombres
  **entiers** partout : 2 sacs, jamais 1,5 sac.
- Elle est affichée à côté des quantités : catalogue et détail de la fiche,
  paniers d'achat et de vente, détail des ventes, factures de vente et d'achat,
  relevés cumulés et page de stock. Les scripts l'insèrent toujours comme texte
  (`textContent` ou `option.text`), jamais comme HTML.

### Formulaire et contrôles

Champs acceptés par la fiche produit (`FormBindingAdvice`, formulaire `produit`) :

| Route | Champs en plus de nom, référence, catégorie et fournisseur |
|---|---|
| création, article en stock | `nature`, `unite`, `seuilAlerte` |
| création, `nature=SERVICE` | `nature`, `unite`, `seuilAlerte`, `prixVente`, `coutMoyen` |
| `/produits/modifier/{id}` | `version`, `unite`, `seuilAlerte`, `prixVente`, `coutMoyen` |

Tout autre champ, deux valeurs de `nature`, une nature inconnue ou un
`coutMoyen` envoyé pour un article en stock sont refusés (400). Un seuil absent
en modification conserve le seuil enregistré. Les erreurs de saisie (seuil
négatif ou vide, unité trop longue, prix d'un service manquant ou négatif)
sont affichées sur le formulaire, en français. La gestion des fiches reste
réservée au gérant ; le caissier consulte le catalogue et vend les services.

### Vérifications

```sh
./gradlew test --tests '*SeuilsUnitesServicesTests'
node --test src/test/js/montants.test.cjs
```

Ces tests couvrent, sur deux boutiques, la vente mixte article + service, la
vente d'un service au-delà de tout stock, les refus à l'achat, à la
modification d'achat, au stock initial et à l'ajustement, les états selon le
seuil, les compteurs, les champs interdits, les droits du caissier et
l'isolation entre boutiques.

## Coût moyen pondéré

Chaque produit porte un coût moyen pondéré (CMP), `produit.cout_moyen`, en GNF
avec deux décimales. Chaque nouvelle ligne de vente fige ce coût dans
`detail_vente.cout_unitaire` : c'est la base du calcul du bénéfice brut,
Σ (prix unitaire − coût unitaire) × quantité.

`prix_achat` garde son rôle : c'est le **dernier** prix d'achat, proposé lors
du prochain achat. Le CMP est une valeur distincte qui n'apparaît pas encore à
l'écran.

### Formule

À chaque entrée en stock (achat, ou stock initial saisi au démarrage de la
boutique), pour chaque ligne :

```
nouveau CMP = (stock avant × CMP actuel + quantité entrée × prix d'achat unitaire)
              / (stock avant + quantité entrée)
```

Le calcul est exact en décimal ; le résultat est arrondi **une seule fois** au
centime, au plus proche (une demie est arrondie vers le haut). Exemples :

| Stock avant | CMP actuel | Entrée | Nouveau CMP |
|---:|---:|---|---:|
| 10 | 1 000,00 | 10 × 2 000 | 1 500,00 |
| 7 | 1 000,00 | 10 × 2 000 | 1 588,24 (1 588,235…) |
| 3 | 1 000,01 | 3 × 1 000,00 | 1 000,01 (1 000,005) |
| 0 | 1 500,00 | 5 × 3 000 | 3 000,00 |

Les lignes d'un même achat sont appliquées l'une après l'autre, dans l'ordre
de saisie.

### Cas particuliers

- **Stock nul ou négatif avant l'entrée, ou CMP inconnu** : le CMP devient le
  prix d'achat de l'entrée. C'est le cas du premier achat, du stock initial et
  d'un nouvel achat après épuisement du stock.
- **CMP à zéro avec du stock** : c'est une vraie valeur (marchandise reçue
  gratuitement, ou prix d'achat nul avant la migration) ; elle est pondérée
  normalement.
- **Vente** : la quantité diminue, le CMP ne change pas. La ligne de vente
  enregistre le CMP lu sous le verrou du produit, au moment de la vente.
- **Ajustement d'inventaire** : la quantité change, le CMP reste identique.
  Les corrections manuelles sont aujourd'hui désactivées ; les ajustements
  historiques du journal sont pris en compte de la même façon lors d'un
  recalcul.
- **Service** : une ligne de vente d'un produit de nature `SERVICE` fige aussi
  `cout_moyen`, qui contient le coût fixe du service. Une entrée ne pondère
  jamais le coût d'un service.
- **Les ventes déjà enregistrées ne sont jamais réécrites** : leur
  `cout_unitaire` reste celui du jour de la vente, même si le CMP est ensuite
  recalculé.

### Correction d'un ancien achat

Modifier un achat (`/achats/modifier/…`) recalcule le CMP de chaque produit
présent dans l'ancienne ou la nouvelle version de l'achat, en rejouant tout son
historique avec la formule ci-dessus :

1. le stock initial, toujours en premier (il est réservé au démarrage) ;
2. puis, par date : les lignes d'achat (entrées valorisées), les ajustements
   d'inventaire (quantité seule) et les lignes de vente (sorties).

L'ordre est déterministe. À date égale, les entrées passent avant les
ajustements puis les sorties, et ensuite par identifiant de document et de
ligne. Un document historique sans date est placé avant les documents datés.
Le journal d'inventaire est daté en UTC ; ses dates sont converties à l'heure
du serveur, celle des achats et des ventes, avant le tri. Les achats et les
ventes sont datés sous le verrou du produit : rejouer un historique non
modifié redonne exactement le CMP calculé au fil des opérations.

Le stock que l'historique n'explique pas, présent avant l'installation du
journal d'inventaire, est placé au début du rejeu et valorisé au premier coût
d'entrée connu du produit. C'est une approximation. Un écart négatif ne change
que la quantité et n'a donc aucun effet sur le CMP.

### Concurrence

Les achats, ventes, stocks initiaux et corrections verrouillent déjà les
produits concernés (verrou pessimiste, toujours dans le même ordre) avant de
modifier le stock. Le CMP est lu et écrit sous ce même verrou : un achat et une
vente simultanés sur le même produit sont appliqués l'un après l'autre, et la
ligne de vente fige le coût correspondant à cet ordre. Lors d'une correction,
l'historique est relu avec des lectures verrouillées, qui voient aussi les
opérations validées après le début de la transaction en REPEATABLE READ
(MariaDB).

### Ventes antérieures au lot 1

L'historique des coûts au moment de chaque vente n'était pas conservé avant le
lot 1. La migration a donc rempli `cout_unitaire` des ventes existantes, et
`cout_moyen` des produits, avec le prix d'achat **actuel** du produit. Le
bénéfice calculé sur ces ventes est une approximation issue de la migration ;
seules les ventes enregistrées après le lot 1 portent leur vrai coût moyen.

### Vérifications

```sh
./gradlew test --tests '*CoutMoyen*'
./gradlew coutMoyenServiceMariaDbTest
```

Le test MariaDB crée puis supprime uniquement un schéma temporaire
`stock_cout_moyen_service_test_*`. La variable
`COUT_MOYEN_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner un
serveur local isolé.

## Tableau de bord

La page d'accueil connectée (`/`) présente l'activité de la boutique du compte
connecté. La boutique vient toujours du compte, jamais d'un paramètre du
navigateur. Tous les chiffres sont calculés par le serveur
(`TableauDeBordService`), en décimal exact, par des requêtes agrégées
(`TableauDeBordRepository` : `SUM`, `COUNT`, `GROUP BY`) ; aucune liste de
ventes n'est chargée pour les calculer.

### Périodes

- **Aujourd'hui** est la date locale du serveur (`LocalDate.now()`), la même
  convention que la caisse et le tableau de bord existant : les ventes, achats
  et opérations de trésorerie sont datés à l'heure du serveur.
- Toutes les périodes sont **semi-ouvertes** : `[début, fin)`. Le jour va de
  minuit inclus au minuit suivant exclu ; le mois du 1er à 0 h inclus au 1er
  du mois suivant exclu ; l'année du 1er janvier inclus au 1er janvier suivant
  exclu. Une vente à 23 h 59 min 59 s le 31 janvier compte en janvier, une vente
  à minuit le 1er février compte en février.
- L'évolution mensuelle regroupe les ventes et les dépenses de l'année par
  mois de leur date (`EXTRACT(MONTH …)`), à l'intérieur des bornes de l'année.
  Les tests vérifient sur MariaDB que ce regroupement redonne exactement les
  chiffres calculés avec les bornes de chaque mois.

### Indicateurs

| Indicateur | Définition |
|---|---|
| Chiffre d'affaires | Σ `vente.montant_total` des ventes de la période, réglées ou non. |
| Bénéfice brut | Σ (`prix_unitaire` − `cout_unitaire`) × `quantite` des lignes de vente de la période, services compris (leur coût fixe est figé à la vente). |
| Dépenses | Σ `operation_tresorerie.montant` des opérations de type `DEPENSE` de la période (loyer, transport, frais…). Les achats et leurs règlements ne sont **pas** des dépenses : le coût des marchandises entre dans le bénéfice brut au moment de la vente. Les transferts entre comptes ne sont pas des dépenses. |
| Bénéfice net | Bénéfice brut − dépenses. |
| Valeur du stock | Σ `quantite` × `cout_moyen` des fiches de nature `STOCK`, avec leur nombre. Les services sont exclus. |
| Produits à surveiller | Articles en stock en rupture (quantité 0) ou au seuil (0 < quantité ≤ seuil), selon la même règle que `Produit.etatStock()`. Les ruptures d'abord, puis les plus petites quantités ; 10 au plus, avec le nombre total et un lien vers le catalogue filtré (`/produits?stock=a-surveiller`). Les services ne sont jamais à surveiller. |
| Évolution mensuelle | Pour chaque mois de l'année choisie : ventes (chiffre d'affaires), bénéfice brut, dépenses et résultat (bénéfice brut − dépenses), avec le total de l'année. |

Les quatre premiers indicateurs sont donnés pour le jour et pour le mois en
cours. L'évolution est affichée en tableau et en graphique en barres dessiné
en HTML/CSS par le serveur, sans bibliothèque : ventes et résultat au-dessus de
l'axe, perte en dessous. Le tableau reste la version accessible du graphique.

### Lignes sans coût connu

Une ligne de vente dont `cout_unitaire` est vide compte son coût à **0** dans
le bénéfice brut. Le nombre de ces lignes est affiché discrètement sous le
bénéfice brut de la période (« 1 ligne de vente sans coût connu, comptée à
0 »), pour que le gérant sache que la marge est surestimée d'autant. Les
ventes migrées ont normalement reçu un coût approché (voir « Ventes
antérieures au lot 1 »).

### Année affichée

Le paramètre `annee` choisit l'année de l'évolution ; le sélecteur ‹ année ›
passe à l'année précédente ou suivante. Il est validé par le serveur : quatre
chiffres, de 2000 à l'année en cours. Une valeur absente affiche l'année en
cours ; une valeur invalide (texte, année future, avant 2000, valeurs
multiples) affiche aussi l'année en cours avec un avertissement, sans erreur.
Une année sans données affiche douze mois à zéro et aucun graphique.

### Droits

| Bloc | Gérant (ADMIN) | Caissier (CAISSIER) |
|---|---|---|
| Soldes et mouvements de trésorerie du jour (existant) | oui | non |
| Chiffre d'affaires du jour | oui | oui |
| Encaissé sur les ventes du jour | oui (carte « Ventes du jour · réglé ») | oui |
| Produits à surveiller | oui | oui (sans prix ni coût) |
| Bénéfices, dépenses, chiffres du mois | oui | non |
| Valeur du stock | oui | non |
| Évolution mensuelle | oui | non ; `annee` est ignoré |

Pour un caissier, les indicateurs réservés ne sont **pas calculés** et ne sont
pas placés dans le modèle (`indicateursJour`, `indicateursMois`,
`valeurStock`, `evolution`) : ils ne sont pas seulement masqués par le gabarit.
La liste des produits à surveiller n'expose que le nom, la référence, la
quantité, l'unité, le seuil et l'état.

La seconde partie du tableau de bord (créances et dettes en retard) dispose
d'un emplacement réservé dans `templates/fragments/tableau-de-bord.html` ; il
n'affiche rien tant que cette partie n'est pas livrée.

### Vérifications

```sh
./gradlew test --tests '*TableauDeBordTests'
./gradlew tableauDeBordMariaDbTest
```

Les tests H2 comparent chaque chiffre à un calcul fait à la main sur un jeu de
données fixe (ventes à coûts figés, une ligne sans coût, un service, dépenses,
achats et transferts qui ne comptent pas, opérations hors période et d'une
autre boutique), et vérifient la valeur du stock, les produits à surveiller,
l'évolution d'une année avec et sans données, les années invalides, le modèle
et la page du caissier, et l'isolation des boutiques. Le test MariaDB crée puis
supprime uniquement un schéma temporaire `stock_tableau_de_bord_test_*` ; la
variable `TABLEAU_DE_BORD_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut
sélectionner un serveur local isolé.
