# Lots 5a et 5b : comptabilité SYSCOHADA (écritures, journal, grand livre, balance, états financiers)

> **Comptabilité en cours de validation par un expert-comptable : vérifiez les états avant tout usage officiel.**
> Cette mention est affichée sur toutes les pages de la section « Comptabilité » et imprimée dans chaque PDF et chaque Excel.
> Les comptes proposés par défaut sont **prudents** et **tous modifiables** : ils sont à valider avec l'expert-comptable.

Cette étape (5a) calcule les écritures comptables en partie double à partir des opérations déjà enregistrées par l'application (ventes,
achats, retours, règlements, dépenses, transferts, financements, comptages de caisse, soldes de départ, stock). L'étape 5b (états financiers) est décrite
plus bas ; l'étape 5c (clôture) n'est pas faite.

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

Menu « Comptabilité » : accueil, états financiers (5b), journal, grand livre, balance, contrôles, plan de comptes et correspondance. Toutes les pages portent
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

## Les cinq contrôles de cohérence (étape 5a ; quatre autres en 5b, voir plus bas)

1. **Équilibre** : chaque écriture est équilibrée et le total des débits égale celui des crédits.
2. **Trésorerie** : pour chaque compte comptable de trésorerie, le solde des livres égale la somme des mouvements de l'application.
3. **Clients** : pour chaque client, solde des livres (4111 et 4191) = solde net de l'application (créances moins avances).
4. **Fournisseurs** : même contrôle sur 4011 et 4091.
5. **Stock** : le compte 31 égale la valeur du stock de l'application.

Les contrôles 3 à 5 comparent avec les soldes d'aujourd'hui : l'application ne conserve pas ses soldes à une date passée. Pour une
période qui finit avant aujourd'hui, ils sont affichés « Non comparé » avec l'explication, jamais « Conforme » à tort.

## États financiers (étape 5b)

Menu « Comptabilité > États financiers » (`/comptabilite/etats-financiers`, gérant seulement) : compte de résultat, bilan et tableau des flux
de trésorerie du **système normal**, ou états du **système minimal de trésorerie** (SMT), au choix de chaque boutique (réglage « Système normal »
par défaut, enregistré dans la table `reglage_comptable_boutique`, voir [la migration](migrations/lot-5.md)). Chaque état a sa page, son PDF
(A4 portrait, en-tête de la boutique, mention de validation, pagination) et son Excel (nombres réels), et porte la mention **« Comptabilité en
cours de validation par un expert-comptable »**. La période par défaut est l'année civile en cours ; l'exercice N-1 (même période un an plus tôt)
est calculé quand il existe des écritures à cette date, sinon sa colonne reste vide.

### Modèles suivis (Acte uniforme révisé, pages du texte officiel fourni)

Références, libellés, ordre des lignes et formules sont ceux du texte officiel, pas de la mémoire : bilan actif (modèle p. 981), bilan passif
(p. 982), compte de résultat (p. 986), tableau des flux de trésorerie (p. 997), correspondance comptes-postes du bilan (p. 1049) et du compte de
résultat (p. 1050), SMT (p. 1056) ; règles de non-compensation et de classement des comptes à double sens aux p. 385, 389, 406 et 414.
Toutes les lignes du modèle sont montrées, même à zéro. Le code est dans `PostesOhada` (modèles et rattachements) et `EtatsFinanciersCalcul`
(calcul pur, sans base de données).

### Origine des montants

Les montants viennent de la balance de l'étape 5a **après la régularisation du stock** (inventaire intermittent). Pour une période, l'application
calcule les écritures arrêtées à la fin de la période et à la veille de son début ; le compte de résultat est la différence des comptes de
gestion (classes 6 à 8), le bilan est la situation à la fin. Les soldes de départ (écritures contre le report à nouveau 12) sont traités comme
l'ouverture : ils n'entrent pas dans le résultat ni dans les flux de la période. Un résultat d'une période antérieure non clôturé s'ajoute au
report à nouveau (CH) ; la clôture est l'étape 5c et n'est pas faite ici.

### Règles de classement du bilan

- **Un compte à double sens se classe d'après le signe de son solde, tiers par tiers, sans compensation** (p. 385 et 389) : un client créditeur
  (avance reçue, 4191 ou 4111) va au passif « Clients, avances reçues » (DI), un client débiteur à l'actif « Clients » (BI) ; un fournisseur
  débiteur (avance versée) va à l'actif « Fournisseurs avances versées » (BH), un fournisseur créditeur au passif (DJ). Même règle pour les
  comptes 42 à 47 (soldes débiteurs en « Autres créances » BJ, soldes créditeurs au passif : DK ou DM). La trésorerie n'est pas compensée non plus
  (p. 414) : un compte débiteur va en BS, un compte créditeur (découvert, ou caisse négative) en trésorerie-passif (DR).
- **Brut, amortissements et net** : chaque poste d'actif est tenu en brut et en amortissements/dépréciations (classes 28, 29, 39, 49, 59) ;
  le net est la différence. Le bilan montre le brut, les amortissements, le net N et le net N-1.
- **Résultat** : CJ est le résultat net du compte de résultat (XI), égal à la somme des comptes de gestion. Un résultat négatif s'affiche en
  négatif.
- **Total général** : actif (BZ) = passif (DZ). Sinon la page, le PDF et l'Excel portent un écart écrit en rouge.
- Un compte que le tableau officiel ne range pas dans un poste précis est placé **par choix de l'application** et signalé en note de l'état
  (colonne « Origine » = « choix » ci-dessous) : comptes d'en-tête sans tiret (10, 11, 21, 23, 28, 29, 48, 49, 59, 186 à 188), comptes HAO à
  sens inverse du texte (481, 482, 484 débiteurs vers BA ; 485 et 488 créditeurs vers DH), comptes 50 à 58 à solde créditeur (DR), 56 et 58 à
  solde débiteur (BS), tout autre compte de classe 1 à 5 inconnu (BJ si débiteur, DM si créditeur). Un test passe les 1 401 comptes du plan
  officiel dans les rattachements : chacun atterrit dans exactement un poste, dans les deux sens de solde.

### Table de rattachement (interne, lisible, non modifiable)

La page « Rattachement des comptes » (`/comptabilite/etats-financiers/rattachements`, avec PDF et Excel) affiche cette table, qui est du code et
n'est pas modifiable par le gérant. Pour chaque poste : colonne, préfixes de comptes (avec exclusions), sens du solde et origine.

#### Bilan

| Poste | Libellé | Colonne | Comptes | Solde | Origine |
|---|---|---|---|---|---|
| AE | Frais de développement et de prospection | Brut | 211, 2181, 2191 | tout solde | officiel |
| AE | Frais de développement et de prospection | Amort. | 2811, 2911 | tout solde | officiel |
| AF | Brevets, licences, logiciels, et droits similaires | Brut | 212, 213, 214, 2193 | tout solde | officiel |
| AF | Brevets, licences, logiciels, et droits similaires | Amort. | 2812, 2813, 2814, 2912, 2913, 2914 | tout solde | officiel |
| AG | Fonds commercial et droit au bail | Brut | 215, 216 | tout solde | officiel |
| AG | Fonds commercial et droit au bail | Amort. | 2815, 2816, 2915, 2916 | tout solde | officiel |
| AH | Autres immobilisations incorporelles | Brut | 217, 218, 2198 (sauf 2181) | tout solde | officiel |
| AH | Autres immobilisations incorporelles | Amort. | 2817, 2818, 2917, 2918, 2919 | tout solde | officiel |
| AJ | Terrains | Brut | 22 | tout solde | officiel |
| AJ | Terrains | Amort. | 282, 292 | tout solde | officiel |
| AK | Bâtiments | Brut | 231, 232, 233, 237, 2391 | tout solde | officiel |
| AK | Bâtiments | Amort. | 2831, 2832, 2833, 2837, 2931, 2932, 2933, 2937 | tout solde | officiel |
| AL | Aménagements, agencements et installations | Brut | 234, 235, 238, 239 (sauf 2391) | tout solde | officiel |
| AL | Aménagements, agencements et installations | Amort. | 2834, 2835, 2838, 2934, 2935, 2938, 2939 | tout solde | officiel |
| AM | Matériel, mobilier et actifs biologiques | Brut | 24 (sauf 245, 2495) | tout solde | officiel |
| AM | Matériel, mobilier et actifs biologiques | Amort. | 284, 294 (sauf 2845, 2945) | tout solde | officiel |
| AN | Matériel de transport | Brut | 245, 2495 | tout solde | officiel |
| AN | Matériel de transport | Amort. | 2845, 2945 | tout solde | officiel |
| AP | Avances et acomptes versés sur immobilisations | Brut | 251, 252 | tout solde | officiel |
| AP | Avances et acomptes versés sur immobilisations | Amort. | 2951, 2952 | tout solde | officiel |
| AR | Titres de participation | Brut | 26 | tout solde | officiel |
| AR | Titres de participation | Amort. | 296 | tout solde | officiel |
| AS | Autres immobilisations financières | Brut | 27 | tout solde | officiel |
| AS | Autres immobilisations financières | Amort. | 297 | tout solde | officiel |
| BA | ACTIF CIRCULANT HAO | Brut | 485, 488 | solde débiteur | officiel |
| BA | ACTIF CIRCULANT HAO | Amort. | 498 | tout solde | officiel |
| BA | ACTIF CIRCULANT HAO | Brut | 481, 482, 484 | solde débiteur | choix |
| BB | STOCKS ET ENCOURS | Brut | 31, 32, 33, 34, 35, 36, 37, 38 | tout solde | officiel |
| BB | STOCKS ET ENCOURS | Amort. | 39 | tout solde | officiel |
| BH | Fournisseurs avances versées | Brut | 40 | solde débiteur | officiel |
| BH | Fournisseurs avances versées | Amort. | 490 | tout solde | officiel |
| BI | Clients | Brut | 41 | solde débiteur | officiel |
| BI | Clients | Amort. | 491 | tout solde | officiel |
| BJ | Autres créances | Brut | 185, 42, 43, 44, 45, 46, 47 (sauf 478) | solde débiteur | officiel |
| BJ | Autres créances | Amort. | 492, 493, 494, 495, 496, 497 | tout solde | officiel |
| BQ | Titres de placement | Brut | 50 | solde débiteur | officiel |
| BQ | Titres de placement | Amort. | 590 | tout solde | officiel |
| BR | Valeurs à encaisser | Brut | 51 | solde débiteur | officiel |
| BR | Valeurs à encaisser | Amort. | 591 | tout solde | officiel |
| BS | Banques, chèques postaux, caisse et assimilés | Brut | 52, 53, 54, 55, 57, 581, 582 | solde débiteur | officiel |
| BS | Banques, chèques postaux, caisse et assimilés | Amort. | 592, 593, 594 | tout solde | officiel |
| BS | Banques, chèques postaux, caisse et assimilés | Brut | 56, 58 | solde débiteur | choix |
| BU | Ecart de conversion-Actif | Brut | 478 | tout solde | officiel |
| CA | Capital | Passif | 101, 102, 103, 104 | tout solde | officiel |
| CB | Apporteurs capital non appelé (-) | Passif | 109 | tout solde | officiel |
| CD | Primes liées au capital social | Passif | 105 | tout solde | officiel |
| CE | Ecarts de réévaluation | Passif | 106 | tout solde | officiel |
| CF | Réserves indisponibles | Passif | 111, 112, 113 | tout solde | officiel |
| CG | Réserves libres | Passif | 118 | tout solde | officiel |
| CH | Report à nouveau (+ ou -) | Passif | 12 | tout solde | officiel |
| CJ | Résultat net de l'exercice (bénéfice + ou perte -) | Passif | 13 | tout solde | officiel |
| CL | Subventions d'investissement | Passif | 14 | tout solde | officiel |
| CM | Provisions réglementées | Passif | 15 | tout solde | officiel |
| DA | Emprunts et dettes financières diverses | Passif | 16, 181, 182, 183, 184 | tout solde | officiel |
| DB | Dettes de location acquisition | Passif | 17 | tout solde | officiel |
| DC | Provisions pour risques et charges | Passif | 19 | tout solde | officiel |
| DH | Dettes circulantes HAO | Passif | 481, 482, 484, 4998 | solde créditeur | officiel |
| DH | Dettes circulantes HAO | Passif | 485, 488 | solde créditeur | choix |
| DI | Clients, avances reçues | Passif | 41 | solde créditeur | officiel |
| DJ | Fournisseurs d'exploitation | Passif | 40 | solde créditeur | officiel |
| DK | Dettes fiscales et sociales | Passif | 42, 43, 44 | solde créditeur | officiel |
| DM | Autres dettes | Passif | 185, 45, 46, 47 (sauf 479) | solde créditeur | officiel |
| DN | Provisions pour risques à court terme | Passif | 499, 599 (sauf 4998) | tout solde | officiel |
| DQ | Banques, crédits d'escompte | Passif | 564, 565 | tout solde | officiel |
| DR | Banques, établissements financiers et crédits de trésorerie | Passif | 52, 53, 561, 566 | solde créditeur | officiel |
| DR | Banques, établissements financiers et crédits de trésorerie | Passif | 5 | solde créditeur | choix |
| DV | Ecart de conversion-Passif | Passif | 479 | tout solde | officiel |


#### Compte de résultat

| Poste | Libellé | Comptes | Origine |
|---|---|---|---|
| TA | Ventes de marchandises | 701 | officiel |
| RA | Achats de marchandises | 601 | officiel |
| RB | Variation de stocks de marchandises | 6031 | officiel |
| TB | Ventes de produits fabriqués | 702, 703, 704 | officiel |
| TC | Travaux, services vendus | 705, 706 | officiel |
| TD | Produits accessoires | 707 | officiel |
| TE | Production stockée (ou déstockage) | 73 | officiel |
| TF | Production immobilisée | 72 | officiel |
| TG | Subventions d'exploitation | 71 | officiel |
| TH | Autres produits | 75 | officiel |
| TI | Transferts de charges d'exploitation | 781 | officiel |
| RC | Achats de matières premières et fournitures liées | 602 | officiel |
| RD | Variation de stocks de matières premières et fournitures liées | 6032 | officiel |
| RE | Autres achats | 604, 605, 608 | officiel |
| RF | Variation de stocks d'autres approvisionnements | 6033 | officiel |
| RG | Transports | 61 | officiel |
| RH | Services extérieurs | 62, 63 | officiel |
| RI | Impôts et taxes | 64 | officiel |
| RJ | Autres charges | 65 | officiel |
| RK | Charges de personnel | 66 | officiel |
| TJ | Reprises d'amortissements, provisions et dépréciations | 791, 798, 799 | officiel |
| RL | Dotations aux amortissements, aux provisions et dépréciations | 681, 691 | officiel |
| TK | Revenus financiers et assimilés | 77 | officiel |
| TL | Reprises de provisions et dépréciations financières | 797 | officiel |
| TM | Transferts de charges financières | 787 | officiel |
| RM | Frais financiers et charges assimilées | 67 | officiel |
| RN | Dotations aux provisions et aux dépréciations financières | 697 | officiel |
| TN | Produits des cessions d'immobilisations | 82 | officiel |
| TO | Autres Produits HAO | 84, 86, 88 | officiel |
| RO | Valeurs comptables des cessions d'immobilisations | 81 | officiel |
| RP | Autres Charges HAO | 83, 85 | officiel |
| RQ | Participation des travailleurs | 87 | officiel |
| RS | Impôts sur le résultat | 89 | officiel |
| RB | Variation de stocks de marchandises | 603 | choix |
| RL | Dotations aux amortissements, aux provisions et dépréciations | 68, 69 | choix |
| TI | Transferts de charges d'exploitation | 78 | choix |
| TJ | Reprises d'amortissements, provisions et dépréciations | 79 | choix |
| TA | Ventes de marchandises | 70 | choix |
| RE | Autres achats | 60 | choix |


### Soldes intermédiaires du compte de résultat

Produits en positif, charges en négatif ; formules du texte : marge commerciale XA = TA + RA + RB ; chiffre d'affaires XB = TA + TB + TC + TD ;
valeur ajoutée XC = XB + RA + RB + (TE à RJ) ; EBE XD = XC + RK ; résultat d'exploitation XE = XD + TJ + RL ; résultat financier XF = TK à RN ;
résultat des activités ordinaires XG = XE + XF ; résultat HAO XH = TN à RP ; résultat net XI = XG + XH + RQ + RS.
Le coût d'achat des marchandises vendues ressort de la variation de stock (RB) : la marge commerciale est correcte, la ligne « Achats » (RA) est
brute des retours.

### Tableau des flux de trésorerie (méthode indirecte)

Trésorerie d'ouverture (A) = classe 5 hors 59 de l'ouverture (actif moins passif). CAFG à partir de l'EBE ; variation du besoin de financement
(stocks, créances y compris avances versées, passif circulant y compris avances reçues) ; investissements (variation du brut des immobilisations) ;
financement (capital, emprunts, comptes courants d'associés, remboursements) lus sur la variation des soldes de la balance. **La trésorerie
finale doit égaler la classe 5 hors 59 de la balance** (contrôle 8).

### Système minimal de trésorerie

États recettes et dépenses (rangés d'après le compte de contrepartie de chaque flux de trésorerie : ventes, autres recettes, achats, loyers,
salaires, impôts, intérêts, autres dépenses), solde C = A - B, variations de stocks, de créances et de dettes d'exploitation, résultat G, puis bilan
simplifié (immobilisations, stocks, clients et débiteurs divers, caisse, banque ; compte exploitant, résultat, emprunt, fournisseurs et
créditeurs divers). Le bilan SMT doit s'équilibrer (contrôle 9) et son résultat égale celui du compte de résultat.

### Contrôles ajoutés à la page Contrôles

6. **Bilan** : total de l'actif = total du passif.
7. **Résultat** : résultat du bilan (CJ) = résultat du compte de résultat (XI) = somme des comptes de gestion.
8. **Flux** : trésorerie finale du tableau des flux = classe 5 hors 59 de la balance.
9. **SMT** : total de l'actif SMT = total du passif SMT.

Ils portent sur la période choisie à la page Contrôles (mois en cours par défaut) ; les cinq premiers sont inchangés.

### Limites et questions pour l'expert-comptable

- **SMT : sens des signes.** Le modèle officiel écrit « G = C - D + E - F » sans définir D et E ; l'application présente chaque variation avec son
  effet sur le résultat (hausse des stocks et des créances : plus ; hausse des dettes : moins). À confirmer. La cession d'immobilisation n'est pas
  traitée par le SMT (l'application n'en crée pas).
- **Comptes « p »** du texte (partagés entre deux postes, par exemple 2818, 2918, 2919, 2939, 2949) : rangés au premier poste cité ; à confirmer.
- **Comptes de repli** (colonne « choix » ci-dessus, dont 603 hors 6031, 70 hors 701 à 707, 68 et 69 hors 681, 691, 697, 78 et 79 hors comptes cités) :
  décisions de l'application, à valider.
- **Écart de caisse** : le manque (658) est rangé en « Autres charges » (RJ), donc dans la valeur ajoutée, et l'excédent (758) en « Autres produits »
  (TH), comme le texte range les comptes 65 et 75 ; à confirmer. Le compte 774 « produits financiers saisis » va en revenus financiers (TK).
- **Charges et produits HAO et CAFG** : 654 et 754 (cessions courantes) sont repris dans la CAFG comme le texte ; les charges et produits HAO 83,
  84 et 88 y sont pris encaissables ; à confirmer.
- **Coût du stock** : le stock d'une date passée est valorisé au coût moyen actuel (limite de l'étape 5a) ; l'exercice N-1 en hérite.
- **Capital et emprunts** : les flux FK, FM, FO et FQ se lisent sur la variation du compte sur l'exercice ; un apport et un retrait de la même
  période se compensent. Les comptes courants d'associés (46) sont présentés en financement par les capitaux étrangers (FP) et les emprunts reçus en FO.
- **Pas de TVA, de provisions, d'impôt sur le résultat ni d'immobilisations créées par l'application** : les lignes correspondantes restent à zéro
  tant que des écritures manuelles n'existent pas.
- **Clôture** (écritures de clôture, report du résultat dans le compte 12, ouverture de l'exercice suivant) : étape 5c, non faite.

## Tests

`ConstructeurEcrituresTests` (une écriture écrite à la main par type d'opération), `PlanEtCorrespondanceTests` (plan, normalisation,
mots-clés), `ComptabiliteIntegrationTests` (jeu de données réel : 5 contrôles conformes, comptes calculés à la main, correspondance
modifiée puis rétablie, sous-comptes, deux boutiques, caissier 403, exports, champs inconnus), `ComptabiliteChargeTests` (10 000
opérations), `Lot5MigrationTests` (H2, 23 cas) et `lot5MariaDbTest` (MariaDB : migration, Hibernate validate, requêtes groupées). Étape 5b : `EtatsFinanciersCalculTests` (jeux équilibrés,
tiers à double sens, découvert, immobilisations, cession, couverture des 1 401 comptes du plan), `EtatsFinanciersIntegrationTests` (jeu réel avec
ventes, achats, retours, avances, emprunt avec intérêts, dépenses, écart de caisse et apport ; compte de résultat, bilan, flux et SMT calculés à la
main, N-1, PDF, Excel, caissier 403, isolation, réglage du système).

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
