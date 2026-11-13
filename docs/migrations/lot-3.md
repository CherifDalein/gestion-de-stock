# Lot 3 : comptes de trésorerie multiples et financements

Cette migration transforme les trois comptes fixes de chaque boutique (`CAISSE`, `ORANGE_MONEY`, `BANQUE`) en comptes nommés d'une
boutique, dont on peut ajouter d'autres (voir [Comptes de trésorerie multiples](../lot-3.md#comptes-de-trésorerie-multiples)). Elle ne
modifie ni stocks, ni montants, ni dates, ni numéros : **les totaux par boutique et par type de compte sont identiques avant et
après**. Elle exige que les migrations des lots 1, 2 et 4 soient terminées.

## Ce qui change

| Table | Avant | Après |
|---|---|---|
| `compte_tresorerie_boutique` | `compte` (énumération), unique `(boutique_id, compte)` | `type`, `nom`, `nom_cle`, `numero`, `principal_type`, `actif`, `ordre` ; uniques `(boutique_id, nom_cle)` et `(boutique_id, principal_type)` |
| `compte_tresorerie_autorise` | n'existe pas | nouvelle : `utilisateur_id`, `compte_id`, unique `(utilisateur_id, compte_id)` |
| `mouvement_caisse` | `compte` VARCHAR(32) | `compte_id` BIGINT NOT NULL, clé étrangère `fk_mouvement_caisse_compte` |
| `operation_tresorerie` | `compte_source`, `compte_destination` VARCHAR(32) | `compte_source_id` NOT NULL, `compte_destination_id` NULL, clés `fk_operation_tresorerie_source` et `_destination` |
| `retour` | `compte_tresorerie` VARCHAR(16) | `compte_tresorerie_id` BIGINT NULL, clé `fk_retour_compte_tresorerie` |

Pour chaque boutique, les trois comptes existants deviennent ses **comptes principaux**, nommés « Caisse » (type `ESPECES`, l'ancienne
valeur `CAISSE`), « Orange Money » et « Banque », actifs, sans numéro. Un compte de base manquant est créé. Chaque mouvement, opération
et remboursement est rattaché au compte principal de son ancien moyen de paiement. Aucune autorisation de caissier n'est inventée :
un caissier sans autorisation utilise les comptes principaux, comme avant.

## Procédure

Arrêter l'application et DevTools, puis sauvegarder la base complète (`scripts/backup-stock-local.sh`) :

```bash
./gradlew lot3Check
LOT3_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot3Migrate
./gradlew lot3Check
```

- `lot3Check` ne modifie rien. Il refuse un schéma incohérent et annonce le nombre d'éléments manquants, de comptes de base à créer
  et de mouvements à rattacher. Les messages sont en français.
- `lot3Migrate` exige `LOT3_BACKUP_DIR` : le répertoire d'une sauvegarde SQL existante et vérifiée (même protection que les lots
  précédents ; refus sans la variable, sans fichier ou avec une empreinte qui ne correspond pas). Il prend le verrou nommé
  `stock_lot3_migration` contre une exécution simultanée.
- La migration est **idempotente** et reprenable. Dans l'ordre :
  1. ajout des colonnes et de la table des autorisations, sans rien supprimer ;
  2. création des comptes de base manquants, puis remplissage des nouvelles colonnes ;
  3. contrôles : aucun mouvement, opération ni remboursement sans compte, chaque compte appartient à la même boutique que son mouvement,
     **totaux (somme et nombre de mouvements) par boutique et par type identiques** à ceux d'avant ;
  4. seulement alors : colonnes rendues obligatoires, contraintes d'unicité, clés étrangères, puis **suppression des anciennes colonnes
     à énumération**, en dernier.
  Une interruption laisse la base dans l'un de ces états, tous reconnus par une relance. Un compte déjà migré (renommé, rendu principal,
  archivé, autorisé) n'est jamais réécrit.
- Seule la syntaxe de MariaDB 10.4 est employée.
- **Les contrôles des lots précédents** (`lot1Check`, `lot2Check`, `lot4Check`) se lancent avant le lot 3. Après lui, ils ne
  contrôlent plus les anciennes colonnes de trésorerie (supprimées) ; ils restent utilisables pour le reste de leur schéma.

## Étape 3b1 : financements

La même commande (`lot3Migrate`) applique aussi le schéma des financements ; **il n'y a pas de tâche supplémentaire**. Elle est valable sur une base
qui n'a pas encore reçu le lot 3 (comptes multiples **et** financements en une fois) comme sur une base où seule l'étape 3a a été appliquée
(`lot3Check` annonce alors les éléments de 3b1 manquants, `lot3Migrate` les ajoute).

| Élément | Ajout |
|---|---|
| `boutique` | `prochain_numero_emprunt` et `prochain_numero_pret`, BIGINT NOT NULL DEFAULT 1 (numéros `EM-` et `PR-`) |
| `partenaire_financier` | nouvelle : banques, associés, personnel, divers ; unique `(boutique_id, nom_cle)` |
| `emprunt` | nouvelle : emprunts et prêts ; uniques `(boutique_id, numero)` et `jeton` |
| `echeance_emprunt` | nouvelle : échéancier, unique `(emprunt_id, rang)` |
| `imputation_echeance` | nouvelle : part de chaque remboursement imputée à une échéance |
| `operation_tresorerie` | `partenaire_financier_id` et `emprunt_id` (NULL), clés `fk_operation_tresorerie_partenaire` et `_emprunt` |

Aucune donnée existante n'est modifiée : les nouvelles colonnes sont vides ou valent 1, les nouvelles tables sont vides, aucun mouvement ni
aucun solde ne change. Les contrôles d'avant/après (totaux par boutique et par type de compte) valent donc aussi pour 3b1. Le même ordre
sûr s'applique : colonnes, tables, contrôles, puis clés étrangères ; une relance après interruption reprend où elle s'est arrêtée, et sur une base
entièrement migrée elle ne fait rien (idempotence). Syntaxe MariaDB 10.4 seule.

Retour arrière : par la sauvegarde, comme ci-dessus. Les saisies de financement faites après la migration seraient perdues par la restauration.

## Étape 3b2 : contrôles

Toujours la même commande `lot3Migrate`, valable sur une base sans lot 3, avec 3a seule, ou avec 3a et 3b1 ; `lot3Check` annonce ce qui manque.

| Élément | Ajout |
|---|---|
| `comptage_caisse` | nouvelle : comptages de caisse ; unique `jeton` |
| `comptage_billetage` | nouvelle : billets par coupure ; unique `(comptage_id, coupure)` |
| `ajustement_solde_depart` | nouvelle : corrections du solde de départ d'un client ou d'un fournisseur ; unique `jeton` |
| `inventaire_creances`, `ligne_inventaire_creances` | nouvelles : inventaire des créances et des dettes |
| `mouvement_caisse` | `comptage_caisse_id` (NULL), clé `fk_mouvement_caisse_comptage` |

Aucune donnée existante n'est modifiée (colonne vide, tables vides). Aucune colonne « utilisateur » n'est ajoutée : les tables d'opérations en
portent déjà une. Ordre sûr et reprise après interruption comme pour 3b1 (colonne, tables, contrôles, clés) ; sur une base complète, la relance ne
fait rien. Retour arrière par la sauvegarde ; les comptages, ajustements et inventaires saisis après la migration seraient perdus.

## Étape 3c : rapprochement bancaire

Même commande `lot3Migrate`, valable sur une base sans lot 3, avec 3a seule, avec 3a et 3b1, ou avec 3a, 3b1 et 3b2 ; `lot3Check` annonce ce qui manque.

| Élément | Ajout |
|---|---|
| `releve_bancaire` | nouvelle : relevés (compte, période, soldes, statut `BROUILLON`/`VALIDE`, validation) ; uniques `(boutique_id, numero)` et `jeton` |
| `rapprochement` | nouvelle : groupes de pointage (`AUTO`, `MANUEL`, `ENREGISTREMENT`, `EXCLUSION`) |
| `ligne_releve` | nouvelle : lignes de relevé, `rapprochement_id` (NULL), empreinte pour les doublons |
| `rapprochement_mouvement` | nouvelle : mouvements d'un rapprochement ; unique `mouvement_id` (un mouvement n'est que dans un rapprochement) |
| `correspondance_import` | nouvelle : correspondance des colonnes mémorisée, unique par compte |
| `evenement_releve` | nouvelle : historique des validations et dévalidations |
| `refus_proposition` | nouvelle : paires refusées, unique `(ligne_id, mouvement_id)` |

Aucune donnée existante n'est modifiée (aucune colonne ajoutée aux tables existantes, sept tables vides). Ordre sûr (tables dans l'ordre de leurs dépendances, contrôles,
clés étrangères) et reprise après interruption comme pour 3b1 et 3b2 ; sur une base complète, la relance ne fait rien (idempotence prouvée par une photographie du schéma
avant et après). Les contrôles vérifient aussi les tables présentes en l'état : relevé d'un compte d'espèces, statut inconnu, relevé validé sans validateur, ligne ou
rapprochement d'un autre relevé, mouvement d'un autre compte, refus dont les clés pointent un autre relevé, ou relevé d'une autre boutique que son compte. Retour arrière par
la sauvegarde ; les relevés saisis après la migration seraient perdus.

## Retour arrière

La migration supprime d'anciennes colonnes : le retour arrière se fait **par la sauvegarde**, pas par un script inverse.

1. Arrêter l'application.
2. Restaurer la sauvegarde SQL faite avant `lot3Migrate` (voir [sauvegardes et MariaDB](../sauvegardes-et-mariadb.md)).
3. Redéployer la version précédente de l'application.

Tout ce qui a été saisi après la migration (nouveaux comptes, mouvements) est perdu par la restauration : ne restaurer que juste après
une migration défaillante, avant la reprise de l'activité.

## Vérifications

```bash
./gradlew test --no-daemon --tests '*Lot3MigrationTests'   # H2 : scénarios valides et invalides, base sans lot 3, base 3a seule, idempotence, sauvegarde, lot 4 requis, schéma migré = schéma neuf
service mariadb start && ./gradlew lot3MariaDbTest         # MariaDB jetable (bases 3a, 3a+3b1, 3a+3b1+3b2 comprises) : mêmes scénarios, verrou, Hibernate validate (migré et neuf)
./gradlew lot1MariaDbTest lot2MariaDbTest lot4MariaDbTest  # les lots précédents appliquent aussi le lot 3 avant Hibernate validate
```

Le fichier `docs/database-schema.sql` contient déjà le schéma final pour une base neuve.
