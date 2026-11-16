# Migration pays-monnaie

Cette migration ajoute le pays et la monnaie des boutiques et l'opérateur des comptes de mobile money
([../pays-monnaie.md](../pays-monnaie.md)). Elle ne supprime aucune donnée ni aucune table et ne modifie ni montants, ni mouvements, ni
comptes (hors le type converti). Elle exige que les migrations des lots 5 et 6 (et donc 1 à 4) soient terminées.

## Changements

| Table | Changement |
|---|---|
| `boutique` | `pays` VARCHAR(2) NOT NULL et `monnaie` VARCHAR(3) NOT NULL ; les boutiques existantes reçoivent `GN` et `GNF` |
| `compte_tresorerie_boutique` | `operateur` VARCHAR(40) NULL |
| `compte_tresorerie_boutique` | `type` et `principal_type` : `ORANGE_MONEY` devient `MOBILE_MONEY` ; l'unicité `uk_compte_tresorerie_principal (boutique_id, principal_type)` est conservée |
| `compte_tresorerie_boutique` | `operateur` = « Orange Money » pour tout compte de mobile money sans opérateur |

Étapes, chacune reprenable après une interruption : ajout des colonnes vides, remplissage du pays et de la monnaie des boutiques qui n'en
ont pas (jamais une valeur déjà posée), passage en NOT NULL, conversion du type, opérateur. Avant et après, la migration compare toutes les
tables (hors colonnes ajoutées, le type converti étant normalisé) et les **totaux des mouvements par compte et par type** ; tout écart
l'arrête. Elle refuse un schéma incohérent : pays inconnu, monnaie qui n'est pas celle du pays, opérateur sur un compte qui n'est pas du
mobile money, type de compte inconnu, compte principal d'un autre type que le sien. Syntaxe compatible MariaDB 10.4.

## Procédure

Arrêter l'application, puis sauvegarder la base complète :

```bash
./gradlew paysMonnaieCheck
PAYS_MONNAIE_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew paysMonnaieMigrate
./gradlew paysMonnaieCheck
```

- `paysMonnaieCheck` ne modifie rien : il dit si la migration est nécessaire, déjà faite ou si le schéma est incohérent.
- `paysMonnaieMigrate` refuse de s'exécuter sans sauvegarde valide dans `PAYS_MONNAIE_BACKUP_DIR`, prend le verrou nommé
  `stock_pays_monnaie_migration`, puis applique les étapes ci-dessus. Elle est **idempotente**.
- Pour une base neuve, `docs/database-schema.sql` contient déjà les colonnes.
- `InitialAdmin` accepte un pays (variable `INITIAL_ADMIN_PAYS`, Guinée par défaut) et reconnaît l'ancien et le nouveau schéma.

## Retour arrière

Restaurer la sauvegarde faite avant `paysMonnaieMigrate` avec la version précédente de l'application. Après migration, l'ancienne version ne
reconnaît plus le type `MOBILE_MONEY` : ne pas la relancer sur une base migrée sans restaurer.

## Vérifications

```bash
./gradlew test --no-daemon --tests '*PaysMonnaieMigrationTests'   # H2 : scénarios, sauvegarde, lots 5 et 6 requis, schéma migré = schéma neuf
service mariadb start && ./gradlew paysMonnaieMariaDbTest        # MariaDB jetable : idempotence, données conservées, immuabilité, Hibernate validate (migré et neuf)
./gradlew mariaDbTestsTous --no-daemon --continue                 # toutes les suites MariaDB, dont celle-ci
```
