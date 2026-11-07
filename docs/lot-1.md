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
du prochain achat. Le CMP est une valeur distincte, affichée au gérant (voir
« Où le voir » ci-dessous).

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

### Où le voir

- **Liste des produits** (`/produits`) : la colonne « Coût moyen » remplace
  l'ancien « Prix d'achat » (pour un service, c'est son coût fixe, avec
  l'info-bulle « Coût fixe du service »). La colonne « Marge unitaire » montre
  prix de vente − coût moyen et, entre parenthèses, le taux sur le prix de vente
  arrondi à une décimale (demie vers le haut), par exemple
  `40 000,00 GNF (26,7 %)`. Une marge négative est en rouge ; sans prix défini
  ou avec un prix de vente nul, la marge affiche « — ». Les deux colonnes se
  trient. La fenêtre de détail donne coût moyen, dernier prix d'achat (« — »
  pour un service) et marge.
- **Fiche produit** : la page de stock (`/produits/{id}/stock`) et la page de
  modification affichent « Coût moyen pondéré » et « Dernier prix d'achat » côte
  à côte, avec la phrase « Le coût moyen tient compte de tous les achats en
  stock ; c'est lui qui sert au calcul du bénéfice. », puis la marge unitaire.
- **Tableau de bord** : le bénéfice brut du jour et du mois est calculé avec le
  coût figé sur chaque ligne de vente (`detail_vente.cout_unitaire`), donc avec
  le coût moyen au moment de la vente, jamais avec le dernier prix d'achat.
- **Droits** : coût moyen, dernier prix d'achat et marge sont réservés au
  gérant (ADMIN), dans le HTML comme dans les attributs `data-*`
  (`data-cost`, `data-last-purchase-price`, `data-margin`, `data-margin-rate`).
  Le caissier ne les reçoit jamais : ni dans `/produits`, ni dans
  `/ventes/nouveau` (qui n'expose que le prix de vente et le stock), ni dans le
  tableau de bord ; les fiches de gestion lui sont fermées (403).
  `Produit.getMargeUnitaire()` et `getTauxMarge()` calculent en `BigDecimal` ;
  elles renvoient `null` quand il n'y a pas de marge.

### Vérifications

```sh
./gradlew test --tests '*CoutMoyen*'
./gradlew coutMoyenServiceMariaDbTest
```

Le test MariaDB crée puis supprime uniquement un schéma temporaire
`stock_cout_moyen_service_test_*`. La variable
`COUT_MOYEN_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner un
serveur local isolé.

## Numéros de facture et échéances

### Numéro de facture

Chaque vente et chaque achat reçoit un numéro à sa création, unique dans la
boutique :

- **Automatique** : `FV-000123` pour une vente, `FA-000123` pour un achat. Au
  moins six chiffres, jamais tronqué (`FV-1234567`). Il est tiré du compteur de
  la boutique (`boutique.prochain_numero_vente`, `prochain_numero_achat`), qui
  avance de 1 par numéro automatique attribué. Si le numéro calculé est déjà
  pris, par exemple par une saisie manuelle, le compteur avance jusqu'au
  premier numéro libre. Une boutique migrée reprend son compteur, initialisé
  au-delà des numéros migrés ; une boutique neuve commence à `FV-000001`.
- **Manuel**, facultatif, à la création seulement : texte de 1 à 30 caractères,
  sans caractère de contrôle. Les espaces aux bords sont retirés ; un champ
  vide demande un numéro automatique. Il doit être unique dans la boutique (un
  même numéro reste permis dans une autre boutique). Un doublon est refusé avec
  un message qui le nomme ; le formulaire réaffiche le panier, le numéro et
  l'échéance saisis. Un numéro manuel ne fait pas avancer le compteur.
- Le numéro n'est **jamais modifiable** ensuite : la colonne est exclue des mises
  à jour JPA et le champ `numero` est refusé (400) hors des deux routes de
  création, y compris à la modification d'un achat et aux règlements.

Sur MariaDB, l'unicité suit la collation de la colonne : avec une collation
insensible à la casse, `fv-1` et `FV-1` sont le même numéro.

### Concurrence

Le compteur est lu (`SELECT … FOR UPDATE`) et avancé sous le verrou de la ligne
`boutique`, dans la transaction de la création. Ce verrou est pris juste après
la clôture éventuelle du démarrage, avant les produits et avant toute insertion,
dans le même ordre que le démarrage et le stock initial. Conséquences :

- deux créations simultanées d'une même boutique reçoivent deux numéros
  différents, l'une attend la fin de l'autre ;
- une création annulée (stock insuffisant, numéro en double, échéance refusée…)
  rend son numéro : la numérotation reste continue ;
- les ventes et achats d'une **même boutique** sont désormais enregistrés l'un
  après l'autre, même s'ils concernent des produits distincts. Sur MariaDB, les
  insertions liées à la boutique (ouverture d'un formulaire, client, mouvement
  de caisse d'un règlement…) attendent aussi la fin de la création en cours.
  Ces transactions sont courtes ; deux boutiques ne s'attendent jamais.
- La recherche d'un numéro déjà pris est une lecture verrouillée : elle voit les
  documents validés après le début de la transaction (REPEATABLE READ).

Les doubles envois restent protégés par le jeton de création : un renvoi
identique retrouve la facture déjà enregistrée, avec son numéro, sans consommer
de second numéro ; le numéro manuel et l'échéance saisis font partie de
l'empreinte du formulaire (voir [doubles envois](creations-et-doubles-envois.md)).

### Échéance

- Une échéance n'existe que **s'il reste à payer après la création**. Elle est
  proposée à la date du jour + le délai de la boutique : **délai accordé aux
  clients** pour une vente, **délai accordé par les fournisseurs** pour un achat
  (30 jours par défaut). Elle reste modifiable dans le formulaire ; une date
  antérieure au jour de la facture est refusée avec un message, panier conservé.
- Une facture payée en totalité à sa création n'a pas d'échéance, même si une
  date a été envoyée. Le champ est masqué et désactivé tant que le reste à payer
  est nul.
- La **modification d'un achat** (`/achats/modifier/…`) et les **pages de
  règlement** permettent de changer l'échéance tant qu'il reste à payer : soit
  avec un versement partiel, soit sans versement avec le bouton « Modifier
  seulement l'échéance » (`POST /ventes/regler/{id}/echeance`,
  `POST /achats/regler/{id}/echeance`). Sans date envoyée, l'échéance
  enregistrée est conservée ; un achat modifié qui retrouve un reste à payer
  sans échéance en reçoit une proposée à partir du jour.
- Quand un règlement solde la facture, l'échéance reste en historique ; elle
  n'est plus modifiable et la facture n'est plus en retard.
- Les droits ne changent pas : le caissier règle les ventes et leurs échéances,
  les achats restent réservés au gérant. Une facture d'une autre boutique est
  introuvable (404).

### Retard

Une facture est **en retard** quand son reste à payer est positif et que son
échéance est **strictement antérieure** à aujourd'hui. Le jour est celui de
l'heure du serveur, comme la date des ventes, des achats et de la caisse
(`EcheanceService.aujourdHui()`, horloge `recoveryClock` injectable en test).
Exemple : une vente à crédit du 7 novembre a pour échéance le 7 décembre ; elle
est « en retard » à partir du 8 décembre si elle n'est pas soldée.

### Paramètres de la boutique

La page **Paramètres de la boutique** (gérant seulement) contient deux champs :
« Délai accordé aux clients (jours) » et « Délai accordé par les fournisseurs
(jours) », entiers de 0 à 365, contrôlés par le serveur. Une valeur hors limites
est signalée sur le champ sans rien enregistrer ; une valeur non numérique est
refusée (400). Un envoi qui ne contient pas ces champs conserve les délais
enregistrés. Les nouveaux délais s'appliquent aux factures créées ensuite ; les
échéances déjà enregistrées ne changent pas.

### Affichage

- Le numéro et l'échéance figurent sur les factures de vente et d'achat, les
  listes de ventes, d'achats et de factures, les pages de règlement et les
  relevés cumulés client et fournisseur. Les documents sans numéro, créés hors
  de l'application, affichent leur identifiant `#123`.
- Les listes de ventes et d'achats affichent un badge « En retard » et un filtre
  « Échues non soldées » (`?filtre=echues`), trié par échéance. Les recherches
  existantes des listes (journal des ventes et des achats, factures) retrouvent
  une facture par son numéro.
- Les textes sont insérés par Thymeleaf (`th:text`) ; le panier reconstitué
  après un refus réutilise `textContent` et `option.text`.

### Vérifications

```sh
./gradlew test --tests '*NumerosEcheancesFacturesTests'
./gradlew test --tests '*StockConcurrencyTests'
./gradlew creationOperationsMariaDbTest
```

Les tests H2 couvrent, sur deux boutiques et avec les rôles gérant et caissier,
les numéros consécutifs, la reprise d'un compteur migré, les grands numéros,
le saut d'un numéro manuel, la numérotation indépendante, deux ventes
simultanées, le numéro manuel en double (panier conservé), le numéro rendu
après un rollback, le double envoi, l'échéance proposée, personnalisée, refusée
ou absente, le retard autour de l'échéance avec une horloge fixée, le filtre,
les délais des paramètres et le refus du champ `numero` hors création. Le
scénario MariaDB vérifie avec les vrais verrous InnoDB qu'une seconde vente
attend le compteur puis prend le numéro suivant, qu'un rollback ne consomme pas
de numéro et qu'un numéro manuel en double est refusé.

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
| Créances, dettes, avances, position nette, clients et fournisseurs en retard | oui | non ; rien n'est calculé |

Pour un caissier, les indicateurs réservés ne sont **pas calculés** et ne sont
pas placés dans le modèle (`indicateursJour`, `indicateursMois`,
`valeurStock`, `evolution`) : ils ne sont pas seulement masqués par le gabarit.
La liste des produits à surveiller n'expose que le nom, la référence, la
quantité, l'unité, le seuil et l'état.

Les créances et les dettes (voir plus bas) sont réservées au gérant de la même
manière : pour un caissier, l'attribut `creancesDettes` n'est ni calculé ni
placé dans le modèle (`SoldePartenaireService` n'est même pas appelé) et la page
ne contient aucun de ces chiffres ni aucun nom de client ou de fournisseur.

### Créances et dettes (gérant seulement)

Ces chiffres sont calculés par `TableauDeBordService.creancesEtDettes()` à partir
de `SoldePartenaireService` : aucun autre calcul de solde n'existe. Ils portent
sur la boutique du compte connecté et sur la date du jour de `EcheanceService`
(la même que les badges « En retard » des ventes et des achats). Une échéance
est **en retard** quand elle est strictement antérieure à aujourd'hui : une
facture qui échoit aujourd'hui n'est pas encore en retard. Les montants en
retard ne comptent que le reste à payer (total − versé − avance utilisée).

| Chiffre | Définition |
|---|---|
| Créances clients | Σ des restes à payer de toutes les ventes de la boutique (ventes sans client comprises) + Σ des restes des soldes de départ « il me doit » (solde de départ − règlements). |
| dont en retard | Même somme, limitée aux factures dont l'échéance est dépassée et aux soldes de départ dont l'échéance est dépassée. |
| Dettes fournisseurs | Σ des restes à payer de tous les achats (achats sans fournisseur compris) + Σ des restes des soldes de départ « je lui dois ». |
| dont en retard | Même règle que pour les créances. |
| Avances (clients) | Σ des avances disponibles de chaque client : argent reçu d'avance (avance de départ + trop-payés − avance déjà utilisée). Chaque solde reste distinct : l'avance d'un client n'est **jamais** déduite de la créance d'un autre. |
| Avances chez les fournisseurs | Σ des avances disponibles de la boutique chez chaque fournisseur (avance de départ + trop-payés − avance déjà utilisée). |
| Position nette | Créances − dettes − avances des clients + avances chez les fournisseurs. Positive : l'ensemble des comptes tiers est en faveur de la boutique. La ligne d'explication sous le chiffre rappelle la formule. |
| Clients en retard | Jusqu'à 10 clients nommés (`TableauDeBordService.LIMITE_RETARDS`), du montant en retard le plus grand au plus petit (puis plus ancienne échéance, puis nom). Pour chacun : nom, montant en retard (factures échues + reste du solde de départ échu), plus ancienne échéance dépassée, et lien vers son relevé cumulé `/factures/client/{id}/cumule`. Le nombre de clients non listés est indiqué. |
| Fournisseurs à payer en retard | Même liste pour les fournisseurs, avec le lien `/factures/fournisseur/{id}/cumule`. |
| Ventes sans client en retard | Restes échus des ventes sans client (client de passage) : ils comptent dans les créances et dans leur part en retard, mais n'ont pas de ligne nominative. Ce montant est affiché à part, uniquement s'il n'est pas nul. Les achats sans fournisseur sont traités de la même façon (« Achats sans fournisseur en retard »). |

Quand une liste est vide, la page affiche « Aucun client en retard » ou
« Aucun fournisseur à payer en retard ». Les noms sont insérés par Thymeleaf
(`th:text`), jamais comme HTML.

Les listes de retards s'appuient sur deux requêtes agrégées de
`SoldePartenaireRepository` (`retardsParPartenaire`, avec `SUM`, `MIN` et
`GROUP BY` sur les factures échues et sur les soldes de départ échus, puis
`retardFacturesSansPartenaire`) ; les avances totales reprennent
`soldesClients()` et `soldesFournisseurs()`. Aucune migration n'est nécessaire.

### Vérifications

```sh
./gradlew test --tests '*TableauDeBordTests'
./gradlew test --tests '*TableauDeBordCreancesDettesTests'
./gradlew tableauDeBordMariaDbTest
```

Les tests H2 comparent chaque chiffre à un calcul fait à la main sur un jeu de
données fixe (ventes à coûts figés, une ligne sans coût, un service, dépenses,
achats et transferts qui ne comptent pas, opérations hors période et d'une
autre boutique), et vérifient la valeur du stock, les produits à surveiller,
l'évolution d'une année avec et sans données, les années invalides, le modèle
et la page du caissier, et l'isolation des boutiques. `TableauDeBordCreancesDettesTests`
fixe la date par l'horloge `recoveryClock` et vérifie, sur un jeu de données fixe
à deux boutiques (plus une boutique vide), chaque chiffre des créances et des
dettes, l'échéance du jour, l'ordre et la limite de 10 des listes, les messages
de liste vide, l'absence de tout calcul et de tout chiffre pour le caissier. Le
test MariaDB reprend ces requêtes agrégées avec des centimes. Il crée puis
supprime uniquement un schéma temporaire `stock_tableau_de_bord_test_*` ; la
variable `TABLEAU_DE_BORD_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut
sélectionner un serveur local isolé.

## Avances et soldes de départ

### Convention de signe

Pour un client comme pour un fournisseur, du point de vue de la boutique :

- solde **positif** : le partenaire doit de l'argent à la boutique ;
- solde **négatif** : la boutique doit de l'argent au partenaire.

Un solde client négatif est donc une avance versée par le client. Un solde
fournisseur positif est une avance versée au fournisseur.

### Solde de départ

Les fiches client et fournisseur (création et modification) acceptent un montant
supérieur ou égal à zéro, un sens (« Il me doit » ou « Je lui dois ») et une
échéance facultative. L'application enregistre le montant signé dans
`solde_initial`, sans aucun mouvement de trésorerie. Le sens et le montant sont
les seuls champs liés : `soldeInitial` et `avanceUtilisee` ne sont jamais reçus
du navigateur (400). Une avance de départ n'a pas d'échéance.

Le solde de départ (montant, sens, échéance) n'est modifiable que **tant qu'il n'a servi à rien** :

- aucun règlement du solde de départ n'a été enregistré pour ce partenaire ;
- et aucune facture de ce partenaire n'a `avance_utilisee` > 0 (ses ventes pour un client, ses achats
  pour un fournisseur). La règle est volontairement stricte : dès qu'une avance a été consommée, quelle
  que soit son origine (solde de départ ou trop-payé), le solde de départ est figé.

Sinon le serveur refuse toute modification (HTTP 200 avec le formulaire et l'erreur) : « Ce solde de départ a déjà
servi (règlement ou avance utilisée) : il ne peut plus être modifié. Une correction se fera par un ajustement
de solde. » Le contrôle se fait dans la transaction, sous le verrou de la fiche, avec des lectures filtrées sur
la boutique (`SoldePartenaireRepository.avanceUtiliseeVerrouillee`). Le reste de la fiche (nom, téléphone,
adresse…) reste modifiable, et renvoyer exactement les mêmes valeurs de solde n'est pas une modification.

**Confirmation.** Tant que le solde est modifiable, un changement du montant ou du sens exige la case
« Je confirme le nouveau solde de départ : ancien → nouveau GNF » (champ `confirmationSoldeDepart`, accepté
seulement par la modification d'une fiche). Sans elle, le serveur renvoie le formulaire avec « Confirmez le
changement du solde de départ. ». La création et un changement d'échéance seule n'exigent pas cette
confirmation.

**Écran de modification.** Solde figé : montant, sens et échéance sont affichés en lecture seule avec
l'explication ci-dessus. Solde modifiable : le montant saisi est reformaté sous le champ (« = 1 000 000 GNF ») et
la case de confirmation, dont le texte montre l'ancien et le nouveau solde signés avec leur sens, apparaît dès
que le montant ou le sens change (`static/js/solde-depart.js`, textes insérés par `textContent`). Le navigateur
n'est qu'une aide : le serveur applique seul la règle.

**Correction d'un solde figé.** Elle se fera par l'opération « Ajustement de solde » (motif et historique),
prévue au lot 3 : elle n'existe pas encore.

Règlement : page `/clients/solde/regler/{id}` et `/fournisseurs/solde/regler/{id}`,
construite comme les règlements de factures (contrôle du reste attendu, choix du
compte, CSRF). Le paiement est un mouvement de trésorerie rattaché au partenaire
(entrée pour un client, sortie pour un fournisseur). Un règlement ne peut pas
dépasser le reste ; un formulaire périmé reçoit 409.

Reste du solde de départ d'un client = `solde_initial` s'il est positif − somme des
règlements. Pour un fournisseur : `-solde_initial` s'il est négatif − somme des règlements.

### Trop-payé et avance

À la **création** d'une vente (resp. d'un achat), le montant versé peut dépasser le
total uniquement si un client (resp. un fournisseur) est choisi ; sans partenaire,
« ne peut pas dépasser le total » reste refusé. Tout l'argent versé entre en
trésorerie ; le surplus (versé − total) devient une avance. Les règlements
ultérieurs d'une facture restent plafonnés à son reste.

Avance disponible d'un client :

    max(0, −solde_initial) + Σ max(0, versé − total) de ses ventes − Σ avance_utilisee de ses ventes

Même formule pour un fournisseur avec `max(0, solde_initial)` et ses achats. Elle
ne peut jamais être négative : sinon une `IncoherenceAvanceException` est levée.

À la création, si le partenaire a une avance et que versé < total, l'application
retire `min(avance disponible, total − versé)` dans `avance_utilisee`, **sans**
mouvement de trésorerie (l'argent est déjà entré). Les formulaires de vente et
d'achat affichent l'avance disponible via `GET /clients/{id}/avance` et
`GET /fournisseurs/{id}/avance` (JSON `{"avance":"20000.00"}`).

Reste à payer = `max(0, total − versé − avance_utilisee)`. Il sert aux règlements,
aux listes, aux factures, aux échéances et retards. Une vente couverte par l'avance
n'a pas d'échéance. Dans le tableau de bord, le « réglé » des ventes du jour vaut
`min(versé, total) + avance_utilisee`.

### Exemples chiffrés

1. Le client paie 120 000 GNF pour une vente de 100 000 : la caisse reçoit
   120 000, l'avance du client est 20 000. Sa vente suivante de 50 000 à crédit :
   `avance_utilisee` = 20 000, reste 30 000, aucun mouvement de caisse, avance
   disponible 0.
2. Fournisseur : solde de départ « Je lui dois » 100 000, paiement de 30 000 :
   sortie de trésorerie de 30 000, reste 70 000, solde net −70 000.
3. Client : solde de départ « Je lui dois » 10 000, vente de 30 000 sans
   versement : avance utilisée 10 000, reste 20 000, solde net +20 000.
4. Client : « Il me doit » 50 000, règlement de 20 000, reste 30 000 ; un règlement
   de 30 000,01 est refusé.

Solde net : client = reste du solde de départ + restes des factures − avance ;
fournisseur = avance − reste du solde de départ − restes des factures.
`SoldePartenaireService` fournit ces valeurs par partenaire, ainsi que, pour la
boutique, le total des créances clients, des dettes fournisseurs et leur part en
retard (même convention de date que `EcheanceService`). Le bloc « créances et
dettes » du tableau de bord n'est pas encore branché.

### Verrous et concurrence

À la création, l'ordre des verrous est : ligne `boutique` (numérotation), ligne du
**partenaire** (`PESSIMISTIC_WRITE`), puis les produits. Le règlement d'un solde
prend la boutique, puis le partenaire. `modifierAchat` verrouille le fournisseur
avant l'achat. Sous MariaDB (REPEATABLE READ), l'avance est relue par des lectures
verrouillantes (`FOR UPDATE`) pour voir les opérations validées après l'instantané.
Deux ventes simultanées d'un même client avec 20 000 d'avance consomment donc
exactement 20 000 au total. Un renvoi du même jeton ne consomme pas l'avance deux
fois et ne crée pas de second mouvement.

### Droits

| Action | Gérant | Caissier |
|---|---|---|
| Saisir ou modifier un solde de départ | oui | non (403) |
| Régler le solde d'un client | oui | oui, comme un règlement de vente |
| Régler le solde d'un fournisseur | oui | non, comme un règlement d'achat |
| Lire l'avance d'un client | oui | oui |
| Lire l'avance d'un fournisseur | oui | non |

Un partenaire d'une autre boutique est introuvable (404) et son avance n'est ni
visible ni utilisable.

### Vérifications

```sh
./gradlew test --tests '*AvancesSoldesPartenairesTests'
./gradlew avancesPartenairesMariaDbTest
./gradlew lot1MariaDbTest
node --test src/test/js/montants.test.cjs
```
