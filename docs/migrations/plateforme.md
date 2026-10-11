# Administration de la plateforme et états des boutiques

Cette migration prolonge la [séparation des boutiques](boutiques.md), qui doit déjà être terminée. Elle ajoute trois champs sans modifier les comptes, rôles, mots de passe, appartenances, quantités, montants ni historiques :

| Table | Colonne | Défaut | Usage |
| --- | --- | --- | --- |
| `boutique` | `actif` | `TRUE` | Boutique ouverte ou suspendue |
| `boutique` | `acces_offert` | `FALSE` | Mode commercial offert |
| `utilisateur` | `administrateur_plateforme` | `FALSE` | Accès à l'administration de la plateforme |

Les trois colonnes sont booléennes et obligatoires. Les boutiques existantes restent actives et aucun compte ADMIN n'obtient automatiquement le droit plateforme. ADMIN demeure le rôle de gérant de boutique ; CAISSIER demeure celui de caissier. Le droit plateforme est un indicateur indépendant, réservé à un ADMIN désigné explicitement. Son compte reste rattaché à sa boutique.

Une boutique suspendue conserve ses données et son mode commercial. L'accès offert ne réactive pas une boutique suspendue et n'accorde aucun droit utilisateur supplémentaire. Ce lot n'implémente pas encore les abonnements ni les paiements.

## Préparation et contrôle

1. Arrêter toutes les instances de l'application, y compris `bootRun` et DevTools. Éviter toute écriture et tout changement de comptes pendant la migration.
2. Créer et conserver une sauvegarde SQL complète :

```sh
./scripts/backup-stock-local.sh
```

3. Si un compte existant doit administrer la plateforme, définir `PLATEFORME_ADMIN_EMAIL` avec son adresse exacte, déjà normalisée en minuscules et sans espaces autour. Cette variable sélectionne le compte sans modifier son mot de passe ni son rôle. Si elle est absente, seuls les champs de schéma seront ajoutés.
4. Contrôler :

```sh
./gradlew plateformeCheck
```

Le contrôle JDBC utilise `DB_URL`, `DB_USERNAME` et `DB_PASSWORD`, sans démarrer Spring ni écrire dans la base. Il reprend les vérifications des boutiques et refuse les relations incohérentes. Il valide aussi les nouvelles colonnes déjà présentes et le compte sélectionné. Seuls des états et compteurs sont publiés : aucun email, mot de passe, empreinte de compte ni message JDBC brut.

## Application

Définir `PLATEFORME_BACKUP_DIR` avec le répertoire créé par le script, contenant `stock_pro.sql` et `SHA256SUMS`, puis appliquer :

```sh
PLATEFORME_BACKUP_DIR='/chemin/vers/le/repertoire-de-la-sauvegarde' ./gradlew plateformeMigrate
```

Le CLI MariaDB exige la cible locale `localhost` ou `127.0.0.1`, port 3306, base `stock_pro`. Il vérifie l'empreinte SHA-256 du dump avant les écritures ; une autre cible nécessitera une procédure dédiée. Un verrou SQL empêche deux migrations plateforme simultanées.

Les colonnes reçoivent leurs défauts. Si `PLATEFORME_ADMIN_EMAIL` est défini, seul le compte correspondant passe de `administrateur_plateforme=FALSE` à `TRUE`. Le compte doit exister exactement, avoir le rôle ADMIN et une boutique valide. Une adresse absente, non normalisée, différente selon la collation SQL, ambiguë ou appartenant à un CAISSIER arrête l'opération avant toute modification.

Une relance sur un schéma conforme conserve les boutiques actives ou suspendues, leur accès offert et les droits plateforme existants. Elle peut attribuer le droit à un autre ADMIN explicitement sélectionné ; elle ne retire ni ne réinitialise les droits déjà accordés. Définir l'email aussi pour `plateformeCheck` afin de vérifier cette attribution avant l'application.

## Arrêts et reprise

Un schéma contenant seulement une partie des trois colonnes est refusé. Des colonnes facultatives, de type ou de défaut incorrect, des valeurs autres que zéro/un, ou un droit plateforme enregistré sur un compte sans rôle ADMIN sont également refusés. Aucune correction des données ou des droits n'est improvisée.

Les DDL MariaDB valident implicitement. Une interruption peut laisser les colonnes partiellement ajoutées ; l'outil refusera alors une relance. Garder l'application arrêtée, examiner le schéma et restaurer la sauvegarde si nécessaire. L'attribution du droit et la vérification de conservation des comptes s'effectuent dans une transaction après les DDL.

Après réussite, relancer `./gradlew plateformeCheck`, puis les tests et le build. Redémarrer avec `DB_DDL_AUTO=validate` et renouveler les sessions : les anciens comptes de gérant et de caissier conservent leurs mots de passe et leur boutique. Vérifier l'accès plateforme du compte désigné et le refus de cet accès pour les autres gérants et caissiers.

## Vérification

```sh
./gradlew test --tests org.example.stock.migration.PlateformeMigrationTests
```

Les tests H2 couvrent la lecture seule, les défauts, la conservation des données et comptes, l'attribution ciblée, la relance, les adresses ambiguës ou invalides, les schémas partiels et les droits incohérents. Le parcours MariaDB utilise uniquement des bases `stock_plateforme_test_*` locales, supprimées après chaque scénario ; il ne lit ni ne modifie `stock_pro` :

```sh
./gradlew plateformeMariaDbTest
```

## Validation locale du 11 octobre 2026

La suite Java complète compte **540 tests réussis**, sans échec ni test ignoré. Elle vérifie notamment la création atomique boutique/gérant, le refus des emails existants sans transfert de compte, le rollback d'une collision SQL, les accès plateforme refusés aux autres gérants et caissiers, les droits de session révoqués, les champs de formulaire falsifiés et le CSRF. La suspension bloque aussi une session métier déjà ouverte ; l'opérateur conserve son accès plateforme pour réactiver une boutique, y compris la sienne.

Les **10 scénarios MariaDB** réussissent avec suppression des bases temporaires. Le schéma SQL neuf a également été importé dans une base temporaire et ses valeurs par défaut ont été vérifiées.

Avant application locale, la sauvegarde de `stock_pro` a été restaurée et comparée à la source. La comparaison des anciennes colonnes des **12 tables et 50 lignes**, avant et après migration, confirme la conservation des données. Les **trois comptes existants** conservent leurs mots de passe, rôles et boutiques. Le droit plateforme est attribué au seul compte désigné ; la boutique existante reste active.

Le démarrage avec `ddl-auto=validate` et la connexion réelle de l'opérateur réussissent. Les écrans de liste, création et détail sont vérifiés dans Chrome à 1440 et 390 pixels : aucun débordement de page, tableaux défilants sur écran étroit et champ de mot de passe vide. Les sessions de vérification ont été fermées ; aucun compte ou boutique fictif n'a été ajouté à la base locale.
