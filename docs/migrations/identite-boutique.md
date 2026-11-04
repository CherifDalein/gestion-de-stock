# Identité de la boutique : logo et deuxième téléphone

Le nom, l'adresse, le téléphone principal et l'email commerciaux restent dans
les paramètres de la boutique. Cette évolution ajoute un deuxième téléphone
facultatif et un logo facultatif. Chaque boutique garde ses propres coordonnées.
Le gérant peut importer, remplacer ou retirer son logo ; les caissiers peuvent
le lire pour afficher les factures, sans pouvoir le modifier.

Les coordonnées et le logo courants apparaissent sur les factures, y compris
leurs réimpressions. Un champ vide est omis. Les fichiers PNG ou JPEG sont
contrôlés puis convertis en PNG, avec proportions et transparence conservées,
sans métadonnées, avec un côté de 512 pixels et une taille de 384 Kio au maximum.
Les dimensions sont réduites si nécessaire, pour permettre aussi la sauvegarde
et la restauration hexadécimale sur le serveur local limité à un Mio par paquet.
Aucun nom de fichier
fourni par l'utilisateur n'est utilisé comme chemin. Les images SVG et les
fichiers corrompus sont refusés. La lecture du logo utilise un accès authentifié
à la boutique courante, avec cache privé désactivé.

## Schéma et conservation

La migration ajoute seulement :

- `boutique.telephone_secondaire VARCHAR(50) NULL`, sans valeur par défaut ;
- `logo_boutique`, dont la clé primaire `boutique_id` est une clé étrangère
  vers `boutique.id`, sans cascade, avec `contenu LONGBLOB NOT NULL` et
  `revision VARCHAR(36) NOT NULL`.

Les dix-neuf anciennes tables et leurs colonnes sont comparées avant/après.
Les anciens noms, adresses, téléphones, emails, droits, abonnements, stocks,
soldes et historiques sont conservés. Les boutiques existantes ne reçoivent
aucun numéro ou logo inventé. Une relance conserve aussi les téléphones et
les octets des logos déjà enregistrés.

Le [SQL de création](../database-schema.sql) contient les vingt tables actuelles
pour une installation neuve. Il ne remplace pas la migration d'une base existante.

## Appliquer sur la base locale

La migration de trésorerie et toutes les migrations précédentes doivent être
terminées. Arrêter l'application et DevTools, puis sauvegarder la base complète :

```sh
./scripts/backup-stock-local.sh
./gradlew identiteBoutiqueCheck
IDENTITE_BOUTIQUE_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew identiteBoutiqueMigrate
./gradlew identiteBoutiqueCheck
```

`--check` est en lecture seule. `--apply` vérifie la cible MariaDB locale
`localhost:3306/stock_pro`, le dump complet et son SHA-256, puis prend un verrou
nommé pour refuser une seconde migration simultanée. Le serveur web garde
Hibernate en mode `validate`.

MariaDB valide ses DDL avant la fin d'un script. En cas d'interruption, garder
l'application arrêtée, contrôler le schéma puis relancer la même migration avec
la sauvegarde vérifiée. Elle ajoute seulement le champ ou la table manquants.
Un schéma, une clé étrangère, un type ou un logo existant incohérent est refusé,
sans réparation automatique et sans remplacement des données.

Le logo est stocké dans la base : les sauvegardes SQL complètes avec
`--hex-blob`, comme le script du projet, couvrent aussi les logos.

## Vérifications

```sh
./gradlew test --tests '*IdentiteBoutiqueMigrationTests'
./gradlew identiteBoutiqueMariaDbTest
```

Les mêmes scénarios H2 et MariaDB couvrent la base historique ou vide, le SQL
neuf, les ajouts partiels, la relance avec logo binaire et téléphone existants,
la nouvelle boutique et les schémas invalides. Les photographies JDBC comparent
les octets des BLOB et les vraies clés primaires. Les tests H2 vérifient aussi
la sauvegarde, la cible, le refus d'une transaction ouverte et la compatibilité
des photographies historiques. MariaDB ajoute le refus d'une migration concurrente.

Les tests MariaDB créent et suppriment uniquement leurs schémas locaux
`stock_identite_test_*`, jamais `stock_pro`. La variable
`IDENTITE_BOUTIQUE_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner
un serveur local isolé.
