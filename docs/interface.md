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
- `templates/caisse/journal.html`, `static/css/caisse.css` et `static/js/caisse.js` :
  soldes, mouvements, filtres et impression du journal.
- `templates/categories/*.html`, `static/css/categories.css` et
  `static/js/categories.js` : répertoire des catégories, formulaires et suppression.
- `static/css/formulaires.css` : présentation commune aux neuf formulaires de
  création/modification de produits, contacts, catégories et achats.
- `templates/error.html` et `static/css/erreurs.css` : pages d'erreur en français
  avec retour à l'accueil et connexion.

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
13. Les journaux d'achat et de vente : défilement horizontal jusqu'aux actions,
    boutons espacés et accessibles au clavier, recherche et pagination, ouverture
    des détails après défilement et fonctionnement du tableau sans DataTables.
14. La caisse : montants signés exacts, filtres combinés, pagination, dates
    inclusives, valeurs anciennes absentes et impression de toutes les lignes de
    la sélection. Avec la commande d'impression du navigateur ou sans JavaScript,
    le journal complet doit être imprimé.
15. Les catégories : recherche, tri, répertoire vide, confirmation de suppression
    nommée, annulation et focus clavier.
16. Les neuf formulaires : champs et labels, erreurs, retour/annulation, affichage
    mobile, version cachée du produit et versement readonly lors d'une modification
    d'achat. Vérifier le panier et les montants sans modifier les règles métier.
17. Les confirmations après création/modification/suppression, ainsi que les pages
    d'erreur 400, 403, 404 et 500 sans détail interne dans la page générique.
18. La quantité puis le badge de stock sur une même ligne, même avec de
    grandes quantités. Les actions des neuf listes doivent garder des dimensions
    communes, sans chevauchement après un filtre ou un changement de page.

La carte « Ventes du jour · réglé » affiche les montants réglés cumulés des ventes
créées aujourd’hui, selon le calcul existant. Les informations financières et les
liens réservés à l’administrateur conservent leurs restrictions de rôle.

Les actions de ligne partagent `app-row-action` : cible d'au moins 44 pixels,
rayon de 9 pixels, libellé visible et espace entre l'icône et le texte. Les groupes
`app-row-actions` espacent les boutons de 8 pixels. Consultation et documents
utilisent un contour vert, modification un contour gris et suppression un
contour rouge. Le règlement restant à effectuer utilise le vert plein. Les
cartes de contacts et catégories permettent le retour à la ligne ; les tableaux
produits, achats et ventes gardent leur défilement horizontal. La quantité en
stock précède son badge sur la même ligne, avec un espace de 8 pixels.

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

Les tableaux des journaux d'achat et de vente conservent une largeur suffisante
pour les dates, montants et boutons. Le défilement horizontal reste dans la carte
et les actions restent alignées, sans rétrécissement des boutons. Avec DataTables,
la recherche et la pagination restent en dehors de la zone de défilement. Cette
zone est nommée et accessible au clavier. Sans DataTables, le tableau utilise
directement son enveloppe responsive, sans second conteneur imbriqué. Les fenêtres
de détails des ventes sont rendues hors du tableau pour rester disponibles après
une recherche ou un changement de page.

Validation du défilement le 6 octobre 2026 : 430 contrôles navigateur réussis aux
largeurs 1440, 1024, 768, 390 et 320 pixels, avec les ressources officielles
DataTables/Bootstrap en cache puis les CDN bloqués. Les contrôles couvrent le
défilement au clavier, les actions, les montants, la pagination et les détails des
ventes. Le JAR est construit et la syntaxe du script local est vérifiée. Les
parcours utilisent une base H2 temporaire sans aucun POST métier.

La caisse distingue le solde actuel, le solde d'ouverture, la variation du jour,
les entrées et les sorties. Les valeurs viennent des calculs serveur existants ;
les filtres de l'historique ne recalculent pas ces indicateurs globaux. Recherche,
source, sens et bornes de date s'appliquent aux mouvements déjà rendus. Les montants
sont triés en centimes `BigInt`, les dates ISO restent locales et les valeurs
anciennes absentes sont signalées. L'impression explicite inclut toutes les pages
de la sélection, indique les critères et masque le tableau quotidien pour éviter
les doublons. L'impression native du navigateur inclut le journal complet.

Les catégories utilisent un script local pour la recherche, le tri et la
pagination. La suppression garde son formulaire POST et son jeton CSRF, avec
une confirmation nommée et un focus initial sur Annuler. Sans JavaScript, le
tableau et la confirmation classique restent disponibles. Les neuf formulaires
utilisent des en-têtes, sections et actions communes, des labels associés et des
erreurs reliées aux champs. La modification d'achat conserve le panier exact et
son versement readonly ; le règlement reste une action distincte.

Les confirmations des produits, clients, fournisseurs et catégories sont des
messages flash ajoutés uniquement après le succès du service. La liste des ventes
affiche aussi son message de création existant. Les journaux présentent les
montants GNF à deux décimales et des libellés cohérents. La page d'erreur générique
affiche un code et une aide en français, sans recopier les exceptions ou traces
du serveur ; les réponses JSON et les règles d'accès restent celles du serveur.

Validation de l'harmonisation le 7 octobre 2026 : 334 tests Java et 359 assertions
navigateur réussis, avec deux bases H2 temporaires (25 catégories et 60 mouvements
de caisse, puis états vides), les rôles ADMIN/CAISSIER et quatre largeurs d'écran
de 320 à 1440 pixels. Les neuf formulaires, leurs contrats de champs, la précision
du panier, les dialogues, les erreurs HTTP, le mode sans JavaScript et les CDN
bloqués sont vérifiés. Huit créations/modifications de fiches jetables confirment
les messages de succès ; la suppression est interceptée avant le serveur.
Quatre PDF de caisse A4 sont vérifiés : sélection de 28 mouvements sur deux pages,
journal complet de 60 mouvements sur quatre pages, même journal sans JavaScript
et journal vide sur une page. Montants maximaux signés, indicateurs, lignes et
en-têtes répétés sont exacts, sans débordement ni page blanche. Aucun formulaire
métier n'a été soumis à la base opérationnelle. Le JAR final est construit.

Validation de l'espacement du stock et des actions le 7 octobre 2026 : 1144
assertions navigateur réussies aux largeurs 320, 390, 768, 1024 et 1440 pixels,
avec les rôles ADMIN/CAISSIER, les CDN disponibles en cache puis bloqués et les
produits sans JavaScript. Quantités extrêmes, badges, actions dans leurs cellules,
défilement, retours à la ligne et annulations sont contrôlés dans des aperçus H2
temporaires. Aucun POST métier n'est soumis et aucune erreur JavaScript locale
n'est détectée. Le JAR est construit et les styles servis sur 8080 sont vérifiés.

Le stock affiche ensuite la quantité puis son statut sur une même ligne.
Cet ajustement passe 47 contrôles navigateur à 320 et 1440 pixels, avec les rôles
ADMIN/CAISSIER et des quantités extrêmes : aucun chevauchement ni débordement.
