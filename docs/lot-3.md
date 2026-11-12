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
