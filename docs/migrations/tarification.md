# Définir les prix lors de l’entrée des marchandises

À sa création, une fiche produit demande uniquement les informations du catalogue. Elle démarre
avec une quantité nulle et des prix « À définir ». L’achat et le stock initial
demandent la quantité, le coût d’achat unitaire et le prix de vente. Ces tarifs
deviennent ceux du produit ; les prochains achats peuvent reprendre puis modifier
le prix de vente actuel. Les ventes et ajustements de quantité restent impossibles
tant que la première entrée n’a pas défini les prix.

Une fois les tarifs définis, le prix de vente peut également être modifié depuis
la fiche produit. Le coût d’achat et la quantité restent gérés par les achats,
le stock initial et les corrections d’inventaire.

Les factures de vente précédentes gardent leur prix unitaire. Le nouveau prix
de vente choisi lors d’un achat est aussi enregistré dans sa ligne, pour conserver
la décision même après une modification ultérieure. Le stock initial garde ce
tarif dans son historique. Cette saisie ne crée pas de dépense fictive en caisse.

Le contrôle des doubles envois d’achat inclut désormais le prix de vente saisi.
La copie exacte d’un achat enregistré avant cette évolution reste reconnue,
uniquement si la requête et les lignes enregistrées n’ont aucun tarif de vente.
Un ancien formulaire non encore enregistré doit être rechargé pour saisir ce prix.

## Ajouts au schéma

Les dix-sept tables existantes sont conservées. Trois colonnes sont ajoutées :

- `produit.prix_definis BOOLEAN NOT NULL DEFAULT TRUE` : conserve les produits
  historiques comme tarifés, même à prix zéro. Le service de création fixe `FALSE`
  pour une nouvelle fiche, avec ses deux prix techniques et sa quantité à zéro.
- `detail_achat.prix_vente_unitaire DECIMAL(17,2) NULL` : conserve le prix de vente
  choisi lors d’un nouvel achat ; `NULL` pour les anciennes lignes.
- `mouvement_stock.prix_vente DECIMAL(17,2) NULL` : conserve le tarif d’un nouveau
  stock initial ; `NULL` pour les anciens mouvements et les ajustements de quantité.

Aucun prix actuel n’est recopié dans une ancienne ligne. Aucun achat, produit,
stock, compte, abonnement, solde ou mouvement de caisse n’est recalculé.

## Migration locale

Précondition : la migration d’inventaire est terminée. Arrêter les instances de
l’application et DevTools, puis créer et vérifier une sauvegarde complète :

```sh
./scripts/backup-stock-local.sh
./gradlew tarificationCheck
TARIFICATION_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew tarificationMigrate
./gradlew tarificationCheck
```

Le contrôle est en lecture seule. L’application de la migration vérifie la cible
MariaDB locale `localhost:3306/stock_pro`, le dump SQL et son empreinte SHA-256,
puis prend un verrou nommé pour refuser une seconde migration simultanée. Les
types, tailles, valeurs nulles, défauts et données déjà présentes sont vérifiés
avant tout ajout. Une interruption entre deux ajouts se reprend uniquement si
les colonnes présentes sont conformes. Leurs valeurs sont conservées.

Après les ajouts, une comparaison contrôle toutes les données et anciennes
colonnes des dix-sept tables. Une relance laisse intactes les nouvelles fiches
sans tarifs et les lignes déjà tarifées. Un statut non booléen, un produit sans
prix avec un stock ou un prix non nul, un tarif négatif ou un ajustement portant
un prix de vente est refusé sans réparation automatique. Les migrations
d’inventaire et d’abonnement restent contrôlables après ces ajouts.

La validation Hibernate reste `ddl-auto=validate`. Le script de création complète
`docs/database-schema.sql` est réservé aux bases neuves.

## Vérifications

```sh
./gradlew test --tests '*TarificationMigrationTests' --tests '*InventaireMigrationTests'
./gradlew tarificationMariaDbTest
```

Les scénarios H2 et MariaDB vérifient l’ancien schéma, le contrôle sans écriture,
les trois interruptions possibles, le schéma vide, les relances avec des tarifs
déjà enregistrés, les prix gratuits existants, les refus de schéma ou de données
incohérents et la conservation de toutes les données. MariaDB vérifie aussi le
verrou concurrent. Chaque scénario MariaDB crée puis supprime uniquement un
schéma temporaire au nom contrôlé ; il ne modifie jamais `stock_pro`.

`TARIFICATION_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` permet éventuellement de
tester sur un serveur local isolé. La cible de migration réelle reste contrôlée
séparément.
