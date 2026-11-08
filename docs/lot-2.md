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
