# Lot 2 : préparation du schéma

Cette migration prépare la base pour les fonctions du lot 2 : devis, retours de
vente et d'achat avec avoirs, vendeur des ventes et annulation des règlements.
Elle crée quatre tables vides et ajoute des colonnes et des contraintes. Aucune
règle métier ni aucun écran ne change : les fonctions qui utiliseront ces
tables et ces colonnes seront livrées séparément. Tant qu'elles ne sont pas
livrées, aucun formulaire ne peut renseigner ces champs.

## Tables créées

Les quatre tables sont créées vides, en InnoDB et `utf8mb4_unicode_ci`, sans
suppression en cascade. Les montants sont en `DECIMAL(17,2)`, les énumérations
en `VARCHAR(16)`, les dates de document en `DATETIME(6)` et les échéances ou
validités en `DATE`.

| Table | Colonnes (hors `id`) | Contraintes |
|---|---|---|
| `devis` | `boutique_id` NOT NULL, `numero` VARCHAR(30) NOT NULL, `client_id` NULL, `utilisateur_id` NULL (auteur), `date_devis` DATETIME(6) NOT NULL, `date_validite` DATE NOT NULL, `montant_total` NOT NULL, `statut` VARCHAR(16) NOT NULL (`EN_COURS`, `TRANSFORME`, `ANNULE`), `vente_id` NULL, `note` VARCHAR(255) NULL, `version` BIGINT NOT NULL DEFAULT 0 | UNIQUE (`boutique_id`, `numero`) `uk_devis_boutique_numero` ; UNIQUE (`vente_id`) `uk_devis_vente` : un devis ne se transforme qu'en une seule vente ; clés vers `boutique`, `client`, `utilisateur`, `vente` |
| `detail_devis` | `devis_id`, `produit_id`, `quantite` INT, `prix_unitaire`, tous NOT NULL | clés vers `devis` et `produit` |
| `retour` | `boutique_id` NOT NULL, `numero` VARCHAR(30) NOT NULL, `type` VARCHAR(16) NOT NULL (`VENTE`, `ACHAT`), `vente_id`, `achat_id`, `client_id`, `fournisseur_id`, `utilisateur_id` NULL, `date_retour` DATETIME(6) NOT NULL, `montant_total` NOT NULL, `montant_rembourse` NOT NULL DEFAULT 0, `compte_tresorerie` VARCHAR(16) NULL (`CAISSE`, `ORANGE_MONEY`, `BANQUE`), `montant_avoir` NOT NULL DEFAULT 0, `motif` VARCHAR(255) NULL | UNIQUE (`boutique_id`, `numero`) `uk_retour_boutique_numero` ; clés vers `boutique`, `vente`, `achat`, `client`, `fournisseur`, `utilisateur` |
| `detail_retour` | `retour_id`, `produit_id` NOT NULL, `detail_vente_id`, `detail_achat_id` NULL, `quantite` INT NOT NULL, `prix_unitaire` NOT NULL, `cout_unitaire` NULL | clés vers `retour`, `produit`, `detail_vente`, `detail_achat` |

Toutes les clés étrangères sont nommées `fk_<table>_<référence>` (par exemple
`fk_devis_vente`, `fk_detail_retour_detail_vente`). Les numéros de devis et de
retour restent à générer par l'application à partir des compteurs ci-dessous.

## Colonnes ajoutées et valeurs des données existantes

| Table | Colonne | Type | Valeur des lignes existantes |
|---|---|---|---|
| `vente` | `vendeur_id` | BIGINT NULL, clé `fk_vente_vendeur` vers `utilisateur` | utilisateur du premier mouvement de caisse de la vente qui en a un (voir ci-dessous), sinon vide |
| `achat` | `utilisateur_id` | BIGINT NULL, clé `fk_achat_utilisateur` vers `utilisateur` | même règle pour les mouvements de l'achat |
| `mouvement_caisse` | `retour_id` | BIGINT NULL, clé `fk_mouvement_caisse_retour` vers `retour` | vide : remboursement d'un retour, aucun mouvement existant n'en est un |
| `mouvement_caisse` | `annulation_de_id` | BIGINT NULL, clé `fk_mouvement_caisse_annulation` vers `mouvement_caisse`, UNIQUE `uk_mouvement_caisse_annulation` | vide : un mouvement qui en annule un autre, au plus une annulation par mouvement |
| `boutique` | `prochain_numero_devis`, `prochain_numero_retour_vente`, `prochain_numero_retour_achat` | BIGINT NOT NULL DEFAULT 1 | 1 |

Précisions :

- **Vendeur et auteur.** Pour chaque vente (resp. achat), la migration retient
  le mouvement de caisse rattaché au document qui a le plus petit identifiant
  parmi ceux qui ont un utilisateur, et recopie cet utilisateur. Un mouvement
  sans utilisateur est ignoré ; un document sans aucun mouvement nominatif
  garde une colonne vide. Rien d'autre n'est rempli, et aucun nom n'est déduit
  d'une autre source. Le rattachement à la boutique de l'utilisateur n'est pas
  recontrôlé : un mouvement ne peut pas nommer un compte d'une autre boutique
  dans le fonctionnement normal.
- **Une annulation par mouvement.** La contrainte unique sur
  `annulation_de_id` laisse plusieurs valeurs vides (les mouvements ordinaires)
  mais refuse qu'un même mouvement soit annulé deux fois, y compris par deux
  requêtes simultanées.
- **Un devis, une vente.** La contrainte unique sur `devis.vente_id` joue le
  même rôle pour la transformation d'un devis en vente.
- **Compteurs.** Les trois compteurs démarrent à 1 : aucun devis ni retour
  n'existe. Une boutique créée après la migration démarre aussi à 1.
- `vendeur_id` et `utilisateur_id` restent facultatifs dans le schéma : les
  documents créés par le code actuel, avant la livraison des fonctions du
  lot 2, les laissent vides, et la migration ne les remplit plus après sa fin.

## Conservation

Les vingt tables existantes et toutes leurs anciennes colonnes sont comparées
avant et après l'application, BLOB compris : stocks, prix, montants,
versements, mouvements de caisse et de stock, logos et historiques restent
identiques. Aucun mouvement n'est créé, aucune des quatre nouvelles tables ne
reçoit de ligne.

La reprise est sûre. Les quatre tables sont créées une à une (une table déjà
présente est seulement contrôlée), les colonnes sont ajoutées, `vendeur_id` et
`utilisateur_id` sont remplis dans une seule transaction en n'écrivant que les
valeurs encore vides, puis la contrainte unique et les clés étrangères sont
ajoutées. Enfin `boutique.prochain_numero_retour_achat` devient obligatoire.
Ce dernier changement marque la fin de l'initialisation : une relance ne
réécrit plus aucune valeur, ni celles migrées, ni celles enregistrées depuis
par l'application (vendeur corrigé, compteurs avancés, devis et retours
saisis). Un type, une longueur, une valeur par défaut, une contrainte, une clé
étrangère (cible erronée, composite ou en cascade), une colonne en trop ou une
valeur incohérente (statut ou type inconnu, montant négatif, compteur inférieur
à 1) sont refusés, sans réparation automatique.

Le [SQL de création](../database-schema.sql) contient ces tables et colonnes
pour une installation neuve. Il ne remplace pas la migration d'une base
existante.

## Appliquer sur la base locale

La [migration du lot 1](lot-1.md) et toutes les migrations précédentes doivent
être terminées : la migration du lot 2 le contrôle et s'arrête avec un message
explicite sinon (« La migration du lot 1 doit être terminée avant le lot 2 »).
Arrêter l'application et DevTools, puis sauvegarder la base complète :

```sh
./scripts/backup-stock-local.sh
./gradlew lot2Check
LOT2_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot2Migrate
./gradlew lot2Check
```

`--check` est en lecture seule : il indique les éléments manquants et le nombre
de lignes à initialiser. `--apply` vérifie la cible MariaDB locale
`localhost:3306/stock_pro`, le dump complet et son SHA-256, puis prend un verrou
nommé pour refuser une seconde migration simultanée. Le serveur web garde
Hibernate en mode `validate` : il refuse de démarrer tant que les tables et les
colonnes ne sont pas présentes. **Le code de cette livraison exige donc le lot 2
sur toute base utilisée avec lui, même avant l'arrivée des fonctions.**

MariaDB valide ses DDL avant la fin d'un script. En cas d'interruption, garder
l'application arrêtée, contrôler le schéma avec `lot2Check` puis relancer la
même migration avec la sauvegarde vérifiée. Ne pas démarrer l'application sur
une base dont l'initialisation est inachevée.

Compatibilité : la migration n'emploie que des instructions acceptées par
MariaDB 10.4 (XAMPP) : `CREATE TABLE`, `ALTER TABLE … ADD COLUMN`,
`MODIFY COLUMN`, `ADD CONSTRAINT … FOREIGN KEY` et `UNIQUE`. Elle n'utilise pas
`RENAME COLUMN` (10.5) ni aucune syntaxe plus récente.

## Vérifications

```sh
./gradlew test --tests '*Lot2MigrationTests'
./gradlew lot2MariaDbTest
```

Les mêmes scénarios H2 et MariaDB couvrent la base vide, la base du lot 1 avec
données sur deux boutiques (règlements nominatifs ou anonymes, insérés dans le
désordre, documents sans règlement), le SQL neuf, les tables et colonnes
partielles, la reprise avant et pendant la finalisation, la relance après une
activité postérieure (vendeur corrigé, devis, retour, remboursement et
annulation conservés), la nouvelle boutique, les unicités (numéro par boutique,
vente d'un devis, annulation d'un mouvement) et trente-trois schémas invalides.
Les tests H2 vérifient aussi qu'une base migrée et une base créée par le SQL
neuf ont le même schéma, que le lot 1 est exigé et que les contrôles des
migrations précédentes acceptent une base migrée. MariaDB ajoute le refus d'une
migration concurrente et d'un lot 1 absent, puis démarre Hibernate en mode
`validate` sur la base migrée, historique ou neuve, et enregistre un devis, un
retour avec ses lignes, un vendeur, un remboursement et son annulation.

Les tests MariaDB créent et suppriment uniquement leurs schémas locaux
`stock_lot2_test_*`, jamais `stock_pro`. La variable
`LOT2_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner un serveur
local isolé.
