# Inscription autonome et essai gratuit

L’accueil public est disponible sur `/` pour un visiteur et sur `/accueil` pour tous. Un utilisateur connecté conserve son tableau de bord sur `/`. Aucun prix ou prestataire de paiement n’est annoncé.

## Parcours

1. Le visiteur indique son email sur `/inscription`. La réponse est identique pour une adresse nouvelle ou déjà utilisée.
2. Il reçoit un lien personnel valable 24 heures par défaut. Consulter le lien ne crée ni boutique ni compte.
3. Sur `/inscription/confirmer`, il renseigne le nom de sa boutique, son nom et un mot de passe confirmé. La boutique, le compte et la consommation du lien sont validés dans une seule transaction.
4. Il se connecte avec son email et son mot de passe. Le rôle est `ADMIN`, sans administration de plateforme ni accès offert permanent, et les données restent isolées par boutique.

Le stockage des demandes contient seulement l’email normalisé, l’empreinte SHA-256 d’un jeton aléatoire et ses dates UTC. Il ne contient aucun mot de passe. Un nouvel envoi remplace le lien précédent. Le lien est à usage unique ; l’unicité de l’email empêche aussi une création concurrente ou un doublon avec un compte existant.

## Réglage de l’essai

La durée initiale est **30 jours**, configurable par `STOCK_TRIAL_DAYS` (1 à 3 650). L’administrateur de plateforme peut enregistrer la durée depuis `/plateforme/parametres`. Ce réglage persistant est prioritaire sur la configuration et s’affiche sur les pages publiques.

L’essai commence lors de la création finale, avec deux dates UTC persistantes (`essai_debut`, `essai_fin`). Modifier la durée globale s’applique aux créations suivantes uniquement. Les essais commencés gardent leurs dates. Les boutiques antérieures et celles créées manuellement par la plateforme conservent leur accès existant : leurs dates restent nulles.

À la date de fin exacte, les pages et opérations métier redirigent vers `/acces-boutique`, y compris dans une session déjà ouverte. Les services métier refusent aussi un appel direct. Les données sont conservées. Le compte personnel, le changement et la récupération du mot de passe restent accessibles tant que le compte et la boutique n’ont pas été désactivés. Le paiement et la souscription restent à intégrer.

L’option administrative `acces_offert` dispense de la limite de l’essai. Elle ne lève aucune suspension manuelle ni désactivation d’utilisateur.

## Emails et protections

L’inscription partage les réglages SMTP de récupération : `stock.recovery.enabled`, `stock.recovery.from`, `stock.recovery.base-url` (`APP_PUBLIC_URL`) et `spring.mail.*`. Le fichier local privé `stock-mail-local.properties` reste ignoré par Git. Les liens utilisent uniquement l’origine configurée, jamais l’en-tête Host. La durée du lien est réglable avec `stock.signup.ttl` (1 à 48 heures), indépendamment de l’essai.

Les formulaires conservent CSRF, limitent strictement leurs champs et refusent les paramètres supplémentaires ou dupliqués. Le mot de passe demande au moins 15 caractères et accepte au maximum 72 octets UTF-8 ; il n’est jamais réaffiché après une erreur. Les pages contenant le lien imposent `Cache-Control: no-store` et `Referrer-Policy: no-referrer`, sans ressources tierces.

Les demandes sont traitées par une file bornée ; SMTP intervient après la transaction. Des quotas distincts limitent demandes et tentatives par email, IP et ensemble des visiteurs. Ces quotas restent locaux à chaque instance et devront être partagés pour plusieurs serveurs. Les détails SMTP/JDBC contenant des données sensibles ne sont pas publiés dans les journaux.

## Installation et vérification

La migration explicite `inscriptionEssaiCheck` / `inscriptionEssaiMigrate` ajoute deux colonnes facultatives et deux tables. Elle exige une sauvegarde SQL locale vérifiée ; Hibernate reste en mode `validate`. Voir [la migration](migrations/inscription-essai.md). Les tests d’email utilisent une boîte SMTP locale sans envoi aux utilisateurs réels.
