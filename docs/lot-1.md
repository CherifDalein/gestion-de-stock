# Lot 1 — règles de gestion

Ce document décrit les règles métier livrées avec le lot 1. Le schéma de base
correspondant est décrit dans [la migration du lot 1](migrations/lot-1.md).

## Seuil d'alerte, unités et services

### Nature d'une fiche

Chaque fiche du catalogue est soit un **article en stock** (`STOCK`, valeur des
fiches existantes), soit un **service** (`SERVICE`) : livraison, pose,
réparation, découpe…

- La nature se choisit à la création et **ne peut plus être changée ensuite**.
  Le formulaire de modification l'affiche en lecture seule et refuse le champ
  `nature` (réponse 400). Une fiche à transformer doit être recréée.
- Un article en stock démarre à zéro, sans prix (« prix à définir ») : ses prix
  et ses quantités arrivent par un achat ou par le stock initial du démarrage.
- Un service a ses prix définis dès la création (`prixDefinis = true`) : un
  prix de vente obligatoire (0 permis) et un **coût fixe** facultatif, 0 par
  défaut. Ce coût est enregistré dans `cout_moyen` ; `prix_achat` reçoit la même
  valeur, pour l'affichage du catalogue. Les deux restent modifiables sur la
  fiche du service. Le coût d'un article en stock ne se saisit jamais sur la
  fiche : il vient des achats.

### Vente d'un service

Un service se vend comme un article, dans le même panier, au prix de sa fiche :

- aucune vérification de quantité, aucune quantité déduite ;
- aucun verrou sur la fiche : seuls les articles en stock du panier sont
  verrouillés. Le service est lu dans la boutique courante ; un service d'une
  autre boutique est introuvable (404), comme un article ;
- aucun mouvement de stock.

Un service est **refusé**, avec un message qui le nomme, dans :

- un achat et une modification d'achat (le formulaire d'achat ne le propose pas) ;
- un stock initial du démarrage de la boutique (le formulaire n'est pas proposé) ;
- un ajustement d'inventaire (déjà désactivé pour tous les produits).

Sa quantité reste à 0 et s'affiche « — ».

### Seuil d'alerte

- Chaque article en stock a son propre seuil, un entier ≥ 0, **5 par défaut**
  (valeur des fiches existantes). Il se saisit sur la fiche.
- **Rupture** : quantité 0. **Stock faible** : 0 < quantité ≤ seuil.
  **Disponible** : quantité > seuil. Avec un seuil de 0, un article n'est jamais
  « faible » : il passe directement de disponible à rupture.
- L'état est calculé par le serveur (`Produit.etatStock()`) et les compteurs du
  catalogue par `ProduitService.ResumeCatalogue`. Le seuil fixe de 5, codé
  auparavant dans la liste et dans `produits.js`, a disparu.
- Les services n'entrent ni dans « En stock », ni dans « Stock faible », ni dans
  « En rupture ». Ils restent comptés dans « Tous les produits ». Aucune valeur
  de stock n'est calculée aujourd'hui ; une future valeur du stock devra elle
  aussi exclure les services.

### Unités

- L'unité est facultative : pièce, kg, sac, carton, bidon, paquet, litre,
  mètre… proposées par une liste, ou tout texte libre de **30 caractères au
  plus**. Les espaces superflus sont retirés ; une unité vide est enregistrée
  comme absente. Aucune unité n'est inventée pour les fiches existantes.
- L'unité sert **seulement à l'affichage**. Les quantités restent des nombres
  **entiers** partout : 2 sacs, jamais 1,5 sac.
- Elle est affichée à côté des quantités : catalogue et détail de la fiche,
  paniers d'achat et de vente, détail des ventes, factures de vente et d'achat,
  relevés cumulés et page de stock. Les scripts l'insèrent toujours comme texte
  (`textContent` ou `option.text`), jamais comme HTML.

### Formulaire et contrôles

Champs acceptés par la fiche produit (`FormBindingAdvice`, formulaire `produit`) :

| Route | Champs en plus de nom, référence, catégorie et fournisseur |
|---|---|
| création, article en stock | `nature`, `unite`, `seuilAlerte` |
| création, `nature=SERVICE` | `nature`, `unite`, `seuilAlerte`, `prixVente`, `coutMoyen` |
| `/produits/modifier/{id}` | `version`, `unite`, `seuilAlerte`, `prixVente`, `coutMoyen` |

Tout autre champ, deux valeurs de `nature`, une nature inconnue ou un
`coutMoyen` envoyé pour un article en stock sont refusés (400). Un seuil absent
en modification conserve le seuil enregistré. Les erreurs de saisie (seuil
négatif ou vide, unité trop longue, prix d'un service manquant ou négatif)
sont affichées sur le formulaire, en français. La gestion des fiches reste
réservée au gérant ; le caissier consulte le catalogue et vend les services.

### Vérifications

```sh
./gradlew test --tests '*SeuilsUnitesServicesTests'
node --test src/test/js/montants.test.cjs
```

Ces tests couvrent, sur deux boutiques, la vente mixte article + service, la
vente d'un service au-delà de tout stock, les refus à l'achat, à la
modification d'achat, au stock initial et à l'ajustement, les états selon le
seuil, les compteurs, les champs interdits, les droits du caissier et
l'isolation entre boutiques.
