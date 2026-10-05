# Comptes utilisateurs et migration des emails

## Création d'un caissier

Se connecter comme ADMIN, puis ouvrir **Créer un compte caissier**. Le serveur vérifie un objet de formulaire dédié ; les champs internes tels que `role`, `id` ou `motDePasse` sont refusés. Le rôle créé reste CAISSIER et la session de l'administrateur est conservée.

- Nom obligatoire, sans espaces autour, maximum 100 caractères Java.
- Email obligatoire, format validé, maximum 254 caractères. Les espaces autour sont retirés et la casse est normalisée avec `Locale.ROOT`, indépendamment de la langue du système.
- Nouveau mot de passe : au moins 15 caractères Unicode et au maximum 72 octets UTF-8, limite du BCrypt utilisé. Les espaces sont conservés ; un mot de passe composé uniquement d'espaces est refusé. Aucune règle de mélange obligatoire de chiffres, majuscules ou symboles n'est ajoutée.

Le minimum de 15 caractères pour une connexion sans MFA suit la [recommandation OWASP](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#implement-proper-password-strength-controls). Le maintien de BCrypt impose toujours la limite en octets ; les mots de passe Unicode longs peuvent atteindre cette limite avant 64 caractères. Cette correction n'ajoute ni liste de mots de passe compromis, ni MFA, ni limitation des tentatives.

En cas d'erreur, le nom et l'email sont conservés, mais le mot de passe n'est pas réaffiché. Les erreurs de base ne sont pas recopiées dans l'interface. Un email déjà utilisé donne un message en français ; la contrainte SQL protège également contre les créations simultanées.

## Connexion et comptes existants

L'inscription, le login Spring Security, le login du service et les écritures JPA utilisent la même normalisation d'email. Le principal de connexion contient l'adresse persistée. Les mots de passe ne sont jamais trimmés ou mis en minuscules.

Le minimum de 15 caractères s'applique seulement aux nouvelles créations. Les anciennes empreintes BCrypt et les anciens mots de passe courts restent utilisables ; la limite stricte de 72 octets à la connexion, introduite dans un lot précédent, reste active. Aucune réinitialisation générale n'est effectuée par la migration d'emails.

## Base existante : procédure de migration

1. Arrêter les instances de l'application, y compris `bootRun`/DevTools, **avant toute compilation**. Avec `ddl-auto=update`, un rechargement pourrait tenter d'ajouter une contrainte avant le contrôle des comptes.
2. Exporter et vérifier une sauvegarde complète de la base opérationnelle. La copie de lignes créée par l'outil ci-dessous ne remplace pas une sauvegarde restaurable du schéma, des données et des relations.
3. Vérifier les adresses :

```sh
./gradlew accountEmailsCheck
```

Cette commande JDBC lit les comptes sans démarrer Spring ni modifier la base. Elle utilise `DB_URL`, `DB_USERNAME` et `DB_PASSWORD` comme l'application, affiche des compteurs, mais ne publie ni adresses ni empreintes. La normalisation est effectuée en Java afin de conserver exactement les règles de l'application, y compris Unicode et les espaces de bord. La vérification teste aussi les collisions selon le jeu de caractères et la collation SQL de la colonne.

Les adresses nulles, vides, mal formées, trop longues ou devenant identiques arrêtent le contrôle. Identifier et corriger les comptes concernés manuellement ; ne pas supprimer un compte lié à l'historique ni fusionner ses mouvements de caisse automatiquement.

4. Une fois le contrôle réussi et la sauvegarde vérifiée, appliquer :

```sh
./gradlew accountEmailsMigrate
```

L'outil vérifie de nouveau les comptes avant toute écriture. Il crée `accounts_backup_20261005_utilisateur` avec les anciennes lignes, normalise uniquement les emails, puis ajoute `NOT NULL` et l'unicité de `email`. Identifiants, noms, dates, rôles, empreintes et références de caisse ne sont pas réécrits. La copie contient des empreintes de mots de passe : réserver son accès aux opérateurs autorisés.

Une base déjà conforme est laissée intacte. Une copie existante n'est pas écrasée. Les changements d'emails sont transactionnels ; les DDL MariaDB/MySQL ont une validation implicite. Une erreur après normalisation peut donc laisser la migration partiellement appliquée avec sa copie conservée. Ne pas effacer cette copie pour forcer une relance : examiner le schéma et restaurer la sauvegarde si nécessaire.

5. Redémarrer l'application, vérifier les connexions et renouveler les sessions ouvertes avant la migration.

Les scénarios ont été exécutés sur H2 et MariaDB 10.4.28. La variante JDBC utilise des opérations compatibles MySQL/MariaDB, mais n'a pas été exécutée sur un serveur MySQL.

## Premier administrateur d'une base locale neuve

Le schéma doit être créé et la table `utilisateur` vide. Utiliser la commande locale réservée à l'opérateur disposant de l'accès JDBC :

```sh
./gradlew initialAdmin
```

La commande exige `localhost` ou `127.0.0.1` et la base `stock_pro`. Elle crée `admin@stock.local`, rôle ADMIN, avec un mot de passe aléatoire de 32 caractères, haché en BCrypt. Les identifiants sont écrits dans un fichier privé de permissions `0600` ; seul son chemin est affiché. Conserver le mot de passe avant le nettoyage des fichiers temporaires du système.

En cas d'échec après préparation des identifiants, le fichier privé est conservé : une erreur de connexion peut survenir après une insertion validée par le serveur. Vérifier l'existence du compte avant de relancer la commande.

Pour choisir l'email, définir `INITIAL_ADMIN_EMAIL`. Un mot de passe peut être fourni via `INITIAL_ADMIN_PASSWORD`, sans le placer dans une commande ou un fichier suivi par Git ; les mêmes règles de validation s'appliquent.

La commande refuse toute base contenant déjà un compte. Un verrou de session SQL empêche deux initialisations simultanées sur MariaDB/MySQL. Aucun endpoint web ne crée le premier administrateur ; l'inscription normale reste réservée à un ADMIN et crée uniquement des CAISSIER.

## Vérification

```sh
./gradlew test bootJar
# Facultatif, avec MariaDB local disponible :
./gradlew accountEmailsMariaDbTest
```

La suite habituelle utilise H2. Le second parcours crée des bases nommées `stock_accounts_test_*`, les supprime ensuite et ne modifie jamais `stock_pro`. Il contrôle les doublons normalisés, les collisions de collation, les emails Unicode, le refus des adresses invalides, les copies existantes, la relance d'une migration et l'initialisation protégée de l'administrateur.
