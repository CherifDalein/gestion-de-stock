# Stockage des liens de réinitialisation

Cette migration ajoute seulement `reinitialisation_mot_de_passe`, vide au premier
passage. Les douze tables existantes, les comptes, mots de passe, coordonnées,
droits, statuts et données métier sont conservés. Les migrations précédentes,
dont les paramètres de boutique, doivent déjà être terminées.

## Vérifier sans modifier

```sh
./gradlew reinitialisationMdpCheck
```

Le contrôle est en lecture seule. Il ne crée aucun lien ni table et n'active
aucun compte. Un schéma partiel ou incohérent est refusé sans réparation.

## Ajouter la table

Arrêter l'application et éviter les écritures concurrentes. Sauvegarder avec
`./scripts/backup-stock-local.sh`, vérifier l'empreinte et effectuer une
restauration de test dans une base distincte. Puis désigner le répertoire privé
contenant `stock_pro.sql` et `SHA256SUMS` :

```sh
REINITIALISATION_MDP_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde \
  ./gradlew reinitialisationMdpMigrate
```

Sur MariaDB, l'application de la migration exige la cible locale `stock_pro`,
une sauvegarde vérifiée et un verrou exclusif de migration. Une seule DDL crée
les cinq colonnes, la clé primaire, la clé étrangère vers `utilisateur` et
l'unicité de l'empreinte du jeton. Les dates sont des `DATETIME(6)` UTC ; le
contrôle de précision MariaDB utilise `information_schema`, car le pilote JDBC
ne renseigne pas `DECIMAL_DIGITS` pour ces colonnes.

La migration compare toutes les anciennes lignes avant et après la création.
Une relance sur un schéma conforme conserve aussi les demandes déjà présentes,
y compris celles qui sont expirées ; aucun nettoyage ou changement de statut
n'est effectué. La clé étrangère n'autorise aucune suppression en cascade.

Après migration, redémarrer avec `ddl-auto=validate` et la configuration SMTP
décrite dans [le parcours du compte](../mon-compte-et-recuperation.md).

## Tests

```sh
./gradlew test --tests '*ReinitialisationMdpMigrationTests'
./gradlew reinitialisationMdpMariaDbTest
```

Les tests H2 et les 32 scénarios MariaDB vérifient notamment le contrôle sans
écriture, l'idempotence, les contraintes, les données incohérentes et le verrou.
MariaDB utilise uniquement des bases temporaires créées pour ces scénarios,
puis supprimées. Aucun lien réel ni email n'est généré par la migration.
