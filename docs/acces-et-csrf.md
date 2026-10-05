# Accès et formulaires sécurisés

La protection CSRF de Spring Security est active, y compris sur `http://localhost:8080`.

Les formulaires POST utilisent `th:action` : Thymeleaf ajoute le champ caché `_csrf`. Cela couvre la connexion, les créations, les modifications, les suppressions et la déconnexion. Après une mise à jour de l'application ou l'expiration de la session, recharger la page avant de soumettre un formulaire.

Les suppressions de produits, clients, catégories et fournisseurs se font uniquement par POST, avec confirmation et jeton CSRF. Ouvrir directement l'ancienne URL GET ne supprime plus rien. Un appel POST sans jeton valide est refusé avec un statut 403.

Pour les futurs appels JavaScript `fetch` ou clients HTTP, envoyer le jeton associé à la session comme paramètre `_csrf` ou dans l'en-tête CSRF approprié ; conserver les cookies de session. Les scripts de panier actuels soumettent des formulaires classiques et n'ont pas besoin d'un envoi supplémentaire du jeton.

## Création de comptes

1. Se connecter avec un compte ADMIN existant.
2. Ouvrir le menu utilisateur, puis **Créer un compte caissier**.
3. Remplir le nom, l'email et le mot de passe du nouveau compte.

La route `/register` est réservée aux administrateurs, en GET comme en POST. Le rôle du nouveau compte est toujours CAISSIER ; soumettre un champ `role=ADMIN` est refusé, sans créer de compte. Le compte créé peut se connecter, et l'administrateur reste dans sa session pour continuer son travail.

Les rôles et mots de passe des comptes existants ne sont pas modifiés par la migration des emails. Cette procédure exige un administrateur existant ; une commande locale protégée permet de créer le premier ADMIN uniquement sur une base vide. Voir [validation des comptes, migration et premier administrateur](comptes-et-migration.md). Aucune route publique ne crée un ADMIN.

## Droits ADMIN / CAISSIER

| Fonction | ADMIN | CAISSIER |
| --- | --- | --- |
| Consulter les produits, stocks et prix de vente | Oui | Oui |
| Consulter les prix d'achat et fournisseurs | Oui | Non |
| Créer, modifier ou supprimer produits, catégories et fournisseurs | Oui | Non |
| Consulter, créer et modifier des clients | Oui | Oui |
| Supprimer des clients | Oui | Non |
| Enregistrer et consulter les ventes | Oui | Oui |
| Enregistrer et consulter les règlements clients | Oui | Oui |
| Consulter et imprimer les factures de vente et relevés clients | Oui | Oui |
| Gérer les achats et consulter les factures fournisseurs | Oui | Non |
| Enregistrer et consulter les règlements fournisseurs | Oui | Non |
| Consulter la caisse globale et ses indicateurs | Oui | Non |
| Créer un compte caissier | Oui | Non |

Le caissier dispose d'un espace de vente à l'accueil. Les indicateurs de caisse globale sont réservés à l'administrateur et ne sont pas ajoutés au modèle du caissier. Les menus et boutons reflètent ces droits ; une action interdite reste refusée par le serveur même si son URL est appelée directement.

Les historiques de ventes et relevés clients sont partagés à l'échelle du magasin. Une restriction aux seules ventes d'un vendeur n'est pas mise en place dans ce modèle.

Les en-têtes HTTP du navigateur ne sont pas liés aux objets de formulaire. Depuis la mise à jour Spring 6.2, leur prise en compte automatique pouvait provoquer un refus 400 par la liste stricte des champs autorisés. Le filtrage des en-têtes dans le binder évite ce refus ; les paramètres imprévus restent rejetés et CSRF reste actif.

Les routes non explicitement autorisées sont refusées par défaut. Les pages de connexion, d'erreur et les ressources statiques restent accessibles pour le fonctionnement de l'interface. Tous les formulaires POST conservent leur protection CSRF.

## Vérification

`./gradlew test` utilise le profil `test` avec une base H2 en mémoire. Les tests de sécurité contrôlent les rôles, les refus sans jeton, le rendu des formulaires Thymeleaf et un parcours réel de connexion/suppression/déconnexion avec les jetons extraits du HTML. Aucun accès à la base MySQL n'est nécessaire.

## Régler un fournisseur après un achat

1. Se connecter comme ADMIN et ouvrir le **Journal des Achats**.
2. Cliquer sur **Régler** à côté de l'achat concerné.
3. Saisir la **somme payée maintenant**, et non le total cumulé des versements.
4. Enregistrer le versement. Le montant déjà versé et la dette sont actualisés ; une sortie de caisse est liée à l'achat avec date et auteur. Le stock et les lignes de facture ne sont pas modifiés.

Cette opération reste possible si tout ou partie des produits ont été vendus. Un achat soldé affiche **Règlements**, qui permet de consulter les versements enregistrés. Le montant doit être positif, comporter au plus deux décimales et ne pas dépasser la dette restante. Les montants sont désormais calculés en `BigDecimal` et stockés en `DECIMAL(17,2)`, sans conversion en `Double`.

Le formulaire vérifie sous verrou le montant déjà payé au moment de son ouverture. Répéter le même formulaire après un versement, ou soumettre deux copies en même temps, ne crée pas une seconde sortie de caisse : la seconde demande reçoit HTTP 409. Vérifier l'historique et recharger le formulaire avant un nouveau versement. Ce contrôle vise les copies d'un même formulaire ; il ne remplace pas une vérification métier de deux paiements réellement distincts.

Le bouton **Modifier** concerne désormais les lignes et le fournisseur de l'achat. Le montant déjà payé y est affiché en lecture seule. Une réduction du total sous le montant déjà payé est refusée ; les remboursements et annulations de paiements ne sont pas proposés dans ce parcours.

Les versements initiaux des nouveaux achats et les nouveaux règlements apparaissent dans l'historique lié à la facture. Les anciens mouvements de caisse restent consultables dans le journal général ; ils ne sont pas automatiquement rattachés à une facture. Voir [mise à jour du schéma](database-schema.md#mise-à-jour-dune-base-existante--règlements-fournisseurs).

## Régler une vente à crédit

1. Se connecter comme ADMIN ou CAISSIER et ouvrir le **Journal des Ventes**.
2. Cliquer sur **Régler** à côté de la vente concernée.
3. Saisir la **somme payée maintenant**, puis enregistrer le versement.

Le montant déjà versé augmente et le reste à payer diminue. Une entrée de caisse positive est liée à la vente, datée et attribuée au compte qui enregistre le versement. Le stock, la date de vente, le client et les lignes de facture ne sont pas modifiés. Le règlement reste possible quand le stock est épuisé. Une vente soldée propose **Règlements** pour consulter son historique ; aucun nouveau versement dépassant le solde n'est accepté.

Les montants doivent être positifs, comporter au plus 15 chiffres entiers et 2 décimales, et ne pas dépasser le reste à payer. Le formulaire utilise CSRF et n'accepte que le montant et le cumul attendu. Le serveur verrouille la vente et relit le cumul avant de l'incrémenter : deux copies du même formulaire ne créent qu'une entrée de caisse, la copie périmée reçoit HTTP 409. Vérifier l'historique et recharger le formulaire avant un versement distinct. Une erreur de caisse annule aussi la mise à jour du cumul.

L'historique inclut les versements initiaux des nouvelles ventes et les règlements effectués après cette mise à jour. Les mouvements antérieurs restent dans le journal général de caisse et ne sont pas automatiquement rattachés. Le montant déjà versé affiché reste la référence ; l'historique d'une ancienne vente peut être incomplet. Voir [mise à jour du schéma](database-schema.md#mise-à-jour-dune-base-existante--règlements-clients).

Comme les ventes et relevés clients, ce parcours est partagé entre les comptes ADMIN/CAISSIER du magasin. Il permet au caissier de consulter les versements de la vente sélectionnée, sans lui ouvrir la caisse globale. Il ne propose pas de remboursement ni d'annulation d'un paiement.
