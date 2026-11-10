# Lot 4 : réglages des reçus

Cette migration ajoute à la table `boutique` les deux réglages des reçus imprimables
(tâche E du lot 4). Elle ne crée aucune table et ne modifie ni stocks, ni montants,
ni mouvements de caisse. Elle exige que la migration du lot 2 soit terminée.

## Colonnes ajoutées

| Table | Colonne | Type | Valeur des boutiques existantes et nouvelles |
|---|---|---|---|
| `boutique` | `format_recu` | VARCHAR(16) NOT NULL DEFAULT 'A4' | `A4` ; autre valeur permise : `TICKET_80` (ticket de 80 mm) |
| `boutique` | `proposer_impression_recu` | BOOLEAN (TINYINT(1)) NOT NULL DEFAULT 1 | 1 : le bouton « Imprimer le reçu » est proposé après chaque vente et chaque règlement |

Une boutique créée après la migration reçoit les mêmes valeurs par défaut. Une relance
ne réécrit jamais un réglage déjà choisi par le gérant.

## Procédure

Arrêter l'application et DevTools, puis sauvegarder la base complète :

```bash
./gradlew lot4Check
LOT4_BACKUP_DIR=/chemin/prive/stock_pro-sauvegarde ./gradlew lot4Migrate
./gradlew lot4Check
```

- `lot4Check` ne modifie rien : il dit si la migration est nécessaire, déjà faite, ou
  si le schéma est incohérent (type, longueur, caractère facultatif, valeur par défaut
  ou valeur inconnue : la migration est alors refusée sans rien écrire).
- `lot4Migrate` vérifie la sauvegarde indiquée par `LOT4_BACKUP_DIR` (même protection
  que les lots précédents), prend un verrou nommé MariaDB (`stock_lot4_migration`) contre
  une exécution simultanée, puis ajoute les colonnes manquantes. Elle est idempotente :
  relancée, elle ne change rien. Les colonnes ajoutées seules avant une interruption
  sont reconnues.
- Seule la syntaxe de MariaDB 10.4 est employée.

## Vérifications

```bash
./gradlew test --no-daemon --tests '*Lot4MigrationTests'   # H2 : scénarios, sauvegarde, lot 2 requis, schéma migré = schéma neuf
service mariadb start && ./gradlew lot4MariaDbTest         # MariaDB jetable : mêmes scénarios, verrou, Hibernate validate (migré et neuf)
./gradlew lot2MariaDbTest                                  # le lot 2 reste valide (Hibernate validate inclut les nouvelles colonnes)
```

Le fichier `docs/database-schema.sql` contient déjà les deux colonnes pour une base neuve.
