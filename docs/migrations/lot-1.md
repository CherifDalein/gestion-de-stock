# Lot 1 : préparation du schéma

Cette migration prépare la base pour les fonctions du lot 1 : seuil d'alerte,
unités, services, coût moyen pondéré, numéros de facture, échéances, avances et
soldes initiaux des clients et fournisseurs. Elle ajoute seulement des colonnes
et deux contraintes uniques. Aucune règle métier ni aucun écran ne change :
les fonctions qui utiliseront ces colonnes seront livrées séparément. Tant
qu'elles ne sont pas livrées, les formulaires existants ne peuvent pas renseigner
ces champs.

## Colonnes ajoutées et valeurs des données existantes

| Table | Colonne | Type | Valeur des lignes existantes |
|---|---|---|---|
| `produit` | `nature` | VARCHAR(16) NOT NULL DEFAULT 'STOCK' | `STOCK` (valeurs admises : `STOCK`, `SERVICE`) |
| `produit` | `unite` | VARCHAR(30) NULL | vide, aucune unité n'est inventée |
| `produit` | `seuil_alerte` | BIGINT NOT NULL DEFAULT 5 | 5 |
| `produit` | `cout_moyen` | DECIMAL(17,2) NOT NULL DEFAULT 0 | le `prix_achat` actuel du produit |
| `detail_vente` | `cout_unitaire` | DECIMAL(17,2) NULL | le `prix_achat` actuel du produit de la ligne (voir ci-dessous) |
| `vente` | `numero` | VARCHAR(30) NULL, UNIQUE (`boutique_id`, `numero`) | `FV-` suivi de l'identifiant sur six chiffres au moins : `FV-000042` |
| `achat` | `numero` | VARCHAR(30) NULL, UNIQUE (`boutique_id`, `numero`) | `FA-` suivi de l'identifiant : `FA-000007` |
| `vente`, `achat` | `date_echeance` | DATE NULL | date du document + 30 jours s'il reste à payer, sinon vide |
| `vente`, `achat` | `avance_utilisee` | DECIMAL(17,2) NOT NULL DEFAULT 0 | 0 |
| `boutique` | `delai_clients_jours`, `delai_fournisseurs_jours` | INT NOT NULL DEFAULT 30 | 30 |
| `boutique` | `prochain_numero_vente`, `prochain_numero_achat` | BIGINT NOT NULL DEFAULT 1 | plus grand identifiant de toute la table `vente` (resp. `achat`) + 1, ou 1 si elle est vide |
| `client`, `fournisseur` | `solde_initial` | DECIMAL(17,2) NOT NULL DEFAULT 0 | 0 |
| `client`, `fournisseur` | `echeance_solde_initial` | DATE NULL | vide |

Précisions :

- Un document « reste à payer » quand `montant_total > montant_verse`, une valeur
  vide comptant pour zéro, comme le reste à payer affiché aujourd'hui. Une vente
  soldée ou trop payée n'a pas d'échéance. Un document sans date n'en reçoit
  pas non plus. L'échéance est calculée sur le jour enregistré de la date, sans
  conversion de fuseau.
- Un identifiant de plus de six chiffres est écrit en entier (`FV-1234567`) :
  les numéros ne sont jamais tronqués. Comme les identifiants sont uniques dans
  toute la table, les numéros migrés sont aussi uniques dans chaque boutique.
- Les deux compteurs de chaque boutique démarrent après le plus grand
  identifiant de toute la table, pas seulement de la boutique. Un futur numéro
  `FV-` ou `FA-` généré par compteur ne peut donc jamais reprendre un numéro
  migré. Une boutique créée après la migration démarre à 1.
- `solde_initial` est signé : positif quand le client ou le fournisseur me doit,
  négatif quand je lui dois ou qu'il m'a versé une avance.
- `numero` reste facultatif dans le schéma : plusieurs numéros vides restent
  permis par la contrainte unique. Depuis la livraison des
  [numéros de facture](../lot-1.md#numéros-de-facture-et-échéances), l'application
  numérote toutes les nouvelles ventes et tous les nouveaux achats ; la colonne
  pourra devenir obligatoire par une migration ultérieure.
  De même, les nouvelles lignes de vente gardent `cout_unitaire` vide et les
  nouveaux produits un `cout_moyen` à zéro jusqu'à la livraison du coût moyen
  pondéré.

### Approximation du coût des ventes passées

L'historique des coûts d'achat au moment de chaque vente n'est pas conservé.
`detail_vente.cout_unitaire` reçoit donc le prix d'achat **actuel** du produit,
qui est aussi le `cout_moyen` initial. Pour un produit dont le prix d'achat a
changé depuis une vente, la marge calculée sur cette vente est approximative.
Cette approximation est assumée : elle ne concerne que les ventes antérieures à
la migration et ne modifie ni le prix de vente, ni le total, ni la caisse.

## Conservation

Les vingt tables et toutes leurs anciennes colonnes sont comparées avant et
après l'application, BLOB compris : stocks, prix, montants, versements,
mouvements de caisse et de stock, logos et historiques restent identiques.
Aucun nouveau mouvement n'est créé.

La reprise est sûre. Les colonnes à initialiser sont d'abord ajoutées
facultatives, puis remplies dans une seule transaction, en n'écrivant que les
valeurs encore vides ; les contraintes uniques sont ensuite ajoutées. Enfin, les
deux compteurs puis `produit.cout_moyen` deviennent obligatoires. Ce dernier
changement marque la fin de l'initialisation : une relance ne réécrit plus
aucune valeur, ni celles migrées, ni celles enregistrées depuis par
l'application (ventes sans numéro, échéances vides, nouveaux produits). Un type,
une longueur, une valeur par défaut, une contrainte ou une valeur incohérents
sont refusés, sans réparation automatique.

Le [SQL de création](../database-schema.sql) contient ces colonnes pour une
installation neuve. Il ne remplace pas la migration d'une base existante.

## Appliquer sur la base locale

La migration de l'identité de boutique et toutes les migrations précédentes
doivent être terminées. Arrêter l'application et DevTools, puis sauvegarder la
base complète :

```sh
./scripts/backup-stock-local.sh
./gradlew lot1Check
LOT1_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot1Migrate
./gradlew lot1Check
```

`--check` est en lecture seule : il indique les éléments manquants et le nombre
de lignes à initialiser. `--apply` vérifie la cible MariaDB locale
`localhost:3306/stock_pro`, le dump complet et son SHA-256, puis prend un verrou
nommé pour refuser une seconde migration simultanée. Le serveur web garde
Hibernate en mode `validate` : il refuse de démarrer tant que les colonnes ne
sont pas présentes.

MariaDB valide ses DDL avant la fin d'un script. En cas d'interruption, garder
l'application arrêtée, contrôler le schéma avec `lot1Check` puis relancer la
même migration avec la sauvegarde vérifiée. Ne pas démarrer l'application sur
une base dont l'initialisation est inachevée.

## Vérifications

```sh
./gradlew test --tests '*Lot1MigrationTests'
./gradlew lot1MariaDbTest
```

Les mêmes scénarios H2 et MariaDB couvrent la base vide, la base historique de
treize boutiques avec ventes et achats soldés, à crédit, trop payés, sans date ou
à grand identifiant, le SQL neuf, les ajouts partiels, la reprise avant et
pendant la finalisation, la relance après une activité postérieure, la nouvelle
boutique, l'unicité des numéros par boutique et les schémas invalides. MariaDB
ajoute le refus d'une migration concurrente et démarre Hibernate en mode
`validate` sur la base migrée, historique ou neuve, puis enregistre une boutique,
un produit, un client et une vente avec les valeurs par défaut attendues.

Les tests MariaDB créent et suppriment uniquement leurs schémas locaux
`stock_lot1_test_*`, jamais `stock_pro`. La variable
`LOT1_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner un serveur
local isolé.
