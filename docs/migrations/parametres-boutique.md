# Paramètres commerciaux des boutiques

Cette migration ajoute trois champs facultatifs dans `boutique` :

- `adresse VARCHAR(500) NULL` ;
- `telephone VARCHAR(50) NULL` ;
- `email VARCHAR(254) NULL`.

Elle exige un schéma conforme aux migrations des [boutiques](boutiques.md), des
[accès plateforme](plateforme.md) et des [accès utilisateurs](acces-utilisateurs.md).
Les noms, identifiants, dates, états actif/offert, utilisateurs, mots de passe,
rôles, droits plateforme et données métier restent identiques. Aucune coordonnée
n'est inventée, copiée d'un utilisateur ou déduite d'un fournisseur.

Le nom commercial utilise le champ `boutique.nom` existant. L'email commercial
est distinct de l'email de connexion ; il n'est pas unique entre boutiques. La
modification des paramètres est réservée au gérant de sa propre boutique. Elle
ne permet pas de changer l'état de la boutique, son accès offert ou les droits
des comptes.

La page **Paramètres de la boutique** (`/boutique/parametres`) permet de modifier
ces quatre champs. Le nom est obligatoire ; laisser une coordonnée vide l'efface.
Les factures de vente, d'achat et les relevés cumulés client/fournisseur affichent
les coordonnées courantes, y compris lors de la réimpression d'un document
existant. Les champs vides sont masqués et les valeurs sont échappées en HTML.

## Préparation

1. Arrêter toutes les instances de l'application, y compris `bootRun` et DevTools.
   Éviter toute modification du schéma ou des données pendant l'opération.
2. Créer une sauvegarde SQL complète :

```sh
./scripts/backup-stock-local.sh
```

3. Contrôler la base sans démarrer Spring ni modifier les données :

```sh
./gradlew parametresBoutiqueCheck
```

Le contrôle JDBC utilise `DB_URL`, `DB_USERNAME` et `DB_PASSWORD`. Il reprend en
lecture seule les contrôles des migrations précédentes, puis vérifie les trois
champs s'ils existent déjà. Il publie uniquement des états et des compteurs,
sans emails, adresses, téléphones, empreintes de mot de passe ou erreurs JDBC
brutes.

## Application

Définir `PARAMETRES_BOUTIQUE_BACKUP_DIR` avec le répertoire contenant
`stock_pro.sql` et `SHA256SUMS` :

```sh
PARAMETRES_BOUTIQUE_BACKUP_DIR='/chemin/vers/le/repertoire-de-la-sauvegarde' ./gradlew parametresBoutiqueMigrate
```

Le CLI MariaDB exige la cible locale `localhost` ou `127.0.0.1`, port 3306,
base `stock_pro`, puis vérifie l'empreinte SHA-256 de la sauvegarde avant toute
écriture. Une autre cible nécessite une procédure de sauvegarde dédiée. Un verrou
SQL empêche deux migrations des paramètres boutique simultanées.

L'application des trois colonnes utilise une seule instruction `ALTER TABLE`.
Aucun `UPDATE` n'est exécuté. Les valeurs anciennes et les lignes des douze
tables sont comparées avant/après ; les coordonnées des boutiques existantes
restent `NULL`. Une relance sur un schéma conforme conserve les coordonnées
saisies, y compris celles des boutiques suspendues ou offertes.

## Arrêts et reprise

L'outil refuse des migrations antérieures incomplètes ou incohérentes, un schéma
partiellement enrichi, une taille ou un type incorrect, un champ obligatoire,
ou un défaut autre que `NULL`. Il ne complète pas un ajout partiel et ne convertit
ni ne tronque les coordonnées existantes.

Les DDL MariaDB valident implicitement. Après une erreur de connexion, garder
l'application arrêtée et vérifier le schéma et les données : la modification
peut déjà être effective. Une relance exige trois colonnes conformes ; si l'état
ne l'est pas, examiner la sauvegarde avant une réparation manuelle ou une
restauration.

Après réussite :

```sh
./gradlew parametresBoutiqueCheck
./gradlew test bootJar
```

Redémarrer avec `DB_DDL_AUTO=validate`. Contrôler la connexion habituelle, les
droits et états conservés, l'accès du gérant aux paramètres de sa boutique et
l'en-tête de ses factures. Les champs facultatifs peuvent être laissés vides.

## Vérification dans des bases temporaires

```sh
./gradlew test --tests org.example.stock.migration.ParametresBoutiqueMigrationTests
./gradlew parametresBoutiqueMariaDbTest
```

Les tests H2 couvrent la lecture seule, la conservation de toutes les anciennes
colonnes des douze tables, les coordonnées nulles par défaut, les relances avec
coordonnées existantes, la création de nouvelles boutiques sans coordonnées,
les états suspendu/offert et les comptes désactivés. Ils refusent les schémas
partiels, les tailles, types, défauts et nullabilités incorrects, ainsi que les
relations et droits antérieurs incohérents.

Le parcours MariaDB crée uniquement des bases locales
`stock_parametres_boutique_test_*`, puis les supprime. Ses 17 scénarios ne lisent
ni ne modifient `stock_pro` ; ils vérifient aussi la conservation des coordonnées
existantes et leur défaut pour les nouvelles boutiques.

## Validation du 12 octobre 2026

- `./gradlew test bootJar parametresBoutiqueMariaDbTest` : 673 tests réussis,
  sans échec ni test ignoré, et 17 scénarios MariaDB réussis. Les bases temporaires
  sont supprimées.
- Les tests HTTP contrôlent les autorisations persistantes, le périmètre de
  boutique, CSRF, les paramètres internes et les en-têtes injectés, la validation
  avec conservation de la saisie et l'identité échappée sur les quatre documents.
- Les tests à transactions distinctes contrôlent les droits retirés, un compte
  transféré, une boutique suspendue et la conservation des coordonnées lors
  d'un changement des accès plateforme.
- Sauvegarde locale restaurée et comparée : 12 tables, 50 lignes. Le schéma SQL
  neuf et les coordonnées nulles par défaut sont également vérifiés.
- Migration appliquée après arrêt de l'application : toutes les anciennes
  colonnes et lignes restent identiques, notamment les noms, secrets et états
  actifs/désactivés. Les trois nouvelles coordonnées restent vides.
- Application démarrée avec validation du schéma. Connexion réelle et formulaire
  enregistrés avec les valeurs déjà présentes ; aucune identité fictive ajoutée.
  Les dix affichages (paramètres et quatre documents sur ordinateur et mobile)
  restent sans débordement de page. Les quatre impressions A4 ont été vérifiées.
- Une comparaison après le parcours réel confirme de nouveau la conservation
  des données et des accès existants.
