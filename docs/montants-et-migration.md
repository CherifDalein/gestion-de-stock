# Montants exacts et migration d'une base existante

Les prix, montants des documents, versements et mouvements de caisse utilisent `BigDecimal` en Java et `DECIMAL(17,2)` en base. Chaque valeur stockée peut comporter **15 chiffres entiers et 2 décimales**, au maximum `999999999999999.99`. Les prix et versements sont positifs ou nuls ; les mouvements de sortie de caisse sont négatifs.

Les nouveaux montants trop précis ou trop grands sont refusés, sans arrondi silencieux. `NaN` et les infinis ne peuvent pas être convertis en `BigDecimal`. Les achats et ventes calculent leur total avec les prix validés et les quantités ; une erreur annule aussi les changements de stock et de caisse. Les agrégats de plusieurs mouvements restent des `BigDecimal` et peuvent dépasser le plafond d'une valeur individuelle.

Les paniers calculent en centimes entiers avec `BigInt`. Ils envoient les montants sous forme de chaînes décimales et conservent les versements saisis manuellement. Les factures et journaux affichent deux décimales. Un ancien versement `NULL` reste conservé en base et compte comme zéro dans le calcul de la dette.

## Avant de démarrer une ancienne base

1. Arrêter toutes les instances de l'application qui utilisent cette base, y compris un `bootRun` avec DevTools. Avec `ddl-auto=update`, un redémarrage ou rechargement automatique peut convertir les colonnes avant le contrôle manuel.
2. Faire une sauvegarde complète de la base, hors du dépôt Git, avec le client d'administration habituel ou `mariadb-dump` / `mysqldump`. Vérifier que cette sauvegarde est lisible avant de continuer.
3. Maintenir l'application arrêtée pendant toute la migration. Les copies et les `ALTER TABLE` ne forment pas une transaction atomique : les opérations DDL produisent des commits implicites.
4. Exécuter le script correspondant au serveur, dans la bonne base. Ces scripts nécessitent les droits de lecture, création/copie de tables et modification du schéma. La variante MySQL nécessite aussi les droits de gestion de routines.

Pour MariaDB 10.2 ou ultérieur, par exemple en local avec le client disponible :

```sh
mariadb -u root -p stock_pro < docs/migrations/2026-10-05-money-decimal.sql
```

Pour MySQL 8 :

```sh
mysql -u root -p stock_pro < docs/migrations/2026-10-05-money-decimal-mysql.sql
```

Adapter l'hôte et l'utilisateur aux [paramètres de connexion](connexion-base.md). Les identifiants distants ne doivent pas être placés dans le script ou dans Git.

## Contrôles et sauvegardes du script

Les neuf colonnes sont contrôlées **avant le premier changement de table**. La migration s'arrête si elle rencontre un prix négatif, un montant hors plage, un montant obligatoire absent, un versement supérieur au total ou une fraction nécessitant un arrondi significatif. Les versements historiques `NULL` sont autorisés et conservés.

Une différence d'au plus `0.0000001` entre l'ancien flottant et son arrondi à deux décimales est acceptée comme bruit de calcul, par exemple `0.30000000000000004`. Un montant tel que `1.234` est refusé et doit être examiné avant migration. Cette conversion ne peut pas reconstituer les centimes déjà perdus dans les anciens calculs en `DOUBLE`.

Après ces contrôles, les six tables concernées sont copiées dans `money_backup_20261005_*`, avec leur schéma et leurs données. Ces copies conservent les anciennes colonnes monétaires. Elles complètent la sauvegarde complète faite avant migration ; les clés étrangères ne sont pas reproduites par `CREATE TABLE ... LIKE`.

Le script refuse d'écraser une copie existante si la migration précédente est incomplète. Une base dont les neuf colonnes sont déjà en `DECIMAL(17,2)` est reconnue sans nouvelle conversion ni nouvelle copie. Le mode SQL initial de la session est restauré, même en cas d'erreur.

Conserver les sauvegardes jusqu'à vérification du schéma, des quantités, des nombres de lignes, des totaux, des versements, des dettes et du solde de caisse. Si une erreur survient après un `ALTER TABLE`, garder l'application arrêtée et examiner les copies et les colonnes déjà converties ; un simple `ROLLBACK` ne restaure pas le schéma. Une restauration complète peut être nécessaire. Ne pas rétablir aveuglément d'anciennes valeurs après de nouveaux paiements.

Redémarrer ensuite avec `DB_DDL_AUTO=validate` pour vérifier le schéma sans demander d'autres modifications. Les scripts ne changent ni les quantités, ni les identifiants, ni les associations, et ne recalculent pas les factures anciennes à partir des prix actuels.

## Vérifications du 5 octobre 2026

- Tests Java sur H2 : achats, ventes, règlements, centimes, grands montants, dépassements, transactions, factures et agrégats.
- Tests JavaScript : `node --test src/test/js/montants.test.cjs`.
- Huit scénarios du script MariaDB exécutés sur MariaDB **10.4.28** dans des bases temporaires : conversion normale, bruit flottant, fractions excessives, prix négatif, dépassement de plafond, surpaiement, prix manquant et sauvegarde déjà présente. Les cas valides contrôlent aussi les copies, les valeurs, les quantités, la conservation de `NULL`, la restauration du mode SQL et la relance sans nouvelle conversion. Les bases temporaires ont été supprimées. La variante MySQL n'a pas été exécutée sur un serveur MySQL.
- Le schéma local `stock_pro` présente les neuf colonnes en `DECIMAL(17,2)`. Le premier contrôle de ces colonnes a eu lieu **après compilation**, alors qu'une application locale avec DevTools et `ddl-auto=update` était lancée. Il ne constitue donc pas une comparaison avant/après conversion ni une sauvegarde préalable des anciennes valeurs. Les valeurs lues sont conformes ; les stocks et nombres de documents ne sont pas modifiés par les sondes JDBC en lecture seule.
- Le fichier exécutable a démarré avec validation du schéma et connexions en lecture seule. `/login` répond HTTP 200 avec un jeton CSRF. Aucun paiement réel n'a été soumis pour cette vérification.

Le contrôle de migration est conservé dans `src/test/migration/StockMoneyMigrationTest.java`. Il nécessite Java 21, le pilote MariaDB sur le classpath et les droits de création/suppression de bases temporaires sur le serveur local. Il refuse une connexion distante et utilise uniquement des bases générées préfixées `stock_money_test_` pour ses écritures :

```sh
java --class-path "$MARIADB_DRIVER_JAR" src/test/migration/StockMoneyMigrationTest.java
```

`MARIADB_DRIVER_JAR` désigne le chemin du JAR MariaDB Connector/J résolu par Gradle.

Références : [DECIMAL dans MariaDB](https://mariadb.com/docs/server/reference/data-types/numeric-data-types/decimal), [ALTER TABLE](https://mariadb.com/docs/server/reference/sql-statements/data-definition/alter/alter-table), [blocs anonymes MariaDB](https://mariadb.com/docs/server/reference/sql-statements/programmatic-compound-statements/using-compound-statements-outside-of-stored-programs).
