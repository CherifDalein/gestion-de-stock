# Pays et monnaie d'une boutique

Chaque boutique a un **pays** et une **monnaie**, choisis à sa création (inscription publique ou création par la plateforme) et qui ne
changent **jamais**. Tout ce que voit l'utilisateur (montants, symbole, montant en lettres, billetage, indicatif téléphonique, opérateurs de
mobile money) en découle. La migration est décrite dans [migrations/pays-monnaie.md](migrations/pays-monnaie.md).

## Table des pays

La table est dans le code : `org.example.stock.pays.Pays` (une ligne par pays) et `org.example.stock.pays.Monnaie` (une ligne par monnaie).

| Pays | Code | Indicatif | Monnaie | Opérateurs de mobile money proposés |
|---|---|---|---|---|
| Guinée | GN | +224 | GNF | Orange Money, MTN MoMo |
| Sénégal | SN | +221 | XOF | Orange Money, Wave, Free Money |
| Côte d'Ivoire | CI | +225 | XOF | Orange Money, MTN MoMo, Moov Money, Wave |
| Mali | ML | +223 | XOF | Orange Money, Moov Money |
| Burkina Faso | BF | +226 | XOF | Orange Money, Moov Money |
| Niger | NE | +227 | XOF | Airtel Money, Moov Money, Orange Money |
| Togo | TG | +228 | XOF | T-Money, Moov Money |
| Bénin | BJ | +229 | XOF | MTN MoMo, Moov Money, Celtiis Cash |
| Guinée-Bissau | GW | +245 | XOF | Orange Money, MTN MoMo |
| Cameroun | CM | +237 | XAF | Orange Money, MTN MoMo |
| Gabon | GA | +241 | XAF | Airtel Money, Moov Money |
| Congo | CG | +242 | XAF | Airtel Money, MTN MoMo |
| Tchad | TD | +235 | XAF | Airtel Money, Moov Money |
| Centrafrique | CF | +236 | XAF | Orange Money, Moov Money |
| Guinée équatoriale | GQ | +240 | XAF | Muni Dinero |

| Monnaie | Nom (singulier / pluriel) | Symbole affiché | Zone | Billets | Pièces |
|---|---|---|---|---|---|
| GNF | franc guinéen / francs guinéens | GNF | Guinée | 20 000, 10 000, 5 000, 2 000, 1 000 | 500, 100 |
| XOF | franc CFA / francs CFA | FCFA | UEMOA | 10 000, 5 000, 2 000, 1 000, 500 | 500, 250, 200, 100, 50, 25, 10, 5 |
| XAF | franc CFA / francs CFA | FCFA | CEMAC | 10 000, 5 000, 2 000, 1 000, 500 | 500, 200, 100, 50, 25, 10, 5 |

Le franc CFA de l'UEMOA (XOF) et celui de la CEMAC (XAF) s'écrivent pareil mais ne sont pas interchangeables : la boutique garde donc le
code ISO de sa monnaie, seul le symbole affiché est commun.

### Ajouter un pays

Une ligne dans `Pays` suffit (code ISO, nom, indicatif, monnaie existante, opérateurs) ; la liste de l'inscription, le choix du pays, le
billetage, l'indicatif et les opérateurs suivent. Une nouvelle monnaie demande en plus une ligne dans `Monnaie` (code, symbole, noms,
coupures) ; les colonnes `boutique.pays` (2 caractères) et `boutique.monnaie` (3 caractères) n'ont pas à changer.

## Règles d'affichage

- **Aucune décimale** : « 150 000 GNF », « 150 000 FCFA » (jamais « 150 000,00 »). Les milliers sont séparés par une **espace insécable**
  (U+00A0), de même entre le nombre et le symbole, pour qu'un montant ne soit jamais coupé en fin de ligne. Un négatif porte « - »
  (« -5 000 FCFA ») ; les écarts signés portent « + » ou « − ».
- **Arrondi HALF_UP, à l'affichage uniquement** : 1 250,50 s'affiche « 1 251 », 0,49 s'affiche « 0 ». Les calculs, les totaux et la base restent en
  `DECIMAL(17,2)` : aucune règle de calcul n'a changé.
- **La somme affichée d'une colonne est l'arrondi de la somme réelle**, pas la somme des valeurs arrondies. Avec trois lignes à 0,50, chaque
  ligne s'affiche « 1 » (0,50 arrondi) et le total s'affiche « 2 » (1,50 arrondi) : l'écart d'un franc visible entre « 1 + 1 + 1 » et « 2 »
  vient de l'arrondi, jamais d'une erreur de calcul. Les monnaies de la zone n'ont pas de centimes : en usage normal, tous les montants
  saisis sont entiers et l'écart ne se voit pas.
- Les champs de saisie acceptent un nombre sans imposer de décimales (`step="any"` ou `step="0.01"` : un entier est toujours accepté).
- Un seul point de formatage côté serveur : le bean Thymeleaf `${@montants.format(x)}` (avec `nombre`, `signe`, `symbole`, `nomPluriel`,
  `lettres`), et côté Java `Monnaie.formater` / `FormatMontants`. Les gabarits PDF reçoivent la variable `monnaie`
  (`${monnaie.formater(x)}`, `${monnaie.symbole}`). Aucun gabarit ne contient « GNF » en dur : un test le vérifie.
- Côté navigateur, le symbole est lu dans l'attribut `data-monnaie` de la balise `body` ; `Montants.afficher` arrondit comme le serveur.
  Les `data-order` et autres `data-*` numériques lus par le JavaScript restent des nombres bruts.
- **Excel** : les cellules contiennent de vrais nombres, avec le format `#,##0 "SYMBOLE"` (sans décimales) ; l'en-tête indique « Montants en
  SYMBOLE ». **PDF** : mêmes montants que l'écran, avec la monnaie de la boutique dans les en-têtes et les pieds de page
  (« Montants exprimés en francs CFA (FCFA) »).
- **Montant en lettres** : « cent quarante mille francs guinéens », « cent quarante mille francs CFA ». Le montant est arrondi au franc, comme
  l'affichage (plus de centimes en lettres).

## Pays et monnaie : immuables

- La monnaie et le pays ne sont écrits qu'à la création (`@Column(updatable = false)`, pas de setter ; `Boutique.definirPays` refuse tout
  changement une fois la boutique enregistrée).
- Aucun champ des paramètres ne les propose : ils s'affichent en lecture seule. Un envoi forcé de `pays` ou `monnaie` est refusé par le
  serveur (les listes blanches de paramètres rejettent la requête).
- La plateforme n'a pas de fonction pour les changer non plus. Corriger une boutique créée dans le mauvais pays demande une intervention sur
  la base, hors application, avec l'accord du client (les montants déjà saisis ne seraient pas convertis).

## Mobile money et opérateur

Le type de compte `ORANGE_MONEY` est devenu `MOBILE_MONEY` (libellé « Mobile money »). Chaque compte de ce type porte un **opérateur**
(colonne `operateur`) choisi dans la liste du pays de la boutique, ou « Autre » avec un nom libre (40 caractères au plus). L'application
affiche le nom du compte, ou « Mobile money · Opérateur » quand elle doit décrire le type. Chaque boutique reçoit trois comptes de base : « Caisse »,
un compte de mobile money nommé d'après l'opérateur le plus répandu du pays (le premier de la liste) et « Banque ». Les comptes existants
deviennent opérateur « Orange Money ». La comptabilité ne change pas : chaque compte reste un sous-compte de 552. Une ancienne valeur de
formulaire `ORANGE_MONEY` est lue comme `MOBILE_MONEY` (comme `CAISSE` est lu comme `ESPECES`).

## Billetage du comptage de caisse

Le formulaire de comptage propose une ligne par valeur de la monnaie de la boutique, de la plus grande à la plus petite (billets et pièces ;
une valeur qui existe en billet et en pièce, comme 500, n'a qu'une ligne). Le serveur recalcule le total avec les mêmes coupures.

## Téléphone

L'indicatif du pays (« +224 », « +221 »…) est proposé par défaut dans les formulaires de client et de fournisseur, et complète le lien `tel:`
d'un numéro saisi sans indicatif. Les numéros déjà saisis ne changent pas.

## Sources et vérifications

Les coupures de référence viennent du client. Elles ont été comparées aux banques centrales (consultées en novembre 2026) :

- **BCEAO** (UEMOA), pages « Billets et pièces » et « Pièces en circulation » : billets de 500 à 10 000 FCFA ; pièces de 500, 250, 200, 100, 50,
  25, 10 et 5 FCFA.
- **BEAC** (CEMAC) : billets de 500 à 10 000 FCFA. La série de pièces 2024 comprend 1, 2, 5, 10, 25, 50, 100, 200 et 500 FCFA ; la pièce de
  **200 FCFA** est en circulation depuis le 5 avril 2025 (financialafrik, 3 avril 2025 ; allAfrica, 26 mars 2025). **Écart voulu avec la liste
  du client** : la pièce de 200 est ajoutée au billetage XAF, la liste du client ne la comprenant pas ; la retirer se fait en supprimant le
  200 de `Monnaie.XAF`. Les pièces de 1 et 2 FCFA ne sont pas proposées (pas de ligne de billetage utile).
- **BCRG** (Guinée) : billets de 500, 1 000, 10 000 et 20 000 GNF sur les affiches consultées ; la liste du client ajoute 5 000, 2 000 et 100, conservés.
- **Opérateurs** : kolonell.com (Bénin : Moov Money et MTN MoMo ; Togo : T-Money et Moov Money ; Niger : Airtel Money et Moov Money ; Sénégal :
  Wave, Orange Money et Free Money) et le360 (Cameroun : Orange Money et MTN MoMo). Les listes des autres pays sont celles du client, à
  valider sur le terrain : l'option « Autre » est toujours disponible.

## Limites

- Une boutique a une seule monnaie : pas de ventes en devises, pas de conversion. Les montants d'un export ou d'un état sont toujours dans la
  monnaie de la boutique.
- La liste de la plateforme affiche le pays et la monnaie de chaque boutique ; ses totaux sont **séparés par monnaie** (jamais d'addition de
  GNF et de FCFA).
- Le plan comptable reste le SYSCOHADA ; la notion de « Région » des comptes 7011 et 6011 dépend de la zone (UEMOA, CEMAC, Guinée) et est à
  confirmer par l'expert-comptable (voir [lot-5.md](lot-5.md)).
- Les listes d'opérateurs et les coupures sont à confirmer sur le terrain à l'ouverture de chaque pays.
