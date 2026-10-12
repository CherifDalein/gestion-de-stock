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

## Mot de passe oublié

La connexion propose **Mot de passe oublié ?**. Après saisie de l'email, la page
répond de la même façon pour un compte présent, absent, désactivé ou une boutique
suspendue : si le compte permet la récupération, un lien est envoyé à son email
de connexion. Aucune adresse de destination différente n'est acceptée.

Le lien expire après 30 minutes, n'est utilisable qu'une fois et est remplacé
par toute nouvelle demande acceptée. Son ouverture ne le consomme pas, afin
de permettre aussi les analyses automatiques des messageries. La saisie du
nouveau mot de passe et de sa confirmation conserve les règles de Mon compte.
Une réinitialisation réussie renvoie à la connexion, bloque les anciennes
sessions et envoie une notification sans mot de passe ni jeton.

Seules les empreintes SHA-256 du jeton et du hash BCrypt initial sont stockées
dans la [table dédiée](migrations/reinitialisation-mdp.md). Le changement du
secret et la consommation du lien sont atomiques, sous verrous. Deux tentatives
simultanées ne peuvent pas toutes les deux réussir. Une demande ne modifie pas
le mot de passe, les rôles, les états d'accès ou les données de la boutique.

Les recherches de compte et l'envoi sont effectués hors de la réponse HTTP,
dans une file de 64 tâches et avec un seul worker. Les demandes sont limitées
à 3 par heure par email et 10 par heure par IP ; les validations du nouveau
mot de passe sont limitées à 5 par lien et 20 par IP sur 10 minutes. Un plafond
de 60 opérations par minute s'applique à chaque parcours. Les compteurs sont
atomiques, expirent et sont bornés à 10 000 entrées. Ces limites sont propres
à chaque instance ; un déploiement réparti devra utiliser des quotas partagés.
Les erreurs SMTP ou la saturation conservent la même réponse et ne publient
ni destinataire, ni lien, ni identifiants dans les logs.

Les pages utilisent des ressources locales, `Cache-Control: no-store` et
`Referrer-Policy: no-referrer`. Les formulaires gardent CSRF et le filtrage strict
des paramètres. L'origine du lien provient exclusivement de la configuration,
jamais de `Host` ou d'un en-tête de proxy. Ces choix suivent les
[recommandations OWASP pour la récupération](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html).

## Configuration de l'envoi

La récupération est désactivée par défaut sans configuration. Le profil `local`,
utilisé par défaut, peut importer `stock-mail-local.properties` à la racine du
projet. Ce fichier est exclu de Git et doit rester privé (`chmod 600`). Le profil
`test` ne l'importe pas ; ses expéditeurs sont factices.

Exemple de fichier privé à compléter dans un éditeur, sans placer le secret dans
une commande ou un fichier suivi :

```properties
stock.recovery.enabled=true
stock.recovery.base-url=http://localhost:8080
stock.recovery.from=adresse-expedition@example.com
spring.mail.host=smtp.gmail.com
spring.mail.port=587
spring.mail.username=adresse-expedition@example.com
spring.mail.password=VOTRE_MOT_DE_PASSE_D_APPLICATION
spring.mail.properties.mail.smtp.auth=true
```

Le projet exige STARTTLS et la vérification du nom du serveur par défaut. Les
délais de connexion, lecture et écriture SMTP sont limités à cinq secondes.
Le mot de passe Gmail est un mot de passe d'application : voir les instructions
[Google](https://support.google.com/accounts/answer/185833?hl=fr).

Pour le déploiement, sélectionner un profil de production et fournir les
paramètres SMTP par un fichier externe privé ou les variables Spring Mail.
`STOCK_RECOVERY_ENABLED`, `STOCK_MAIL_FROM` et `APP_PUBLIC_URL` configurent
l'activation, l'expéditeur et l'origine du lien ; cette dernière doit être HTTPS
hors de localhost. `STOCK_RECOVERY_TTL` permet une durée entre une et soixante
minutes, par défaut `PT30M`. `ALERT_TO` n'est pas utilisé : le destinataire est
toujours l'email du compte concerné.

La configuration Gmail locale a été enregistrée dans le fichier privé et sa
connexion STARTTLS/authentification a été vérifiée sans envoyer d'email réel.

## Vérifications du parcours complet

Le 13 octobre 2026 : 832 tests Java réussis et 32 scénarios MariaDB temporaires.
Un aperçu H2 séparé et une boîte SMTP locale ont validé 93 assertions HTTP et
navigateur, dont l'émission réelle d'un email de test, la réinitialisation, la
reconnexion, le refus des anciens mots de passe et sessions, l'usage unique et
l'invalidation d'un lien après changement dans Mon compte. Huit affichages ont
été contrôlés à 1440 et 390 pixels. Les destinataires sont fictifs et la boîte de
test refuse toute autre adresse ; aucun email réel n'a été envoyé.

La sauvegarde locale a été restaurée dans une base temporaire et comparée aux
douze tables et cinquante lignes existantes. La migration ajoute une table vide
et conserve chaque ancienne colonne, ligne, mot de passe, droit et statut.
Le schéma neuf des treize tables a aussi été vérifié dans une base temporaire.
