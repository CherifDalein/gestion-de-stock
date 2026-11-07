# Lot 1 — règles de gestion

Ce document décrit les règles métier livrées avec le lot 1. Le schéma et sa
migration sont décrits dans [migrations/lot-1.md](migrations/lot-1.md).

## Coût moyen pondéré

Chaque produit porte un coût moyen pondéré (CMP), `produit.cout_moyen`, en GNF
avec deux décimales. Chaque nouvelle ligne de vente fige ce coût dans
`detail_vente.cout_unitaire` : c'est la base du calcul du bénéfice brut,
Σ (prix unitaire − coût unitaire) × quantité.

`prix_achat` garde son rôle : c'est le **dernier** prix d'achat, proposé lors
du prochain achat. Le CMP est une valeur distincte qui n'apparaît pas encore à
l'écran.

### Formule

À chaque entrée en stock (achat, ou stock initial saisi au démarrage de la
boutique), pour chaque ligne :

```
nouveau CMP = (stock avant × CMP actuel + quantité entrée × prix d'achat unitaire)
              / (stock avant + quantité entrée)
```

Le calcul est exact en décimal ; le résultat est arrondi **une seule fois** au
centime, au plus proche (une demie est arrondie vers le haut). Exemples :

| Stock avant | CMP actuel | Entrée | Nouveau CMP |
|---:|---:|---|---:|
| 10 | 1 000,00 | 10 × 2 000 | 1 500,00 |
| 7 | 1 000,00 | 10 × 2 000 | 1 588,24 (1 588,235…) |
| 3 | 1 000,01 | 3 × 1 000,00 | 1 000,01 (1 000,005) |
| 0 | 1 500,00 | 5 × 3 000 | 3 000,00 |

Les lignes d'un même achat sont appliquées l'une après l'autre, dans l'ordre
de saisie.

### Cas particuliers

- **Stock nul ou négatif avant l'entrée, ou CMP inconnu** : le CMP devient le
  prix d'achat de l'entrée. C'est le cas du premier achat, du stock initial et
  d'un nouvel achat après épuisement du stock.
- **CMP à zéro avec du stock** : c'est une vraie valeur (marchandise reçue
  gratuitement, ou prix d'achat nul avant la migration) ; elle est pondérée
  normalement.
- **Vente** : la quantité diminue, le CMP ne change pas. La ligne de vente
  enregistre le CMP lu sous le verrou du produit, au moment de la vente.
- **Ajustement d'inventaire** : la quantité change, le CMP reste identique.
  Les corrections manuelles sont aujourd'hui désactivées ; les ajustements
  historiques du journal sont pris en compte de la même façon lors d'un
  recalcul.
- **Service** : une ligne de vente d'un produit de nature `SERVICE` fige aussi
  `cout_moyen`, qui contient le coût fixe du service. Une entrée ne pondère
  jamais le coût d'un service.
- **Les ventes déjà enregistrées ne sont jamais réécrites** : leur
  `cout_unitaire` reste celui du jour de la vente, même si le CMP est ensuite
  recalculé.

### Correction d'un ancien achat

Modifier un achat (`/achats/modifier/…`) recalcule le CMP de chaque produit
présent dans l'ancienne ou la nouvelle version de l'achat, en rejouant tout son
historique avec la formule ci-dessus :

1. le stock initial, toujours en premier (il est réservé au démarrage) ;
2. puis, par date : les lignes d'achat (entrées valorisées), les ajustements
   d'inventaire (quantité seule) et les lignes de vente (sorties).

L'ordre est déterministe. À date égale, les entrées passent avant les
ajustements puis les sorties, et ensuite par identifiant de document et de
ligne. Un document historique sans date est placé avant les documents datés.
Le journal d'inventaire est daté en UTC ; ses dates sont converties à l'heure
du serveur, celle des achats et des ventes, avant le tri. Les achats et les
ventes sont datés sous le verrou du produit : rejouer un historique non
modifié redonne exactement le CMP calculé au fil des opérations.

Le stock que l'historique n'explique pas, présent avant l'installation du
journal d'inventaire, est placé au début du rejeu et valorisé au premier coût
d'entrée connu du produit. C'est une approximation. Un écart négatif ne change
que la quantité et n'a donc aucun effet sur le CMP.

### Concurrence

Les achats, ventes, stocks initiaux et corrections verrouillent déjà les
produits concernés (verrou pessimiste, toujours dans le même ordre) avant de
modifier le stock. Le CMP est lu et écrit sous ce même verrou : un achat et une
vente simultanés sur le même produit sont appliqués l'un après l'autre, et la
ligne de vente fige le coût correspondant à cet ordre. Lors d'une correction,
l'historique est relu avec des lectures verrouillées, qui voient aussi les
opérations validées après le début de la transaction en REPEATABLE READ
(MariaDB).

### Ventes antérieures au lot 1

L'historique des coûts au moment de chaque vente n'était pas conservé avant le
lot 1. La migration a donc rempli `cout_unitaire` des ventes existantes, et
`cout_moyen` des produits, avec le prix d'achat **actuel** du produit. Le
bénéfice calculé sur ces ventes est une approximation issue de la migration ;
seules les ventes enregistrées après le lot 1 portent leur vrai coût moyen.

### Vérifications

```sh
./gradlew test --tests '*CoutMoyen*'
./gradlew coutMoyenServiceMariaDbTest
```

Le test MariaDB crée puis supprime uniquement un schéma temporaire
`stock_cout_moyen_service_test_*`. La variable
`COUT_MOYEN_TEST_DB_URL=jdbc:mariadb://127.0.0.1:13306/` peut sélectionner un
serveur local isolé.
