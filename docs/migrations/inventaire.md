# Stock initial, corrections d’inventaire et ouverture de caisse

Créer une fiche produit enregistre un stock nul et des prix « À définir ». À la création, la quantité, le prix d’achat et le prix de vente ne sont pas acceptés dans les formulaires du catalogue. Les achats et le stock initial définissent les deux tarifs ; le prix de vente reste ensuite modifiable depuis la fiche. Modifier la fiche conserve les quantités et coûts existants. Les achats alimentent le stock et enregistrent leur propre prix ; les ventes le consomment. Voir la [migration des tarifs](tarification.md) pour les trois colonnes ajoutées à ce parcours.

Depuis la liste des produits, le bouton **Stock** ouvre le stock initial, les ajustements et leur historique. Le stock initial est réservé à un produit sans quantité ni achat, vente ou mouvement d’inventaire précédent. Il ne peut donc pas être répété après consommation du stock. Quantité positive, coût unitaire non négatif, prix de vente non négatif et origine sont obligatoires. Les corrections remplacent le stock par la quantité comptée, avec un motif obligatoire ; elles conservent les prix et ne sont possibles qu’après la définition des tarifs. Aucune de ces deux opérations ne crée de facture, dette fournisseur ou dépense de caisse.

Les deux formulaires comportent la version actuelle du produit. Sous verrou, une vente, un achat, une autre correction ou une répétition qui a changé cette version provoque un refus et invite à recharger la page. L’historique date les opérations en UTC et fige le nom du gérant, le stock avant/après, le coût initial et le motif. Il commence à l’installation ; aucune ancienne opération n’est inventée. Un produit possédant cet historique ne peut plus être supprimé. Chaque gérant voit et modifie seulement sa boutique ; un caissier ne peut ni lire les coûts d’inventaire ni exécuter ces commandes.

Dans **Caisse → Solde initial**, le gérant renseigne l’argent déjà détenu avant le premier mouvement de caisse. Zéro est accepté et enregistré explicitement. Cette opération se fait une seule fois ; le verrou de boutique et une transaction en READ COMMITTED évitent une double initialisation concurrente, même à zéro. Elle reste distincte des ventes/achats, entre dans le solde d’ouverture et le solde courant, et ne gonfle pas les encaissements ni la variation opérationnelle du jour. Les jours suivants, le solde à l’ouverture provient automatiquement des mouvements antérieurs. Une caisse déjà utilisée est conservée ; aucun solde initial automatique n’est ajouté.

## Migration locale

Précondition : la migration des abonnements est terminée. Garder l’application arrêtée et créer une sauvegarde :

```sh
./scripts/backup-stock-local.sh
./gradlew inventaireCheck
INVENTAIRE_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew inventaireMigrate
./gradlew inventaireCheck
```

La commande d’application vérifie la cible locale MariaDB `localhost:3306/stock_pro`, le dump et son empreinte SHA-256. Elle ajoute uniquement la table `mouvement_stock`, vide, puis compare toutes les données des seize tables existantes. Elle reste relançable et refuse de réparer automatiquement un journal incohérent. Hibernate reste en mode `validate`.

## Vérifications

```sh
./gradlew test
./gradlew inventaireMariaDbTest
```

Le test MariaDB utilise un schéma temporaire dont le nom est contrôlé avant création/suppression, le véritable service d’inventaire et celui de caisse, ainsi que deux gérants simultanés. Il vérifie la migration relançable, le stock initial unique, l’ajustement et l’ouverture unique à zéro. Aucun compte, stock ou solde réel n’est modifié. `INVENTAIRE_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` sélectionne éventuellement un serveur local isolé ; la cible de migration réelle reste strictement contrôlée.
