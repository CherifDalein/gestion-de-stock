# Périodes d'abonnement et historique des accès

Cette migration ajoute `abonnement_debut` et `abonnement_fin` à `boutique`,
deux dates UTC facultatives en `DATETIME(6)`, puis crée la table vide
`historique_abonnement`. Les quinze tables précédentes et toutes leurs anciennes
colonnes sont conservées, notamment les essais déjà commencés, comptes,
coordonnées, demandes d'inscription et liens de récupération. Les migrations
précédentes, jusqu'à l'inscription publique et l'essai, doivent être terminées.

## Contrôle en lecture seule

```sh
./gradlew abonnementCheck
```

Le contrôle ne crée aucune date, abonnement ou ligne d'historique. Il refuse
toute incohérence des types, valeurs nulles, défauts, contraintes, index,
relations et données sans correction automatique. Sur MariaDB, la précision
des dates est lue dans `information_schema.columns.datetime_precision` ; le
pilote JDBC ne renseigne pas `DECIMAL_DIGITS` pour `DATETIME(6)`.

## Application après sauvegarde

Arrêter toutes les instances de l'application et empêcher les écritures
concurrentes. Sauvegarder avec `./scripts/backup-stock-local.sh`, vérifier
l'empreinte et restaurer le SQL dans une base distincte pour vérifier la copie.
Désigner ensuite le répertoire privé contenant `stock_pro.sql` et `SHA256SUMS` :

```sh
ABONNEMENT_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde \
  ./gradlew abonnementMigrate
```

L'application sur MariaDB exige la cible locale `localhost:3306/stock_pro`, une
sauvegarde dont l'empreinte SHA-256 correspond et un verrou nommé exclusif.
Toutes les vérifications précèdent les écritures de schéma. Les deux dates
absentes sont ajoutées dans un seul `ALTER TABLE`. La création du journal et de
ses index se fait dans un seul `CREATE TABLE` sur MariaDB. Le journal utilise
InnoDB et `utf8mb4_unicode_ci` ; seul son identifiant est auto-incrémenté. Ses
références vers boutique et acteur n'autorisent aucune suppression en cascade.

Les anciennes boutiques gardent leurs dates d'abonnement `NULL` et leurs états
existants. Aucun accès ne devient payant, aucun essai n'est prolongé et aucun
événement n'est inventé. La table d'historique commence vide. Les nouvelles
décisions de plateforme enregistrent ensuite leur auteur, action, motif
facultatif et, pour les prolongations ou activations, la période accordée.
Cette période doit correspondre exactement au nombre de jours de prolongation
ou à un mois calendaire ; l'ancienne échéance ne peut pas dépasser son début.

Une relance peut compléter un composant entièrement absent si les composants
présents sont conformes. Une seule date peut être complétée uniquement si elle
ne contient aucune valeur. Les dates isolées, inversées ou une table partielle
sont refusées sans déduction ni nettoyage. Une relance conserve les abonnements
expirés, essais et événements déjà enregistrés. Le jeton UUID unique protège
contre un renvoi du même formulaire ; l'index `(boutique_id, cree_le, id)` permet
la consultation paginée de chaque historique. Les données sont comparées avant
et après l'application. Redémarrer ensuite avec `ddl-auto=validate`.

## Tests

```sh
./gradlew test --tests '*AbonnementMigrationTests'
./gradlew abonnementMariaDbTest
./gradlew abonnementServiceMariaDbTest
```

Les scénarios H2 et MariaDB couvrent la lecture seule, la sauvegarde, les
prérequis, les contraintes, les dates et actions incohérentes, les composants
conformes après interruption, la relance et le verrou. Chaque base MariaDB
temporaire porte un préfixe vérifié avant sa création et sa suppression. Aucun
compte ni événement réel n'est créé pendant ces vérifications.

Le dernier contrôle utilise le véritable service d’abonnement et des
transactions concurrentes : renouvellements sur une même boutique ou des
boutiques différentes, demandes répétées, mois bissextiles, essais et accès
offerts. Son contexte de test est isolé de l’application habituelle et sa
base MariaDB temporaire est supprimée à la fin.

Une instance MariaDB privée sur un port isolé peut être sélectionnée avec
`ABONNEMENT_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/`. Cette URL de test vise
uniquement un serveur local sans catalogue, sur un port de 1024 à 65535. Les
schémas utilisés restent temporaires ; cette option n'assouplit ni la cible ni
la sauvegarde requise par la migration réelle.
