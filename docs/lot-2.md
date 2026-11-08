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
   habituel (jeton du formulaire, boutique et numéro de vente), **verrouille le
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
vente, puis l'argent est rendu ou gardé en avoir. Les retours d'achat ne font pas partie de ce lot.

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

Ordre : **vente** (ligne verrouillée) → boutique (numéro RV) → client → produits (par identifiant). Les créations et
règlements existants prennent boutique → client → produits ; le retour ajoute seulement le verrou de la vente **en tête**.
Il n'y a pas de cycle : un règlement (`reglerVente`) verrouille la vente avant d'écrire son mouvement de caisse, qui pose un
verrou partagé sur la boutique par sa clé étrangère ; si le retour prenait la boutique avant la vente, l'un tiendrait la boutique
en attendant la vente de l'autre. Une création de vente n'a pas encore de ligne `vente` : elle ne peut pas croiser un retour sur cette
vente. Deux retours simultanés sur la même vente s'exécutent l'un après l'autre sur le verrou de la vente et relisent les
quantités déjà retournées sous ce verrou : au plus la quantité vendue passe.

La transaction du retour est en **READ COMMITTED** : en REPEATABLE READ, les lectures verrouillées des retours d'une vente
posent des verrous de plage qui provoquaient un deadlock InnoDB entre deux retours de ventes différentes (constaté sur MariaDB).

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
les avoirs fournisseur restent à faire.
