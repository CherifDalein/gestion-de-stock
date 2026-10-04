# Accès et formulaires sécurisés

La protection CSRF de Spring Security est active, y compris sur `http://localhost:8080`.

Les formulaires POST utilisent `th:action` : Thymeleaf ajoute le champ caché `_csrf`. Cela couvre la connexion, les créations, les modifications, les suppressions et la déconnexion. Après une mise à jour de l'application ou l'expiration de la session, recharger la page avant de soumettre un formulaire.

Les suppressions de produits, clients, catégories et fournisseurs se font uniquement par POST, avec confirmation et jeton CSRF. Ouvrir directement l'ancienne URL GET ne supprime plus rien. Un appel POST sans jeton valide est refusé avec un statut 403.

Pour les futurs appels JavaScript `fetch` ou clients HTTP, envoyer le jeton associé à la session comme paramètre `_csrf` ou dans l'en-tête CSRF approprié ; conserver les cookies de session. Les scripts de panier actuels soumettent des formulaires classiques et n'ont pas besoin d'un envoi supplémentaire du jeton.

## Création de comptes

1. Se connecter avec un compte ADMIN existant.
2. Ouvrir le menu utilisateur, puis **Créer un compte caissier**.
3. Remplir le nom, l'email et le mot de passe du nouveau compte.

La route `/register` est réservée aux administrateurs, en GET comme en POST. Le rôle du nouveau compte est toujours CAISSIER ; soumettre un champ `role=ADMIN` ne le modifie pas. Le compte créé peut se connecter, et l'administrateur reste dans sa session pour continuer son travail.

Les rôles et mots de passe des comptes existants ne sont pas modifiés par ce lot. Cette procédure exige donc un administrateur existant ; la création sécurisée du premier administrateur d'une base neuve reste à prévoir. Aucune procédure publique attribuant automatiquement le rôle ADMIN n'est conservée.

## Droits ADMIN / CAISSIER

| Fonction | ADMIN | CAISSIER |
| --- | --- | --- |
| Consulter les produits, stocks et prix de vente | Oui | Oui |
| Consulter les prix d'achat et fournisseurs | Oui | Non |
| Créer, modifier ou supprimer produits, catégories et fournisseurs | Oui | Non |
| Consulter, créer et modifier des clients | Oui | Oui |
| Supprimer des clients | Oui | Non |
| Enregistrer et consulter les ventes | Oui | Oui |
| Consulter et imprimer les factures de vente et relevés clients | Oui | Oui |
| Gérer les achats et consulter les factures fournisseurs | Oui | Non |
| Consulter la caisse globale et ses indicateurs | Oui | Non |
| Créer un compte caissier | Oui | Non |

Le caissier dispose d'un espace de vente à l'accueil. Les indicateurs de caisse globale sont réservés à l'administrateur et ne sont pas ajoutés au modèle du caissier. Les menus et boutons reflètent ces droits ; une action interdite reste refusée par le serveur même si son URL est appelée directement.

Les historiques de ventes et relevés clients sont partagés à l'échelle du magasin. Une restriction aux seules ventes d'un vendeur n'est pas mise en place dans ce modèle.

Les routes non explicitement autorisées sont refusées par défaut. Les pages de connexion, d'erreur et les ressources statiques restent accessibles pour le fonctionnement de l'interface. Tous les formulaires POST conservent leur protection CSRF.

## Vérification

`./gradlew test` utilise le profil `test` avec une base H2 en mémoire. Les tests de sécurité contrôlent les rôles, les refus sans jeton, le rendu des formulaires Thymeleaf et un parcours réel de connexion/suppression/déconnexion avec les jetons extraits du HTML. Aucun accès à la base MySQL n'est nécessaire.
