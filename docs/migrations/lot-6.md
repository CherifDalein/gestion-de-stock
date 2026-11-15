# Lot 6 : corbeille

Cette migration prépare la corbeille ([lot-6.md](../lot-6.md)). Elle ne supprime aucune donnée ni aucune table, et ne modifie ni stocks,
ni montants, ni mouvements. Elle exige que les migrations des lots 3 et 4 (et donc 1 et 2) soient terminées.

## Changements

| Table | Ajout |
|---|---|
| `client`, `fournisseur`, `produit`, `categorie` | `supprime_le` DATETIME(6) NULL, `supprime_par` BIGINT NULL (vides : rien n'est à la corbeille) |
| `categorie` | `nom_cle` VARCHAR(255) NULL, rempli avec le nom des catégories existantes |
| `categorie` | unicité `uk_categorie_boutique_nom_cle (boutique_id, nom_cle)` ajoutée, puis ancienne `uk_categorie_boutique_nom (boutique_id, nom)` retirée |

La nouvelle unicité est posée **avant** le retrait de l'ancienne (InnoDB peut s'appuyer sur elle pour la clé étrangère `boutique_id`).
Aucun doublon n'est possible : les noms existants étaient déjà uniques. Syntaxe compatible MariaDB 10.4.

## Procédure

Arrêter l'application, puis sauvegarder la base complète :

```bash
./gradlew lot6Check
LOT6_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot6Migrate
./gradlew lot6Check
```

- `lot6Check` ne modifie rien : il dit si la migration est nécessaire, déjà faite ou si le schéma est incohérent.
- `lot6Migrate` refuse de s'exécuter sans sauvegarde valide dans `LOT6_BACKUP_DIR`, prend le verrou nommé `stock_lot6_migration`,
  puis applique les étapes ci-dessus. Elle est **idempotente** et reprend après une interruption.

## Retour arrière

Restaurer la sauvegarde faite avant `lot6Migrate` avec la version précédente de l'application. Si la base est déjà migrée et que
l'ancienne version doit tourner, aucune fiche ne doit être à la corbeille (les restaurer ou les effacer d'abord) : les colonnes
ajoutées sont inoffensives pour l'ancienne version, mais ses listes montreraient des fiches supprimées.

## Vérifications

```bash
./gradlew test --no-daemon --tests '*Lot6MigrationTests'   # H2 : scénarios, sauvegarde, lots 3 et 4 requis, schéma migré = schéma neuf
service mariadb start && ./gradlew lot6MariaDbTest         # MariaDB jetable : idempotence, base sans lot 6, Hibernate validate (migré et neuf) ; enchaîne corbeilleMariaDbTest
./gradlew corbeilleMariaDbTest                             # SQL natif de la corbeille : lister, restaurer, conflit de nom, effacement, vidage, purge
./gradlew corbeilleMariaDbTest lot1MariaDbTest lot2MariaDbTest lot3MariaDbTest lot4MariaDbTest ordreVerrousMariaDbTest   # les lots précédents restent valides
```
