# Créations d'achats et de ventes : doubles envois

Chaque ouverture de **Nouvel achat** ou **Nouvelle vente** crée un identifiant aléatoire du formulaire, lié au compte connecté et au type d'opération. Cet identifiant accompagne le POST, en plus du jeton CSRF.

- Un premier envoi valide crée le document, change le stock et enregistre le paiement initial.
- Une copie identique du même formulaire retrouve le succès déjà enregistré et affiche un message de confirmation. Elle n'ajoute aucun document ni mouvement.
- Un panier ou un paiement changé après le succès avec le même identifiant est refusé avec HTTP 409 ; un lien permet d'ouvrir un nouveau formulaire.
- Une erreur métier annule la création entière et laisse l'identifiant disponible. Le même formulaire peut être corrigé puis renvoyé.
- Deux formulaires ouverts séparément ont deux identifiants : ils permettent deux opérations identiques légitimes.

Le formulaire inutilisé expire au bout de 24 heures. Un résultat réussi est conservé et peut être reconnu même après ce délai, une reconnexion du même compte ou un redémarrage de l'application. Les anciens formulaires ouverts avant cette mise à jour doivent être rechargés ; un identifiant absent ou mal formé donne HTTP 400 avec un lien vers un nouveau formulaire. Un identifiant inconnu, d'un autre compte ou d'un autre type donne HTTP 409.

Le panier affiché reste à reconstituer après une erreur métier, comme avant ce lot ; l'identifiant est conservé pour permettre ce réessai. Ce correctif ne déduit pas un doublon à partir de la ressemblance de deux documents.

## Garantie transactionnelle

La table `operation_creation` conserve l'identifiant, le propriétaire, le type, la date d'ouverture, l'empreinte des saisies et le lien vers l'achat ou la vente enregistré. Le serveur verrouille cette ligne avant les produits. Deux copies simultanées du même formulaire sont ainsi traitées l'une après l'autre, y compris sur plusieurs instances partageant la même base.

Le document, ses lignes, le stock, la caisse, l'empreinte et le résultat sont validés dans **une seule transaction**. Un rollback rend le formulaire disponible ; aucun résultat n'est marqué réussi dans une transaction indépendante. La copie d'un succès est reconnue avant de recalculer le stock ou les prix courants.

L'empreinte SHA-256 porte sur les identifiants des relations, le paiement saisi, l'ordre des lignes et leurs quantités, ainsi que les prix unitaires saisis pour les achats. Les échelles décimales équivalentes sont normalisées ; un paiement omis reste distinct d'un zéro explicite. Les prix de vente courants ne sont pas réintroduits lors d'une copie.

Le paramètre `jetonCreation` est accepté uniquement sur les deux routes de création. Les champs internes restent interdits, les en-têtes HTTP exclus du binding et CSRF actif. Les appels internes historiques à `AchatService.enregistrerAchat` et `VenteService.effectuerVente` ne prennent pas de clé ; les deux routes web passent obligatoirement par `OperationCreationService`.

Aucune purge automatique des identifiants n'est ajoutée : supprimer les résultats réussis supprimerait la protection contre leurs renvois ultérieurs. Les formulaires abandonnés expirent mais leurs lignes restent présentes.

## Base existante et installation

1. Arrêter l'application, y compris `bootRun`/DevTools, avant compilation et modification du schéma.
2. Sauvegarder `stock_pro` avec [le script local](sauvegardes-et-mariadb.md).
3. Exécuter [la migration additive](migrations/2026-10-05-operation-creation.sql) sur la base applicative sélectionnée. Elle crée seulement `operation_creation` et ses clés étrangères ; aucun document, stock, paiement ni compte existant n'est réécrit. Le script convient à MariaDB/MySQL ; l'exécution réelle de ce lot utilise MariaDB.
4. Redémarrer avec validation du schéma, vérifier les connexions, puis ouvrir de nouveaux formulaires.

Avec `ddl-auto=update`, Hibernate peut créer cette table au démarrage. Pour un schéma géré manuellement, utiliser la migration avant le démarrage avec `validate`. `CREATE TABLE IF NOT EXISTS` ne corrige pas une table déjà présente avec un autre schéma ; la validation doit réussir avant utilisation.

Le script de sauvegarde exporte automatiquement cette nouvelle table avec le reste de la base. Conserver ces identifiants et résultats lors d'une restauration. Les anciens documents n'ont pas de jeton rétroactif et restent accessibles normalement.

## Vérifications

```sh
./gradlew test bootJar
# Facultatif, MariaDB local disponible :
./gradlew creationOperationsMariaDbTest
```

Les tests H2 utilisent des transactions distinctes et vérifient les doubles POST, les copies après changement de stock/prix, les paniers de plusieurs lignes, les paiements partiels, les rollbacks et réessais, l'expiration, les propriétaires/types, les champs manipulés et CSRF. Le parcours MariaDB crée uniquement une base `stock_creation_test_*`, exerce les vrais services et verrous InnoDB, puis ferme l'application de test et supprime cette base. Il n'écrit aucun document de test dans `stock_pro`.
