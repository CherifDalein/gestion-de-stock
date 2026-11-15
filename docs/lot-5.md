# Lot 5a : comptabilité SYSCOHADA (écritures, journal, grand livre, balance)

> **Comptabilité en cours de validation par un expert-comptable : vérifiez les états avant tout usage officiel.**
> Cette mention est affichée sur toutes les pages de la section « Comptabilité » et imprimée dans chaque PDF et chaque Excel.
> Les comptes proposés par défaut sont **prudents** et **tous modifiables** : ils sont à valider avec l'expert-comptable.

Cette étape (5a) calcule les écritures comptables en partie double à partir des opérations déjà enregistrées par l'application (ventes,
achats, retours, règlements, dépenses, transferts, financements, comptages de caisse, soldes de départ, stock). Les étapes 5b et 5c ne
sont pas faites ici.

## Principe : rien n'est stocké, tout est recalculé

- Aucune écriture n'est enregistrée. À chaque affichage, `ConstructeurEcritures` (une classe pure, sans base de données) transforme les
  opérations lues par `DonneesComptablesService` en écritures. Le même enregistrement donne toujours les mêmes écritures ; une
  correction d'une règle s'applique à tout l'historique.
- La lecture se fait avec un nombre fixe de requêtes groupées (jamais une par opération) : 10 000 opérations se calculent en environ
  0,1 s sur H2 (test de charge, seuil 2 s).
- Rien dans les règles métier existantes n'a changé : la comptabilité est en lecture seule sur les opérations.
- Les deux seules tables ajoutées portent des réglages : `sous_compte_boutique` et `correspondance_compta`
  (voir [la migration](migrations/lot-5.md) et `database-schema.md`).

## Plan de comptes

- Le **plan officiel du SYSCOHADA révisé** (1 401 comptes, pages 218 à 276 du plan fourni) est embarqué dans l'application
  (`src/main/resources/comptabilite/plan-comptes-syscohada-revise.csv`) et en **lecture seule**. Les intitulés trop longs ou imparfaits du
  document source sont repris tels quels ; ils ne sont pas corrigés ici.
- Chaque boutique ajoute ses **sous-comptes** : un numéro de 3 à 8 chiffres qui commence par un compte officiel parent de 2 à 4 chiffres,
  jamais un numéro officiel, unique dans la boutique.
- **Chaque compte de trésorerie reçoit son sous-compte** : caisse sous 571 (5713, car 5711 et 5712 existent au plan officiel), Orange Money sous
  552, banque sous 521, avec le plus petit numéro libre. L'intitulé suit le nom du compte de trésorerie. Ces sous-comptes sont créés par la
  migration pour les comptes existants, puis par l'application à l'ouverture de la comptabilité pour les comptes créés ensuite (un compte
  archivé garde le sien : l'historique reste lisible). Ils ne se suppriment pas.
- Page « Plan de comptes » : recherche par numéro ou par mots de l'intitulé (sans tenir compte des accents ni des majuscules), filtre par
  classe, classes repliables, ajout, renommage et suppression d'un sous-compte manuel. Un sous-compte utilisé dans la table de
  correspondance ne se supprime pas et ne change pas de numéro.

## Table de correspondance

Une ligne par nature d'opération (`CleCompta`), avec son compte par défaut. **Seules les valeurs modifiées sont stockées** :
« Rétablir les valeurs par défaut » supprime les lignes de la boutique. Un compte saisi doit exister au plan officiel ou parmi les
sous-comptes de la boutique ; une valeur identique au défaut supprime la ligne.

| Groupe | Clé | Compte par défaut |
|---|---|---|
| Clients | Créances clients / avances reçues | 4111 / 4191 |
| Ventes | Marchandises / services / retours de ventes | 701 / 706 / 701 |
| Fournisseurs | Dettes / avances versées | 4011 / 4091 |
| Achats | Achats de marchandises / retours d'achats | 601 / 601 |
| Stock | Stock / variation de stock | 31 / 6031 |
| Trésorerie | Virements internes / frais bancaires / écart de caisse (manque, excédent) | 585 / 6318 / 658, 758 |
| Financements | Emprunts / prêts / intérêts d'emprunts / intérêts de prêts / frais de dossier | 162 / 272 / 6712 / 7712 / 631 |
| Associés | Capital de l'exploitant / compte courant | 104 / 4621 |
| Produits et charges saisis | Produits financiers / autres produits / autres charges | 774 / 758 / 658 |
| Divers | Report à nouveau / compte d'attente | 12 / 471 |
| Dépenses | Eau 6051, électricité 6052, fournitures 6055, transport 61, loyer 622, entretien 624, assurance 625, publicité 627, téléphone 628, banque 6318, impôts 64, personnel 66, autre 658 | |

### Classement d'une dépense

La dépense n'a pas de catégorie dans l'application : elle est classée d'après les **mots de son motif**. Chaque catégorie a des
mots-clés modifiables ; le premier mot-clé trouvé donne le compte, dans l'ordre des catégories. La comparaison ignore accents et
majuscules, et un mot-clé doit **commencer un mot** du motif (« eau » ne reconnaît pas « bureau »). Un motif inconnu va à « autre
dépense » (658). Les comptes de trésorerie se règlent par leurs sous-comptes (voir plus haut).

## Écritures calculées

Journaux : **VE** ventes, **AC** achats, **TR** trésorerie, **OD** opérations diverses (reports, stock, ajustements).
Les tiers (client, fournisseur, associé) sont portés par les lignes de créance, de dette et d'avance.

| Opération | Écriture (par défaut) |
|---|---|
| Vente (total M, avance utilisée A, services S) | VE : D 4111 (M − A), D 4191 A / C 701 (M − S), C 706 S |
| Encaissement d'une vente ou règlement | TR : D trésorerie / C 4111. Le trop-payé (versé − total) est crédité en 4191 (avance du client) |
| Achat (total M, avance utilisée A) | AC : D 601 M / C 4011 (M − A), C 4091 A |
| Paiement d'un achat ou règlement | TR : D 4011 / C trésorerie. Le trop-payé est débité en 4091 |
| Retour de vente (total M, avoir a) | VE : D 701 (M − services), D 706 services / C 4111 (M − a), C 4191 a ; le remboursement en argent : D 4111 / C trésorerie |
| Retour d'achat (total M, avoir a) | AC : D 4011 (M − a), D 4091 a / C 601 M ; le remboursement reçu : D trésorerie / C 4011 |
| Solde de départ d'un client | OD : D 4111 / C 12 (s'il doit) ; D 12 / C 4191 (si la boutique lui doit) |
| Solde de départ d'un fournisseur | OD : D 12 / C 4011 (si la boutique lui doit) ; D 4091 / C 12 (s'il doit) |
| Ajustement d'un solde de départ | OD : la différence entre l'ancien et le nouveau solde, à la date de l'ajustement ; le report d'origine est l'ancien solde |
| Fonds de départ d'un compte | OD : D trésorerie / C 12 |
| Dépense | TR : D compte de la catégorie / C trésorerie |
| Transfert | TR : D 585 / C source ; D destination / C 585 (le 585 revient à zéro) |
| Apport en capital, retrait | TR : D trésorerie / C 104 (et inverse) |
| Apport, retrait en compte courant | TR : D trésorerie / C 4621 (et inverse), tiers = partenaire |
| Déblocage d'emprunt, remboursement | D trésorerie / C 162 ; D 162 / C trésorerie |
| Prêt accordé, remboursement reçu | C trésorerie / D 272 ; D trésorerie / C 272 |
| Intérêts d'emprunt, frais de dossier | D 6712 / C trésorerie ; D 631 / C trésorerie |
| Intérêts de prêt reçus | D trésorerie / C 7712 |
| Produit financier, charge financière | D trésorerie / C 774 ; D 6318 / C trésorerie |
| Autre produit, autre charge | D trésorerie / C 758 ; D 658 / C trésorerie |
| Écart de caisse validé (excédent, manque) | D trésorerie / C 758 ; D 658 / C trésorerie |
| Annulation d'un mouvement | **Contre-passation exacte** de l'écriture annulée (mêmes comptes, sens inverse), marquée « contre-passation » |
| Stock | voir ci-dessous |

Un compte de trésorerie inconnu, ou une annulation dont l'origine n'est pas retrouvée, va au compte d'attente 471 : l'écriture reste
équilibrée et visible.

### Convention des retours et des avances

Pour garder l'égalité « 4111 − 4191 = créances − avances » pour chaque client (et son équivalent fournisseur), l'avoir d'un retour
est crédité en 4191 et le reste du retour vient en moins de 4111 ; le remboursement en argent débite 4111. Le trop-payé d'un règlement
est une avance (4191 ou 4091), comme dans les soldes de l'application.

### Stock : inventaire intermittent

Les achats vont en charges (601) et les ventes en produits (701) à leur date. Le stock est **régularisé à la fin de la période demandée** :
D 31 / C 6031 de la différence entre la valeur du stock à cette date et la valeur du stock de départ (entrées « stock initial »,
reportées à l'ouverture en D 31 / C 12). Le compte 31 égale alors la valeur du stock, et 6031 porte la variation. Les résultats de la
période se lisent donc sans stock permanent (coût des ventes par ligne) : l'**inventaire permanent est documenté mais non fait**.

- Valeur à aujourd'hui ou plus tard : quantité actuelle × coût moyen pondéré actuel de chaque produit en stock (les services sont exclus).
- Valeur à une date passée : les quantités actuelles sont **remontées en arrière** par les ventes, achats, retours et ajustements
  d'inventaire postérieurs à la date (requêtes groupées), puis valorisées au coût moyen actuel. Le coût moyen d'une date passée n'est pas
  conservé par l'application : c'est une approximation signalée (voir les limites).

## Écrans (gérant seulement)

Menu « Comptabilité » : accueil, journal, grand livre, balance, contrôles, plan de comptes et correspondance. Toutes les pages portent
la mention de validation, tiennent sur téléphone (tableaux défilants, formulaires empilés) et suivent le thème sombre (jetons de
`theme.css`).

- **Journal** : période (mois en cours par défaut), journal, début de numéro de compte, recherche sur la pièce ou le libellé. Une ligne
  par ligne d'écriture, totaux débit et crédit.
- **Grand livre** : un tableau par compte, avec le report à nouveau (solde avant la période), le solde après chaque ligne, les totaux.
- **Balance** : ouverture, mouvements et soldes de chaque compte, totaux ; le débit égale le crédit.
- **Contrôles** (voir ci-dessous).
- Exports **PDF** et **Excel** de chacun (mêmes lignes que l'écran), nommés `journal_AAAA-MM-JJ_AAAA-MM-JJ`, etc. Une période invalide
  donne un avertissement et le mois en cours à l'écran, et 400 pour un export.
- Droits : `/comptabilite/**` est réservé au gérant ; le caissier reçoit 403 et le menu ne la montre pas. Chaque boutique ne voit que
  ses opérations. Les formulaires (POST) refusent tout champ inconnu ou répété (400) et exigent le jeton CSRF.

## Les cinq contrôles de cohérence

1. **Équilibre** : chaque écriture est équilibrée et le total des débits égale celui des crédits.
2. **Trésorerie** : pour chaque compte comptable de trésorerie, le solde des livres égale la somme des mouvements de l'application.
3. **Clients** : pour chaque client, solde des livres (4111 et 4191) = solde net de l'application (créances moins avances).
4. **Fournisseurs** : même contrôle sur 4011 et 4091.
5. **Stock** : le compte 31 égale la valeur du stock de l'application.

Les contrôles 3 à 5 comparent avec les soldes d'aujourd'hui : l'application ne conserve pas ses soldes à une date passée. Pour une
période qui finit avant aujourd'hui, ils sont affichés « Non comparé » avec l'explication, jamais « Conforme » à tort.

## Tests

`ConstructeurEcrituresTests` (une écriture écrite à la main par type d'opération), `PlanEtCorrespondanceTests` (plan, normalisation,
mots-clés), `ComptabiliteIntegrationTests` (jeu de données réel : 5 contrôles conformes, comptes calculés à la main, correspondance
modifiée puis rétablie, sous-comptes, deux boutiques, caissier 403, exports, champs inconnus), `ComptabiliteChargeTests` (10 000
opérations), `Lot5MigrationTests` (H2, 23 cas) et `lot5MariaDbTest` (MariaDB : migration, Hibernate validate, requêtes groupées).

## Limites et questions ouvertes

- **Comptes par défaut à valider** par l'expert-comptable (surtout : 701 pour les retours de vente, 104 pour le capital de l'exploitant,
  6031 pour la variation de stock, 585 pour les virements, 471 pour l'attente).
- **Inventaire permanent** non fait : pas de coût d'achat des marchandises vendues par ligne ni de sortie de stock en comptabilité.
- **Coût moyen d'une date passée** approché par le coût actuel ; la quantité, elle, est remontée exactement. Les produits supprimés
  (corbeille) ne comptent pas dans la valeur du stock.
- **Pas de TVA, d'amortissements, de provisions, de paie ni de clôture d'exercice** : hors périmètre de cette étape.
- **Dépenses classées par mots-clés** : une dépense au motif inhabituel va en « autre dépense » ; l'application n'a pas de catégorie de dépense.
- **Une écriture par mouvement** : un paiement qui règle plusieurs documents n'existe pas dans l'application, donc n'est pas ventilé.
- **Dates** : l'application date les mouvements d'un document à l'enregistrement ; un document daté après son règlement (rare) apparaît dans
  l'ordre des dates, sans effet sur les soldes.
- Un client ou fournisseur envoyé à la corbeille garde ses écritures ; le contrôle 3 ou 4 le signale « hors liste » s'il garde un solde.
