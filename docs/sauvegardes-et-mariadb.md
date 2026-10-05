# Sauvegardes locales et maintenance MariaDB

## Sauvegarder stock_pro

Le script utilise le `mysqldump` de XAMPP et la connexion locale TCP `127.0.0.1:3306`, utilisateur `root`. Il ne lit pas les variables JDBC de l'application et ne cible pas une base distante.

Arrêter l'application et éviter toute modification du schéma pendant l'export :

```sh
./scripts/backup-stock-local.sh
```

Chaque exécution crée un dossier unique dans `.local-backups/`, contenant `stock_pro.sql` et `SHA256SUMS`. Le dossier est privé (`0700`), les fichiers sont privés (`0600`) et les sauvegardes sont exclues de Git. Une erreur de `mysqldump`, un export vide ou un échec de calcul de l'empreinte provoque un échec du script ; les fichiers incomplets sont supprimés.

Un autre répertoire peut être fourni en argument. Il doit rester hors du dépôt suivi :

```sh
./scripts/backup-stock-local.sh /chemin/prive/sauvegardes
```

Avec un mot de passe MariaDB, utiliser `MYSQL_DEFAULTS_FILE` pour désigner un fichier d'options absolu, appartenant à l'utilisateur et sans accès groupe/autres (`chmod 600`). Ce fichier peut contenir la section `[client]` et sa propriété `password`. Ne pas placer le mot de passe dans les arguments, les logs ou Git.

L'export comprend le schéma des tables, les données et les déclencheurs. Il utilise `--single-transaction`, adapté aux tables InnoDB de l'application. Les routines et événements sont explicitement omis pour permettre l'export même avant réparation de `mysql.proc`. Aucun de ces objets n'est défini pour `stock_pro` par l'application ; si des objets SQL sont ajoutés manuellement, les sauvegarder séparément. Le fichier ne contient pas de sélection forcée de la base `stock_pro`, afin de permettre une restauration isolée.

Le dump contient les empreintes de mots de passe des comptes : conserver ses permissions privées. Ces sauvegardes sont manuelles et locales ; aucune planification ni copie sur un autre appareil n'a été installée.

## Vérifier une sauvegarde

Vérifier l'empreinte depuis le dossier de sauvegarde :

```sh
shasum -a 256 -c SHA256SUMS
```

L'empreinte vérifie les octets du fichier. Une restauration de test vérifie également que le SQL se recharge et que les données et le schéma correspondent.

Pour la restauration, créer une base temporaire avec un nom neuf, sans `IF NOT EXISTS`, puis importer `stock_pro.sql` avec le client `mysql` en sélectionnant cette base. Ne jamais sélectionner `stock_pro` pour un test. Comparer les dix schémas de tables, les nombres de lignes et les lignes complètes, puis supprimer uniquement cette base temporaire.

Le 5 octobre 2026, l'export a été restauré dans une base isolée `stock_restore_check_*`. Les dix schémas de tables et toutes les lignes correspondent à `stock_pro`, dont les deux comptes présents au début de la maintenance. La base temporaire a ensuite été supprimée.

## Réparer l'erreur 1558 des tables système

L'incident local provenait de `mysql.proc`, créé avec MariaDB 10.1.8 (20 colonnes) alors que XAMPP exécutait MariaDB 10.4.28 (21 colonnes attendues). Cette réparation met à niveau les tables système ; elle ne change pas la version du serveur installé et ne répare pas automatiquement une table InnoDB absente du moteur.

La [documentation MariaDB](https://mariadb.com/docs/server/clients-and-utilities/deployment-tools/mariadb-upgrade) recommande une sauvegarde avant l'outil de mise à niveau. Pour l'intervention locale :

1. Arrêter l'application et exporter `stock_pro`.
2. Arrêter proprement MariaDB avec son client `mysqladmin shutdown`, puis vérifier que le processus serveur et son superviseur `mysqld_safe` sont réellement arrêtés. Le script XAMPP a signalé « not running » alors qu'un serveur était encore actif ; ce message seul ne garantit pas l'arrêt.
3. Copier à froid tout `/Applications/XAMPP/xamppfiles/var/mysql/` et `etc/my.cnf`, avec les droits macOS nécessaires. La copie complète conserve aussi les comptes et droits SQL des tables système et les autres bases. Ne pas déplacer ou remplacer `ibdata1` pour réparer le serveur.
4. Redémarrer MariaDB et utiliser le binaire correspondant à la version du serveur :

```sh
/Applications/XAMPP/xamppfiles/bin/mysql_upgrade --no-defaults \
  --protocol=tcp --host=127.0.0.1 --port=3306 --user=root \
  --upgrade-system-tables --force
```

`--no-defaults` doit rester le premier argument ; la commande montrée utilise le compte local sans mot de passe. Avec un secret, utiliser un fichier d'options privé en première position à sa place. L'outil conserve son contrôle de version. `--upgrade-system-tables` limite l'intervention à `mysql` ; ne pas lancer manuellement les anciens fichiers SQL de `share/mysql`. Des droits macOS sont nécessaires pour écrire le fichier `mysql_upgrade_info` dans le dossier XAMPP protégé.

5. Redémarrer proprement MariaDB, puis vérifier une nouvelle connexion TCP, `mysql.proc` à 21 colonnes avec `aggregate`, l'accès à `INFORMATION_SCHEMA.ROUTINES` et les tables :

```sh
/Applications/XAMPP/xamppfiles/bin/mysqlcheck --no-defaults \
  --protocol=tcp --host=127.0.0.1 --port=3306 --user=root \
  --check --databases mysql stock_pro
```

6. Comparer les empreintes et les compteurs de `stock_pro`, tester la restauration SQL isolée, puis redémarrer l'application avec `ddl-auto=validate` et vérifier la connexion web avec CSRF.

La copie physique de cette intervention est dans `.local-backups/maintenance-20261005/xampp-before-system-upgrade.tar`. Elle a été faite serveur effectivement arrêté ; sa restauration complète n'a pas été exécutée. L'export SQL se trouve dans le sous-dossier `stock_pro-20261005-120333-CHv9x7/` ; sa restauration isolée a été vérifiée. Une première copie faite sans arrêt confirmé porte le nom `xampp-copy-not-validated.tar` et n'est pas considérée comme une sauvegarde à froid fiable.

Après intervention, les contrôles de toutes les tables système et des dix tables de stock passent. La lecture des routines fonctionne, `mysql.user` est une vue sur la nouvelle table `mysql.global_priv`, les deux comptes applicatifs sont conservés et les dix empreintes de tables sont identiques avant/après. L'application démarre sur le port 8080 avec validation du schéma ; connexion administrateur, accueil, formulaire caissier et déconnexion avec CSRF ont été vérifiés sans création de données métier.
