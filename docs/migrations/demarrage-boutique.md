# Réserver le stock et le solde initiaux au démarrage

Une nouvelle boutique commence en préparation. Son gérant peut créer les fiches
produit, saisir les marchandises déjà détenues et indiquer le solde initial de
caisse. Plusieurs produits peuvent recevoir un stock initial, chacun une seule
fois. La création d’une fiche produit reste possible à tout moment, avec une
quantité nulle et des prix « À définir ».

La préparation se ferme définitivement lorsque le gérant la termine ou dès le
premier achat, la première vente ou le premier mouvement opérationnel de caisse.
Un achat ou une vente à crédit ferme également cette phase, même sans versement.
Une fois fermée, aucun produit — y compris une fiche créée ensuite — ne peut
recevoir de stock initial et la caisse ne peut plus recevoir de solde initial.
Les quantités évoluent alors avec les achats, les ventes et les modifications
des lignes d’achat. Les corrections manuelles de quantité
ne sont plus proposées et leur ancienne commande refuse toute écriture.
Une fiche produit peut être supprimée uniquement si son stock est nul et si elle
ne figure dans aucun achat, aucune vente et aucun historique d’inventaire.

Un verrou de boutique commun sérialise les opérations initiales, la fermeture et
le premier achat ou la première vente. Les écritures sont transactionnelles : un
achat refusé n’interrompt pas la préparation. Le statut fermé est conservé
indépendamment des documents restants. Après fermeture, les opérations retrouvent
leurs verrous par document et produit, sans verrou global de boutique. Le statut est déterminé depuis
la boutique du compte authentifié ; aucune saisie de formulaire ne peut rouvrir
la préparation ni cibler une autre boutique.

## Schéma et boutiques existantes

Les dix-sept tables restent présentes. Une seule colonne est ajoutée :

```sql
ALTER TABLE boutique
    ADD COLUMN initialisation_terminee BOOLEAN NOT NULL DEFAULT FALSE;
```

Le défaut `FALSE` réserve une préparation ouverte aux nouvelles boutiques. La
migration referme les boutiques existantes dont l’utilisation est attestée par :

- au moins un achat ou une vente, y compris à crédit ;
- un mouvement de caisse qui n’est pas à la fois `source=SOLDE_INITIAL` et
  `type=OUVERTURE` ; une source ou un type vide n’est pas une ouverture initiale ;
- une ancienne correction `AJUSTEMENT` du stock ;
- un produit ayant une quantité positive sans mouvement `INITIAL` pour ce
  produit dans cette boutique, ce qui conserve l’usage des stocks hérités.

Une boutique n’ayant que des fiches à zéro, plusieurs stocks initiaux et/ou un
solde initial reste en préparation. Les prix n’interviennent pas dans cette
classification : un stock hérité gratuit constitue aussi une utilisation.

Les stocks, versions des produits, coûts, tarifs, documents, comptes, droits,
abonnements, journaux et soldes précédents sont conservés. Les anciens ajustements
restent lisibles ; aucun événement ni solde initial rétroactif n’est créé.

## Migration locale

Précondition : les migrations d’inventaire et des tarifs sont terminées.
Arrêter l’application et DevTools, puis sauvegarder la base complète :

```sh
./scripts/backup-stock-local.sh
./gradlew initialisationBoutiqueCheck
INITIALISATION_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew initialisationBoutiqueMigrate
./gradlew initialisationBoutiqueCheck
```

Le contrôle reste en lecture seule. Il indique si la colonne est absente et le
nombre de boutiques utilisées dont la préparation doit être fermée. L’application
de la migration vérifie la cible MariaDB locale `localhost:3306/stock_pro`, le
dump SQL et son empreinte SHA-256, puis prend un verrou nommé contre les migrations
concurrentes. Elle compare toutes les anciennes colonnes et données des dix-sept
tables avant et après les écritures. Hibernate reste en mode `validate`.

La colonne doit être un booléen obligatoire avec `FALSE` pour défaut, contenant
uniquement zéro ou un. Un type, un défaut ou une valeur incohérents sont refusés
sans réparation automatique. Une relance ne passe jamais `TRUE` à `FALSE`.
Si l’exécution s’est interrompue après l’ajout de la colonne ou après une partie
des fermetures, `--apply` termine uniquement les passages `FALSE` vers `TRUE`
justifiés par l’activité historique. Les boutiques nouvelles ou encore en
préparation ne sont pas fermées par une simple relance.

Le script [database-schema.sql](../database-schema.sql) est réservé aux bases
neuves ; il ne remplace pas la migration des bases existantes.

## Vérifications

```sh
./gradlew test --tests '*InitialisationBoutiqueMigrationTests'
./gradlew initialisationBoutiqueMariaDbTest inventaireMariaDbTest
```

Les mêmes scénarios H2 et MariaDB couvrent l’ancien schéma, une base vide, les
achats et ventes à crédit, les ouvertures à zéro, plusieurs stocks initiaux, les
stocks hérités gratuits, les anciennes corrections, les sources de caisse
inconnues ou détournées, les interruptions, les relances et les statuts invalides.
MariaDB vérifie aussi le verrou concurrent. Chaque scénario crée et supprime
uniquement un schéma temporaire au nom contrôlé ; aucun test ne modifie `stock_pro`.
`INITIALISATION_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` permet de sélectionner
un serveur local isolé pour ces vérifications.
