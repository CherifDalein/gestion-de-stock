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
