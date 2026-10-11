# Accès individuels des utilisateurs

Cette migration ajoute `utilisateur.actif BOOLEAN NOT NULL DEFAULT TRUE`. Elle exige un schéma déjà conforme aux migrations des [boutiques](boutiques.md) et des [accès plateforme](plateforme.md).

Les comptes existants restent actifs au premier ajout de la colonne. Leurs identifiants, noms, emails, empreintes de mots de passe, dates, rôles, droits plateforme et appartenances aux boutiques sont conservés. La désactivation ultérieure d'un compte bloque son accès sans supprimer ses historiques ; elle ne change pas l'état de sa boutique. Une relance conserve tous les comptes déjà désactivés et ne réinitialise aucun droit plateforme.

## Préparation

1. Arrêter toutes les instances de l'application, y compris `bootRun` et DevTools. Éviter toute modification des comptes ou du schéma pendant la migration.
2. Créer une sauvegarde SQL complète :

```sh
./scripts/backup-stock-local.sh
```

3. Contrôler sans modifier la base ni démarrer Spring :

```sh
./gradlew accesUtilisateurCheck
```

Le contrôle JDBC utilise `DB_URL`, `DB_USERNAME` et `DB_PASSWORD`. Il reprend les vérifications complètes des boutiques et de la plateforme : références obligatoires, relations entre boutiques, catégories, types et valeurs des états, cohérence des droits plateforme. Il vérifie ensuite la colonne individuelle si elle existe déjà.

Seuls des états et compteurs sont affichés. Les emails, mots de passe, empreintes et messages JDBC bruts ne sont pas publiés. Aucun email de bootstrap plateforme n'est utilisé par cet outil : il conserve strictement les droits déjà attribués.

## Application

Définir `ACCES_UTILISATEUR_BACKUP_DIR` avec le répertoire de sauvegarde contenant `stock_pro.sql` et `SHA256SUMS`, puis appliquer :

```sh
ACCES_UTILISATEUR_BACKUP_DIR='/chemin/vers/le/repertoire-de-la-sauvegarde' ./gradlew accesUtilisateurMigrate
```

Le CLI MariaDB exige la cible locale `localhost` ou `127.0.0.1`, port 3306, base `stock_pro`, et vérifie l'empreinte SHA-256 du dump avant toute écriture. Une autre cible nécessitera une procédure de sauvegarde dédiée. Un verrou SQL empêche deux migrations d'accès utilisateurs simultanées.

La seule écriture de migration est l'ajout de la colonne avec son défaut `TRUE`. Les anciennes colonnes des comptes et les compteurs des douze tables sont comparés avant/après. Une colonne déjà conforme est uniquement contrôlée, sans aucun `UPDATE` et sans réactivation des comptes désactivés.

## Arrêts et reprise

L'outil refuse un schéma plateforme absent, partiel ou incohérent. Il refuse aussi une colonne `utilisateur.actif` facultative, de type incorrect, sans défaut `TRUE`, ou contenant des valeurs nulles ou autres que zéro/un. Il n'improvise aucune réparation de compte, changement de rôle ou réattribution de droit plateforme.

Les DDL MariaDB valident implicitement. Une erreur de connexion peut donc survenir après l'ajout effectif de la colonne : garder l'application arrêtée, contrôler son état et les données, et restaurer la sauvegarde si nécessaire. Une relance n'est autorisée que si le schéma et les valeurs sont conformes ; elle ne réactive aucun compte.

Après réussite :

```sh
./gradlew accesUtilisateurCheck
./gradlew test bootJar
```

Redémarrer avec `DB_DDL_AUTO=validate` et renouveler les sessions de vérification. Vérifier que les comptes existants se connectent avec leurs mots de passe habituels et que les droits plateforme sont conservés. L'accès individuel et l'état de la boutique sont distincts : activer un utilisateur ne réactive pas sa boutique suspendue.

## Vérification dans des bases temporaires

```sh
./gradlew test --tests org.example.stock.migration.AccesUtilisateurMigrationTests
./gradlew accesUtilisateurMariaDbTest
```

Les tests H2 vérifient la lecture seule, la conservation de toutes les anciennes colonnes des douze tables, les droits plateforme, le défaut des nouveaux comptes, la base vide, la relance sans réactivation et le refus des schémas ou valeurs invalides. Le parcours MariaDB crée uniquement des bases locales `stock_acces_utilisateur_test_*`, puis les supprime ; il ne lit ni ne modifie `stock_pro`.

## Validation du 11 octobre 2026

- `./gradlew test bootJar` : 600 tests réussis, sans échec ni test ignoré. Les parcours HTTP utilisent aussi une véritable session de connexion pour vérifier son blocage après désactivation.
- `./gradlew accesUtilisateurMariaDbTest` : 11 scénarios réussis et bases temporaires supprimées.
- Les mutations verrouillent l'acteur et la cible dans l'ordre des identifiants, puis recontrôlent les droits persistants. Le test de deux opérateurs qui se ciblent simultanément confirme qu'ils ne peuvent pas se désactiver mutuellement.
- Sauvegarde locale restaurée dans une base temporaire et comparée : 12 tables, 50 lignes. Le schéma SQL neuf et ses valeurs par défaut sont également vérifiés.
- Migration locale appliquée après arrêt de l'application. Toutes les anciennes colonnes et lignes sont identiques avant/après ; les trois comptes restent actifs avec leurs secrets, rôles et droits conservés.
- Application redémarrée avec validation du schéma. Connexion réelle plateforme, détail de boutique et liste des utilisateurs vérifiés sur ordinateur (1440 px) et mobile (390 px), sans débordement horizontal de page ni secret rendu. Aucun compte réel n'a été désactivé pour ces vérifications.
