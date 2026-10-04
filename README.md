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

- [Accès, CSRF et règlements fournisseurs](docs/acces-et-csrf.md)
- [Dépendances de sécurité et limite des mots de passe](docs/securite-dependances.md)
- [Schéma et mises à jour de la base](docs/database-schema.md)
- [Montants exacts et migration DECIMAL](docs/montants-et-migration.md)
- [Audit et suivi des corrections](docs/audit-2026-10-04.md)
