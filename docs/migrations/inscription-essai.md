# Inscriptions publiques et essai configurable

Cette migration ajoute deux colonnes facultatives à `boutique` : `essai_debut`
et `essai_fin`, dates UTC en `DATETIME(6)`. Elle crée aussi les tables vides
`inscription_boutique` et `parametres_plateforme`. Les treize anciennes tables,
toutes leurs anciennes colonnes et leurs lignes sont conservées, y compris les
comptes désactivés, mots de passe, coordonnées et demandes de récupération.
Les migrations précédentes, jusqu'à la réinitialisation du mot de passe,
doivent déjà être terminées et conformes.

## Vérifier sans modifier

```sh
./gradlew inscriptionEssaiCheck
```

Le contrôle ne crée aucune colonne, demande, boutique ou ligne de paramètres.
Il refuse les types, tailles, valeurs, contraintes, moteurs ou collations
incompatibles sans réparation. Les dates MariaDB sont vérifiées dans
`information_schema.columns.datetime_precision` : le pilote JDBC ne renseigne
pas `DECIMAL_DIGITS` pour `DATETIME(6)`.

## Appliquer après sauvegarde

Arrêter l'application et éviter les écritures concurrentes. Sauvegarder avec
`./scripts/backup-stock-local.sh`, vérifier l'empreinte et restaurer une copie
dans une base de test distincte. Puis désigner le répertoire privé contenant
`stock_pro.sql` et `SHA256SUMS` :

```sh
INSCRIPTION_ESSAI_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde \
  ./gradlew inscriptionEssaiMigrate
```

L'application sur MariaDB exige la cible locale `localhost:3306/stock_pro`,
une sauvegarde dont l'empreinte SHA-256 correspond et un verrou nommé exclusif.
Toutes les vérifications des composants déjà présents précèdent les DDL.
L'ajout des deux dates utilise une seule instruction quand elles sont absentes.
Les tables utilisent InnoDB et `utf8mb4_unicode_ci` ; leurs colonnes sont
obligatoires, sans défaut ni auto-incrément. La demande d'inscription n'a pas de
clé étrangère, car le compte et la boutique n'existent pas encore.

Les boutiques historiques reçoivent deux dates `NULL` : leur accès existant
reste sans limite d'essai. Les demandes d'inscription et la ligne de paramètres
restent vides ; la migration n'invente aucun utilisateur ni essai et ne crée
aucun abonnement payant. Sans ligne de paramètres, `stock.trial.days` fournit
30 jours par défaut. Une durée enregistrée par l'administration de plateforme
concerne uniquement les futures créations ; les fins d'essai déjà persistées
restent identiques. L'accès offert reste indépendant de la suspension manuelle.

Une relance peut compléter un composant absent après une interruption si tous
les composants présents sont conformes. Une seule colonne de date peut être
complétée uniquement si elle ne contient aucune date. Des dates isolées, des
dates inversées ou une table partielle sont refusées sans déduction ni nettoyage.
La migration conserve les essais existants, même expirés, les demandes en
attente et la durée configurée. Les anciennes données sont comparées avant et
après l'application. Redémarrer ensuite avec `ddl-auto=validate`.

## Tests

```sh
./gradlew test --tests '*InscriptionEssaiMigrationTests'
./gradlew inscriptionEssaiMariaDbTest
```

Les scénarios H2 et MariaDB couvrent la lecture seule, les prérequis, la
sauvegarde, les contraintes, les dates incohérentes, la reprise de composants
conformes, l'idempotence et le verrou. MariaDB utilise uniquement des bases
temporaires contrôlées puis supprimées. Aucun email réel n'est envoyé et aucune
boutique réelle n'est créée pendant ces vérifications.

Pour une instance MariaDB privée de test sur un port isolé, la tâche accepte
`INSCRIPTION_ESSAI_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/`. Cette URL doit
viser un serveur local sans catalogue, sur un port de 1024 à 65535 ; les schémas
utilisés restent exclusivement des schémas temporaires au préfixe contrôlé.
Cette option de test n'assouplit pas la cible ni la sauvegarde de la migration.
