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

Le prix, le pays de commercialisation, la monnaie d'abonnement, le moyen de
paiement et les éventuelles limites d'utilisateurs restent à définir. Cette
feuille de route ne les impose pas.

## État actuel

Le premier lot introduit les boutiques et isole les données côté serveur. Chaque
compte appartient à une boutique ; les rôles ADMIN et CAISSIER donnent des droits
dans cet espace. Les listes, documents, relations, règlements, factures, verrous
et totaux de caisse utilisent la boutique du compte authentifié.

Dans ce premier lot, un compte appartient à une seule boutique. Les noms de
catégories sont uniques par boutique et les emails restent uniques pour les
comptes. Les données locales existantes sont conservées dans la Boutique
principale par la [migration explicite](migrations/boutiques.md).

L'administrateur actuel correspond au gérant de la boutique. L'administration de
la plateforme SaaS doit disposer de droits distincts pour gérer les boutiques et
leurs abonnements. La création actuelle de comptes caissiers est réservée à ADMIN.

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

- Distinguer le gérant, les caissiers et l'administrateur de la plateforme.
- Organiser l'ouverture d'une boutique et la gestion de ses utilisateurs.
- Prévoir l'écran de gestion des boutiques et de leur mode d'accès commercial.
- Attribuer l'accès offert à la boutique du frère, avec les mêmes contrôles
  d'isolation que pour une boutique payante.

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

La séparation des boutiques est implémentée. L'administration de la plateforme,
la création publique de boutiques, l'accès commercial offert et la facturation
restent à mettre en œuvre.
