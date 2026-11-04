# Comptes de trésorerie et transferts

Chaque boutique possède trois comptes de suivi : `CAISSE` (espèces),
`ORANGE_MONEY` et `BANQUE`. Les encaissements des ventes, les paiements des
achats et les règlements ultérieurs portent le compte choisi pour chaque
versement. Une facture peut donc recevoir plusieurs versements sur des comptes
différents. Ces écritures enregistrent les paiements saisis dans Stock Pro ;
elles n'effectuent aucun virement ou paiement auprès d'un opérateur externe.

Le journal montre le compte de chaque mouvement et les trois soldes séparés.
Le total correspond à leur somme. Un transfert débite sa source et crédite sa
destination du même montant, sans augmenter les recettes de la boutique. Les
dépenses et transferts sont réservés au gérant de la boutique. L'historique,
les comptes utilisateurs et les documents restent conservés.

## Schéma

La migration ajoute deux tables et deux colonnes ; les dix-sept anciennes
tables et toutes leurs anciennes colonnes sont préservées :

- `compte_tresorerie_boutique` : un identifiant de verrou par boutique et compte,
  avec contrainte unique `(boutique_id, compte)`. Aucun solde redondant n'est
  stocké : les soldes sont calculés depuis les mouvements. Trois lignes sont
  ajoutées pour chaque boutique existante, y compris les boutiques suspendues.
- `operation_tresorerie` : en-tête des transferts et dépenses, avec boutique,
  utilisateur, date, montant `DECIMAL(17,2)`, motif et jeton unique. Le compte
  destination est obligatoire pour un transfert et vide pour une dépense.
- `mouvement_caisse.compte VARCHAR(32) NOT NULL DEFAULT 'CAISSE'` : les mouvements
  anciens restent tous en caisse. Aucun mode de paiement historique n'est déduit.
- `mouvement_caisse.operation_tresorerie_id BIGINT NULL` : lien facultatif vers
  une opération de trésorerie. Les anciennes lignes gardent un lien vide.

Les nouvelles tables utilisent InnoDB et `utf8mb4_unicode_ci` sur MariaDB. Les
clés étrangères ne suppriment pas les historiques en cascade. Le verrou est
pris sur les comptes concernés dans un ordre stable : deux transferts ne
peuvent pas consommer simultanément le même solde disponible. Le débit et le
crédit du transfert sont validés dans une seule transaction. Un renvoi du même
formulaire ne crée pas une seconde opération ; réutiliser le jeton avec un
contenu différent est refusé.

Le gérant peut saisir le solde initial de chaque compte une fois pendant la
préparation de la boutique. L'ajout de cette fonctionnalité ne rouvre pas le
démarrage d'une boutique déjà utilisée et ne crée aucun mouvement initial
rétroactif. Les nouvelles boutiques reçoivent leurs trois comptes lors de leur
création.

## Migration locale

Précondition : les migrations du démarrage et précédentes sont terminées.
Arrêter l'application et DevTools, puis sauvegarder la base complète :

```sh
./scripts/backup-stock-local.sh
./gradlew tresorerieCheck
TRESORERIE_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew tresorerieMigrate
./gradlew tresorerieCheck
```

`--check` reste en lecture seule : il indique les éléments et comptes manquants.
`--apply` vérifie la cible MariaDB locale `localhost:3306/stock_pro`, le dump SQL
complet et son empreinte SHA-256. Un verrou nommé exclut une seconde migration
simultanée. Toutes les données et définitions des anciennes colonnes sont
comparées avant et après. Le démarrage applicatif conserve Hibernate en mode
`validate`.

Les DDL MariaDB peuvent être validés avant la fin du script. En cas
d'interruption, garder l'application arrêtée et relancer la même migration
après le contrôle : elle ajoute seulement les éléments et comptes manquants.
Elle préserve les nouveaux mouvements, opérations et comptes déjà présents.
Elle refuse les types, tailles, défauts, contraintes, cascades, comptes ou
appartenances incohérents ; elle ne tente aucune réparation automatique et ne
reclasse aucun mouvement existant.

Pour une base neuve, [database-schema.sql](../database-schema.sql) crée les
dix-neuf tables actuelles. Ce script de création ne remplace pas la migration
d'une base existante.

## Vérifications

```sh
./gradlew test --tests '*TresorerieMigrationTests'
./gradlew tresorerieMariaDbTest
```

Les mêmes scénarios H2 et MariaDB vérifient : ancien schéma, base vide,
interruptions entre les ajouts, comptes partiellement créés, relances avec
activité existante ou nouvelle boutique, conservation des anciennes sommes et
relations, refus des colonnes, défauts et contraintes incorrects, montants ou
jetons d'opération invalides, acteur d'une autre boutique. Les tests H2 couvrent
aussi l'exigence de sauvegarde et le refus d'une transaction déjà ouverte.
MariaDB ajoute le refus d'une migration concurrente. Chaque scénario crée et
supprime uniquement son schéma `stock_tresorerie_test_*` contrôlé.

`TRESORERIE_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` sélectionne un serveur
local isolé ; aucun test ne modifie `stock_pro`.
