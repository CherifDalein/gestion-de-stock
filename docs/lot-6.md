# Lot 6 : la corbeille

Un client, un fournisseur, un produit ou une catégorie que l'on supprime n'est plus effacé : il est **placé dans la
corbeille**, y reste 30 jours, puis est effacé automatiquement. Le gérant peut le restaurer à tout moment pendant ce délai.

## Règles

- **Concerné** : clients, fournisseurs, produits, catégories. Les partenaires financiers ne sont pas concernés : ils s'archivent
  déjà (champ `actif`), ils ne se suppriment pas.
- **Les règles de suppression ne changent pas.** Ce qui était refusé reste refusé : un produit avec du stock, de l'historique
  d'inventaire ou présent dans un achat, une vente ou un devis ; un client ou un fournisseur référencé par une vente, un achat, un
  devis, un retour, un mouvement de caisse, un solde de départ ou un inventaire des créances ; une catégorie qui a des produits.
  Avant la corbeille, c'était la clé étrangère qui refusait (erreur 500) ; le refus est maintenant un message clair, au même cas.
- **Disparition complète.** Une fiche à la corbeille n'apparaît plus nulle part : listes, recherches, sélecteurs des ventes, achats,
  devis et produits, exports Excel et PDF, relevés et soldes des partenaires, compteurs et totaux, contrôles d'unicité des
  formulaires. Le filtre est posé sur les entités (`@SQLRestriction("supprime_le is null")`) : il s'applique à toutes les requêtes
  JPQL, aux recherches par identifiant et aux jointures. Les requêtes SQL natives ne le reçoivent pas : celle des soldes des
  partenaires a été complétée (`supprime_le IS NULL`) et la corbeille elle-même est lue en SQL natif.
- **Message et « Annuler ».** Après une suppression : « Client X placé dans la corbeille pour 30 jours. », avec un lien
  « Annuler » qui restaure immédiatement et ramène à la liste.
- **Droits.** Seul le gérant voit la corbeille, restaure et efface définitivement (le caissier reçoit 403 et le menu ne la montre
  pas). Les fiches d'une autre boutique donnent 404.
- **Page « Corbeille »** (menu du gérant) : type, nom, supprimé le, supprimé par, jours restants ; filtre par type et recherche ;
  « Restaurer », « Supprimer définitivement » et « Vider la corbeille » avec la fenêtre de confirmation habituelle ; cartes sur
  téléphone, thème sombre pris en charge ; aucun export.

## Unicité des noms

Seules les catégories ont un nom unique par boutique. Méthode : la colonne `categorie.nom_cle` vaut le nom tant que la catégorie est
active et **NULL dans la corbeille**. L'unicité porte sur `(boutique_id, nom_cle)` (à la place de `(boutique_id, nom)`) : une clé
unique ignore les valeurs vides, donc une catégorie à la corbeille ne bloque pas la création d'une catégorie du même nom, alors que
deux catégories actives du même nom restent impossibles. `nom_cle` est tenu à jour par l'entité (création, modification, mise à la
corbeille) ; il n'est jamais lié depuis un formulaire.

**Restauration** d'une catégorie dont le nom a été repris : refusée, avec un message qui propose de renommer d'abord la catégorie
active. Le contrôle est refait sous verrou, et la clé d'unicité arrête une création simultanée.

Les clients et fournisseurs n'ont pas d'unicité de nom : leur restauration n'a jamais de conflit.

## Limites à connaître

- **Catégorie et produits à la corbeille.** Les produits à la corbeille ne comptent pas dans « la catégorie a des produits » :
  une catégorie dont les seuls produits sont à la corbeille peut elle-même y aller. Un produit ne se restaure alors qu'après sa
  catégorie (et son fournisseur) ; sinon la restauration est refusée avec un message qui le dit. À l'inverse, effacer
  définitivement une catégorie ou un fournisseur encore référencé par un produit de la corbeille est refusé : effacer ce produit
  d'abord.
- **Vider la corbeille** efface produits, clients, fournisseurs puis catégories ; une fiche encore référencée est gardée et comptée
  dans le message.
- Les ventes, achats et devis gardent leur lien : aucune fiche référencée n'arrive à la corbeille, donc l'historique reste complet.

## Durée de conservation et effacement automatique

| Propriété | Variable | Défaut |
|---|---|---|
| `stock.corbeille.retention-jours` | `STOCK_CORBEILLE_RETENTION_JOURS` | 30 (1 à 3650) |
| `stock.corbeille.purge.actif` | `STOCK_CORBEILLE_PURGE_ACTIF` | `true` (`false` dans les tests) |
| `stock.corbeille.purge.cron` | `STOCK_CORBEILLE_PURGE_CRON` | `0 0 3 * * *` (3 h, heure du serveur) |

Chaque nuit, toutes boutiques confondues, les fiches à la corbeille depuis plus de la durée de conservation sont effacées
définitivement : produits d'abord, puis clients, fournisseurs et catégories. Une transaction par fiche, avec l'ordre de verrous
habituel (boutique, puis fiche). Une fiche impossible à effacer (une référence oubliée) est journalisée sans son nom et laissée à la
corbeille ; la tâche passe à la suivante. Le message de la page et le texte « 30 jours » suivent la propriété.

## Verrous

Restaurer et effacer définitivement prennent `verrouillerBoutiqueCourante()` d'abord, puis la fiche (`for update`). L'effacement
automatique suit le même ordre. Voir « Ordre des verrous » dans [lot-2.md](lot-2.md).

## Mise en place

[Migration du lot 6](migrations/lot-6.md). Les colonnes pour une base neuve sont dans [database-schema.sql](database-schema.sql).
