# Trésorerie de la boutique

Chaque boutique suit séparément ses espèces, son compte Orange Money et son
compte bancaire. Les trois soldes et leur total sont visibles dans le menu
**Trésorerie**. Les données et les soldes restent propres à chaque boutique.

> Depuis le lot 3, une boutique peut avoir plusieurs comptes de chaque type (jusqu'à 12) : « Caisse (espèces) », « Orange Money » et
> « Banque » sont des *types* de compte, et les trois comptes ci-dessus sont ses comptes principaux. Voir
> [Comptes de trésorerie multiples](lot-3.md#comptes-de-trésorerie-multiples).

## Ventes, achats et règlements

Lors de l'enregistrement d'une vente, choisir le compte qui reçoit la somme
réellement encaissée : **Caisse (espèces)**, **Orange Money** ou **Banque**.
Une vente avec zéro encaissé laisse les trois soldes inchangés. Lorsqu'un
client règle sa dette, sélectionner le compte utilisé pour ce nouveau
versement. Une facture peut donc être réglée en plusieurs fois sur des
comptes différents ; l'historique de ses règlements conserve chaque choix.

Pour un achat ou son règlement, le compte choisi est débité de la somme
effectivement versée au fournisseur. Modifier les lignes d'un achat ne change
pas les comptes de ses versements déjà enregistrés. Les anciennes opérations
conservent leur classement en caisse, faute de mode de paiement historique.

Les caissiers peuvent choisir le compte des encaissements clients. Les achats,
la consultation de la trésorerie, les dépenses et les transferts restent
réservés au gérant, selon les droits existants de l'application.

## Annulation d'un règlement

Un règlement ultérieur d'une vente ou d'un achat, ou le règlement d'un solde de
départ de client ou de fournisseur, peut être annulé par le gérant depuis la
page de règlement (bouton « Annuler », motif obligatoire). Rien n'est effacé :
un mouvement inverse (montant opposé, même compte) est ajouté au journal. Le
règlement d'origine affiche « Annulé le … » et son motif, le mouvement inverse
« Annulation du mouvement du … ». Les soldes des comptes et le total comptent
les deux mouvements. Annuler un encaissement retire de l'argent d'un compte : le
compte doit avoir le solde nécessaire, comme pour une dépense. Le paiement
saisi à la création d'une vente ou d'un achat, les dépenses, transferts et
retours ne s'annulent pas. Détail des règles dans
[Annulation d'un règlement](lot-2.md#annulation-dun-règlement).

## Transferts et dépenses

Dans **Trésorerie**, choisir **Transférer des fonds**, puis le compte de départ,
le compte d'arrivée, un montant positif et un motif. Les comptes doivent être
différents et le compte de départ doit disposer de la somme nécessaire.

Par exemple, enregistrer un transfert de 100 000 GNF de la caisse vers la banque
retire 100 000 GNF des espèces et ajoute 100 000 GNF à la banque. Le total ne
change pas. Le journal conserve les deux mouvements, liés à la même référence.
Ces mouvements internes sont exclus des entrées et sorties d'activité du jour.

Pour une dépense (loyer, transport, frais, etc.), choisir **Enregistrer une
dépense**, son compte de paiement, son montant et son motif. Le compte doit
disposer de la somme dépensée. La dépense réduit le solde de ce compte et le
total de la boutique, sans modifier le stock ou les factures.

La validation est protégée contre le renvoi du même formulaire. Un transfert
est enregistré intégralement, avec son débit et son crédit dans une seule
transaction. Deux opérations simultanées ne peuvent pas dépenser deux fois
le même argent disponible.

Ces écrans enregistrent les paiements et les transferts effectués par la
boutique. Ils ne déclenchent pas de paiement Orange Money ou de virement
bancaire auprès d'un service externe.

## Démarrage et historique

Pendant la préparation d'une nouvelle boutique, le gérant peut saisir une
fois le solde initial de chacun des trois comptes, y compris un solde nul.
Le premier achat, la première vente, le premier transfert ou la première
dépense termine cette préparation. Les soldes initiaux deviennent alors
inaccessibles à la modification, comme le stock initial.

Le journal présente le compte, la date, le montant, le motif et l'utilisateur
de chaque mouvement. Ses filtres permettent de consulter un compte précis.
La mise en place des trois comptes ne rouvre pas le démarrage d'une boutique
existante. Voir la [migration de trésorerie](migrations/tresorerie.md) pour
installer cette évolution sur une base déjà utilisée.
