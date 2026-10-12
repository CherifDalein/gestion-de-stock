# Évolution de Stock Pro vers un SaaS

## Modèle retenu

Le 7 octobre 2026, le modèle commercial retenu est un abonnement mensuel **par
boutique**, couvrant son gérant et ses utilisateurs, notamment ses caissiers.
Chaque boutique possède son propre stock, ses partenaires, ses achats, ses ventes,
ses factures et sa caisse.

La boutique du frère du propriétaire de la plateforme bénéficie d'un accès offert.
Cette gratuité est indépendante des droits des utilisateurs : elle ne donne aucun
accès aux autres boutiques ni à l'administration de la plateforme. Elle doit être
gérée comme un mode d'accès commercial, sans condition codée sur un email.

La Guinée est le pays indiqué pour l'activité. Le prix, la monnaie d'abonnement,
le moyen de paiement et les éventuelles limites d'utilisateurs restent à définir.
Le choix du prestataire et les paiements sont en attente ; cette feuille de route
ne les impose pas.

## État actuel

Le premier lot introduit les boutiques et isole les données côté serveur. Chaque
compte appartient à une boutique ; les rôles ADMIN et CAISSIER donnent des droits
dans cet espace. Les listes, documents, relations, règlements, factures, verrous
et totaux de caisse utilisent la boutique du compte authentifié.

Dans ce premier lot, un compte appartient à une seule boutique. Les noms de
catégories sont uniques par boutique et les emails restent uniques pour les
comptes. Les données locales existantes sont conservées dans la Boutique
principale par la [migration explicite](migrations/boutiques.md).

Le rôle ADMIN correspond au gérant de la boutique. Le droit distinct
`administrateur_plateforme` permet l'administration de la plateforme et reste
désactivé par défaut, y compris pour les autres gérants. Il est attribué
explicitement à un ADMIN existant par la [migration des accès plateforme](migrations/plateforme.md),
sans modifier son rôle, son mot de passe ni sa boutique. La création de comptes
caissiers reste réservée aux gérants de leur propre boutique.

Chaque compte possède aussi un état actif ou désactivé, distinct de son rôle et
de l'état de sa boutique. La désactivation suspend son accès en conservant ses
identifiants, son mot de passe, ses droits et les historiques auxquels il est
lié. La [migration des accès utilisateurs](migrations/acces-utilisateurs.md)
conserve tous les comptes existants actifs lors du premier ajout ; ses relances
ne réactivent pas les comptes déjà désactivés.

Les boutiques portent un état actif ou suspendu et un mode commercial offert.
L'accès offert concerne la boutique, indépendamment des comptes et de leurs
droits. Il ne donne aucun accès aux autres boutiques et ne réactive pas une
boutique suspendue. La gestion de ces états prépare l'abonnement mensuel ; elle
ne déclenche aucun paiement.

Le gérant peut personnaliser le nom commercial et les coordonnées de sa propre
boutique : adresse, téléphone et email. Les coordonnées sont facultatives ;
l'email commercial ne remplace aucun email de connexion. Elles sont utilisées
dans l'en-tête des factures de cet espace. La [migration des paramètres boutique](migrations/parametres-boutique.md)
ajoute seulement ces trois champs, vides par défaut, en conservant les données
et les états existants.

Chaque utilisateur dispose de **Mon compte** pour consulter son identité et
changer son propre mot de passe avec confirmation du mot de passe actuel.
Le changement ferme la session courante et bloque les autres sessions anciennes,
sans modifier les rôles ni les accès. Le [parcours du compte](mon-compte-et-recuperation.md)
décrit les contrôles et la reconnexion.

## Lots de réalisation

### 1. Boutiques et isolation des données

Premier lot réalisé : modèle Boutique, rattachement obligatoire des données et
contrôles d'appartenance sur les opérations métier et HTTP. La création publique
des boutiques, leur administration et les abonnements relèvent des lots suivants.

- Introduire la boutique et l'appartenance des comptes, avec leurs rôles dans la
  boutique. Prévoir qu'un compte puisse avoir plusieurs appartenances si ce
  besoin est confirmé plus tard.
- Rattacher les données existantes à une première boutique par une migration
  explicite qui les conserve. Les comptes ADMIN existants restent les gérants
  de cette boutique, avec leurs identifiants et leurs mots de passe conservés.
- Déterminer la boutique autorisée depuis la session côté serveur. Un identifiant
  reçu dans un formulaire ou une URL ne suffit pas à autoriser l'accès.
- Limiter les listes, consultations, modifications, suppressions, règlements,
  factures, verrous, jetons de création et calculs à la boutique concernée.
- Vérifier que les relations entre produits, catégories, partenaires et documents
  appartiennent à la même boutique. Adapter les contraintes globales, notamment
  l'unicité du nom des catégories, à ce périmètre.
- Tester deux boutiques avec des données distinctes, y compris les tentatives
  d'accès aux identifiants de l'autre boutique et les opérations simultanées.

Ce lot constitue la première étape de mise en œuvre. Ajouter le paiement dépend
de cette séparation : souscrire un abonnement doit donner accès à une boutique
dont les données sont déjà isolées.

### 2. Gestion des boutiques et de leurs utilisateurs

Deuxième lot réalisé : un espace d'administration distingue le gérant, les
caissiers et l'administrateur de la plateforme. Il permet de consulter les
boutiques et leurs utilisateurs, de créer une boutique avec son gérant, puis
de régler son statut et son mode d'accès.
L'état individuel des comptes complète cette gestion : un accès utilisateur peut
être suspendu sans supprimer le compte ni modifier sa boutique ou son rôle.
Les paramètres commerciaux permettent à chaque gérant de personnaliser l'identité
de son propre espace et les coordonnées présentées sur ses factures.
Les boutiques peuvent être ouvertes ou suspendues et bénéficier d'un accès
offert. Les données et utilisateurs restent rattachés à leur boutique ; la
création publique de boutiques et la souscription relèvent encore des étapes
suivantes.

- Organiser l'ouverture des boutiques et la gestion de leurs utilisateurs depuis
  l'administration de la plateforme, avec contrôle des droits côté serveur.
- Réserver le choix du mode d'accès commercial à l'administration de la plateforme.
- Attribuer l'accès offert à la boutique du frère lorsqu'elle est identifiée,
  avec les mêmes contrôles d'isolation que pour une boutique payante ; ne pas
  déduire cette gratuité d'un email ou d'un rôle utilisateur.

### 3. Abonnements mensuels

- Définir l'offre et les règles de renouvellement, d'échec de paiement,
  d'annulation et d'accès aux données après expiration.
- Choisir un prestataire compatible avec le pays, la monnaie et les moyens de
  paiement des clients visés.
- Relier les paiements et leur historique à la boutique, avec traitement fiable
  des notifications et des événements reçus plusieurs fois.
- Calculer l'accès depuis l'abonnement ou le mode offert, indépendamment du rôle
  du compte connecté. Conserver les données selon les règles définies pour la
  suspension et la fermeture d'une boutique.

### 4. Préparation du déploiement

Vérifier l'isolation entre boutiques, les contrôles d'accès, les sauvegardes et
leur restauration, les connexions, la supervision et la procédure de migration.
L'automatisation des sauvegardes et le renforcement des connexions, reportés
pendant le travail local, restent à traiter avant l'ouverture du service.

La séparation des boutiques, le droit plateforme distinct et les états de
boutique préparent le service SaaS. La création publique de boutiques, les offres,
les paiements et la facturation récurrente restent à mettre en œuvre.
