# Abonnements manuels par boutique

Le gérant consulte `/mon-abonnement` pour connaître le statut commercial de sa propre boutique, les dates UTC et le nombre de jours restants. La page est en lecture seule. Elle reste disponible dans une session existante après expiration ou suspension, sans ouvrir les pages métier. Un compte désactivé et un caissier ne peuvent pas la consulter.

## Commandes de plateforme

Dans `/plateforme/boutiques/{id}`, l’administrateur de plateforme peut :

- prolonger un essai existant de 1 à 3 650 jours, depuis sa fin s’il est encore valide ou depuis maintenant s’il a expiré ;
- activer ou renouveler **un mois civil**, depuis la fin de l’essai ou de l’abonnement encore valide, en conservant les jours restants ;
- accorder ou retirer un accès offert et suspendre ou réactiver la boutique avec les commandes d’accès déjà présentes.

Une boutique sans essai ne reçoit pas de délai artificiel par la prolongation. Si un abonnement est déjà actif ou prévu, la prolongation de l’essai est refusée : il faut ajouter un mois à l’abonnement pour conserver les périodes déjà accordées. L’activation d’un mois sur une boutique disposant de l’accès existant transforme cet accès en une période datée ; le formulaire affiche cette conséquence. Les boutiques anciennes restent inchangées tant qu’aucune activation n’est demandée.

L’activation d’un mois ne lève aucune suspension et ne retire aucun accès offert. Un accès offert autorise les opérations sans limite commerciale, à condition que la boutique et le compte soient actifs. Retirer l’accès offert réapplique les dates enregistrées. Le statut affiché est « Boutique suspendue », « Accès offert », « Abonnement actif », « Essai gratuit », « Accès existant » ou « Accès expiré » selon la situation.

Un mois suit le calendrier UTC, avec l’ajustement au dernier jour si nécessaire : un mois à partir du 31 janvier se termine le 28 février (ou le 29 une année bissextile). Un abonnement enregistré avant la fin de l’essai démarre à sa suite ; les deux périodes restent visibles. À la fin d’une période sans autre accès valide, les contrôleurs et services métier bloquent les opérations et conservent les données. Le compte et la consultation de l’abonnement restent accessibles selon leurs droits propres.

## Historique et demandes répétées

Chaque activation, prolongation et changement effectif de suspension ou d’accès offert est enregistré dans la même transaction que la modification. L’historique contient l’action, sa date UTC, le nom de l’administrateur au moment de l’action, la période ajoutée, la fin précédente et un motif facultatif. Le motif est visible du gérant. Il ne doit pas contenir de mot de passe ou de secret. Aucun paiement ou débit n’est créé par cette activation.

L’historique commence à l’installation de cette fonctionnalité ; aucune ancienne action n’est inventée. Il est présenté par pages de 20 lignes, avec ordre déterministe et index par boutique/date/identifiant. Le gérant voit seulement sa boutique ; l’administration peut consulter chaque boutique. Les vues n’exposent ni jeton d’action ni email privé de l’administrateur dans l’historique.

Chaque formulaire d’activation/prolongation porte un UUID indépendant. Répéter la même demande n’ajoute pas une seconde période. Réutiliser cet UUID pour une autre boutique, action, quantité, personne ou motif est refusé. CSRF et la liste stricte des champs restent appliqués. Les en-têtes HTTP ne remplissent pas les formulaires. Les verrous et le rafraîchissement des données évitent de perdre un renouvellement ou une suspension concurrente.

## Installation

La [migration des abonnements](migrations/abonnement.md) ajoute `abonnement_debut` et `abonnement_fin` facultatifs, puis une table d’historique vide. Les 15 tables antérieures et leurs données sont conservées. Hibernate reste en mode `validate`. La migration explicite exige une sauvegarde SQL locale vérifiée avant l’application.

Le prestataire de paiement, les prix et la collecte automatique ne sont pas encore intégrés. Ce lot prépare le suivi des périodes et leur administration manuelle sans annoncer de paiement reçu.
