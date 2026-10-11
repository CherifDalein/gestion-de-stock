# Rattachement des données existantes aux boutiques

Cette migration transforme une base Stock Pro existante en conservant les identifiants, les comptes, les empreintes de mots de passe, les prix, les quantités, les paiements et les historiques. Les neuf tables racines sont rattachées à une **Boutique principale** : utilisateurs, catégories, clients, fournisseurs, produits, achats, ventes, mouvements de caisse et jetons de création. Les lignes d'achat et de vente appartiennent à la boutique de leur document ; elles ne reçoivent pas de colonne supplémentaire.

## Préparation

1. Arrêter toutes les instances de l'application, y compris `bootRun` et DevTools. Aucun utilisateur ne doit écrire pendant la migration.
2. Exporter une sauvegarde SQL complète avec le script existant :

```sh
./scripts/backup-stock-local.sh
```

Le script crée un répertoire privé contenant `stock_pro.sql` et `SHA256SUMS`. Garder cette sauvegarde pour pouvoir restaurer le schéma et les données. L'empreinte vérifie l'intégrité du fichier ; elle ne remplace pas une vérification de restauration.

3. Contrôler sans modifier la base ni démarrer Spring :

```sh
./gradlew boutiquesCheck
```

L'outil utilise les paramètres `DB_URL`, `DB_USERNAME` et `DB_PASSWORD` de l'application. Il affiche uniquement des états et compteurs, sans emails, mots de passe, empreintes de comptes ni détail des exceptions JDBC. Les onze tables métier doivent déjà exister. Une base entièrement absente n'est pas créée par cette commande.

## Application

```sh
BOUTIQUES_BACKUP_DIR='/chemin/vers/le/repertoire-de-la-sauvegarde' ./gradlew boutiquesMigrate
```

Sur MariaDB, l'outil exige le dump et vérifie son empreinte SHA-256 avant les écritures. Le script sauvegardant uniquement la base locale, `--apply` exige MariaDB sur `localhost` ou `127.0.0.1`, port 3306, base `stock_pro` ; une autre cible nécessitera une procédure de sauvegarde dédiée. Un verrou SQL empêche deux migrations simultanées. Il crée la boutique principale, ajoute `boutique_id`, rattache les lignes existantes et rend les références obligatoires avec une clé étrangère vers `boutique.id`.

Les noms de catégories deviennent uniques **par boutique**, via `uk_categorie_boutique_nom(boutique_id, nom)`. Seuls les anciens index ou contraintes uniques portant exactement sur `categorie.nom` sont supprimés ; les clés primaires et les autres index sont conservés. Le contrôle des doublons utilise la collation SQL de la base.

Une base métier vide contenant déjà les onze tables reçoit une boutique principale et aucun compte inventé. Une base déjà migrée est uniquement contrôlée : les boutiques existantes ne sont jamais fusionnées ni réaffectées.

## Arrêts et reprise

L'outil refuse avant toute modification les tables manquantes, les relations orphelines, les parents obligatoires absents, les catégories en doublon et les schémas mélangeant l'ancienne et la nouvelle structure. Sur une base migrée, il refuse également les relations entre boutiques différentes, y compris les produits des lignes d'achat ou de vente, et les références de boutique sans contraintes obligatoires.

Les DDL MariaDB valident implicitement. Une interruption peut donc laisser une structure partielle même si les écritures de rattachement sont transactionnelles. **Ne pas forcer la relance ni supprimer des colonnes pour contourner les gardes** : garder l'application arrêtée, examiner la structure et restaurer la sauvegarde si nécessaire. L'outil refuse de terminer automatiquement une migration partielle.

Après réussite :

```sh
./gradlew boutiquesCheck
./gradlew test bootJar
```

Redémarrer l'application avec `DB_DDL_AUTO=validate` et renouveler les sessions. Vérifier la connexion des comptes existants, les quantités, les achats, les ventes, leurs factures et les totaux de caisse.

## Vérification dans des bases de test

```sh
./gradlew test --tests org.example.stock.migration.BoutiquesMigrationTests
./gradlew boutiquesMariaDbTest
```

Les tests H2 couvrent la lecture seule, la conservation de toutes les colonnes métier, la base vide, la relance, les noms identiques entre boutiques, les index conservés, les relations invalides et l'intégrité de la sauvegarde. Le parcours MariaDB crée uniquement des bases `stock_boutiques_test_*` sur le serveur local puis les supprime ; il ne lit ni ne modifie `stock_pro`. Il vérifie aussi les collisions selon la collation MariaDB.

## Validation locale du 11 octobre 2026

La suite Java compte **461 tests réussis**, sans échec ni test ignoré. Les parcours MariaDB ont validé **9 scénarios de migration** et **9 scénarios de création**, dont les doubles envois concurrents. Les tests avec deux boutiques couvrent les listes, relations, modifications, règlements, factures, utilisateurs et totaux de caisse, avec des connexions et des refus d'accès à l'autre boutique.

La sauvegarde de `stock_pro` a été restaurée dans une base temporaire, comparée à la base source, puis cette base temporaire a été supprimée. La migration locale a ensuite conservé les **48 lignes des 11 tables existantes** et créé une seule Boutique principale. Une comparaison de toutes les anciennes colonnes, ordonnée par clé primaire, confirme des valeurs identiques avant et après : comptes, empreintes de mots de passe et données métier inclus.

Le contrôle JDBC après migration et le démarrage Hibernate avec `ddl-auto=validate` réussissent. La page de connexion répond HTTP 200, contient son jeton CSRF et sert ses ressources locales. Les mots de passe des comptes locaux n'ont pas été réinitialisés pour ces vérifications ; les connexions automatisées et les opérations métier ont été testées dans les bases temporaires.
