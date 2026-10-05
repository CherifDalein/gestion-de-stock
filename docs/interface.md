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

La carte « Ventes du jour · réglé » affiche les montants réglés cumulés des ventes
créées aujourd’hui, selon le calcul existant. Les informations financières et les
liens réservés à l’administrateur conservent leurs restrictions de rôle.

Validation du 5 octobre 2026 : les 334 tests existants passent. Le contrôle
navigateur couvre 82 assertions sur ordinateur et mobile, dont le focus du menu,
le CSRF, les libellés du tableau, le fonctionnement sans Simple DataTables et la
déconnexion. Les parcours de vérification n’ont soumis aucun formulaire de vente,
d’achat ou de création de compte.
