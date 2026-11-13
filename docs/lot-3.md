# Lot 3 : trésorerie

## Comptes de trésorerie multiples

Une boutique peut avoir **plusieurs comptes de chaque type** : par exemple « Orange Money Labé » et « Orange Money Conakry », ou
« Ecobank » et « BICIGUI ». Le moyen de paiement n'est plus une énumération à trois valeurs mais un **compte nommé** de la boutique.
Les règles de calcul ne changent pas : montants, signes, annulation par contre-passation, solde = solde de départ + somme des
mouvements, un seul mouvement d'ouverture par compte.

Décisions du gérant : au plus **12 comptes par boutique** (comptes de base compris) ; un **caissier n'utilise que les comptes que le
gérant lui a autorisés**.

### Vocabulaire

| Notion | Valeurs | Où |
|---|---|---|
| **Type** de compte | `ESPECES` (« Caisse (espèces) »), `ORANGE_MONEY`, `BANQUE` (`TypeCompteTresorerie`) | colonne `type` du compte ; sert aux totaux par type du bilan |
| **Compte** | une ligne de `compte_tresorerie_boutique` : nom, numéro facultatif, type, principal, actif | `CompteTresorerieBoutique` |

L'ancienne énumération `CompteTresorerie` (`CAISSE`, `ORANGE_MONEY`, `BANQUE`) est devenue `TypeCompteTresorerie`. Le nom change parce
que l'objet « compte » existe désormais à côté du type et que garder le même nom pour les deux aurait rendu le code et les tests
ambigus. La valeur stockée pour les espèces passe de `CAISSE` à `ESPECES` ; les libellés affichés (« Caisse (espèces) », « Orange
Money », « Banque ») et les en-têtes du bilan sont inchangés. Un formulaire ouvert avant le déploiement qui enverrait encore `CAISSE`,
`ORANGE_MONEY` ou `BANQUE` reste compris : le nom d'un type désigne le **compte principal** de ce type.

### Règles d'un compte

- **Nom obligatoire** (60 caractères), unique dans la boutique **sans tenir compte de la casse ni des espaces superflus**
  (« ecobank » et « Ecobank  » sont le même nom). Colonne `nom_cle` : le nom en minuscules, qui porte l'unicité sur H2 comme sur
  MariaDB. **Numéro facultatif** (40 caractères) : numéro de compte bancaire ou de téléphone Orange Money.
- **Un compte principal par type et par boutique.** Colonne `principal_type` : elle vaut le type du compte quand il est principal et
  reste vide sinon ; l'unicité `(boutique_id, principal_type)` garantit un seul principal par type (MariaDB 10.4 n'a pas d'index
  unique partiel). Les trois comptes de base d'une boutique, « Caisse », « Orange Money » et « Banque », sont créés avec elle et sont
  principaux. Le principal d'un type est le choix par défaut dans tous les formulaires.
- **Limite de 12 comptes actifs** par boutique, comptes de base compris. Au-delà, l'ajout est refusé avec un message clair
  (« Une boutique peut avoir au plus 12 comptes actifs… ») ; la page affiche le compteur « n/12 ». Les comptes archivés ne comptent pas.
- **Solde de départ** : saisi à la création du compte (montant positif ou zéro), daté du jour, écrit comme un mouvement
  `OUVERTURE`/`SOLDE_INITIAL`. Un compte créé avec zéro peut encore recevoir son solde de départ plus tard, mais **uniquement tant
  qu'il n'a aucun mouvement** : dès qu'un mouvement existe (le solde de départ lui-même compris), il est figé et le message le dit.
  Le solde d'un compte reste calculé depuis le journal : aucun montant n'est stocké dans la table des comptes.
- **Modification** : nom et numéro. Le type d'un compte ne change jamais (l'historique et les bilans en dépendent).
- **Archivage** : réservé aux comptes dont le solde est **zéro** et qui ne sont **pas principaux**. Un compte archivé disparaît des
  listes de choix mais garde son historique, apparaît dans le journal, les états et les justificatifs, et peut être désarchivé (dans
  la limite de 12). Si un compte archivé reçoit ensuite une contre-passation (annulation d'un règlement ancien), son solde peut
  redevenir non nul : le compte reste visible dans le bilan tant que son solde n'est pas zéro.
- **Aucun compte n'est supprimé.** L'archivage est l'unique « retrait ».

### Droits

| Qui | Comptes utilisables |
|---|---|
| Gérant (ADMIN) | tous les comptes **actifs** de sa boutique ; il gère la page « Comptes de trésorerie » |
| Caissier (CAISSIER) | les comptes que le gérant a cochés pour lui (table `compte_tresorerie_autorise`) ; **s'il n'en a aucun de coché, les comptes principaux des trois types** (le comportement d'avant le lot, conservé après la migration) |

La page `/caisse/comptes` appartient au gérant (règle existante `/caisse/**`). La section « Comptes autorisés par caissier » liste
chaque caissier actif avec une case par compte actif. Cocher puis enregistrer remplace ses autorisations ; tout décocher le ramène aux
comptes principaux. Un compte archivé n'est plus utilisable, même s'il est coché.

Chaque écran qui demande un compte (vente, règlement de vente, achat, règlement d'achat, règlement d'un solde de départ, remboursement
d'un retour, dépense, transfert, ouverture) propose les comptes autorisés et actifs, **groupés par type**, avec le compte principal du
type choisi par défaut. Côté serveur, le compte reçu est toujours revérifié : un compte inconnu, archivé, non autorisé ou d'une autre
boutique donne **400** (message générique, sans indiquer à quelle boutique il appartient) et n'écrit rien. Un transfert d'un compte
vers lui-même est refusé. Les formulaires n'acceptent aucun champ inconnu (liste blanche, 400).

### Écrans et documents

- **Page « Comptes de trésorerie »** : comptes groupés par type avec le solde de chacun et le total du type, ajout, modification,
  choix du principal, archivage/désarchivage, compteur n/12, autorisations des caissiers.
- **Journal** : le nom du compte de chaque mouvement ; le filtre par compte liste les comptes de la boutique (archivés compris) ;
  les exports Excel et PDF utilisent l'identifiant du compte (`compte=<id>`).
- **Tableau de bord, bilan, états, justificatifs** : les totaux par type (trois colonnes, mêmes en-têtes qu'avant) sont inchangés pour
  une boutique qui n'a que ses comptes de base. Pour plusieurs comptes d'un même type, le détail par compte est ajouté sous le total
  du type (« Trésorerie détaillée par compte », sous-totaux « Total Orange Money »…).
- **Reçus** : le moyen de paiement affiché est le **nom du compte**.

### Verrous

Les comptes sont le dernier maillon de l'[ordre des verrous](lot-2.md#ordre-des-verrous) : boutique → document → partenaire → produits
→ **comptes, par identifiant croissant**. Une écriture sur un seul compte (encaissement, paiement, règlement, remboursement) le
verrouille en lecture partagée. Un transfert, une annulation, l'archivage d'un compte et le changement de principal verrouillent en
écriture tous les comptes concernés, triés par identifiant, en une seule requête : deux transferts croisés (A vers B et B vers A) ne
peuvent donc pas se bloquer. L'annulation d'un règlement se fait sur le **compte d'origine**, même archivé depuis.

### Limites connues

- La limite de 12 compte les comptes **actifs** ; un compte archivé sort du compteur. Lecture alternative possible (compter aussi les
  archivés) : une seule ligne à changer, à décider avec le gérant.
- Un compte archivé dont le solde redevient non nul après une annulation reste dans le bilan jusqu'au retour à zéro ; il ne peut pas
  être « réarchivé » tant que son solde n'est pas nul.
- Les contrôles `lot1Check`, `lot2Check` et `lot4Check` s'appliquent **avant** le lot 3. Une fois les anciennes colonnes supprimées,
  ils n'examinent plus les colonnes de trésorerie qu'elles citaient (voir [la migration](migrations/lot-3.md)).
- Le type d'un compte ne se change pas : pour passer de « banque » à « Orange Money », créer un nouveau compte et transférer le solde.

### Vérifications

```sh
./gradlew test --no-daemon                       # H2 : règles, droits, isolation, exports, reçus, migration
node --test src/test/js/*.cjs
./gradlew lot3MariaDbTest lot1MariaDbTest lot2MariaDbTest lot4MariaDbTest ordreVerrousMariaDbTest
```

## Financements et flux hors ventes et achats

Étape 3b1. Elle ajoute, **pour le gérant seul**, tout ce qui touche à la trésorerie sans être une vente, un achat ou une dépense courante :
les autres partenaires (banques, associés, personnel, divers), les apports et retraits d'associés, les emprunts et prêts avec leur échéancier,
et les produits et charges hors activité. Aucune règle des comptes de la 3a ne change : un flux de financement entre sur un compte ou en sort
comme n'importe quel encaissement ou paiement, avec le contrôle de solde habituel pour une sortie.

### Écrans

| Écran | Adresse | Rôle |
|---|---|---|
| Emprunts et prêts | `/financements/emprunts` | liste filtrable (nature, état), capital restant, alerte de retards |
| Fiche d'un dossier | `/financements/emprunts/{id}` | échéancier, situation, historique, reçus, exports |
| Remboursement | `/financements/emprunts/{id}/rembourser` | paiement partiel, multi-échéances ou anticipé |
| Autres partenaires | `/financements/partenaires` | soldes, fiche, relevé, archivage, exports Excel et PDF |
| Apports, retraits et autres flux | `/financements/operations` | apports, retraits, produits et charges hors activité |
| État « Emprunts et prêts » | `/etats/emprunts-prets` | situation de chaque dossier et remboursements de la période (exports Excel et PDF) |
| Justificatifs | `/etats/justificatifs` | justificatif PDF d'un partenaire financier |

Le caissier reçoit **403** sur toutes les adresses `/financements/**` ; un dossier ou un partenaire d'une autre boutique est **introuvable (404)**.
Les formulaires n'acceptent que leurs champs (liste blanche, 400 pour un champ inconnu ou répété) et exigent le jeton CSRF.

### Autres partenaires

Un partenaire a un nom (unique dans la boutique, sans tenir compte de la casse), un type (banque, associé, personnel, divers), un téléphone, une
note et un état actif ou archivé. Le type ne change plus dès qu'il a une opération ou un dossier. Il ne peut être archivé que si son solde est nul
et s'il n'a aucun emprunt ou prêt en cours ; il garde alors son historique.

**Solde d'un partenaire** (positif : la boutique lui doit de l'argent) :

```
solde = apports en compte courant − retraits en compte courant (non annulés)
      + Σ emprunts (capital restant dû + intérêts échus non payés)
      − Σ prêts   (capital restant dû + intérêts échus non payés)
```

Le capital apporté (apports et retraits de capital) est affiché à titre d'information et n'entre pas dans le solde : il appartient à l'entreprise.
Le justificatif d'un partenaire (`JS-AAAAMMJJ-P<id>`) reprend ce solde, en chiffres et en lettres, avec les signatures.

### Apports et retraits d'associé

Quatre natures : apport ou retrait **en capital** ou **en compte courant**. Le partenaire doit être un associé actif. L'apport entre sur le compte
choisi, le retrait en sort (solde suffisant exigé). Une opération s'annule par contre-passation, avec un motif de 3 à 255 caractères ; elle
reste visible dans l'historique, marquée « Annulé ».

### Emprunts et prêts

Un dossier a un numéro `EM-000001` (emprunt) ou `PR-000001` (prêt), pris dans la numérotation de la boutique sous son verrou. À la création, le
capital **entre** (emprunt) ou **sort** (prêt) du compte choisi à la date du dossier ; les frais de dossier facultatifs d'un emprunt sortent du
même compte et sont une charge financière (ils ne peuvent pas dépasser le capital).

**Échéancier** : capital constant, intérêts simples.

```
capital d'une échéance = capital ÷ n arrondi à 2 décimales, le reliquat va à la dernière échéance
intérêt d'une échéance = capital restant dû avant l'échéance × taux annuel × (1 ÷ périodes par an), arrondi à 2 décimales (HALF_UP)
périodes par an : hebdomadaire 52, mensuelle 12, trimestrielle 4
```

Exemple : 1 200 000 GNF à 12 % sur 12 mois. Chaque échéance rembourse 100 000 GNF de capital ; l'intérêt est 12 000 GNF (échéance 1),
11 000 (échéance 2), puis 1 000 de moins chaque mois jusqu'à 1 000 : 78 000 GNF d'intérêts au total. Avec un taux de 0 %, tous les intérêts sont à 0.
Exemple d'arrondi : 100 000 GNF sur 3 mois à 12 % donne des parts de capital de 33 333,33, 33 333,33 et 33 333,34 et des intérêts de 1 000,00,
666,67 et 333,33.

**Modification** : l'échéancier (dates et parts de capital) se modifie tant qu'**aucun remboursement** n'est enregistré ; le total des parts doit
rester égal au capital emprunté, et les intérêts sont recalculés. Dès qu'un remboursement non annulé existe, l'écran est fermé ; si tous les remboursements sont annulés, l'échéancier redevient modifiable.

**Remboursement** : un montant unique, imputé aux échéances dans l'ordre, **intérêts puis capital** de chaque échéance. Il peut être partiel, couvrir
plusieurs échéances ou payer en avance. Exemple : 20 000 GNF sur l'échéance 1 (capital 100 000, intérêts 12 000) paient 12 000 d'intérêts et
8 000 de capital ; il reste 92 000 de capital sur cette échéance. Un remboursement en avance ne réduit pas les intérêts prévus des échéances suivantes.
Il produit une opération « Remboursement » et deux mouvements de caisse (capital, intérêts ; un montant à 0 est omis).

**Remboursement anticipé** : la case « Remboursement anticipé » exige le montant exact du solde total (capital restant dû + intérêts des échéances
déjà échues et impayées). Les intérêts des échéances à venir sont **remis** (ramenés à 0) et le dossier est clos. Cas limite : si tout le capital est
déjà payé mais qu'il reste des intérêts futurs, le solde anticipé vaut 0 et n'est pas payable ; ils se règlent à leur échéance.

**Protection contre le double paiement** : le formulaire emporte le rang de l'échéance et le reste dû qu'il a affichés. Sous le verrou de l'emprunt,
si ces valeurs ont changé (autre remboursement entre-temps), le paiement est refusé (409) et rien n'est écrit. La répétition exacte d'un envoi (même
jeton) renvoie « déjà enregistré » sans second paiement.

**Annulation d'un remboursement** : par contre-passation de ses mouvements, du plus récent au plus ancien (on ne peut pas annuler un remboursement
antérieur tant qu'un plus récent n'est pas annulé), avec un motif de 3 à 255 caractères. Pour un prêt, l'annulation fait sortir de l'argent et exige
un solde suffisant.

**Situation** (calculée, jamais stockée) : capital restant dû, intérêts payés, prochaine échéance, échéances en retard (date passée et reste > 0),
jours de retard. Un dossier dont toutes les échéances sont payées (ou remises) est **clos automatiquement**. Les retards apparaissent sur le tableau
de bord du gérant et en tête de la liste.

**Reçu de remboursement** : PDF au format de la boutique (A4 ou ticket 80 mm) avec capital, intérêts, reste du capital après le remboursement et la
mention « ANNULÉ » si besoin. **Échéancier** : PDF et Excel depuis la fiche.

### Produits et charges hors activité

Quatre natures, distinctes des dépenses : **produit financier**, **charge financière**, **autre produit**, **autre charge**. Un produit entre sur un
compte, une charge en sort. Les intérêts d'un emprunt et les frais de dossier sont des charges financières ; les intérêts d'un prêt sont des produits
financiers. Les uns et les autres comptent dans le résultat du bilan (voir ci-dessous), sans passer par la liste des dépenses.

### Effets sur le bilan et les états (lot 4)

- **Bénéfice net** = bénéfice brut − dépenses + produits financiers − charges financières + autres produits − autres charges. Les intérêts sont comptés
  **à leur paiement** (base caisse).
- **Ne comptent pas dans le bénéfice** : le capital d'un emprunt reçu ou remboursé, le capital d'un prêt, les apports et retraits. Ils modifient la
  trésorerie, pas le résultat.
- Le bilan ajoute une section « Financements et autres flux » : apports, retraits, emprunts reçus, prêts accordés, remboursements de capital,
  intérêts et charges financières, produits financiers, autres produits et charges, nets des annulations, puis la **variation de trésorerie** (solde de
  fin − solde de début) et les « autres flux » (variation − financements).
- Exemple : un emprunt de 1 200 000 GNF puis un premier remboursement de 112 000 (100 000 de capital, 12 000 d'intérêts) font varier la trésorerie
  de +1 088 000 GNF, alors que le bénéfice net baisse de 12 000 GNF seulement.
- Le journal de caisse et les exports portent des libellés clairs : « Emprunt reçu », « Remboursement d'emprunt (capital) », « Intérêts d'emprunt
  payés », « Apport en capital »… ; le filtre de source du journal accepte ces libellés.
- L'état **Emprunts et prêts** et le **justificatif d'un partenaire** sont disponibles dans `/etats`.

### Verrous

Boutique, puis **emprunt**, puis **partenaire financier**, puis les comptes par identifiant croissant (voir l'[ordre des verrous](lot-2.md#ordre-des-verrous)).
Deux remboursements simultanés de la même échéance ne paient donc pas deux fois : le second voit le reste déjà réglé et est refusé (409).

### Limites connues

- Un emprunt ou un prêt entier ne s'annule pas, pas plus que son déblocage ou ses frais de dossier ; seuls les remboursements s'annulent. Pour une
  erreur de saisie avant tout remboursement, saisir l'opération inverse (par exemple une charge ou un apport) avec un motif clair.
- Les intérêts sont **simples**, sans capitalisation, avec trois périodicités ; pas de différé, ni de taux variable, ni de pénalités de retard.
- Les intérêts sont comptés à leur paiement, pas à leur échéance : une échéance en retard n'apparaît pas encore dans le bénéfice.
- Un partenaire doit avoir un solde nul et aucun dossier en cours pour être archivé ; ce solde ne peut pas être « forcé » à zéro.
- Une dépense, un frais de dossier et un déblocage ne sont pas annulables par les écrans de financement.
- Le gérant seul voit ces écrans ; un caissier n'a aucun accès, même en lecture.
- Un mouvement daté d'un jour passé est enregistré à midi ; daté d'aujourd'hui, à l'heure de la saisie.

## Contrôles

Étape 3b2 : comptage de caisse, correction du solde de départ, inventaire des créances et des dettes, et la mention « Saisi par ».
Aucune règle existante (reste à payer, avances, coût moyen, numérotation, retours, comptes de 3a, financements de 3b1) ne change.

### Comptage de caisse (`/controles/comptages`)

Le caissier ou le gérant compte un compte de trésorerie qu'il a le droit d'utiliser (règle de 3a : un caissier voit ses comptes autorisés ou
les comptes principaux). Il saisit le montant compté et un commentaire facultatif. L'application calcule le **solde théorique** (somme des
mouvements du compte, lue sous verrou) et l'**écart = compté − théorique**, figés au moment du comptage.

**Comptage à l'aveugle.** Le **caissier ne voit jamais** le solde théorique ni l'écart : ni en direct dans le formulaire, ni après
l'enregistrement, ni dans la liste ou le détail de ses comptages, ni dans un PDF. Il voit seulement le montant qu'il a compté, le billetage
et le **statut** (Conforme, En attente de validation, Validé, Refusé). Raison : s'il voyait le chiffre attendu, il n'aurait qu'à le recopier et le
contrôle ne vérifierait plus rien. Techniquement, le solde n'est ni lu ni transmis au navigateur pour un caissier (aucun `data-solde`, aucun
élément d'écart, aucun solde de compte dans le parcours), le procès-verbal PDF est réservé au gérant, et aucune route JSON n'existe. Le
**gérant** garde le solde théorique, l'écart calculé en direct pendant la saisie et le détail complet. Limite : le statut seul laisse
deviner qu'il y a un écart ; un caissier qui recompte plusieurs fois voit ce statut changer, ce qui est voulu (il doit recompter, pas recopier).

| Écart | Statut | Mouvement |
|---|---|---|
| zéro | **Conforme** | aucun |
| non nul | **En attente de validation** | aucun, tant que le gérant n'a pas décidé |
| validé | **Validé** | « Écart de caisse » : entrée si excédent, sortie si manque |
| refusé | **Refusé** (motif obligatoire) | aucun, l'écart n'est pas régularisé |
| validé puis annulé | **Annulé** (motif obligatoire) | contre-passation du mouvement d'écart |

Exemple : solde théorique 1 250 000 GNF, compté 1 240 000 : écart -10 000, en attente. Si le gérant valide avec le motif « erreur de rendu »,
une sortie de 10 000 GNF est postée sur ce compte (source « Écart de caisse », classée en **autres charges** dans le bilan, comme dans 3b1) ; un
excédent de 5 000 serait une entrée classée en **autres produits**.

**Comptage ancien.** L'écart s'applique **tel qu'il était au moment du comptage**, même si des ventes ont changé la caisse avant la validation :
le mouvement d'écart vaut l'écart figé, pas un écart recalculé. Le gérant voit le solde théorique et la date du comptage sur la fiche pour juger. À
la validation il n'y a pas de blocage sur le solde : un manque validé peut rendre le solde du compte négatif. **Le gérant en est averti** avant de
valider (fiche et liste « À valider ») : « le solde actuel du compte est de X ; valider ce manque le rendrait négatif (Y) ». Décision confirmée.

**Billetage (espèces).** Pour un compte d'espèces, le caissier peut saisir des quantités par coupure, et le total doit être égal au montant compté
(sinon refus). Coupures proposées : **20 000, 10 000, 5 000, 2 000, 1 000, 500 et 100 GNF**. Les coupures de 50, 25, 10, 5 et 1 GNF ne circulent plus et
ont été retirées (décision du client) ; la liste est la constante `Billetage.COUPURES`, dans l'ordre du formulaire. Pas de billetage sur
Orange Money ni sur la banque.

**Décisions.** Seul le gérant valide, refuse ou annule (motif d'au moins 3 caractères). Une décision déjà prise rend **409** (double clic, deux
gérants). Le gérant a une liste des comptages en attente (`/controles/comptages/en-attente`) et le tableau de bord affiche un badge avec leur
nombre. **PDF** du comptage (billetage, signatures du caissier et du gérant) et liste exportable (Excel et PDF, gérant).

**Droits.** Le caissier compte et consulte **ses** comptages (sans théorique ni écart, voir « Comptage à l'aveugle ») ; il ne voit ni les autres,
ni l'attente, ni les exports, ni le PDF (403), et ne peut ni valider ni compter un compte non autorisé. Une boutique ne voit jamais les comptages d'une autre.

### Correction du solde de départ

Décision du 7 novembre 2026 : le solde de départ est **gelé après usage**, une erreur se corrige par un **ajustement** (gérant seul, sur la
fiche du client ou du fournisseur : nouveau montant, sens « il me doit », « je lui dois » ou « avance », et motif obligatoire).

Règle : le nouveau solde (signé) **remplace** le solde de départ dans le calcul ; les règlements déjà enregistrés ne sont pas réécrits. Le
reste dû du solde de départ est `max(0, dû − déjà réglé)`, et ce qui a été réglé en trop devient une **avance** du partenaire.

Exemple : solde de départ 100 000 GNF « il me doit », 60 000 déjà réglés, reste 40 000. Ajusté à 50 000 : reste 0 et **10 000 d'avance**.
Ajusté à 150 000 : reste 90 000. Ajusté à 30 000 : reste 0 et 30 000 d'avance.

Garde-fous : l'avance ne devient jamais négative (un ajustement qui la rendrait négative est refusé) ; l'annulation d'un règlement devenu avance est
refusée si cette avance a été utilisée ; la fiche ne permet plus de modifier le solde de départ une fois ajusté (seul l'ajustement le fait) ;
l'échéance du solde disparaît quand il n'y a plus de dette. Aucun mouvement de trésorerie. L'**historique** (date, auteur, ancien et nouveau
solde, motif) figure sur la fiche, le justificatif PDF du partenaire (tableau « Ajustements ») et le relevé, qui part du solde d'origine et
montre chaque ajustement comme un événement. Un jeton évite le double envoi.

### Inventaire des créances et des dettes (`/controles/creances`)

À une date, le gérant choisit des clients et fournisseurs (au plus 100 par inventaire, recherche par nom ; autres partenaires de 3b1 en option) et
saisit le solde que chacun **confirme**. L'application compare à son propre solde **au moment de l'enregistrement** (positif : le partenaire me
doit, pour un client comme pour un fournisseur) et garde l'écart avec la raison. **Aucun solde n'est modifié.** Depuis une ligne avec écart, un lien
mène à l'ajustement du solde de départ ; il n'est jamais automatique. Documents : liste, détail, Excel et PDF.

### Saisi par

Les écrans et exports suivants affichent l'auteur sous le libellé « Saisi par » : ventes, achats, retours, devis, règlements (historique), journal
de caisse (dépenses et transferts compris), opérations de financement (prêts, remboursements), comptages, ajustements, inventaires. Les anciennes
colonnes « Vendeur », « Auteur », « Utilisateur » et « Enregistré par » ont été renommées sans changer de place ; les exports des retours et du
relevé de financement reçoivent une colonne « Saisi par » **à la fin**. Toutes les tables concernées portaient déjà la colonne utilisateur
(nullable pour les ventes et achats historiques, affichés « — ») : la migration n'en ajoute aucune. Le gérant peut filtrer le journal de
trésorerie (écran et exports, paramètre `auteur`) et la liste des ventes (paramètre `vendeur`) par auteur.

### Verrous

Voir l'[ordre des verrous](lot-2.md#ordre-des-verrous) : boutique → comptage → comptes (validation, refus, annulation) ; boutique → partenaire →
factures (ajustement). `OrdreVerrousMariaDbTest` rejoue deux validations simultanées du même comptage : une seule passe.

### Limites connues

- L'écart validé est celui du moment du comptage ; il n'est pas recalculé à la validation (voir « Comptage ancien »).
- Pas de blocage de solde sur la sortie d'un manque validé : le compte peut devenir négatif ; un avertissement s'affiche au gérant.
- Un inventaire fige le solde de l'application à son enregistrement ; il ne suit pas les mouvements suivants. Au plus 100 partenaires par inventaire.
- Un comptage conforme n'est pas annulable (il n'a aucun effet).
- Le rapprochement bancaire est décrit dans la section suivante (étape 3c).

### Vérifications

```sh
./gradlew test --no-daemon
node --test src/test/js/*.cjs
./gradlew lot3MariaDbTest lot1MariaDbTest lot2MariaDbTest lot4MariaDbTest ordreVerrousMariaDbTest
```

## Rapprochement bancaire

Étape 3c : comparer le relevé d'un compte **Banque** ou **Orange Money** avec les mouvements de l'application (`/rapprochements`). Aucune règle
existante ne change ; un mouvement annulé, une opération de financement ou un transfert se rapprochent comme les autres mouvements du compte.
Un compte d'espèces n'a pas de relevé : il se contrôle par le [comptage de caisse](#comptage-de-caisse-controlescomptages).

### Droits

Le **gérant seul** voit et utilise ces écrans, les exports et la carte du tableau de bord. Un caissier reçoit 403 partout (pages, actions,
PDF, Excel). Un relevé d'une autre boutique répond 404. Les formulaires n'acceptent que leurs champs (un champ inconnu ou répété, ou plus de
100 cases cochées, donnent 400) ; chaque action est un POST protégé par le jeton CSRF.

### Le relevé

Un relevé a un compte, une période (au plus 366 jours), un **solde d'ouverture**, un **solde de clôture**, un numéro `RB-AAAAMM-<n>` (mois de
la fin, `<n>` : numéro du compte, suivi de `-2`, `-3`… si ce numéro existe déjà) et un statut : *En cours* ou *Validé*. Ses lignes ont une date dans la période, un libellé, une référence
facultative et un **montant signé** : crédit positif, débit négatif. Au plus 5 000 lignes par relevé. On ajoute, modifie et supprime des lignes à
la main tant que le relevé n'est pas validé.

**Contrôle des soldes** : ouverture + somme des lignes = clôture. Sinon un avertissement s'affiche (avec l'écart) ; il **bloque la validation, pas la
saisie**. Le relevé suivant d'un compte propose la clôture du précédent comme ouverture et le lendemain de sa fin comme début. Les périodes d'un
même compte ne se chevauchent jamais.

### Import CSV ou Excel

Fichier `.csv`, `.txt` ou `.xlsx` (le `.xls` et le PDF sont refusés), **2 Mo** et **5 000 lignes de données** au plus. Le type est vérifié sur le contenu et
sur l'extension. Le fichier n'est **jamais écrit sur le disque** : il reste en mémoire 30 minutes (au plus 30 imports en attente, liés à la boutique,
au relevé et au gérant), puis est oublié. Les formules ne sont jamais évaluées ; un libellé ou une référence qui commence par `=`, `+`, `-` ou `@` reçoit
une apostrophe en tête.

- **CSV** : séparateur `;`, `,` ou tabulation et encodage UTF-8 ou Windows-1252 reconnus automatiquement.
- **Écran de correspondance** : on indique la ligne d'en-tête (ou « aucune »), la colonne de la date, du libellé, de la référence (facultative) et soit **une
  colonne de montant signé**, soit **une colonne de débit et une colonne de crédit** ; puis le format de date (`jj/mm/aaaa`, `aaaa-mm-jj`, `jj-mm-aaaa`) et le
  séparateur décimal (virgule ou point). Les titres du fichier servent à proposer un choix.
- **Montants** : espaces, espaces insécables, « GNF » ou « FG » acceptés (`1 200 000,50 GNF`) ; plus de deux décimales refusées.
- **Aperçu** des 10 premières lignes avec les erreurs signalées (date illisible, libellé ou montant manquant, montant nul, date hors période). Rien n'est
  enregistré avant la confirmation ; les lignes en erreur ne sont jamais importées.
- **Doublons** : une ligne identique (date, montant, libellé, référence) à une ligne déjà enregistrée sur ce compte, dans n'importe quel relevé, est signalée
  et **ignorée par défaut** (case « importer aussi les doublons »). Deux frais identiques le même jour dans un même fichier restent deux lignes.
- **Mémoire** : la correspondance choisie est mémorisée par compte et reproposée pour le fichier suivant.

### Pointage

L'écran de pointage montre deux colonnes : les lignes du relevé non rapprochées et les mouvements de l'appli non rapprochés (du début du relevé moins 7 jours à
sa fin plus 7 jours ; un lien affiche tous les mouvements plus anciens du compte). Sur un téléphone, deux onglets.

- **Propositions automatiques** : même montant exact (signe compris), date du mouvement à **3 jours** au plus de celle de la ligne. S'il y a plusieurs
  candidats, la **date la plus proche** l'emporte ; un mouvement n'est **jamais proposé deux fois**. On accepte, on refuse (la paire n'est plus proposée)
  ou on clique **Tout accepter**.
- **Pointage manuel 1-n et n-1** : une ligne avec plusieurs mouvements, ou plusieurs lignes avec un mouvement, **de sommes égales au franc près**, sans aucune
  tolérance. Un rapprochement porte au plus 100 éléments de chaque côté.
- **Enregistrer dans l'appli** : pour une ligne sans mouvement (frais, agios, intérêts, frais Orange Money, virement inconnu), crée à la date de la ligne l'opération
  de trésorerie adaptée (crédit : produit financier ou autre produit ; débit : charge financière ou autre charge) et la rapproche aussitôt. Aucun contrôle de solde
  pour une sortie : la banque a déjà débité. L'opération reste dans le journal si on défait le rapprochement (elle s'annule par l'écran des financements).
- **Défaire** un rapprochement tant que le relevé n'est pas validé.
- **Mouvement annulé** : il ne se rapproche jamais seul. Le mouvement et sa contre-passation s'**excluent ensemble** (type *Exclusion*) : ils se compensent, aucune ligne
  n'est concernée.

### État de rapprochement

```
solde du compte selon l'appli à la clôture
 − mouvements de l'appli non pointés
 + lignes du relevé non pointées
 = solde calculé       (doit égaler le solde de clôture du relevé : écart 0)
```

Exemple (GNF). Solde de départ de l'appli au 20/02 : 1 000 000. Relevé de mars : ouverture 1 000 000, lignes +500 000 (virement du 05/03), −200 000 (chèque du
10/03), −15 000 (frais du 20/03), clôture 1 285 000 (1 000 000 + 285 000, soldes cohérents). L'appli a un encaissement de 500 000 le 04/03, un autre de 500 000 le 07/03
et un paiement de 200 000 le 12/03 : solde de l'appli au 31/03 = 1 800 000. Après « Tout accepter », la ligne du 05/03 est liée à l'encaissement du 04/03 (écart
d'un jour, le plus proche) et celle du 10/03 au paiement du 12/03. Restent un mouvement non pointé de +500 000 (l'encaissement du 07/03, absent du relevé) et une ligne non
pointée de −15 000 (les frais). Calcul : 1 800 000 − 500 000 + (−15 000) = **1 285 000** = solde du relevé, **écart 0**. Le gérant peut alors enregistrer les frais dans
l'appli (le mouvement non pointé et la ligne disparaissent de l'état) ou valider tel quel.

L'état s'affiche à l'écran et s'exporte en **PDF** (A4, en-tête de la boutique, numéro `RB-AAAAMM-<compte>`, cadres de signature « Le gérant » et « Vérifié par ») et en
**Excel**. Un mouvement antérieur au premier relevé du compte n'y figure pas (il est dans le solde de l'appli) ; les lignes non pointées d'un relevé précédent y figurent.

### Validation et dévalidation

La **validation** exige : soldes cohérents (ouverture + lignes = clôture), **écart de rapprochement nul**, période sans chevauchement, relevé précédent du compte déjà
validé avec sa clôture égale à l'ouverture, et aucun mouvement pointé annulé depuis sans sa contre-passation dans le même rapprochement. Le relevé est alors **figé** : lignes,
soldes et rapprochements ne changent plus, et un mouvement rapproché **ne peut plus être annulé** (« mouvement rapproché sur le relevé RB-… validé »). Le solde de l'appli
est conservé avec la validation.

La **dévalidation** n'est possible que pour le **dernier relevé validé du compte**, avec un motif (3 à 255 caractères). Le relevé redevient modifiable, et ses mouvements
annulables ; l'historique (validations et dévalidations, avec auteur, date, motif) est conservé et affiché. Un relevé non validé peut être supprimé ; ses lignes et
rapprochements aussi, mais les opérations créées par « Enregistrer dans l'appli » restent dans le journal.

### Tableau de bord du gérant

Une carte indique, pour chaque compte Banque et Orange Money, la date de fin du **dernier relevé validé**. Elle signale en rouge « À rapprocher » un compte dont ce
relevé date de plus de **45 jours**, ou qui n'en a aucun.

### Verrous

Ordre : boutique → **relevé** (au rang du document) → comptes → mouvements, par identifiant croissant (voir [ordre des verrous](lot-2.md#ordre-des-verrous)). Deux validations
simultanées du même relevé n'en laissent passer qu'une (l'autre reçoit 409) ; l'annulation d'un mouvement qui croise la validation du relevé qui le contient a un seul gagnant
cohérent : relevé validé et mouvement intact, ou mouvement annulé et validation refusée. `OrdreVerrousMariaDbTest` rejoue ce croisement avec les vrais verrous InnoDB.

### Limites connues

- Les propositions ne comparent que le montant et la date : ni libellé, ni référence.
- Pas de tolérance de montant : un écart de 1 GNF empêche le rapprochement (on saisit alors la différence comme ligne ou comme opération).
- Un fichier importé est gardé 30 minutes en mémoire du serveur ; il est perdu si le serveur redémarre (renvoyer le fichier).
- Pas d'import `.xls`, PDF ou OFX/MT940 ; une seule feuille, la première, d'un classeur Excel est lue.
- L'écran affiche au plus 1 000 lignes et 1 000 mouvements non rapprochés (un message l'indique) ; un relevé compte au plus 5 000 lignes.
- Les anciens mouvements d'un compte (avant sa première période de relevé) ne sont pas à pointer ; le premier relevé doit partir d'une ouverture qui reprend le solde de l'appli.
- Pas de rapprochement des comptes d'espèces ; pas de relevé multi-comptes.
- Le mouvement d'une opération saisie après la validation, mais datée dans la période d'un relevé validé, apparaît comme non pointé et crée un écart au relevé suivant ;
  il se règle en dévalidant le dernier relevé ou par une ligne du relevé suivant.

### Vérifications

```sh
./gradlew test --tests '*LecteurReleveTests' --tests '*RapprochementBancaireTests'
node --test src/test/js/rapprochement.test.cjs
./gradlew lot3MariaDbTest ordreVerrousMariaDbTest
```
