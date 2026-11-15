# Lot 5a et 5b : comptabilité

Cette migration prépare la comptabilité ([lot-5.md](../lot-5.md)). Elle **ne modifie, ne supprime et ne réécrit** aucune donnée ni aucune
colonne existante : ni stocks, ni montants, ni mouvements. Elle exige que les migrations des lots 1 à 4 **et du lot 6** soient terminées.

## Changements

| Élément | Ajout |
|---|---|
| Table `sous_compte_boutique` | `id`, `boutique_id` (FK), `numero` VARCHAR(8), `parent_numero` VARCHAR(4), `intitule` VARCHAR(120), `compte_tresorerie_id` (FK facultative, unique), `date_creation` DATETIME(6) ; unicité `(boutique_id, numero)` |
| Table `correspondance_compta` | `id`, `boutique_id` (FK), `cle` VARCHAR(40), `compte_numero` VARCHAR(8), `mots_cles` VARCHAR(255) NULL ; unicité `(boutique_id, cle)` |
| Table `reglage_comptable_boutique` (lot 5b) | `id`, `boutique_id` (FK, unique), `systeme` VARCHAR(10) (`NORMAL` ou `MINIMAL`) ; vide par défaut : une boutique sans ligne est au système normal |
| Table `exercice_comptable` (lot 5c) | `boutique_id` (FK), `date_debut`, `date_fin`, `statut` (`OUVERT`/`CLOTURE`), `cloture_le`, `cloture_par`, `motif`, `resultat`, `valeur_stock`, `plafond_client_id`, `plafond_fournisseur_id` ; unicité `(boutique_id, date_debut)` |
| Table `solde_ouverture` (5c) | `exercice_id` (FK), `compte`, `tiers_type`, `tiers_id`, `tiers_nom`, `solde` DECIMAL(19,2) ; unicité `(exercice_id, compte, tiers_type, tiers_id)` |
| Tables `instantane_exercice` et `instantane_document` (5c) | l'instantané figé (statut `ACTIF`/`ANNULE`, empreinte SHA-256, contenu LONGBLOB) et ses documents (PDF et Excel LONGBLOB) ; unicités `(exercice_id, version)` et `(instantane_id, code)` |
| Table `affectation_resultat` (5c) | une affectation par exercice (`exercice_id` unique) |
| Colonnes de `reglage_comptable_boutique` (5c) | `debut_premier_exercice` DATE, `valide_cabinet` VARCHAR(120), `valide_le` DATE, toutes facultatives |
| Données | un sous-compte pour chaque compte de trésorerie existant (571 caisse, 552 Orange Money, 521 banque), au plus petit numéro libre qui n'est ni officiel ni déjà pris |

La table de correspondance reste **vide** : une ligne absente veut dire « valeur par défaut ». Les écritures ne sont pas stockées.
Le lot 5b ajoute seulement `reglage_comptable_boutique` : la migration est la même commande, **sûre sur une base où le 5a est déjà posé** (seule la table absente est créée,
les sous-comptes et les lignes de correspondance ne sont pas réécrits) comme sur une base sans 5a.
Le lot 5c ajoute cinq tables et trois colonnes facultatives à `reglage_comptable_boutique` : la même commande est **idempotente** sur une base sans lot 5, avec le 5a seul,
avec le 5a et le 5b (les réglages saisis sont conservés) ou interrompue (une ligne d'exercice déjà présente est conservée). Aucune ligne d'exercice n'est créée : sans ligne,
les exercices sont les années civiles et rien n'est verrouillé. Si un `instantane_exercice` ou un exercice incohérent existe (statut inconnu, deux instantanés actifs, contenu
non LONGBLOB), la migration refuse sans rien modifier.
Syntaxe compatible MariaDB 10.4.

## Procédure

Arrêter l'application, sauvegarder la base complète avec `scripts/backup-stock-local.sh`, puis :

```bash
./gradlew lot5Check
LOT5_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot5Migrate
./gradlew lot5Check
```

- `lot5Check` ne modifie rien : il dit si la migration est nécessaire, déjà faite ou si le schéma est incohérent (colonne de mauvais type,
  unicité ou clé étrangère absente, sous-compte qui reprend un numéro officiel ou ne suit pas le type de son compte).
- `lot5Migrate` refuse de s'exécuter sans sauvegarde valide dans `LOT5_BACKUP_DIR`, prend le verrou nommé `stock_lot5_migration`, crée
  les tables absentes puis les sous-comptes manquants, et compare avant et après toutes les autres tables. Elle est **idempotente** et reprend
  après une interruption (tables créées à moitié, sous-comptes déjà posés à la main ou en partie).
- Sans migration, l'application crée elle-même les sous-comptes de trésorerie manquants à l'ouverture de la comptabilité, mais les tables
  doivent exister (Hibernate en mode `validate`).

## Retour arrière

Restaurer la sauvegarde faite avant `lot5Migrate` avec la version précédente de l'application. Si la base est déjà migrée et que l'ancienne
version doit tourner, les deux tables ajoutées sont inoffensives pour elle (elle les ignore) ; on peut les laisser ou les supprimer
(`DROP TABLE affectation_resultat, instantane_document, instantane_exercice, solde_ouverture, exercice_comptable, reglage_comptable_boutique, correspondance_compta, sous_compte_boutique`, dans cet ordre) : aucune autre table n'y fait référence. Les colonnes ajoutées à `reglage_comptable_boutique` disparaissent avec elle ; l'ancienne version les ignore sinon.

## Vérifications

```bash
./gradlew test --no-daemon --tests '*Lot5MigrationTests'   # H2 : 19 scénarios, lot 6 requis, sauvegarde, schéma migré = schéma neuf
service mariadb start && ./gradlew lot5MariaDbTest         # MariaDB jetable : scénarios, verrou, Hibernate validate (migré et neuf), requêtes groupées
./gradlew lot1MariaDbTest lot2MariaDbTest lot3MariaDbTest lot4MariaDbTest lot6MariaDbTest   # les lots précédents restent valides
```
