# Connexion à la base de données

Le démarrage local utilise MariaDB sur `localhost:3306`, base `stock_pro`, avec les identifiants locaux par défaut (`root`, mot de passe vide). Le pilote JDBC et le dialecte Hibernate sont choisis automatiquement depuis la connexion. Le pilote MariaDB Connector/J 3.5.8 est géré par Spring Boot 3.5.16.

```sh
./gradlew bootRun
```

L'application est accessible sur <http://localhost:8080>. La base doit être démarrée. En local, la création de la base si elle est absente et la mise à jour du schéma JPA sont activées.

## Paramètres de connexion

| Variable | Valeur locale par défaut | Utilisation |
| --- | --- | --- |
| `DB_URL` | `jdbc:mariadb://localhost:3306/stock_pro?timezone=UTC&sslMode=disable&createDatabaseIfNotExist=true` | Adresse JDBC complète |
| `DB_USERNAME` | `root` | Utilisateur de base |
| `DB_PASSWORD` | vide | Mot de passe de base |
| `DB_DDL_AUTO` | `update` | Mode de gestion du schéma Hibernate |

Les valeurs locales par défaut conviennent au poste existant. Pour Railway ou un autre hébergement, renseigner les variables de connexion du serveur concerné. Les anciennes coordonnées Railway ne sont plus présentes dans la configuration courante. Les propriétés Spring standard `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` et `SPRING_JPA_HIBERNATE_DDL_AUTO` peuvent également être utilisées.

Les pilotes MySQL et MariaDB restent disponibles. Une URL `jdbc:mysql://...` choisit MySQL Connector/J ; une URL `jdbc:mariadb://...` choisit MariaDB Connector/J. Aucun `driver-class-name` ni dialecte n'est imposé dans le fichier commun. L'option historique `timezone=UTC` reste compatible avec MariaDB Connector/J 3.5 ; les options d'une URL MySQL doivent correspondre au pilote MySQL.

Ne pas conserver de mot de passe distant dans ce fichier ni dans les commandes suivies par Git. Les anciennes valeurs restent dans l'historique Git ; supprimer la configuration courante ne révoque pas ces identifiants.

## Tests et vérifications

```sh
./gradlew test
```

Les tests d'intégration utilisent le profil `test` et H2 en mémoire. Ce profil remplace l'URL et le pilote de connexion, même si `DB_URL` est défini. Les tests de configuration vérifient aussi la sélection des pilotes MariaDB/MySQL sans ouvrir de connexion.

La vérification locale du 4 octobre 2026 a identifié MariaDB 10.4.28 avec MariaDB Connector/J 3.3.3. Le schéma et les nombres de lignes ont été lus sans soumission de paiement. Cette vérification ne certifie pas un déploiement distant ni tous les scénarios métier sur MariaDB.

Après la mise à jour de Spring Boot, un démarrage avec validation du schéma et connexions en lecture seule a confirmé la compatibilité avec MariaDB 10.4.28 et Connector/J 3.5.8. La page de connexion répond HTTP 200 avec un jeton CSRF. Aucun changement de données ni de schéma n'a été demandé pour cette vérification.

Références : [MariaDB Connector/J, URL et pilote](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j), [versions gérées par Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html).

## Maintenance locale du 5 octobre 2026

L'anomalie `mysql.proc` (erreur 1558, tables système 10.1.8 sur serveur 10.4.28) a été corrigée avec le binaire XAMPP `mysql_upgrade`, limité aux tables système. Une sauvegarde SQL de `stock_pro` et une copie à froid de tout le serveur ont précédé la réparation. Les dix empreintes de tables et les deux comptes applicatifs restent identiques ; la restauration SQL dans une base isolée et le parcours de connexion web ont réussi après redémarrage. Le serveur reste en version 10.4.28.

Voir [sauvegardes et procédure de maintenance](sauvegardes-et-mariadb.md) pour le script d'export privé, les limites de l'export et les contrôles effectués.
