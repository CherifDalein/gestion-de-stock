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
