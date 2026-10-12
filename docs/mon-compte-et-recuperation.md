# Mon compte et mots de passe

## Changer son mot de passe

Le menu du compte et la navigation donnent accès à **Mon compte** pour les
gérants et les caissiers. Nom, email, boutique, rôle et date de création restent
en lecture seule. Le changement concerne uniquement le compte connecté : aucun
identifiant ni email de compte cible n'est accepté dans le formulaire.

Le formulaire demande le mot de passe actuel, le nouveau et sa confirmation.
Les règles de création restent appliquées : au moins 15 caractères Unicode,
au plus 72 octets UTF-8, espaces conservés. Le nouveau doit différer de l'ancien.
Un ancien mot de passe plus court peut toujours être utilisé pour autoriser ce
changement. Les erreurs s'affichent en français et les trois champs secrets
restent vides au nouvel affichage.

Le compte et sa boutique sont relus sous verrou avant de modifier le hash
BCrypt. Les droits, états d'accès, rattachements et autres informations ne
changent pas. Un compte désactivé ou une boutique suspendue ne peut pas utiliser
ce parcours ; un véritable administrateur de plateforme conserve la gestion de
son propre compte même lorsque sa boutique est suspendue.

Après succès, la session courante est fermée et la connexion affiche une
confirmation. Chaque session authentifiée conserve une empreinte du hash
initial, même après l'effacement des credentials par Spring Security. Le filtre
`ValidationSessionFilter` la compare au hash courant en base avant les requêtes
protégées : les autres sessions deviennent inutilisables après un changement,
y compris sur plusieurs instances partageant la base. Le contrôle vérifie aussi
le compte, son email et sa boutique. Les contrôles existants des états d'accès
restent appliqués. Aucun registre de sessions
en mémoire ni changement de schéma n'est nécessaire pour ce lot.

Le formulaire conserve la protection CSRF. Les champs supplémentaires, les
paramètres de compte cible et le binding par en-têtes sont refusés.

Validation du 13 octobre 2026 : 734 tests Java réussis, dont 61 contrôles du compte,
du changement de mot de passe et des sessions. Deux transactions simultanées
utilisant le même ancien mot de passe ne peuvent pas réussir toutes les deux.
Le navigateur vérifie la page réelle à 1440 et 390 pixels : labels séparés,
aucun débordement, champs secrets vides, profil en lecture seule et liens de
navigation. Le JAR est construit et chargé localement ; aucun mot de passe réel
n'a été modifié pendant les vérifications.
