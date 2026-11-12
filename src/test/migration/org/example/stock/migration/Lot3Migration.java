package org.example.stock.migration;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Lot 3 : comptes de trésorerie multiples. Avant le lot, une boutique avait un compte par type, désigné par une valeur
 * d'énumération ({@code CAISSE}, {@code ORANGE_MONEY}, {@code BANQUE}) dans {@code compte_tresorerie_boutique.compte},
 * {@code mouvement_caisse.compte}, {@code operation_tresorerie.compte_source}, {@code operation_tresorerie.compte_destination} et
 * {@code retour.compte_tresorerie}. Après le lot, le type est une notion ({@code ESPECES}, {@code ORANGE_MONEY}, {@code BANQUE})
 * et chaque mouvement, opération et remboursement référence un compte par clé étrangère.
 *
 * <p>Déroulement, chaque étape pouvant être reprise après une interruption :
 * <ol>
 *   <li>ajout, sans rien supprimer, des colonnes du compte (type, nom, clé du nom, numéro, principal, actif, ordre), de la table
 *       des comptes autorisés aux caissiers et des colonnes {@code compte_id}, {@code compte_source_id}, {@code compte_destination_id}
 *       et {@code retour.compte_tresorerie_id} (facultatives pour l'instant) ;</li>
 *   <li>création des comptes de base manquants, puis remplissage : chaque compte existant devient le compte principal de son type, nommé
 *       « Caisse », « Orange Money » ou « Banque », et chaque mouvement, opération et remboursement reçoit son {@code compte_id} ;</li>
 *   <li>contrôles : aucun mouvement ni opération sans compte, compte de la même boutique, totaux par boutique et par type
 *       identiques avant et après ;</li>
 *   <li>seulement alors : colonnes rendues obligatoires, contraintes d'unicité et clés étrangères, puis suppression des anciennes
 *       colonnes à énumération.</li>
 * </ol>
 * Aucune valeur déjà migrée n'est réécrite : un compte renommé ou choisi principal depuis la migration reste tel quel.
 * Le lot 4 doit être terminé (et, par lui, les lots 1 et 2). Syntaxes acceptées par MariaDB 10.4 uniquement.
 */
public final class Lot3Migration {
    static final String VERROU = "stock_lot3_migration";
    static final List<String> ANCIENS = List.of("CAISSE", "ORANGE_MONEY", "BANQUE");
    static final List<String> TYPES = List.of("ESPECES", "ORANGE_MONEY", "BANQUE");
    static final String COMPTES = "compte_tresorerie_boutique", AUTORISES = "compte_tresorerie_autorise", PARTENAIRES = "partenaire_financier";
    private static final Set<Integer> BOOLEEN = Set.of(Types.BOOLEAN, Types.BIT, Types.TINYINT);
    private static final Set<Integer> TEXTE = Set.of(Types.VARCHAR);
    private static final Set<Integer> ENTIER = Set.of(Types.INTEGER);
    private static final Set<Integer> LONG = Set.of(Types.BIGINT);

    /** Colonne ajoutée : définition d'ajout (facultative le temps du remplissage), forme finale obligatoire ou non. */
    record Champ(String table, String nom, String ajout, String finale, Set<Integer> types, int taille, boolean obligatoire, String defaut) {
        String cle() { return table + "." + nom; }
    }
    record Cle(String table, String colonne, String nom, String cible) {}
    /** Table créée par l'étape 3b1 (financements) : colonnes attendues, unicités et clés étrangères (colonne vers table cible). */
    record TableNeuve(String nom, String creation, Set<String> colonnes, List<List<String>> uniques, Map<String, String> cles) {}

    static final List<Champ> CHAMPS = List.of(
            new Champ(COMPTES, "type", "VARCHAR(32) NULL", "VARCHAR(32) NOT NULL", TEXTE, 32, true, null),
            new Champ(COMPTES, "nom", "VARCHAR(60) NULL", "VARCHAR(60) NOT NULL", TEXTE, 60, true, null),
            new Champ(COMPTES, "nom_cle", "VARCHAR(60) NULL", "VARCHAR(60) NOT NULL", TEXTE, 60, true, null),
            new Champ(COMPTES, "numero", "VARCHAR(40) NULL", "VARCHAR(40) NULL", TEXTE, 40, false, null),
            new Champ(COMPTES, "principal_type", "VARCHAR(32) NULL", "VARCHAR(32) NULL", TEXTE, 32, false, null),
            new Champ(COMPTES, "actif", "BOOLEAN NOT NULL DEFAULT TRUE", "BOOLEAN NOT NULL DEFAULT TRUE", BOOLEEN, 0, true, "TRUE"),
            new Champ(COMPTES, "ordre", "INT NOT NULL DEFAULT 0", "INT NOT NULL DEFAULT 0", ENTIER, 0, true, "0"),
            new Champ("mouvement_caisse", "compte_id", "BIGINT NULL", "BIGINT NOT NULL", LONG, 0, true, null),
            new Champ("operation_tresorerie", "compte_source_id", "BIGINT NULL", "BIGINT NOT NULL", LONG, 0, true, null),
            new Champ("operation_tresorerie", "compte_destination_id", "BIGINT NULL", "BIGINT NULL", LONG, 0, false, null),
            new Champ("retour", "compte_tresorerie_id", "BIGINT NULL", "BIGINT NULL", LONG, 0, false, null),
            // Étape 3b1 : compteurs de numérotation EM-/PR- et rattachement des opérations de trésorerie à un partenaire et à un emprunt.
            new Champ("boutique", "prochain_numero_emprunt", "BIGINT NOT NULL DEFAULT 1", "BIGINT NOT NULL DEFAULT 1", LONG, 0, true, "1"),
            new Champ("boutique", "prochain_numero_pret", "BIGINT NOT NULL DEFAULT 1", "BIGINT NOT NULL DEFAULT 1", LONG, 0, true, "1"),
            new Champ("operation_tresorerie", "partenaire_financier_id", "BIGINT NULL", "BIGINT NULL", LONG, 0, false, null),
            new Champ("operation_tresorerie", "emprunt_id", "BIGINT NULL", "BIGINT NULL", LONG, 0, false, null));
    static final List<Cle> CLES = List.of(
            new Cle("mouvement_caisse", "compte_id", "fk_mouvement_caisse_compte", COMPTES),
            new Cle("operation_tresorerie", "compte_source_id", "fk_operation_tresorerie_source", COMPTES),
            new Cle("operation_tresorerie", "compte_destination_id", "fk_operation_tresorerie_destination", COMPTES),
            new Cle("retour", "compte_tresorerie_id", "fk_retour_compte_tresorerie", COMPTES),
            new Cle("operation_tresorerie", "partenaire_financier_id", "fk_operation_tresorerie_partenaire", PARTENAIRES),
            new Cle("operation_tresorerie", "emprunt_id", "fk_operation_tresorerie_emprunt", "emprunt"));
    /** Dans l'ordre de création : chaque table ne référence que les précédentes. */
    static final List<TableNeuve> TABLES_FINANCEMENTS = List.of(
            new TableNeuve(PARTENAIRES, "%s (id BIGINT NOT NULL %s, boutique_id BIGINT NOT NULL, nom VARCHAR(80) NOT NULL, nom_cle VARCHAR(80) NOT NULL,"
                    + " type VARCHAR(16) NOT NULL, telephone VARCHAR(30) NULL, note VARCHAR(255) NULL, actif BOOLEAN NOT NULL DEFAULT TRUE, date_creation DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id), CONSTRAINT uk_partenaire_financier_nom UNIQUE (boutique_id, nom_cle),"
                    + " CONSTRAINT fk_partenaire_financier_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id))",
                    Set.of("id", "boutique_id", "nom", "nom_cle", "type", "telephone", "note", "actif", "date_creation"),
                    List.of(List.of("boutique_id", "nom_cle")), Map.of("boutique_id", "boutique")),
            new TableNeuve("emprunt", "%s (id BIGINT NOT NULL %s, boutique_id BIGINT NOT NULL, partenaire_id BIGINT NOT NULL, utilisateur_id BIGINT NOT NULL,"
                    + " compte_id BIGINT NOT NULL, numero VARCHAR(30) NOT NULL, sens VARCHAR(16) NOT NULL, date_emprunt DATE NOT NULL, capital DECIMAL(17,2) NOT NULL,"
                    + " taux_annuel DECIMAL(5,2) NOT NULL, nombre_echeances INT NOT NULL, periodicite VARCHAR(16) NOT NULL, date_premiere_echeance DATE NOT NULL,"
                    + " frais_dossier DECIMAL(17,2) NOT NULL DEFAULT 0, note VARCHAR(255) NULL, jeton VARCHAR(36) NOT NULL, date_creation DATETIME(6) NOT NULL,"
                    + " PRIMARY KEY (id), CONSTRAINT uk_emprunt_boutique_numero UNIQUE (boutique_id, numero), CONSTRAINT uk_emprunt_jeton UNIQUE (jeton),"
                    + " CONSTRAINT fk_emprunt_boutique FOREIGN KEY (boutique_id) REFERENCES boutique (id),"
                    + " CONSTRAINT fk_emprunt_partenaire FOREIGN KEY (partenaire_id) REFERENCES partenaire_financier (id),"
                    + " CONSTRAINT fk_emprunt_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),"
                    + " CONSTRAINT fk_emprunt_compte FOREIGN KEY (compte_id) REFERENCES " + COMPTES + " (id))",
                    Set.of("id", "boutique_id", "partenaire_id", "utilisateur_id", "compte_id", "numero", "sens", "date_emprunt", "capital", "taux_annuel",
                            "nombre_echeances", "periodicite", "date_premiere_echeance", "frais_dossier", "note", "jeton", "date_creation"),
                    List.of(List.of("boutique_id", "numero"), List.of("jeton")),
                    Map.of("boutique_id", "boutique", "partenaire_id", PARTENAIRES, "utilisateur_id", "utilisateur", "compte_id", COMPTES)),
            new TableNeuve("echeance_emprunt", "%s (id BIGINT NOT NULL %s, emprunt_id BIGINT NOT NULL, rang INT NOT NULL, date_echeance DATE NOT NULL,"
                    + " capital DECIMAL(17,2) NOT NULL, interet DECIMAL(17,2) NOT NULL, PRIMARY KEY (id),"
                    + " CONSTRAINT uk_echeance_emprunt_rang UNIQUE (emprunt_id, rang),"
                    + " CONSTRAINT fk_echeance_emprunt_emprunt FOREIGN KEY (emprunt_id) REFERENCES emprunt (id))",
                    Set.of("id", "emprunt_id", "rang", "date_echeance", "capital", "interet"),
                    List.of(List.of("emprunt_id", "rang")), Map.of("emprunt_id", "emprunt")),
            new TableNeuve("imputation_echeance", "%s (id BIGINT NOT NULL %s, operation_id BIGINT NOT NULL, echeance_id BIGINT NOT NULL,"
                    + " capital DECIMAL(17,2) NOT NULL, interet DECIMAL(17,2) NOT NULL, interet_remis DECIMAL(17,2) NOT NULL DEFAULT 0, PRIMARY KEY (id),"
                    + " CONSTRAINT fk_imputation_echeance_operation FOREIGN KEY (operation_id) REFERENCES operation_tresorerie (id),"
                    + " CONSTRAINT fk_imputation_echeance_echeance FOREIGN KEY (echeance_id) REFERENCES echeance_emprunt (id))",
                    Set.of("id", "operation_id", "echeance_id", "capital", "interet", "interet_remis"),
                    List.of(), Map.of("operation_id", "operation_tresorerie", "echeance_id", "echeance_emprunt")));
    /** Anciennes colonnes à énumération, supprimées en dernier. */
    static final List<String[]> ANCIENNES_COLONNES = List.of(
            new String[]{"mouvement_caisse", "compte"}, new String[]{"operation_tresorerie", "compte_source"},
            new String[]{"operation_tresorerie", "compte_destination"}, new String[]{"retour", "compte_tresorerie"}, new String[]{COMPTES, "compte"});
    private Lot3Migration() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        if (args.length != 1 || !Set.of("--check", "--apply").contains(args[0])) throw new IllegalArgumentException("Utiliser --check ou --apply");
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String url = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")).replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            boolean appliquer = args[0].equals("--apply");
            if (appliquer) verifierAutorisationApplication(url, System.getenv("LOT3_BACKUP_DIR"));
            try (var c = DriverManager.getConnection(url, AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username")),
                    AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password")))) { migrer(c, appliquer); }
        } catch (Exception e) {
            // Les pilotes peuvent inclure des valeurs métier dans leurs exceptions.
            System.err.println("Migration lot 3 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Connexion ou opération SQL impossible ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    static void verifierAutorisationApplication(String url, String dossier) throws Exception {
        BoutiquesMigration.verifierCibleSauvegarde(url);
        if (dossier == null || dossier.isBlank()) throw new IllegalStateException("Une sauvegarde SQL complète est requise : définir LOT3_BACKUP_DIR sur le répertoire créé par scripts/backup-stock-local.sh.");
        BoutiquesMigration.verifierSauvegarde(dossier);
    }

    static void migrer(Connection c, boolean appliquer) throws SQLException {
        if (!Set.of("H2", "MariaDB").contains(c.getMetaData().getDatabaseProductName())) throw new IllegalStateException("MariaDB ou H2 requis.");
        if (!c.getAutoCommit()) throw new IllegalStateException("Connexion dédiée sans transaction ouverte requise.");
        boolean verrou = false;
        try {
            c.setReadOnly(!appliquer);
            if (appliquer && maria(c)) {
                if (BoutiquesMigration.compter(c, "SELECT GET_LOCK('" + VERROU + "',0)") != 1) throw new IllegalStateException("Une migration lot 3 est déjà en cours.");
                verrou = true;
            }
            exigerLot4(c);
            c.setReadOnly(!appliquer);
            verifierPresents(c);
            if (!appliquer) {
                System.out.println("Lot 3 vérifié en lecture seule ; éléments manquants : " + manquants(c) + ", comptes de base à créer : " + comptesACreer(c)
                        + ", mouvements à rattacher : " + aRattacher(c) + ". Aucun stock, montant ni historique modifié.");
                return;
            }
            var avant = photographie(c); var schemas = schemasAnciens(c); var totaux = totauxParType(c); var comptes = comptesExistants(c);
            boolean dejaFinalise = manquants(c) == 0;
            if (!dejaFinalise) {
                ajouterColonnes(c);
                creerAutorises(c);
                creerFinancements(c);
                verifierPresents(c);
                remplir(c);
                verifierRemplissage(c, totaux);
                finaliser(c);
            }
            verifierPresents(c);
            if (manquants(c) != 0 || !avant.equals(photographie(c)) || !schemas.equals(schemasAnciens(c)) || !totaux.equals(totauxParType(c)) || !comptesConserves(c, comptes))
                nonConforme("des données ou colonnes existantes ont changé ; conserver l'application arrêtée et examiner la sauvegarde");
            System.out.println(dejaFinalise ? "Lot 3 déjà migré ; aucune valeur réécrite."
                    : "Migration lot 3 terminée ; comptes de base conservés comme comptes principaux, mouvements rattachés, totaux par boutique et par type inchangés.");
        } finally { if (verrou) BoutiquesMigration.compter(c, "SELECT RELEASE_LOCK('" + VERROU + "')"); }
    }

    /** Le lot 3 s'appuie sur les lots 1, 2 et 4 ; une base déjà migrée n'a plus les colonnes que ces lots contrôlaient. */
    private static void exigerLot4(Connection c) throws SQLException {
        var tables = BoutiquesMigration.tables(c);
        if (!tables.containsKey("boutique") || !tables.containsKey(COMPTES) || !tables.containsKey("mouvement_caisse") || !tables.containsKey("operation_tresorerie") || !tables.containsKey("retour"))
            throw new IllegalStateException("La migration du lot 4 doit être terminée avant le lot 3 : lancer lot4Check puis lot4Migrate (LOT4_BACKUP_DIR) après sauvegarde.");
        if (ancien(c)) {
            if (!Lot1Migration.finalise(c) || !Lot2Migration.finalise(c) || !Lot4Migration.finalise(c))
                throw new IllegalStateException("La migration du lot 4 doit être terminée avant le lot 3 : lancer lot4Check puis lot4Migrate (LOT4_BACKUP_DIR) après sauvegarde.");
        }
    }

    /** Vrai tant qu'au moins une ancienne colonne à énumération subsiste. */
    static boolean ancien(Connection c) throws SQLException {
        for (var a : ANCIENNES_COLONNES) if (colonnes(c, a[0]).containsKey(a[1])) return true;
        return false;
    }

    /** Vrai une fois les anciennes colonnes supprimées : les contrôles des migrations précédentes qui les citent ne s'appliquent plus. */
    static boolean appliquee(Connection c) throws SQLException {
        return BoutiquesMigration.tables(c).containsKey(COMPTES) && !ancien(c) && colonnes(c, "mouvement_caisse").containsKey("compte_id");
    }

    static void ajouterColonnes(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (!colonnes(c, champ.table()).containsKey(champ.nom()))
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " ADD COLUMN " + champ.nom() + " " + champ.ajout());
    }

    static void creerAutorises(Connection c) throws SQLException {
        if (BoutiquesMigration.tables(c).containsKey(AUTORISES)) return;
        sql(c, "CREATE TABLE " + AUTORISES + " (id BIGINT NOT NULL " + (maria(c) ? "AUTO_INCREMENT" : "GENERATED BY DEFAULT AS IDENTITY") + ", utilisateur_id BIGINT NOT NULL, compte_id BIGINT NOT NULL,"
                + " PRIMARY KEY (id), CONSTRAINT uk_compte_tresorerie_autorise UNIQUE (utilisateur_id, compte_id),"
                + " CONSTRAINT fk_compte_autorise_utilisateur FOREIGN KEY (utilisateur_id) REFERENCES utilisateur (id),"
                + " CONSTRAINT fk_compte_autorise_compte FOREIGN KEY (compte_id) REFERENCES " + COMPTES + " (id))"
                + (maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
    }

    /** Tables de l'étape 3b1, vides à la création ; une table déjà présente n'est ni recréée ni modifiée. */
    static void creerFinancements(Connection c) throws SQLException {
        for (var t : TABLES_FINANCEMENTS) {
            if (BoutiquesMigration.tables(c).containsKey(t.nom())) continue;
            sql(c, "CREATE TABLE " + t.nom() + " " + t.creation().formatted("", maria(c) ? "AUTO_INCREMENT" : "GENERATED BY DEFAULT AS IDENTITY").substring(1)
                    + (maria(c) ? " ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci" : ""));
        }
    }

    /**
     * Comptes de base manquants, puis remplissage. Seules les lignes sans type sont écrites, dans une même instruction : un compte
     * déjà migré (renommé, rendu principal ou archivé depuis) n'est jamais réécrit.
     */
    static void remplir(Connection c) throws SQLException {
        if (!colonnes(c, COMPTES).containsKey("compte")) return;
        for (String ancien : ANCIENS) sql(c, "INSERT INTO " + table(c, COMPTES) + "(boutique_id,compte) SELECT b.id,'" + ancien + "' FROM " + table(c, "boutique")
                + " b WHERE NOT EXISTS (SELECT 1 FROM " + table(c, COMPTES) + " x WHERE x.boutique_id=b.id AND x.compte='" + ancien + "')");
        String type = "CASE compte WHEN 'CAISSE' THEN 'ESPECES' ELSE compte END";
        sql(c, "UPDATE " + table(c, COMPTES) + " SET type=" + type + ", principal_type=" + type
                + ", nom=CASE compte WHEN 'CAISSE' THEN 'Caisse' WHEN 'ORANGE_MONEY' THEN 'Orange Money' ELSE 'Banque' END"
                + ", nom_cle=CASE compte WHEN 'CAISSE' THEN 'caisse' WHEN 'ORANGE_MONEY' THEN 'orange money' ELSE 'banque' END"
                + ", ordre=CASE compte WHEN 'CAISSE' THEN 0 WHEN 'ORANGE_MONEY' THEN 1 ELSE 2 END WHERE type IS NULL");
        String compte = "(SELECT x.id FROM " + table(c, COMPTES) + " x WHERE x.boutique_id=%s.boutique_id AND x.compte=%s.%s)";
        if (colonnes(c, "mouvement_caisse").containsKey("compte"))
            sql(c, "UPDATE " + table(c, "mouvement_caisse") + " SET compte_id=" + compte.formatted("mouvement_caisse", "mouvement_caisse", "compte") + " WHERE compte_id IS NULL");
        if (colonnes(c, "operation_tresorerie").containsKey("compte_source")) {
            sql(c, "UPDATE " + table(c, "operation_tresorerie") + " SET compte_source_id=" + compte.formatted("operation_tresorerie", "operation_tresorerie", "compte_source") + " WHERE compte_source_id IS NULL");
            sql(c, "UPDATE " + table(c, "operation_tresorerie") + " SET compte_destination_id=" + compte.formatted("operation_tresorerie", "operation_tresorerie", "compte_destination")
                    + " WHERE compte_destination_id IS NULL AND compte_destination IS NOT NULL");
        }
        if (colonnes(c, "retour").containsKey("compte_tresorerie"))
            sql(c, "UPDATE " + table(c, "retour") + " SET compte_tresorerie_id=" + compte.formatted("retour", "retour", "compte_tresorerie")
                    + " WHERE compte_tresorerie_id IS NULL AND compte_tresorerie IS NOT NULL");
    }

    /** Aucune suppression ni contrainte avant que tout soit rattaché, cohérent et que les totaux par boutique et par type soient identiques. */
    private static void verifierRemplissage(Connection c, Map<String, String> totaux) throws SQLException {
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type IS NULL OR nom IS NULL OR nom_cle IS NULL") != 0)
            nonConforme("un compte n'a pas pu recevoir de type ni de nom");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + " WHERE compte_id IS NULL") != 0)
            nonConforme("un mouvement de caisse n'a pas pu être rattaché à un compte");
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "operation_tresorerie") + " WHERE compte_source_id IS NULL") != 0)
            nonConforme("une opération de trésorerie n'a pas pu être rattachée à son compte source");
        if (colonnes(c, "operation_tresorerie").containsKey("compte_destination") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "operation_tresorerie")
                + " WHERE compte_destination IS NOT NULL AND compte_destination_id IS NULL") != 0) nonConforme("une opération n'a pas pu être rattachée à son compte destination");
        if (colonnes(c, "retour").containsKey("compte_tresorerie") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "retour")
                + " WHERE compte_tresorerie IS NOT NULL AND compte_tresorerie_id IS NULL") != 0) nonConforme("un remboursement n'a pas pu être rattaché à son compte");
        if (!totaux.equals(totauxParType(c))) nonConforme("les totaux par boutique et par type ont changé");
        verifierValeurs(c);
    }

    /** Étape finale : colonnes obligatoires, unicités, clés étrangères, puis suppression des anciennes colonnes. */
    static void finaliser(Connection c) throws SQLException {
        for (var champ : CHAMPS) if (champ.obligatoire() && colonnes(c, champ.table()).get(champ.nom()).nullable() != DatabaseMetaData.columnNoNulls)
            sql(c, "ALTER TABLE " + table(c, champ.table()) + " " + (maria(c) ? "MODIFY COLUMN " : "ALTER COLUMN ") + champ.nom() + " " + champ.finale());
        var uniques = indexesUniques(c, COMPTES).values();
        if (!uniques.contains(List.of("boutique_id", "nom_cle"))) sql(c, "ALTER TABLE " + table(c, COMPTES) + " ADD CONSTRAINT uk_compte_tresorerie_nom UNIQUE (boutique_id, nom_cle)");
        if (!uniques.contains(List.of("boutique_id", "principal_type"))) sql(c, "ALTER TABLE " + table(c, COMPTES) + " ADD CONSTRAINT uk_compte_tresorerie_principal UNIQUE (boutique_id, principal_type)");
        for (var cle : CLES) if (!cles(c, cle.table()).containsKey(cle.colonne()))
            sql(c, "ALTER TABLE " + table(c, cle.table()) + " ADD CONSTRAINT " + cle.nom() + " FOREIGN KEY (" + cle.colonne() + ") REFERENCES " + table(c, cle.cible()) + " (id)");
        if (colonnes(c, COMPTES).containsKey("compte") && indexesUniques(c, COMPTES).containsValue(List.of("boutique_id", "compte")))
            sql(c, "ALTER TABLE " + table(c, COMPTES) + (maria(c) ? " DROP INDEX " : " DROP CONSTRAINT ") + "uk_compte_tresorerie_boutique");
        for (var ancienne : ANCIENNES_COLONNES) if (colonnes(c, ancienne[0]).containsKey(ancienne[1]))
            sql(c, "ALTER TABLE " + table(c, ancienne[0]) + " DROP COLUMN " + ancienne[1]);
    }

    private static int manquants(Connection c) throws SQLException {
        int manquants = 0;
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col == null || champ.obligatoire() && col.nullable() != DatabaseMetaData.columnNoNulls) manquants++;
        }
        if (!BoutiquesMigration.tables(c).containsKey(AUTORISES)) manquants++;
        for (var t : TABLES_FINANCEMENTS) if (!BoutiquesMigration.tables(c).containsKey(t.nom())) manquants++;
        for (var a : ANCIENNES_COLONNES) if (colonnes(c, a[0]).containsKey(a[1])) manquants++;
        if (colonnes(c, COMPTES).containsKey("nom_cle")) {
            var uniques = indexesUniques(c, COMPTES).values();
            if (!uniques.contains(List.of("boutique_id", "nom_cle"))) manquants++;
            if (!uniques.contains(List.of("boutique_id", "principal_type"))) manquants++;
        }
        for (var cle : CLES) if (colonnes(c, cle.table()).containsKey(cle.colonne()) && !cles(c, cle.table()).containsKey(cle.colonne())) manquants++;
        return manquants;
    }

    private static long comptesACreer(Connection c) throws SQLException {
        if (!colonnes(c, COMPTES).containsKey("compte")) return 0;
        long total = 0;
        for (String ancien : ANCIENS) total += BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "boutique") + " b WHERE NOT EXISTS (SELECT 1 FROM " + table(c, COMPTES)
                + " x WHERE x.boutique_id=b.id AND x.compte='" + ancien + "')");
        return total;
    }

    private static long aRattacher(Connection c) throws SQLException {
        if (!colonnes(c, "mouvement_caisse").containsKey("compte")) return 0;
        String condition = colonnes(c, "mouvement_caisse").containsKey("compte_id") ? " WHERE compte_id IS NULL" : "";
        return BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse") + condition);
    }

    private static void verifierPresents(Connection c) throws SQLException {
        boolean strict = !ancien(c);
        for (var champ : CHAMPS) {
            var col = colonnes(c, champ.table()).get(champ.nom());
            if (col != null) verifierColonne(champ, col, strict);
        }
        if (BoutiquesMigration.tables(c).containsKey(AUTORISES)) {
            var cols = colonnes(c, AUTORISES);
            if (!cols.keySet().equals(Set.of("id", "utilisateur_id", "compte_id"))) nonConforme("colonnes inattendues dans " + AUTORISES);
            if (!indexesUniques(c, AUTORISES).containsValue(List.of("utilisateur_id", "compte_id"))) nonConforme(AUTORISES + " : unicité (utilisateur, compte) absente");
            var attendues = new TreeMap<String, String>(); attendues.put("compte_id", COMPTES); attendues.put("utilisateur_id", "utilisateur");
            if (!cles(c, AUTORISES).equals(attendues)) nonConforme(AUTORISES + " : clés étrangères absentes ou vers une mauvaise table");
        }
        for (var cle : CLES) if (colonnes(c, cle.table()).containsKey(cle.colonne())) {
            String cible = cles(c, cle.table()).get(cle.colonne());
            if (cible != null && !cible.equals(cle.cible())) nonConforme(cle.table() + "." + cle.colonne() + " doit référencer " + cle.cible());
        }
        for (var t : TABLES_FINANCEMENTS) if (BoutiquesMigration.tables(c).containsKey(t.nom())) {
            if (!colonnes(c, t.nom()).keySet().equals(t.colonnes())) nonConforme("colonnes inattendues dans " + t.nom());
            for (var unique : t.uniques()) if (!indexesUniques(c, t.nom()).containsValue(unique)) nonConforme(t.nom() + " : unicité " + unique + " absente");
            if (!cles(c, t.nom()).equals(new TreeMap<>(t.cles()))) nonConforme(t.nom() + " : clés étrangères absentes ou vers une mauvaise table");
        }
        if (colonnes(c, COMPTES).containsKey("nom_cle")) {
            var uniques = indexesUniques(c, COMPTES).values();
            if (uniques.contains(List.of("boutique_id")) || uniques.contains(List.of("nom_cle"))) nonConforme(COMPTES + " : unicité globale au lieu d'une unicité par boutique");
        }
        verifierValeurs(c);
    }

    private static void verifierColonne(Champ champ, InscriptionEssaiMigration.Colonne col, boolean strict) {
        boolean booleen = champ.types().equals(BOOLEEN);
        boolean nullable = col.nullable() == DatabaseMetaData.columnNullable;
        // Le temps du remplissage, une colonne finalement obligatoire peut encore accepter le vide ; ensuite elle est stricte.
        boolean nullabiliteIncorrecte = champ.obligatoire() ? nullable && (strict || champ.ajout().contains("NOT NULL")) : !nullable;
        if (!champ.types().contains(col.type()) || col.autoIncrement() || champ.taille() > 0 && col.taille() != champ.taille()
                || booleen && col.type() == Types.BIT && col.taille() != 1 || nullabiliteIncorrecte
                || champ.defaut() != null && !(booleen ? PlateformeMigration.defautConforme(col.defaut(), true) : defautEgal(col.defaut(), champ.defaut()))
                || champ.defaut() == null && !defautNul(col.defaut()))
            nonConforme(champ.cle() + " doit être " + (strict ? champ.finale() : champ.ajout()));
    }

    private static void verifierValeurs(Connection c) throws SQLException {
        var comptes = colonnes(c, COMPTES);
        if (comptes.containsKey("compte") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE compte NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0)
            nonConforme("un compte de trésorerie a un type inconnu");
        if (colonnes(c, "mouvement_caisse").containsKey("compte") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "mouvement_caisse")
                + " WHERE compte NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0) nonConforme("un mouvement de caisse a un compte inconnu");
        var operations = colonnes(c, "operation_tresorerie");
        if (operations.containsKey("compte_source") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "operation_tresorerie")
                + " WHERE compte_source NOT IN ('CAISSE','ORANGE_MONEY','BANQUE') OR compte_destination IS NOT NULL AND compte_destination NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0)
            nonConforme("une opération de trésorerie a un compte inconnu");
        if (colonnes(c, "retour").containsKey("compte_tresorerie") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, "retour")
                + " WHERE compte_tresorerie IS NOT NULL AND compte_tresorerie NOT IN ('CAISSE','ORANGE_MONEY','BANQUE')") != 0) nonConforme("un remboursement a un compte inconnu");
        if (comptes.containsKey("type") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE type IS NOT NULL AND type NOT IN ('ESPECES','ORANGE_MONEY','BANQUE')") != 0)
            nonConforme("un compte de trésorerie a un type inconnu");
        if (comptes.containsKey("type") && comptes.containsKey("principal_type") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES)
                + " WHERE principal_type IS NOT NULL AND (type IS NULL OR principal_type<>type)") != 0) nonConforme("un compte principal n'a pas le type qu'il représente");
        if (comptes.containsKey("ordre") && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, COMPTES) + " WHERE ordre<0") != 0) nonConforme("l'ordre d'un compte est négatif");
        coherenceBoutiques(c, "mouvement_caisse", "compte_id");
        coherenceBoutiques(c, "operation_tresorerie", "compte_source_id");
        coherenceBoutiques(c, "operation_tresorerie", "compte_destination_id");
        coherenceBoutiques(c, "retour", "compte_tresorerie_id");
        if (BoutiquesMigration.tables(c).containsKey(AUTORISES) && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, AUTORISES) + " a JOIN " + table(c, COMPTES)
                + " x ON x.id=a.compte_id JOIN " + table(c, "utilisateur") + " u ON u.id=a.utilisateur_id WHERE x.boutique_id<>u.boutique_id") != 0)
            nonConforme("un caissier est autorisé sur le compte d'une autre boutique");
    }

    /** Un document ne référence jamais le compte d'une autre boutique. */
    private static void coherenceBoutiques(Connection c, String table, String colonne) throws SQLException {
        if (!colonnes(c, table).containsKey(colonne) || !colonnes(c, COMPTES).containsKey("type")) return;
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM " + table(c, table) + " t JOIN " + table(c, COMPTES) + " x ON x.id=t." + colonne + " WHERE x.boutique_id<>t.boutique_id") != 0)
            nonConforme(table + "." + colonne + " référence le compte d'une autre boutique");
    }

    /** Somme et nombre de mouvements par boutique et par type de compte, calculés depuis l'ancienne colonne ou, une fois migrée, depuis le compte. */
    static Map<String, String> totauxParType(Connection c) throws SQLException {
        var resultat = new TreeMap<String, String>();
        boolean ancien = colonnes(c, "mouvement_caisse").containsKey("compte");
        String type = ancien ? "CASE m.compte WHEN 'CAISSE' THEN 'ESPECES' ELSE m.compte END" : "x.type";
        String jointure = ancien ? "" : " JOIN " + table(c, COMPTES) + " x ON x.id=m.compte_id";
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT m.boutique_id," + type + ",SUM(m.montant),COUNT(*) FROM " + table(c, "mouvement_caisse") + " m" + jointure
                + " GROUP BY m.boutique_id," + type)) {
            while (r.next()) {
                BigDecimal somme = r.getBigDecimal(3) == null ? BigDecimal.ZERO : r.getBigDecimal(3);
                resultat.put(r.getLong(1) + "|" + r.getString(2), somme.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString() + "|" + r.getLong(4));
            }
        }
        return resultat;
    }

    /** Identifiants des comptes existants avant l'étape : aucun n'est supprimé ni déplacé. */
    private static List<List<Object>> comptesExistants(Connection c) throws SQLException {
        var resultat = new ArrayList<List<Object>>();
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT id,boutique_id FROM " + table(c, COMPTES) + " ORDER BY id")) {
            while (r.next()) resultat.add(Arrays.asList(r.getLong(1), r.getLong(2)));
        }
        return resultat;
    }

    private static boolean comptesConserves(Connection c, List<List<Object>> avant) throws SQLException {
        return comptesExistants(c).containsAll(avant);
    }

    private static boolean nouvelleTable(String nom) {
        for (var t : TABLES_FINANCEMENTS) if (t.nom().equals(nom)) return true;
        return false;
    }

    static Set<String> nouvelles(String table) {
        var resultat = new TreeSet<String>();
        for (var champ : CHAMPS) if (champ.table().equals(table)) resultat.add(champ.nom());
        for (var ancienne : ANCIENNES_COLONNES) if (ancienne[0].equals(table)) resultat.add(ancienne[1]);
        return resultat;
    }

    /** Toutes les tables autres que les comptes, avec toutes leurs colonnes hors celles du lot 3 : stocks, montants et mouvements compris. */
    static Map<String, List<List<Object>>> photographie(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (nom.equals(COMPTES) || nom.equals(AUTORISES) || nouvelleTable(nom)) continue;
            var exclues = nouvelles(nom); var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY " + PhotographieJdbc.ordre(c, nom))) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) {
                        if (exclues.contains(r.getMetaData().getColumnName(i).toLowerCase(Locale.ROOT))) continue;
                        ligne.add(PhotographieJdbc.valeur(r, i));
                    }
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    static Map<String, Map<String, InscriptionEssaiMigration.Colonne>> schemasAnciens(Connection c) throws SQLException {
        var resultat = new TreeMap<String, Map<String, InscriptionEssaiMigration.Colonne>>();
        for (String nom : BoutiquesMigration.tables(c).keySet()) {
            if (nom.equals(COMPTES) || nom.equals(AUTORISES) || nouvelleTable(nom)) continue;
            var champs = new TreeMap<>(colonnes(c, nom));
            champs.keySet().removeAll(nouvelles(nom));
            resultat.put(nom, champs);
        }
        return resultat;
    }

    /** Contenu des comptes et des autorisations : une relance ne le modifie jamais. */
    static Map<String, List<List<Object>>> donneesComptes(Connection c) throws SQLException {
        var resultat = new TreeMap<String, List<List<Object>>>();
        for (String nom : List.of(COMPTES, AUTORISES)) if (BoutiquesMigration.tables(c).containsKey(nom)) {
            var lignes = new ArrayList<List<Object>>();
            try (var s = c.createStatement(); var r = s.executeQuery("SELECT * FROM " + table(c, nom) + " ORDER BY id")) {
                while (r.next()) {
                    var ligne = new ArrayList<Object>();
                    for (int i = 1; i <= r.getMetaData().getColumnCount(); i++) ligne.add(PhotographieJdbc.valeur(r, i));
                    lignes.add(ligne);
                }
            }
            resultat.put(nom, lignes);
        }
        return resultat;
    }

    static Map<String, List<String>> indexesUniques(Connection c, String nom) throws SQLException { return Lot2Migration.indexesUniques(c, nom); }

    /** Colonne -> table référencée, pour toutes les clés étrangères simples d'une table. */
    static Map<String, String> cles(Connection c, String nom) throws SQLException {
        var resultat = new TreeMap<String, String>();
        try (var r = c.getMetaData().getImportedKeys(c.getCatalog(), c.getSchema(), BoutiquesMigration.tables(c).get(nom))) {
            while (r.next()) {
                String colonne = r.getString("FKCOLUMN_NAME").toLowerCase(Locale.ROOT);
                if (!r.getString("PKCOLUMN_NAME").equalsIgnoreCase("id")
                        || !Set.of((int) DatabaseMetaData.importedKeyNoAction, (int) DatabaseMetaData.importedKeyRestrict).contains((int) r.getShort("DELETE_RULE"))
                        || resultat.put(colonne, r.getString("PKTABLE_NAME").toLowerCase(Locale.ROOT)) != null)
                    nonConforme(nom + "." + colonne + " : relation composite, redondante ou avec cascade");
            }
        }
        return resultat;
    }

    static boolean finalise(Connection c) throws SQLException { return manquants(c) == 0; }

    private static boolean defautNul(String valeur) { return valeur == null || valeur.strip().equalsIgnoreCase("NULL"); }
    private static boolean defautEgal(String valeur, String attendu) {
        if (valeur == null) return false;
        String v = valeur.strip();
        while (v.startsWith("(") && v.endsWith(")")) v = v.substring(1, v.length() - 1).strip();
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) v = v.substring(1, v.length() - 1);
        try { return new BigDecimal(v).compareTo(new BigDecimal(attendu)) == 0; }
        catch (NumberFormatException e) { return v.equals(attendu); }
    }

    private static boolean maria(Connection c) throws SQLException { return c.getMetaData().getDatabaseProductName().equals("MariaDB"); }
    private static Map<String, InscriptionEssaiMigration.Colonne> colonnes(Connection c, String nom) throws SQLException { return InscriptionEssaiMigration.colonnes(c, nom); }
    private static String table(Connection c, String nom) throws SQLException {
        String physique = BoutiquesMigration.tables(c).get(nom);
        if (physique == null || !physique.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("Identifiant SQL inattendu.");
        String q = c.getMetaData().getIdentifierQuoteString().strip(); return q + physique + q;
    }
    static void sql(Connection c, String requete) throws SQLException { BoutiquesMigration.executer(c, requete); }
    private static void nonConforme(String raison) { throw new IllegalStateException("Schéma lot 3 non conforme : " + raison + ". Aucune réparation automatique."); }
}
