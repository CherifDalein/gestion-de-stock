# Interface Stock Pro

Le thème clair avec accents verts s’applique à la navigation, au tableau de bord,
aux tableaux et aux formulaires. La connexion et la création de comptes utilisent
une présentation adaptée aux ordinateurs et aux téléphones.

## Fichiers

- `templates/dashboard.html` : navigation commune, compte et tableau de bord.
- `static/css/stock-pro.css` : thème commun, chargé après les styles Bootstrap existants.
- `templates/login.html`, `templates/register.html` et `static/css/auth.css` : accès aux comptes.
- `static/js/scripts.js` : menu mobile, focus clavier et tableaux avec défilement horizontal.
- `static/js/datatables-simple-demo.js` : recherche et pagination en français.
- `templates/achats/nouveau.html` et `templates/ventes/nouveau.html` : partenaire,
  panier et récapitulatif de règlement.
- `templates/achats/regler.html` et `templates/ventes/regler.html` : détail du
  document, historique des versements et paiement du solde.
- `static/js/achat.js` et `static/js/panier.js` : calculs exacts, compteur de
  lignes, état du panier et indication du paiement.
- `templates/produits/liste.html` et `static/js/produits.js` : inventaire,
  recherche, filtres de stock, consultation et confirmation de suppression.
- `templates/clients/liste.html`, `templates/fournisseurs/liste.html` et
  `static/js/partenaires.js` : répertoires, recherche des coordonnées, documents
  associés et confirmation de suppression.
- `templates/factures/liste*.html` et `static/js/factures.js` : historiques,
  périodes, recherche et état des règlements.
- `templates/factures/template_*.html` et `static/css/factures.css` : factures
  individuelles, relevés cumulés et mise en page A4.

Les styles d’authentification sont limités à `body.stock-auth`; le thème commun à
`body.stock-app`. Les icônes, Bootstrap JavaScript et Simple DataTables utilisent
les CDN déjà présents. Si Simple DataTables ne charge pas, la liste reste visible
dans un tableau avec défilement horizontal.

## Vérification

Exécuter `./gradlew test` pour les contrôles existants des formulaires, du CSRF
et des rôles. En navigateur, vérifier :

1. La connexion et la création de compte sur ordinateur et téléphone.
2. Le tableau de bord et la rubrique active dans la navigation.
3. Le menu mobile : ouverture, fermeture au clic sur le fond ou avec Échap,
   déplacement du focus avec Tab et retour au bouton du menu.
4. Les produits et les formulaires d’achat/vente : champs lisibles et tableaux
   qui défilent sans élargir toute la page.
5. La déconnexion depuis le menu du compte.
6. Les paniers d’achat et de vente : ajout et retrait d’un produit, retour à l’état
   vide, total exact et conservation du versement saisi après un retrait.
7. Les paiements à crédit, partiels et complets, ainsi que les messages en cas de
   montant invalide ou supérieur au total.
8. Les écrans de règlement : historique, reste à payer, montant maximal autorisé,
   état entièrement réglé et affichage mobile.
9. Les produits : filtres combinés, recherche avec et sans accents, pagination,
   tri des prix et quantités, consultation et annulation de suppression.
10. L’inventaire vide et les restrictions du caissier, y compris dans les détails
    du produit. Vérifier aussi les filtres lorsque les CDN ne répondent pas.
11. Les clients et fournisseurs : recherche par nom, téléphone avec ou sans
    espaces, email et adresse ; filtres de coordonnées ; documents et listes
    vides ; cartes sur téléphone et annulation de suppression.
12. Les factures : périodes, recherche et règlements, prix historiques, centimes,
    versements absents et documents vides. Exporter des PDF A4, y compris avec
    de nombreuses lignes et de grands montants, depuis ordinateur et téléphone.

La carte « Ventes du jour · réglé » affiche les montants réglés cumulés des ventes
créées aujourd’hui, selon le calcul existant. Les informations financières et les
liens réservés à l’administrateur conservent leurs restrictions de rôle.

Validation du 5 octobre 2026 : les 334 tests existants passent. Le contrôle
navigateur couvre 82 assertions sur ordinateur et mobile, dont le focus du menu,
le CSRF, les libellés du tableau, le fonctionnement sans Simple DataTables et la
déconnexion. Les parcours de vérification n’ont soumis aucun formulaire de vente,
d’achat ou de création de compte.

Les écrans d’achat et de vente présentent les produits et le partenaire à gauche,
le règlement à droite sur grand écran. Sur un téléphone, les sections se suivent
et les tableaux défilent horizontalement. Le panier vide et les erreurs d’ajout
s’affichent directement dans le formulaire. Le statut de paiement décrit le
montant saisi ; les validations du serveur restent appliquées à l’enregistrement.
Les calculs utilisent toujours les montants exacts du module `montants.js`.

Validation des écrans d’opération : 334 tests Java, 4 tests Node des calculs et
174 assertions navigateur pour les paniers sur ordinateur, tablette et téléphone.
Les écrans de règlement ont été contrôlés avec 72 assertions sur des achats et
ventes partiellement ou entièrement réglés dans une base H2 temporaire. Aucun
formulaire métier n’a été envoyé à la base locale pendant ces vérifications.

L’inventaire présente les compteurs du catalogue, les produits en stock, les
stocks faibles et les ruptures. Le seuil existant est conservé : 1 à 5 unités
correspondent à un stock faible, 0 à une rupture. La recherche ignore la casse et
les accents et se combine avec les filtres de catégorie et de stock. Les
compteurs portent sur tout le catalogue, indépendamment des filtres appliqués.

Le script local `produits.js` gère la recherche, le tri et la pagination sans
Simple DataTables. Le tri des quantités et des prix utilise `BigInt` pour conserver
les grandes valeurs exactes. Les détails sont affichés dans une boîte de dialogue ;
le prix d’achat, le fournisseur et les actions de gestion restent réservés à
l’administrateur, y compris dans les attributs HTML. La suppression demande une
confirmation puis envoie le formulaire POST d’origine avec son jeton CSRF.
Sans JavaScript, les lignes restent visibles et les actions de gestion conservent
leur fonctionnement et la confirmation native de suppression.

Validation du catalogue le 6 octobre 2026 : 334 tests Java réussis, 24 contrôles
privés de logique et 340 assertions navigateur sur ordinateur, tablette et
téléphone. Les parcours utilisent deux bases H2 temporaires (25 produits fictifs
et catalogue vide), les rôles administrateur et caissier, avec les CDN bloqués.
L’envoi de suppression confirmé est intercepté avant le serveur pour vérifier le
CSRF ; aucun produit n’est supprimé pendant les contrôles.

Les répertoires Clients et Fournisseurs partagent le script local
`partenaires.js`. La recherche couvre le nom, le téléphone, l’email et l’adresse,
avec ou sans accents ; un numéro peut être saisi avec ou sans ses séparateurs.
Les filtres distinguent les contacts avec téléphone, avec email et sans les deux.
Les compteurs décrivent tout le répertoire. Les coordonnées absentes s’affichent
comme « Non renseigné ». Sur téléphone, chaque ligne devient une carte avec les
coordonnées, les actions et les documents visibles ; le tri reste accessible.

Le caissier conserve la création, la modification et les documents des clients.
La suppression des clients et l’accès aux fournisseurs restent réservés à
l’administrateur. La consultation et la confirmation utilisent des dialogues
natifs ; le formulaire POST et son jeton CSRF sont conservés. Sans JavaScript,
les répertoires et leurs actions existantes restent utilisables.

Validation des répertoires le 6 octobre 2026 : 334 tests Java, 34 contrôles privés
de logique et 690 assertions navigateur réussis. Les parcours utilisent deux
bases H2 temporaires (25 clients et 25 fournisseurs fictifs, puis répertoires
vides), les rôles administrateur et caissier, trois tailles d’écran et les CDN
bloqués. Les deux suppressions confirmées sont interceptées avant le serveur.
Six accès en lecture aux factures et relevés cumulés ont aussi été vérifiés.

Les historiques de factures présentent la période, le nombre de documents et les
montants total, versé et restant. Ces sommes sont calculées côté serveur pour
toute la période ; la recherche et le filtre de règlement affinent uniquement
les lignes affichées. Le script local `factures.js` gère la pagination et le tri
chronologique sans dépendance externe ni conversion des montants. La période
« 7 derniers jours » conserve le calcul existant du serveur. Les liens vers les
relevés cumulés conservent la période sélectionnée.

Les factures et relevés partagent `factures.css`, avec un en-tête « Stock Pro » et
les coordonnées réelles du client ou du fournisseur. Les prix des lignes restent
ceux enregistrés lors de l'opération ; un versement absent vaut zéro et le reste
est toujours visible. Le bouton Imprimer ouvre la commande du navigateur, sans
impression automatique. Le format A4 répète les en-têtes de tableau, conserve les
totaux en fin de document et masque les commandes. La règle de visibilité est
limitée aux documents pour corriger le masquage global hérité de `styles.css`.

Validation des factures le 6 octobre 2026 : 334 tests Java et 14 contrôles privés
de logique réussis. Les parcours navigateur couvrent les trois historiques et
les quatre documents sur ordinateur, tablette et téléphone, les périodes, les
montants exacts, les rôles, les listes vides et le fonctionnement sans JavaScript.
Douze PDF A4 ont été contrôlés, notamment des factures de 60 lignes, des relevés
de huit et sept pages, des versements absents, de grands montants et des relevés
vides. La validation finale des PDF et des parcours restants comporte 196
assertions réussies. Les vérifications utilisent deux bases H2 temporaires ;
aucun formulaire métier n'a été envoyé à la base locale. Les exports PDF ont été
vérifiés, sans essai sur une imprimante physique.
