# Lot 2 — règles de gestion

Ce document décrit les règles de gestion livrées par le lot 2. Le schéma qu'il
utilise est préparé par la [migration du lot 2](migrations/lot-2.md).

## Vendeur des ventes

Le vendeur d'une vente est le **compte connecté** qui la saisit, gérant ou
caissier. Il n'existe pas de fiche vendeur : le vendeur est une référence vers
`utilisateur` (`vente.vendeur_id`). Il n'est jamais choisi dans un formulaire ni
envoyé par le navigateur.

### Enregistrement

- **Vente.** `VenteService.effectuerVente` lit le compte connecté côté serveur
  (le même que celui qui signe le mouvement de caisse de la vente) et le place
  dans `vente.vendeur`, quelle que soit la valeur que l'objet reçu portait.
  Un devis transformé en vente passe par ce même service : son vendeur est le
  compte qui valide la vente, pas l'auteur du devis.
- **Achat.** `AchatService.enregistrerAchat` place de la même façon le compte
  connecté dans `achat.utilisateur`. Une **modification d'achat ne change pas
  l'auteur** : seule la création le renseigne.
- **Liste blanche des formulaires.** `FormBindingAdvice` n'accepte ni `vendeur`
  (ni `vendeur.id`, `vendeur.nom`…) sur une vente, ni `utilisateur` sur un achat,
  à la création comme à la modification : le champ est refusé avec une erreur 400
  et rien n'est enregistré. Les tests `VendeurDesVentesTests` et
  `FormBindingAdviceTests` le vérifient.
- **Ventes et achats anciens.** La migration n'a rempli `vendeur_id` et
  `utilisateur_id` que lorsqu'un mouvement de caisse nominatif existait ; sinon la
  colonne est vide. Un document sans vendeur ni auteur affiche **« Non renseigné »**.

### Affichage

Le nom affiché est le nom du compte (`utilisateur.nom`), comme dans l'en-tête de
l'application. Un compte désactivé depuis garde son nom sur ses anciennes ventes.

| Écran | Affichage |
|---|---|
| Journal des ventes (`/ventes`) | colonne « Vendeur » ; ligne « Vendeur : … » dans le détail d'une vente ; visibles du gérant et du caissier |
| Facture de vente imprimée (`/factures/vente/{id}`) | ligne « Vendeur » dans les informations du document |
| Journal des achats (`/achats`, gérant) | colonne « Saisi par » |

Les noms sont insérés par Thymeleaf (`th:text`), jamais comme HTML.

### Filtre par vendeur (gérant)

Dans le journal des ventes, le gérant dispose d'une liste « Vendeur » (paramètre
`vendeur`, identifiant d'un compte). Elle se combine avec le filtre « Échues non
soldées ».

- Sont proposés **les comptes de la boutique du gérant**, y compris les comptes
  désactivés (ils ont pu saisir des ventes, signalés « compte désactivé »).
- Le paramètre est validé côté serveur : une seule valeur, numérique, désignant
  un compte de la boutique. Toute autre valeur (texte, valeurs multiples,
  identifiant inexistant ou d'une autre boutique) est **ignorée** : la liste
  complète de la boutique s'affiche avec l'avertissement « Ce vendeur n'existe pas
  dans votre boutique ». La réponse est identique pour un compte inexistant et pour
  celui d'une autre boutique, donc rien ne révèle son existence. Une valeur vide
  signifie « Tous les vendeurs ».
- Le **caissier ne voit pas ce filtre** et le paramètre est ignoré sans
  avertissement : la liste qu'il voit (toutes les ventes de la boutique) ne change pas.
- Il n'existe pas de filtre sur les ventes sans vendeur.

### État « Ventes par vendeur » (gérant)

Page `/etats/ventes-par-vendeur`, lien « Ventes par vendeur » de la section
Documents du menu. Elle est réservée au gérant : la configuration de sécurité
(`/etats/**` : `ADMIN`) renvoie 403 au caissier, y compris pour tout sous-chemin.

**Période.** Deux dates « Du » et « Au » (au inclus). Par défaut, le mois en cours,
du 1er au dernier jour. La période est **semi-ouverte** comme celle du
[tableau de bord](lot-1.md#périodes) : de « du » à 0 h inclus jusqu'au lendemain
de « au » à 0 h exclu, en heure locale du serveur. Une vente du 31 à 23 h 59 min 59
compte pour le 31 ; une vente du lendemain à minuit n'en fait pas partie. Les dates
sont validées par le serveur : une seule valeur `AAAA-MM-JJ` réelle, de 2000 à 9998,
« du » au plus tard égal à « au ». Sinon le mois en cours est affiché avec un
avertissement, sans erreur.

**Lignes.** Pour chaque vendeur de la boutique :

| Colonne | Définition |
|---|---|
| Ventes | nombre de ventes de la période |
| Chiffre d'affaires | Σ `vente.montant_total`, réglées ou non |
| Bénéfice brut | Σ (`prix_unitaire` − `cout_unitaire`) × `quantite` des lignes de ces ventes, au coût figé à la vente comme le tableau de bord ; une ligne sans coût compte pour 0 et le nombre de ces lignes est signalé sous le montant |
| Panier moyen | chiffre d'affaires ÷ nombre de ventes, arrondi une fois au centime (au plus proche, demie vers le haut) ; 0 sans vente |

Quels vendeurs figurent :

- les **comptes actifs** de la boutique, même sans vente sur la période (ligne à zéro) ;
- un compte **désactivé** ou un compte d'administration de la plateforme, seulement
  s'il a vendu sur la période (un compte désactivé est marqué « compte désactivé ») ;
- une ligne **« Non renseigné »** regroupe les ventes sans vendeur, uniquement s'il en
  existe ; elle suit les vendeurs.

Les vendeurs sont triés par **chiffre d'affaires décroissant** (puis par nom). Une
ligne de **total** clôt le tableau ; son chiffre d'affaires et son bénéfice brut
égalent ceux du tableau de bord sur la même période. Un vendeur référencé qui
n'appartiendrait pas à la boutique (donnée incohérente) est compté dans « Non
renseigné » : il n'est jamais nommé et le total reste exact.

**Requêtes.** Deux requêtes agrégées de `TableauDeBordRepository`
(`ventesParVendeur` : `COUNT`, `SUM` ; `beneficeBrutParVendeur` : `SUM`, `COUNT(CASE …)`,
`GROUP BY v.vendeur.id`), toujours filtrées sur la boutique du compte connecté,
assemblées par `TableauDeBordService.ventesParVendeur`. Aucune liste de ventes n'est
chargée. Aucune migration n'est nécessaire.

**Impression.** Le bouton « Imprimer » appelle l'impression du navigateur ; la feuille
(`static/css/etats.css`, `@media print`) suit le modèle du journal de trésorerie :
menu, bouton et sélecteur de période masqués, en-tête avec la période et l'heure
d'édition, tableau avec en-tête répété et lignes insécables, A4 portrait.

### Vérifications

```sh
./gradlew test --tests '*VendeurDesVentesTests'
./gradlew test --tests '*VentesParVendeurTests'
./gradlew test --tests '*FormBindingAdviceTests'
./gradlew tableauDeBordMariaDbTest
```

`VendeurDesVentesTests` (deux boutiques, un gérant, un second gérant, un caissier, un
compte désactivé, comptes de l'autre boutique) vérifie le vendeur d'une vente saisie par
un caissier puis par un gérant, le rejet d'un champ `vendeur` ou `utilisateur` (400), la
conservation de l'auteur d'un achat modifié, l'affichage dans la liste, le détail, la
facture et le journal des achats (avec « Non renseigné » et le nom d'un compte
désactivé), le filtre du gérant, l'avertissement identique pour un compte inexistant ou
d'une autre boutique et l'absence de filtre pour le caissier. `VentesParVendeurTests`
compare chaque chiffre de l'état à un calcul fait à la main sur un jeu de données fixe
(bornes de minuit, coût absent, centimes, ligne « Non renseigné », autre boutique exclue,
période invalide, 403 du caissier). Le scénario de `TableauDeBordMariaDbTest` reprend
l'agrégat sur MariaDB.
