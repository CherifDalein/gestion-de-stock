# Gestion de stock

Application Spring Boot pour les produits, achats, ventes et règlements.

Versions : Spring Boot 3.5.16 et Spring Security 6.5.11.

## Démarrage local

Prérequis : Java 21 et le serveur MariaDB local démarré sur le port 3306.
Sur macOS/Linux, si nécessaire : `chmod +x gradlew`.

```sh
./gradlew bootRun
```

Ouvrir <http://localhost:8080>. La connexion locale utilise la base `stock_pro`.
Voir [les paramètres de connexion](docs/connexion-base.md) pour les identifiants et une connexion distante.

Le schéma est validé au démarrage ; il n'est plus modifié automatiquement. Pour
une base existante sans boutiques, appliquer d'abord la
[migration des boutiques](docs/migrations/boutiques.md), après sauvegarde.
Appliquer ensuite la [migration de l'administration plateforme](docs/migrations/plateforme.md)
si les trois champs d'accès ne sont pas encore présents.
Ajouter ensuite le [statut individuel des comptes](docs/migrations/acces-utilisateurs.md)
si `utilisateur.actif` n'existe pas encore.
Appliquer enfin la [migration des paramètres de boutique](docs/migrations/parametres-boutique.md)
si les colonnes de coordonnées de `boutique` sont absentes.

Pour une base déjà à jour avec les stocks et les tarifs, appliquer ensuite la
[migration du démarrage de boutique](docs/migrations/demarrage-boutique.md), après
sauvegarde. Elle réserve les stocks et le solde initiaux à la préparation de la
boutique, puis conserve les achats et ventes comme seules variations de stock.

Pour séparer les espèces, Orange Money et la banque, appliquer la
[migration de trésorerie](docs/migrations/tresorerie.md) après sauvegarde.
Les anciens mouvements restent affectés à la caisse, avec leurs montants
et leurs liens d'origine. Le [guide de trésorerie](docs/tresorerie.md) décrit
les paiements, les transferts et les dépenses.

Pour préparer le lot 1 (seuils, unités, services, coût moyen, numéros de facture,
échéances, avances et soldes initiaux), appliquer la
[migration du lot 1](docs/migrations/lot-1.md) après sauvegarde. Les ventes et
achats existants sont numérotés et reçoivent leur échéance ; stocks, montants
et mouvements restent inchangés.

## Tests

```sh
./gradlew test
```

Les tests utilisent H2 en mémoire.

Les calculs des paniers se vérifient aussi avec Node.js :

```sh
node --test src/test/js/montants.test.cjs
```

## Documentation

- [Interface et vérification du thème](docs/interface.md)
- [Évolution SaaS : boutiques et abonnements mensuels](docs/saas.md)
- [Migration des données existantes vers les boutiques](docs/migrations/boutiques.md)
- [Administration de la plateforme et gestion des accès](docs/migrations/plateforme.md)
- [Activation et désactivation des comptes utilisateurs](docs/migrations/acces-utilisateurs.md)
- [Paramètres de boutique et coordonnées sur les factures](docs/migrations/parametres-boutique.md)
- [Logo et coordonnées propres à chaque boutique](docs/identite-boutique.md)
- [Démarrage de boutique, stock et solde initiaux](docs/migrations/demarrage-boutique.md)
- [Caisse, Orange Money, banque et transferts](docs/tresorerie.md)
- [Migration des trois comptes de trésorerie](docs/migrations/tresorerie.md)
- [Préparation du schéma du lot 1](docs/migrations/lot-1.md)
- [Accès, CSRF et règlements clients/fournisseurs](docs/acces-et-csrf.md)
- [Comptes utilisateurs, migration des emails et premier administrateur](docs/comptes-et-migration.md)
- [Dépendances de sécurité et limite des mots de passe](docs/securite-dependances.md)
- [Schéma et mises à jour de la base](docs/database-schema.md)
- [Sauvegardes locales et maintenance MariaDB](docs/sauvegardes-et-mariadb.md)
- [Créations d'achats/ventes et protection contre les doubles envois](docs/creations-et-doubles-envois.md)
- [Montants exacts et migration DECIMAL](docs/montants-et-migration.md)
- [Audit et suivi des corrections](docs/audit-2026-10-04.md)
