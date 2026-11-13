# Lot 2 — règles de gestion

Ce document décrit les règles métier livrées avec le lot 2. Le schéma de base
correspondant est décrit dans [la migration du lot 2](migrations/lot-2.md) ;
les règles du lot précédent (numéros, échéances, avances, services) sont dans
[le lot 1](lot-1.md).

## Devis

Un devis est une **proposition de prix**. Il n'a aucun effet sur le stock, la
trésorerie, les créances, les avances, le tableau de bord ni le coût moyen :
créer, prolonger, annuler ou imprimer un devis ne crée aucun mouvement de stock
ni de caisse, ne consomme aucun numéro de vente et ne clôture pas le démarrage
de la boutique. Seule la transformation en vente produit des effets, et ce sont
exactement ceux d'une vente ordinaire.

### Droits

Le gérant (ADMIN) et le caissier (CAISSIER) disposent des mêmes actions :
créer, lister, voir, imprimer, prolonger, annuler, transformer en vente. Un
compte non connecté est renvoyé vers la connexion. Tout est lu dans la boutique
du compte connecté : un devis, un client ou un produit d'une autre boutique est
introuvable (404), y compris pour imprimer, prolonger, annuler ou transformer.
Les formulaires sont protégés par CSRF et par la liste blanche de
`FormBindingAdvice` : un champ inconnu (statut, total, numéro, vente…) est
rejeté avec une réponse 400, de même qu'une valeur multiple pour un champ
unique.

### Numéro

`FD-000001`, tiré du compteur de la boutique `boutique.prochain_numero_devis`,
avec au moins six chiffres, jamais tronqué. Comme pour les ventes (`FV-`) et
les achats (`FA-`), le compteur est lu et avancé sous le verrou de la ligne
boutique, dans la transaction de la création : deux devis simultanés reçoivent
deux numéros différents, une création refusée rend son numéro, et chaque
boutique a sa propre suite. **Il n'y a pas de numéro manuel pour un devis** : le
champ `numero` est refusé (400). La numérotation des ventes et des achats n'est
pas modifiée.

### Validité et expiration

- À la création, la validité est proposée à **la date du jour + 30 jours** et
  reste modifiable. Elle doit être **supérieure ou égale à la date du devis** ;
  une date antérieure est refusée avec un message, les lignes saisies sont
  conservées.
- Un devis **En cours** dont la date de validité est **strictement antérieure**
  à aujourd'hui est **Expiré**. Le dernier jour de validité, le devis est donc
  encore valable (même règle que le retard des factures : une échéance du 7 est
  en retard à partir du 8).
- « Expiré » est un état **calculé**, jamais stocké : la colonne `statut` reste
  `EN_COURS`. Le jour est celui de l'heure du serveur, comme pour les échéances
  (`EcheanceService.aujourdHui()`, horloge `recoveryClock` injectable en test).
- Un devis expiré peut être prolongé (il redevient valable) ou annulé ; il ne
  peut pas être transformé tant qu'il n'est pas prolongé.

### Contenu

- **Client** facultatif, pris dans la boutique (un client d'une autre boutique
  donne 404).
- **Lignes** : un produit ou un service de la boutique, une **quantité entière
  supérieure à 0** et un **prix unitaire supérieur ou égal à 0** saisi (deux
  décimales au plus). Le prix de vente du produit est proposé dans le
  formulaire, mais c'est le prix saisi qui est enregistré. Un devis a au moins
  une ligne. Un produit dont les prix ne sont pas encore définis (jamais acheté
  ni stocké) ne peut pas figurer dans un devis : il ne pourrait pas être
  transformé en vente.
- **Note** facultative, 255 caractères au plus, sans caractère de contrôle.
- Le **total** est recalculé par le serveur (somme prix × quantité, en décimal
  exact) ; le navigateur n'envoie jamais le total, le numéro, la date ni le
  statut. Aucun contrôle de stock n'est fait à la création : on peut proposer
  une quantité supérieure au stock.
- Les lignes **ne se modifient pas** après la création : pour changer le
  contenu, on annule le devis et on en refait un autre.
- L'auteur (compte connecté) et la date du devis sont enregistrés. Un produit
  figurant dans un devis ne peut plus être supprimé (comme pour les achats et
  les ventes).

### États et actions

| État affiché | Condition | Actions possibles |
|---|---|---|
| En cours | statut `EN_COURS`, validité du jour ou postérieure | voir, imprimer, prolonger, annuler, transformer |
| Expiré | statut `EN_COURS`, validité antérieure au jour | voir, imprimer, prolonger, annuler |
| Transformé | statut `TRANSFORME`, vente liée | voir, imprimer, lien vers la vente |
| Annulé | statut `ANNULE` | voir, imprimer |

Un devis transformé ou annulé **ne se modifie plus** : prolongation et
annulation sont refusées avec un message.

- **Liste** (`/devis`) : du plus récent au plus ancien, avec un filtre par état
  (`?etat=en-cours`, `expire`, `transforme`, `annule`). Un filtre inconnu
  affiche tous les devis. Le menu latéral propose « Devis » sous « Ventes ».
- **Prolonger** (`POST /devis/{id}/prolonger`, champ `dateValidite`) : nouvelle
  date supérieure ou égale à aujourd'hui **et postérieure à la validité
  actuelle** (on ne raccourcit pas un devis). Réservé à un devis En cours ou
  Expiré.
- **Annuler** (`POST /devis/{id}/annuler`, sans donnée) : réservé à un devis En
  cours ou Expiré, avec confirmation dans l'interface. Un devis transformé ne
  s'annule pas : la vente existe, elle suit ses propres règles.
- Les deux écritures verrouillent le devis : une annulation et une
  transformation simultanées n'aboutissent jamais toutes les deux.

### Impression

`/devis/{id}/imprimer` produit un document A4 construit sur le modèle des
factures : titre « Devis FD-000123 », numéro, date, **validité**, client, lignes,
total, note et la mention **« Ce devis n'est pas une facture. »** Il ne contient
**aucune information de paiement** (ni montant versé, ni reste à payer, ni
échéance, ni avance, ni mode de règlement). Un devis expiré, annulé ou
transformé porte en plus son état.

### Transformation en vente

1. Sur un devis En cours et non expiré, le bouton **Transformer en vente**
   ouvre `/ventes/nouveau?devis={id}`. Le formulaire de vente est **pré-rempli** :
   client du devis et lignes avec **les prix du devis**. Les lignes ne peuvent
   ni être retirées ni être ajoutées dans cet écran. Le vendeur saisit le
   paiement (mode, somme reçue, échéance) comme pour toute vente. Sur un devis
   transformé, annulé ou expiré, l'ouverture redirige vers la page du devis avec
   la raison du refus.
2. À l'enregistrement, le formulaire envoie l'identifiant du devis. Dans **la
   même transaction que la vente**, le service prend les verrous dans l'ordre
   habituel (boutique, jeton du formulaire et numéro de vente, voir [Ordre des verrous](#ordre-des-verrous)), **verrouille le
   devis**, puis vérifie qu'il est de la boutique (sinon 404), toujours
   `EN_COURS`, non expiré, et que les lignes envoyées sont **exactement** celles
   du devis. La vente est alors créée, le devis passe à `TRANSFORME` avec son
   `vente_id`, et la vente suit le chemin normal (numéro `FV-`, stock, caisse,
   créance, avance du client).
3. **Les prix des lignes sont ceux du devis**, relus côté serveur : les lignes
   de la vente sont reconstruites depuis le devis, jamais depuis le navigateur
   (un champ de prix envoyé est d'ailleurs rejeté en 400). Le prix actuel de la
   fiche produit n'intervient pas ; le coût unitaire de la vente est, comme
   toujours, le coût moyen au moment de la vente.
4. **Le stock est contrôlé normalement** à l'enregistrement. Un manque de stock
   refuse toute la vente, avec le message habituel (« Stock insuffisant pour… ») ;
   rien n'est écrit, le numéro de vente est rendu, le devis **reste En cours** et
   le formulaire garde ses lignes et le même identifiant pour un nouvel essai.
5. **Une seule transformation** est possible :
   - la contrainte d'unicité de `devis.vente_id` et le verrou du devis
     l'imposent même pour deux transformations simultanées ; la seconde est
     refusée (« déjà été transformé en vente ») ;
   - un **double envoi** du même formulaire est reconnu par l'identifiant de
     création (voir [doubles envois](creations-et-doubles-envois.md)) : il
     retrouve la vente déjà enregistrée, sans seconde vente ni second mouvement.
     L'identifiant du devis fait partie de l'empreinte du formulaire : réutiliser
     le même identifiant pour un autre devis est un conflit (409).
6. **Lignes et quantités identiques.** Si le formulaire contient des lignes
   retirées, ajoutées ou aux quantités changées, la vente est refusée avec le
   message « Une vente issue d'un devis doit reprendre exactement ses lignes et
   ses quantités… » : il faut annuler le devis et en faire un autre. L'ordre des
   lignes n'a pas d'importance.

Le **client** de la vente est pré-rempli avec celui du devis mais reste
modifiable (un devis sans client peut être transformé en vente à crédit pour un
client choisi à ce moment). Le total de la vente est toujours celui du devis.

### Limites connues

- La création d'un devis n'a pas d'identifiant anti-doublon : le bouton est
  bloqué après le premier clic, mais deux envois distincts créent deux devis
  (inoffensifs, puisqu'un devis n'a aucun effet).
- La facture de vente ne mentionne pas le devis d'origine ; le lien existe dans
  l'autre sens (le devis transformé renvoie vers sa vente).

### Vérifications

```sh
./gradlew test --tests '*DevisTests'
./gradlew test --tests '*StockConcurrencyTests'
./gradlew creationOperationsMariaDbTest
node --test src/test/js/*.cjs
```

Les tests H2 (`DevisTests`) couvrent, sur deux boutiques et avec les rôles
gérant et caissier : l'absence d'effet d'un devis sur le stock, la caisse, les
créances, le tableau de bord et les compteurs ; la numérotation `FD-`
consécutive et indépendante ; la validité par défaut, saisie et refusée ;
l'expiration autour de la date avec une horloge fixée et la prolongation ; la
transformation aux prix du devis après un changement du prix du produit ;
le passage à `TRANSFORME` avec la vente ; la seconde transformation, le devis
expiré ou annulé, le manque de stock, les lignes modifiées et le double envoi ;
deux transformations simultanées, et une annulation simultanée d'une
transformation ; l'impression sans information de paiement ; l'isolation entre
boutiques ; les droits, le CSRF et le rejet des champs inconnus.

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

## Retour de vente

Un client rapporte de la marchandise d'une vente déjà enregistrée. Le retour réduit d'abord la dette du client sur cette
vente, puis l'argent est rendu ou gardé en avoir. Le symétrique côté achats est décrit dans [Retour d'achat](#retour-dachat).

### Droits

Réservé au gérant (`ADMIN`) : le bouton « Retour » du détail d'une vente, `/ventes/{id}/retour`, `/retours` et
`/retours/{id}/imprimer` répondent 403 au caissier (règle dans `SecurityConfig`, contrôle répété dans le service). Une vente
ou un retour d'une autre boutique est introuvable (404). Le formulaire n'accepte que `lignes[i].detailVenteId`,
`lignes[i].quantite`, `motif`, `modeRemboursement`, `comptePaiement` et `retoursConnus` (autre champ ou valeur répétée : 400) ;
montants, prix, coûts, dates et numéro sont toujours calculés côté serveur.

### Règles

- Un retour porte sur **une** vente de la boutique, ligne par ligne. La quantité est un entier ≥ 0, au plus la quantité vendue
  moins ce qui a déjà été retourné sur cette ligne (tous retours confondus) ; au moins une quantité doit être > 0.
- Montant du retour **R** = Σ quantité × prix unitaire de la ligne de vente. Le prix **et le coût unitaire** de la ligne sont
  recopiés sur la ligne de retour (`detail_retour`) : ils ne changent plus.
- **Total effectif** = `montant_total` − `montant_retours`. **Reste à payer** = max(0, total effectif − versé − avance
  utilisée). Le surplus d'origine reste max(0, versé − `montant_total`) avec le total d'origine, sinon il serait compté
  deux fois.
- Avec **D** = reste à payer juste avant le retour : la part **imputée** = min(R, D) réduit la dette, sans aucun
  mouvement d'argent ; l'**excédent** E = R − min(R, D) est soit **rendu** (`montant_rembourse` = E, sortie de trésorerie
  de source `RETOUR_VENTE` sur le compte choisi, liée au retour et au gérant), soit **gardé en avoir** (`montant_avoir` = E,
  aucun mouvement). Si E = 0, aucun choix n'est demandé. Le choix se fait à chaque retour.
- Une vente **sans client** ne peut être remboursée qu'en argent (l'avoir est refusé).
- **Avance du client** = formule existante + Σ `montant_avoir` de ses retours de vente. Elle est consommée
  automatiquement par la vente à crédit suivante, comme un surplus.
- **Stock** : un produit en stock reprend la quantité retournée. Le **CMP** reçoit une entrée au **coût figé** de la ligne :
  (stock × CMP + quantité × coût figé) / (stock + quantité), jamais au CMP du jour. Le recalcul depuis l'historique
  (`CoutMoyenService`) rejoue les retours comme des entrées datées de `date_retour`. Un service retourné ne touche ni le
  stock ni le coût. Une ligne migrée sans coût figé remet la quantité en stock sans changer le CMP.
- **Tableau de bord et état « Ventes par vendeur »** : chiffre d'affaires et bénéfice brut sont nets des retours, comptés à
  **la date du retour** en négatif (CA − R ; bénéfice − Σ (prix − coût figé) × quantité). Dans l'état par vendeur le retour est
  attribué au vendeur de la vente d'origine ; la ligne « Non renseigné » s'applique aux ventes sans vendeur.
- **Numéro** `RV-000001`, consécutif par boutique (`boutique.prochain_numero_retour_vente`), rendu en cas de rollback.
- **Documents** : « Bon de retour » (ou « Avoir » quand une part est gardée en avoir) A4 imprimable, lignes « Retours » et
  « Total net » et numéros de retour sur la facture et le détail de la vente.

### Exemples

1. **Vente payée.** Vente 100 000 payée 100 000, retour de 30 000 : D = 0, imputé 0, E = 30 000. En argent : sortie de caisse de
   30 000, reste 0, chiffre d'affaires net 70 000.
2. **Vente à crédit.** Vente 100 000, versé 20 000 (D = 80 000), retour de 30 000 : imputé 30 000, E = 0. Aucun choix, aucun
   mouvement de trésorerie ; reste 50 000 et créance du client diminuée de 30 000.
3. **Avance et avoir.** Vente 100 000, versé 120 000 (avance 20 000), retour de 30 000 gardé en avoir : D = 0, E = 30 000.
   L'avance passe à 20 000 + 30 000 = 50 000 ; une vente à crédit de 50 000 la consomme entièrement (reste 0).
   Cas mixte : vente 100 000 avec 80 000 versés (D = 20 000), retour de 50 000 : imputé 20 000, E = 30 000 rendus ou en avoir.

### Verrous et concurrence

Ordre : boutique → **vente** → client → produits (par identifiant), comme toute écriture métier ; voir [Ordre des verrous](#ordre-des-verrous).
Deux retours simultanés sur la même vente, ou un retour et un règlement de cette vente, s'exécutent l'un après l'autre. Le retour
relit sous verrou les quantités déjà retournées : au plus la quantité vendue passe. La transaction du retour est en **READ COMMITTED**
(voir [Isolation](#isolation)).

### Double envoi

Le jeton de `OperationCreationService` suppose une table et une clé propres aux créations d'achats et de ventes ; le schéma du lot
n'ajoute que deux colonnes. Le contrôle équivalent est le champ caché `retoursConnus` : nombre de retours de la vente au moment de
l'ouverture du formulaire, comparé sous le verrou de la vente. Un second envoi du même formulaire, ou d'un formulaire ouvert
avant le retour d'un autre compte, répond 409, n'enregistre rien et rouvre le formulaire à jour. Le bouton est aussi désactivé
dès le premier envoi.

### Vérifications

```sh
./gradlew test --tests '*RetourVenteTests'
node --test src/test/js/retour-vente.test.cjs
./gradlew lot2MariaDbTest retourVenteMariaDbTest avancesPartenairesMariaDbTest coutMoyenServiceMariaDbTest tableauDeBordMariaDbTest creationOperationsMariaDbTest
```

`RetourVenteTests` couvre les trois exemples ci-dessus, la vente payée par avance, la vente sans client, les quantités
(dépassement, retours successifs, retours simultanés), le stock et le coût figé avec le rejeu de l'historique, le service,
le tableau de bord et l'état par vendeur à la date du retour, les droits, l'isolation entre deux boutiques, les champs inconnus,
les numéros consécutifs et le double envoi. `RetourVenteMariaDbTest` rejoue la concurrence avec les verrous InnoDB.

### Limites connues

Les relevés cumulés de client affichent encore le « Montant total » d'origine par facture ; le retour y apparaît par le reste
et l'avance. Un mois où ventes et retours s'annulent exactement compte comme « vide » dans le graphique. Les retours d'achat et
les avoirs fournisseur sont décrits dans [Retour d'achat](#retour-dachat).

## Retour d'achat

La boutique rend à son fournisseur de la marchandise qu'il lui a livrée, sur un achat déjà enregistré (« retour au
fournisseur »). C'est le symétrique du retour de vente : le retour réduit d'abord ce que la boutique doit au fournisseur sur
cet achat, puis, pour ce qui était déjà payé, le fournisseur rend l'argent ou le garde en avoir. Le formulaire, les verrous, le
contrôle de double envoi, le niveau d'isolation et les tests suivent ceux du retour de vente.

### Droits

Réservé au gérant (`ADMIN`) : le bouton « Retour au fournisseur » de la liste des achats, `/achats/{id}/retour`, `/retours` et
`/retours/{id}/imprimer` répondent 403 au caissier (règle `/achats/**` et `/retours/**` dans `SecurityConfig`, contrôle répété
dans le service). Un achat ou un retour d'une autre boutique est introuvable (404). Le formulaire n'accepte que
`lignes[i].detailAchatId`, `lignes[i].quantite`, `motif`, `modeRemboursement`, `comptePaiement` et `retoursConnus` (autre champ
ou valeur répétée : 400) ; montants, prix, coûts, dates, fournisseur et numéro sont toujours calculés côté serveur.

### Règles

- Un retour porte sur **un** achat de la boutique, ligne par ligne. La quantité est un entier ≥ 0, au plus **le plus petit** de
  (quantité achetée − quantités déjà retournées sur cette ligne, tous retours confondus) et du **stock actuel** du produit : on
  ne renvoie pas au fournisseur ce qui a déjà été vendu. Le stock est relu sous le verrou du produit ; si l'achat a plusieurs
  lignes du même produit, leurs quantités se cumulent contre ce stock. Au moins une quantité doit être > 0. Un achat ne contient
  jamais de service : ce cas est ignoré.
- Montant du retour **R** = Σ quantité × prix unitaire de la ligne d'achat. `detail_retour` garde ce prix unitaire et, à titre
  d'information seulement (`cout_unitaire`), le coût moyen du produit juste avant le retour.
- `achat.montant_retours` += R. **Reste à payer** = max(0, `montant_total` − `montant_retours` − versé − avance utilisée). Le
  trop-payé d'origine reste max(0, versé − `montant_total`) avec le total d'origine, sinon il serait compté deux fois.
- Avec **D** = reste à payer juste avant le retour : la part **imputée** = min(R, D) réduit la dette envers le fournisseur, sans
  aucun mouvement d'argent ; l'**excédent** E = R − min(R, D) (marchandise déjà payée) est soit **rendu par le fournisseur**
  (`montant_rembourse` = E, mouvement de trésorerie **entrant** de E, source `RETOUR_ACHAT`, sur le compte choisi, lié au retour
  par `retour_id` et au gérant), soit **gardé en avoir chez le fournisseur** (`montant_avoir` = E, aucun mouvement). Si E = 0,
  aucun choix n'est demandé. Le choix se fait à chaque retour. L'entrée n'est jamais rattachée à l'achat ni au fournisseur : elle
  ne figure ni parmi les versements de l'achat ni parmi les règlements du solde de départ.
- **Avance chez un fournisseur** = formule existante (max(0, solde de départ) + Σ trop-payés − Σ avance utilisée) + Σ
  `montant_avoir` de ses retours d'achat. `SoldePartenaireRepository` la calcule pour les deux partenaires avec la même requête
  (colonne `client_id` + type `VENTE`, ou `fournisseur_id` + type `ACHAT`), en agrégat (`factures`, utilisé par la liste des
  fournisseurs, le total `avancesFournisseurs` et le tableau de bord) comme en lecture verrouillée
  (`facturesVerrouillees`, utilisée par `avanceDisponibleVerrouillee`). L'avance est consommée automatiquement par l'achat à
  crédit suivant, comme un trop-payé.
- **Stock** : `quantite` −= quantité retournée.
- **Coût moyen**, avec q la quantité retournée, p le prix de la ligne d'achat et S le stock avant le retour :
  - S − q > 0 : nouveau CMP = (S × CMP − q × p) / (S − q), calculé exactement puis **arrondi une seule fois** (HALF_UP, 2 décimales) ;
  - S − q = 0 (le stock est vidé) : le CMP **reste inchangé** ;
  - si le résultat est négatif (on rend plus cher que ce que vaut le reste du stock) : CMP = **0**, jamais négatif.

  Le **recalcul depuis l'historique** (`CoutMoyenService.recalculer`, utilisé par la modification d'un autre achat) rejoue les
  retours d'achat comme des **sorties** datées de `date_retour`, valorisées au prix de la ligne d'achat, avec exactement la même
  fonction : il redonne la valeur du calcul au fil de l'eau. À date égale, le retour passe après les entrées, ajustements et ventes.
- **Achat figé** : un achat qui a au moins un retour **ne peut plus être modifié**. `/achats/modifier/{id}` (GET et POST) refuse
  avec le message « Cet achat a déjà fait l'objet d'un retour au fournisseur : il ne peut plus être modifié. Ses lignes, ses prix
  et ses quantités sont figés. » et la liste n'offre plus le bouton « Modifier ». C'est la règle la plus sûre : modifier remplace
  les lignes (nouveaux identifiants) et recalcule le stock et le CMP depuis l'achat entier, ce qui n'a plus de sens quand une
  partie de la marchandise est déjà repartie ; les retours, les prix et les quantités restent ainsi cohérents avec la facture.
  Le règlement du reste à payer reste possible.
- **Numéro** `RA-000001`, consécutif par boutique (`boutique.prochain_numero_retour_achat`, compteur distinct de celui des
  `RV-`), rendu en cas de rollback.
- **Documents** : « Bon de retour fournisseur » (ou « Avoir fournisseur » quand une part est gardée en avoir) A4 imprimable ;
  lignes « Retours » et « Total net » et numéros de retour sur la facture d'achat ; mention « − retours » dans la liste des
  achats et ligne « Retours au fournisseur » dans la page de règlement. La liste `/retours` réunit les retours de vente et
  d'achat avec un filtre Tous / Vente / Achat (`?type=vente` ou `?type=achat`).
- **Tableau de bord** : rien ne change à part ce qui suit de lui-même. La valeur du stock baisse du stock retiré au CMP corrigé ;
  les dettes fournisseurs baissent de la part imputée ; le chiffre d'affaires, le bénéfice brut, les dépenses (opérations de type
  `DEPENSE`) et l'état « Ventes par vendeur » ne comptent que les retours de **vente** (requêtes filtrées sur `type = VENTE`) et
  ne bougent donc pas. L'argent rendu par le fournisseur est une entrée de trésorerie, pas un revenu.

### Exemples

1. **Achat payé.** Achat 100 000 payé 100 000, retour de 30 000 remboursé : D = 0, imputé 0, E = 30 000. Entrée de caisse de
   30 000 (source `RETOUR_ACHAT`), reste 0, stock −3 unités.
2. **Achat à crédit.** Achat 100 000, versé 20 000 (D = 80 000), retour de 30 000 : imputé 30 000, E = 0. Aucun choix, aucun
   mouvement de trésorerie ; reste 50 000 et dette envers le fournisseur diminuée de 30 000 (80 000 → 50 000).
3. **Avoir.** Achat 100 000, versé 120 000 (avance 20 000), retour de 30 000 gardé en avoir : D = 0, E = 30 000. L'avance chez le
   fournisseur passe à 20 000 + 30 000 = 50 000 ; un achat à crédit de 50 000 la consomme entièrement (reste 0, aucun mouvement).
   Cas mixte : achat 100 000 avec 80 000 versés (D = 20 000), retour de 50 000 : imputé 20 000, E = 30 000 rendus ou en avoir.
4. **Coût moyen.** 10 unités à 1 000 puis 10 à 2 000 : CMP 1 500, stock 20. Retour des 10 à 2 000 : (20 × 1 500 − 10 × 2 000) / 10
   = **1 000**. Si, avant, 10 unités avaient été vendues (stock 10, CMP 1 500), le même retour vide le stock : le CMP reste
   **1 500**. Avec 10 à 1 000 puis 10 à 3 000 (CMP 2 000) et 8 vendues (stock 12, valeur 24 000), le retour des 10 à 3 000 donne
   (24 000 − 30 000) / 2 < 0 : le CMP est ramené à **0**.
5. **Stock actuel.** Achat de 10, puis 105 unités vendues sur un stock de 110 : il reste 5 en stock, le retour est plafonné à 5
   même s'il reste 10 à retourner sur la ligne.

### Verrous et concurrence

Ordre : boutique → **achat** → fournisseur → produits (par identifiant), comme toute écriture métier ; voir [Ordre des verrous](#ordre-des-verrous).

- Deux retours simultanés sur le même achat, ou un retour et un règlement de cet achat, s'exécutent l'un après l'autre sur le
  verrou de l'achat et relisent sous ce verrou les quantités déjà retournées : au plus la quantité achetée passe. Un retour et une
  vente simultanés de tout le stock se départagent sur le verrou du produit : un seul passe, le stock ne devient jamais négatif.
- **Modification d'un achat** (`modifierAchat`) : même ordre que le retour (boutique, achat, fournisseur, produits). Elle relit ensuite
  sous verrou les retours de l'achat et refuse s'il y en a un : soit la modification passe d'abord (le retour est alors refusé, ses
  lignes ont changé), soit le retour passe d'abord (la modification est refusée).
- La transaction du retour et celle de la modification d'achat sont en **READ COMMITTED** (voir [Isolation](#isolation)).

### Double envoi

Même contrôle que le retour de vente : le champ caché `retoursConnus` (nombre de retours de l'achat à l'ouverture du formulaire)
est comparé sous le verrou de l'achat. Un second envoi du même formulaire, ou d'un formulaire ouvert avant le retour d'un autre
compte, répond 409, n'enregistre rien et rouvre le formulaire à jour ; le bouton est aussi désactivé dès le premier envoi. Comme
pour la vente, ce contrôle passe avant les plafonds : la quantité n'est donc jamais dépassée par deux envois simultanés, mais le
second échoue d'abord sur le formulaire périmé.

### Vérifications

```sh
./gradlew test --tests '*RetourAchatTests' --tests '*CoutMoyenCalculTests'
node --test src/test/js/retour-achat.test.cjs
./gradlew lot2MariaDbTest retourAchatMariaDbTest retourVenteMariaDbTest avancesPartenairesMariaDbTest coutMoyenServiceMariaDbTest tableauDeBordMariaDbTest creationOperationsMariaDbTest
```

`RetourAchatTests` couvre les trois exemples de montants, l'argent rendu sur un autre compte, l'excédent sans choix, les
quantités (dépassement, retours successifs, retours simultanés), le plafond du stock après une vente, le coût moyen au fil de
l'eau **et** rejoué (retour du dernier achat, arrondi, stock vidé, résultat négatif ramené à 0), l'achat figé (GET, POST, service,
bouton), le tableau de bord, les droits, l'isolation entre deux boutiques, les champs inconnus, les numéros par boutique, le
double envoi et les documents. `RetourAchatMariaDbTest` rejoue la concurrence avec les verrous InnoDB : retours simultanés sur un
même achat, retour et règlement, retours sur deux achats du même fournisseur, retour en avoir et achat à crédit, retour et vente
du dernier stock, retour et modification du même achat (cinq fois).

### Limites connues

- Les relevés cumulés de fournisseur affichent encore le « Montant total » d'origine par facture ; le retour y apparaît par le
  reste et l'avance.
- Un retour d'achat n'est pas annulable ; une correction passe par un nouvel achat ou un ajustement d'inventaire.

## Annulation d'un règlement

Le gérant peut annuler un **règlement ultérieur** : encaissement d'une vente, paiement d'un achat, règlement du solde de départ d'un
client ou d'un fournisseur. L'argent s'est trompé de compte, de client ou de montant : on le remet comme avant, sans rien effacer.

### Droits

Réservé au gérant (`ADMIN`) : un caissier reçoit 403 sur les deux pages (confirmation et envoi), et le bouton « Annuler » ne
s'affiche pas pour lui. Un mouvement, une vente, un achat, un client ou un fournisseur d'une autre boutique donne 404. Le jeton CSRF
est exigé. Le formulaire n'accepte que le champ `motif` : tout autre champ donne 400.

### Ce qui s'annule, ce qui ne s'annule pas

| Mouvement | Annulable ? |
|---|---|
| Règlement ultérieur d'une vente (source `REGLEMENT_VENTE`) ou d'un achat (`REGLEMENT_ACHAT`) | oui |
| Règlement du solde de départ d'un client (`SOLDE_CLIENT`) ou d'un fournisseur (`SOLDE_FOURNISSEUR`) | oui |
| Paiement saisi à la création d'une vente ou d'un achat (`VENTE`, `ACHAT`) | non : se corrige par un retour |
| Dépense, transfert, retour, fonds de départ, ajustement | non |
| Mouvement déjà annulé | non : « Ce règlement a déjà été annulé. » |
| Mouvement d'annulation (`ANNULATION`) | non |

Jusqu'ici le règlement ultérieur et le paiement de création portaient la même source (`VENTE` ou `ACHAT`). Les règlements écrits
par la page de règlement ont désormais leur propre source ; la migration reclasse les anciens (voir
[Lot 2 : préparation du schéma](migrations/lot-2.md#reclassement-des-règlements)).

### Effets (une seule transaction)

1. Un **mouvement inverse** est créé : montant opposé, même compte, source `ANNULATION`, lien `annulation_de_id` vers l'origine,
   motif, utilisateur et date. L'origine n'est jamais modifiée ni supprimée.
2. Facture : `montant_verse` diminue du montant annulé. Si la facture n'avait plus d'échéance et que son reste redevient positif,
   une échéance est proposée (date du jour plus le délai de la boutique) ; une échéance existante est conservée.
3. Solde de départ : le reste à régler est rétabli. La règle « solde figé après usage » ne change pas : un solde déjà réglé,
   même annulé ensuite, ne redevient pas modifiable.
4. Un encaissement annulé est une **sortie** : même contrôle de solde que les dépenses et transferts (« Solde insuffisant sur le
   compte … »). Un paiement fournisseur annulé est une entrée.

Le motif est obligatoire (3 à 255 caractères, sans caractère de contrôle).

### Refus

- **Retour postérieur** : si un retour daté du règlement ou après existe sur la facture, l'annulation est refusée. Annuler ferait
  disparaître l'argent sur lequel le retour s'est appuyé ; on traite alors la facture par un nouveau retour ou règlement.
- Source non annulable, déjà annulé, motif invalide, solde du compte insuffisant (encaissement), solde de départ qui ne couvre plus
  le montant : message clair, rien n'est écrit.
- Double envoi ou deux gérants en même temps : la contrainte unique `uk_mouvement_caisse_annulation` et les verrous garantissent une
  seule annulation ; le second envoi reçoit 409 avec le message « déjà annulé », jamais une erreur 500.

### Exemples chiffrés

- Vente de 100 000 GNF, 20 000 versés à la création, règlement de 30 000 sur la banque : reste 50 000, trésorerie +50 000. Annulation :
  mouvement de −30 000 sur la banque, montant versé 20 000, reste 80 000, trésorerie +20 000, créance du client de nouveau 80 000.
  Le paiement de 20 000 saisi à la création n'est pas annulable.
- Achat de 100 000 GNF, 20 000 versés, paiement de 30 000 sur Orange Money, puis annulation : entrée de +30 000 sur Orange Money,
  reste dû 80 000.
- Client devant 100 000 GNF de solde de départ, 30 000 réglés (reste 70 000), puis annulation : reste 100 000, caisse revenue à 0.
  Pour un fournisseur à qui la boutique doit 100 000 et 40 000 payés, l'annulation est une entrée de 40 000, reste dû 100 000.

### Écrans

Les pages de règlement (vente, achat, solde de départ client et fournisseur) affichent un bouton « Annuler » sur chaque règlement
annulable (gérant seulement). Il ouvre une page de confirmation (montant, date, compte, auteur) avec le champ motif. Le journal de
caisse écrit « Annulé le … · motif : … » sur l'origine et « Annulation du mouvement du … » sur le mouvement inverse ; les totaux
comptent les deux, donc restent justes. Les relevés cumulés montrent l'annulation d'un règlement de solde de départ en négatif.

### Verrous et concurrence

L'ordre est celui de [Ordre des verrous](#ordre-des-verrous) : boutique, puis le document (vente, achat, ou partenaire du solde),
puis le partenaire, puis les comptes de trésorerie. La transaction est en `READ_COMMITTED` (comme les retours) pour relire l'état
réel après une attente de verrou.

### Vérifications

`AnnulationReglementTests` (H2) couvre : vente, achat, solde client et fournisseur ; échéance proposée ou conservée ; refus (création,
dépense, déjà annulé, annulation, retour postérieur, motif, caissier 403, autre boutique 404, champ inconnu 400, CSRF) ; deux envois
simultanés ; journal. `OrdreVerrousMariaDbTest` croise 25 fois, sur les vrais verrous InnoDB, l'annulation d'un règlement avec un
nouveau règlement de la même vente et avec un retour de la même vente.

### Limites connues

- Un retour (de vente ou d'achat) n'est pas annulable ; un retour postérieur au règlement bloque son annulation.
- Une annulation n'est pas elle-même annulable : pour remettre le règlement, on en saisit un nouveau.

## Ordre des verrous

Cette section est la seule description de l'ordre des verrous de l'application ; les autres sections et le
[lot 1](lot-1.md#verrous-et-concurrence) y renvoient. Elle remplace les descriptions locales (« document d'abord », « partenaire
puis produits »…) qui se contredisaient et produisaient de vrais interblocages InnoDB sur MariaDB.

### La règle

Toute opération d'écriture métier qui modifie l'argent, le stock, les factures, les retours, les avances ou les soldes verrouille
**d'abord la ligne `boutique` de la boutique courante** (`StockLockRepository.verrouillerBoutiqueCourante()`, `PESSIMISTIC_WRITE`),
en première instruction de sa transaction, puis, dans cet ordre et seulement ceux dont elle a besoin :

1. la ligne `boutique` (elle porte aussi les compteurs de numéros `FV-`, `FA-`, `FD-`, `RV-`, `RA-`) ;
2. le document : jeton de création, devis, vente ou achat, ou, pour les financements, l'**emprunt ou prêt** (un seul document par
   transaction) ;
3. le partenaire : fiche du client ou du fournisseur, ou **partenaire financier** (banque, associé, personnel, divers) ;
4. les factures du partenaire : lectures `FOR UPDATE` des ventes ou achats qui portent une avance (`SoldePartenaireService`),
   des retours et des règlements du solde de départ ;
5. les produits, par identifiant croissant ;
6. les comptes de trésorerie (`compte_tresorerie_boutique`), **par identifiant croissant** : une boutique peut en avoir jusqu'à douze
   (voir [Comptes de trésorerie multiples](lot-3.md#comptes-de-trésorerie-multiples)). Une écriture sur un seul compte (encaissement,
   paiement, règlement, remboursement) le verrouille en lecture partagée ; un transfert, une annulation, l'archivage d'un compte ou le
   changement de compte principal verrouillent en écriture tous les comptes concernés, triés par identifiant, en une seule fois.

Contrôles de l'étape 3b2 (voir [Contrôles](lot-3.md#contrôles)) : la validation, le refus et l'annulation d'un comptage verrouillent la
boutique, puis la **ligne du comptage** (au rang du document, étape 2), puis le compte à zéro écart de lecture exclusive (étape 6) ; deux
validations simultanées du même comptage n'en laissent donc passer qu'une, l'autre reçoit 409. Un ajustement de solde de départ
verrouille la boutique, puis le partenaire (étape 3), puis ses factures (étape 4). L'inventaire des créances ne prend que le verrou de la
boutique, car il ne modifie aucun solde.

Rapprochement bancaire (étape 3c, voir [Rapprochement bancaire](lot-3.md#rapprochement-bancaire)) : toute écriture sur un relevé verrouille la boutique, puis la **ligne du
relevé** (au rang du document, étape 2), puis les comptes (étape 6, le compte du relevé), puis les **mouvements concernés par identifiant croissant** (`RapprochementMouvementRepository.verrouillerMouvements`).
La validation, la dévalidation, un pointage, « Tout accepter » et « Enregistrer dans l'appli » suivent cet ordre. L'annulation d'un mouvement (`CaisseService.enregistrerAnnulation`, appelée par
les services qui annulent, tous en READ COMMITTED) lit le relevé du mouvement **après** le verrou de la boutique : elle voit une validation terminée et la refuse (« mouvement rapproché sur le relevé RB-… validé »)
ou, si elle passe la première, la validation est refusée parce que le mouvement a été annulé depuis le pointage. Deux validations simultanées du même relevé n'en laissent passer qu'une (409).

Rien ne doit être verrouillé avant la boutique : ni document, ni partenaire, ni compte utilisateur. Le verrou est pris une seule
fois par transaction : un second appel, par exemple depuis `CaisseService` appelé par une vente, ne fait rien et ne relit pas la
ligne (une modification non flushée, comme la clôture du démarrage, n'est donc pas perdue). Il est tenu jusqu'à la fin de la
transaction.

Pourquoi la boutique en premier : un mouvement de caisse (règlement, entrée ou sortie) a une clé étrangère vers `boutique`, qui pose
un verrou partagé sur cette ligne. Toute opération qui insère un mouvement après avoir verrouillé son document ou son partenaire
attend donc la boutique, que tient une création en attente de ce même document. Avec un ordre unique, aucune de ces attentes ne
peut former de cycle. Exemple observé avant la règle : une création d'achat tenait la boutique et attendait un achat porteur d'avance
(lecture `FOR UPDATE`), pendant qu'un retour tenait cet achat et attendait la boutique pour son numéro `RA-`.

### Opérations concernées

| Domaine | Opérations qui verrouillent la boutique en premier |
|---|---|
| Achats | création (`enregistrerAchat`), modification (`modifierAchat`), règlement (`reglerAchat`), échéance (`modifierEcheance`) |
| Ventes | création et transformation d'un devis (`effectuerVente`), règlement (`reglerVente`), échéance (`modifierEcheance`) |
| Création avec jeton | `OperationCreationService.creerAchat` et `creerVente` (la boutique avant la ligne du jeton) |
| Retours | `RetourVenteService.enregistrer`, `RetourAchatService.enregistrer` |
| Annulation de règlement | `AnnulationReglementService.annulerReglementVente`, `annulerReglementAchat`, `annulerReglementSoldeClient`, `annulerReglementSoldeFournisseur` |
| Soldes de départ | `SoldeDepartService.reglerClient` et `reglerFournisseur` ; `appliquerALaModification` (modification d'une fiche client ou fournisseur avec solde de départ) |
| Devis | création, prolongation, annulation |
| Financements (3b1) | `EmpruntService.creer`, `modifierEcheancier`, `rembourser`, `annulerRemboursement` (boutique, emprunt, partenaire, comptes) ; `OperationsFinancieresService.enregistrer`, `annuler` ; `PartenairesFinanciersService.creer`, `modifier`, `definirActif` |
| Trésorerie | `CaisseService.initialiser`, `depenser`, `transferer` et toute écriture de mouvement (`enregistrerEntree*`, `enregistrerSortie*`) |
| Inventaire et produits | `InventaireService.initialiser`, `ProduitService.modifierProduit` et `supprimerProduit` ; la fin du démarrage (`InitialisationBoutiqueService.terminer`) |
| Partenaires | `ClientService.supprimerClient`, `FournisseurService.supprimerFournisseur` |

Les lectures (`readOnly = true`) ne verrouillent rien. La création d'un produit, d'un client, d'un fournisseur ou d'une catégorie
n'attend aucun autre verrou et n'en tient aucun : elle ne verrouille pas la boutique. L'ouverture d'un formulaire de création
(`OperationCreationService.ouvrir`) insère seulement la ligne du jeton.

### Isolation

Les retours (vente et achat) et la modification d'achat restent en **READ COMMITTED**. Ils relisent les retours d'un document par
une lecture verrouillée ; sous REPEATABLE READ elle pose des verrous de plage (« gap ») sur l'index des retours, et ces verrous
croisent l'insertion d'un retour d'une **autre boutique** (même table, mêmes plages d'index). L'ordre des verrous ne protège pas ce
cas, puisque deux boutiques ne partagent aucun verrou de boutique. Vérifié sur MariaDB : sans READ COMMITTED, le scénario « deux
boutiques » de `OrdreVerrousMariaDbTest` donne un interblocage dès le premier tour (insertion dans `retour`). Avec READ COMMITTED,
les 25 tours passent, et les lectures verrouillées voient toujours les opérations validées.

### Limites connues

- **Les écritures d'une même boutique sont sérialisées** : une vente, un achat, un règlement, un retour, une opération de caisse ou
  de devis attend la fin de la précédente de sa boutique, même si elles touchent des produits ou des partenaires différents.
  Pour un commerce de un à trois postes, ces transactions durent quelques millisecondes. Deux boutiques ne s'attendent jamais.
- Les opérations de compte et d'administration (mon compte, accès des utilisateurs, paramètres de la boutique, réinitialisation du mot
  de passe, abonnement) gardent leur propre ordre (compte utilisateur, puis boutique en lecture ou en écriture) : elles ne modifient ni
  l'argent ni le stock et ne sont pas concernées par la règle.
- Toute nouvelle opération d'écriture métier doit appeler `verrouillerBoutiqueCourante()` avant tout autre verrou.

### Vérifications

```sh
./gradlew ordreVerrousMariaDbTest retourAchatMariaDbTest retourVenteMariaDbTest
```

`OrdreVerrousMariaDbTest` croise, 25 fois chacun et avec les vrais verrous InnoDB : création d'achat pour un fournisseur ayant une
avance et retour en avoir d'un de ses achats ; création de vente pour un client ayant une avance et retour de vente en avoir ;
règlement d'un achat et retour du même achat ; règlement d'une vente et retour de la même vente ; règlement du solde de départ d'un
client et vente à ce client ; règlement du solde de départ d'un fournisseur et achat chez lui ; modification d'un achat et création
d'un achat pour le même fournisseur ; annulation d'un règlement et nouveau règlement de la même vente ; annulation d'un règlement et retour de la même vente ; retours et créations croisés dans deux boutiques. Aucun ne doit produire d'erreur.
