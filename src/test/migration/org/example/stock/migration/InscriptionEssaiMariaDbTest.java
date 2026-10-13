package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Scénarios MariaDB locaux jetables : la base stock_pro n'est jamais utilisée. */
public final class InscriptionEssaiMariaDbTest {
    private static final String PREFIXE = "stock_inscription_essai_test_";
    private static final Set<String> VALIDES = Set.of("ancien", "relance", "conforme", "vide", "debut_seul", "fin_seule", "table_inscription", "table_parametres", "colonnes_essai");
    private static final List<String> SCENARIOS = List.of("ancien", "relance", "conforme", "vide", "debut_seul", "fin_seule", "table_inscription", "table_parametres", "colonnes_essai",
            "essai_precision", "essai_type", "essai_defaut", "essai_isole", "essai_ordre", "essai_null",
            "inscription_partielle", "inscription_colonne", "email_taille", "jeton_type", "inscription_nullable", "date_precision", "date_type", "inscription_defaut", "inscription_primaire", "unique_absent", "unique_nom", "unique_date", "unique_prefixe", "primaire_prefixe", "charset", "collation", "moteur",
            "jeton_invalide", "expiration", "email_invalide", "email_non_normalise",
            "parametres_partiels", "parametres_type", "parametres_unsigned", "parametres_nullable", "parametres_defaut", "parametres_auto", "parametres_primaire", "parametres_unique", "parametres_relation", "parametres_id", "parametres_duree", "parametres_maximum",
            "recuperation_absente", "recuperation_invalide", "acces_invalide", "plateforme", "relation", "verrou");

    private InscriptionEssaiMariaDbTest() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String scenarioEnCours = "préparation";
        try {
            List<String> scenarios = args.length == 0 ? SCENARIOS : List.of(args);
            if (!SCENARIOS.containsAll(scenarios)) throw new IllegalStateException("Scénario de test MariaDB inconnu.");
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            String serveur = urlServeurTest(url, System.getenv("INSCRIPTION_ESSAI_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                for (String scenario : scenarios) {
                    scenarioEnCours = scenario;
                    c.setReadOnly(false);
                    String base = PREFIXE + scenario.substring(0, Math.min(16, scenario.length())) + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    verifierBaseTemporaire(base);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base); preparerBase(c, scenario.equals("vide")); preparerScenario(c, scenario);
                        if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                        else verifierScenario(c, scenario);
                        reussis++; System.out.println("OK inscription et essai MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null); verifierBaseTemporaire(base); sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios inscription et essai MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification inscription et essai MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour le scénario " + scenarioEnCours
                    + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "")
                    + " ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    static String urlServeurTest(String urlApplication, String urlTest) {
        boolean prive = urlTest != null && !urlTest.isBlank();
        String url = prive ? urlTest : urlApplication;
        URI cible;
        try { cible = URI.create(url.substring(5)); }
        catch (RuntimeException e) { throw new IllegalStateException("URL de test MariaDB locale invalide."); }
        boolean portValide = prive ? cible.getPort() >= 1024 && cible.getPort() <= 65535 : Set.of(-1, 3306).contains(cible.getPort());
        boolean cheminValide = prive ? "/".equals(cible.getPath()) : "/stock_pro".equals(cible.getPath());
        if (!"mariadb".equals(cible.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(cible.getHost())
                || !portValide || !cheminValide || cible.getUserInfo() != null || cible.getFragment() != null)
            throw new IllegalStateException("Serveur MariaDB local requis ; une URL privée doit viser uniquement le serveur sans catalogue.");
        return (prive ? url : url.replace("/stock_pro?", "/?").replace("/stock_pro", "/"))
                .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
    }

    private static void preparerBase(Connection c, boolean vide) throws SQLException {
        BoutiquesMariaDbTest.schema(c, !vide); BoutiquesMigration.migrer(c, true);
        if (!vide) {
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
        }
        PlateformeMigration.migrer(c, true, vide ? null : "admin@example.test"); AccesUtilisateurMigration.migrer(c, true); ParametresBoutiqueMigration.migrer(c, true);
        c.setReadOnly(false);
        if (!vide) {
            sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE,adresse='Kaloum, Conakry',telephone='+224 600 00 00 00',email='contact@example.test' WHERE id=2");
            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=3");
        }
        ReinitialisationMdpMigration.migrer(c, true);
        c.setReadOnly(false);
        if (!vide) sql(c, "INSERT INTO reinitialisation_mot_de_passe VALUES(3,REPEAT('a',64),REPEAT('b',64),'2025-01-01 00:00:00.123456','2025-01-01 00:30:00.123456')");
        else sql(c, "DELETE FROM boutique"); // Retirer uniquement la boutique vide créée par la fixture de la migration historique.
    }

    private static void preparerScenario(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "relance", "vide", "verrou").contains(scenario)) return;
        if (scenario.equals("debut_seul") || scenario.equals("fin_seule")) {
            sql(c, "ALTER TABLE boutique ADD COLUMN " + (scenario.equals("debut_seul") ? "essai_debut" : "essai_fin") + " DATETIME(6) NULL"); return;
        }
        if (scenario.equals("table_inscription")) { InscriptionEssaiMigration.creerInscriptions(c); inserer(c); return; }
        if (scenario.equals("table_parametres")) { InscriptionEssaiMigration.creerParametres(c); sql(c, "INSERT INTO parametres_plateforme VALUES(1,45)"); return; }
        if (scenario.equals("colonnes_essai")) { InscriptionEssaiMigration.ajouterEssais(c); remplirEssai(c); return; }
        InscriptionEssaiMigration.ajouterEssais(c); InscriptionEssaiMigration.creerInscriptions(c); InscriptionEssaiMigration.creerParametres(c);
        if (scenario.equals("conforme")) { remplirEssai(c); inserer(c); sql(c, "INSERT INTO parametres_plateforme VALUES(1,45)"); }
        switch (scenario) {
            case "essai_precision" -> sql(c, "ALTER TABLE boutique MODIFY COLUMN essai_fin DATETIME(3) NULL");
            case "essai_type" -> sql(c, "ALTER TABLE boutique MODIFY COLUMN essai_debut TIMESTAMP(6) NULL");
            case "essai_defaut" -> sql(c, "ALTER TABLE boutique ALTER COLUMN essai_debut SET DEFAULT CURRENT_TIMESTAMP");
            case "essai_isole" -> { sql(c, "ALTER TABLE boutique DROP COLUMN essai_fin"); sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00' WHERE id=1"); }
            case "essai_ordre" -> sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00',essai_fin='2025-01-01 00:00:00' WHERE id=1");
            case "essai_null" -> sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00' WHERE id=1");
            case "inscription_partielle" -> sql(c, "ALTER TABLE inscription_boutique DROP COLUMN expire_le");
            case "inscription_colonne" -> sql(c, "ALTER TABLE inscription_boutique ADD COLUMN password VARCHAR(255)");
            case "email_taille" -> sql(c, "ALTER TABLE inscription_boutique MODIFY COLUMN email VARCHAR(253) NOT NULL");
            case "jeton_type" -> sql(c, "ALTER TABLE inscription_boutique MODIFY COLUMN empreinte_jeton CHAR(64) NOT NULL");
            case "inscription_nullable" -> sql(c, "ALTER TABLE inscription_boutique MODIFY COLUMN empreinte_jeton VARCHAR(64) NULL");
            case "date_precision" -> sql(c, "ALTER TABLE inscription_boutique MODIFY COLUMN cree_le DATETIME(3) NOT NULL");
            case "date_type" -> sql(c, "ALTER TABLE inscription_boutique MODIFY COLUMN expire_le TIMESTAMP(6) NOT NULL");
            case "inscription_defaut" -> sql(c, "ALTER TABLE inscription_boutique ALTER COLUMN empreinte_jeton SET DEFAULT 'NULL'");
            case "inscription_primaire" -> sql(c, "ALTER TABLE inscription_boutique DROP PRIMARY KEY");
            case "unique_absent" -> sql(c, "ALTER TABLE inscription_boutique DROP INDEX uk_inscription_boutique_jeton");
            case "unique_nom" -> { sql(c, "ALTER TABLE inscription_boutique DROP INDEX uk_inscription_boutique_jeton"); sql(c, "ALTER TABLE inscription_boutique ADD CONSTRAINT nom_inattendu UNIQUE(empreinte_jeton)"); }
            case "unique_date" -> sql(c, "ALTER TABLE inscription_boutique ADD CONSTRAINT unique_incorrecte UNIQUE(cree_le)");
            case "unique_prefixe" -> { sql(c, "ALTER TABLE inscription_boutique DROP INDEX uk_inscription_boutique_jeton"); sql(c, "ALTER TABLE inscription_boutique ADD CONSTRAINT uk_inscription_boutique_jeton UNIQUE(empreinte_jeton(8))"); }
            case "primaire_prefixe" -> { sql(c, "ALTER TABLE inscription_boutique DROP PRIMARY KEY"); sql(c, "ALTER TABLE inscription_boutique ADD PRIMARY KEY(email(32))"); }
            case "charset" -> sql(c, "ALTER TABLE inscription_boutique CONVERT TO CHARACTER SET latin1 COLLATE latin1_swedish_ci");
            case "collation" -> sql(c, "ALTER TABLE inscription_boutique CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            case "moteur" -> sql(c, "ALTER TABLE parametres_plateforme ENGINE=MyISAM");
            case "jeton_invalide" -> { inserer(c); sql(c, "UPDATE inscription_boutique SET empreinte_jeton=REPEAT('z',64)"); }
            case "expiration" -> { inserer(c); sql(c, "UPDATE inscription_boutique SET expire_le=cree_le"); }
            case "email_invalide" -> { inserer(c); sql(c, "UPDATE inscription_boutique SET email='invalide'"); }
            case "email_non_normalise" -> { inserer(c); sql(c, "UPDATE inscription_boutique SET email='Majuscules@example.test'"); }
            case "parametres_partiels" -> sql(c, "ALTER TABLE parametres_plateforme DROP COLUMN duree_essai_jours");
            case "parametres_type" -> sql(c, "ALTER TABLE parametres_plateforme MODIFY COLUMN duree_essai_jours BIGINT NOT NULL");
            case "parametres_unsigned" -> sql(c, "ALTER TABLE parametres_plateforme MODIFY COLUMN duree_essai_jours INT UNSIGNED NOT NULL");
            case "parametres_nullable" -> sql(c, "ALTER TABLE parametres_plateforme MODIFY COLUMN duree_essai_jours INT NULL");
            case "parametres_defaut" -> sql(c, "ALTER TABLE parametres_plateforme ALTER COLUMN duree_essai_jours SET DEFAULT 30");
            case "parametres_auto" -> sql(c, "ALTER TABLE parametres_plateforme MODIFY COLUMN id INT NOT NULL AUTO_INCREMENT");
            case "parametres_primaire" -> sql(c, "ALTER TABLE parametres_plateforme DROP PRIMARY KEY");
            case "parametres_unique" -> sql(c, "ALTER TABLE parametres_plateforme ADD CONSTRAINT unique_incorrecte UNIQUE(duree_essai_jours)");
            case "parametres_relation" -> {
                sql(c, "CREATE TABLE parent_test(id INTEGER NOT NULL PRIMARY KEY) ENGINE=InnoDB");
                sql(c, "ALTER TABLE parametres_plateforme ADD CONSTRAINT fk_incorrecte FOREIGN KEY(id) REFERENCES parent_test(id)");
            }
            case "parametres_id" -> sql(c, "INSERT INTO parametres_plateforme VALUES(2,30)");
            case "parametres_duree" -> sql(c, "INSERT INTO parametres_plateforme VALUES(1,0)");
            case "parametres_maximum" -> sql(c, "INSERT INTO parametres_plateforme VALUES(1,3651)");
            case "recuperation_absente" -> sql(c, "DROP TABLE reinitialisation_mot_de_passe");
            case "recuperation_invalide" -> sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN cree_le DATETIME(3) NOT NULL");
            case "acces_invalide" -> sql(c, "ALTER TABLE utilisateur ALTER COLUMN actif SET DEFAULT FALSE");
            case "plateforme" -> sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
            case "relation" -> sql(c, "UPDATE produit SET boutique_id=2 WHERE id=1");
            default -> { }
        }
    }

    private static void verifierScenario(Connection c, String scenario) throws SQLException {
        boolean valide = VALIDES.contains(scenario);
        var tablesAvant = BoutiquesMigration.tables(c);
        var colonnesAvant = InscriptionEssaiMigration.colonnes(c, "boutique");
        // Le scénario de prérequis absent conserve les douze autres tables.
        var avant = scenario.equals("recuperation_absente") ? ReinitialisationMdpMigration.photographie(c) : InscriptionEssaiMigration.photographie(c);
        var essais = InscriptionEssaiMigration.essais(c);
        var inscriptions = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION);
        var parametres = InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES);
        try { InscriptionEssaiMigration.migrer(c, false); if (!valide) throw new AssertionError("Contrôle accepté : " + scenario); }
        catch (IllegalStateException e) { if (valide) throw e; }
        if (!tablesAvant.equals(BoutiquesMigration.tables(c)) || !colonnesAvant.equals(InscriptionEssaiMigration.colonnes(c, "boutique"))) throw new AssertionError("DDL pendant le contrôle.");
        try { InscriptionEssaiMigration.migrer(c, true); if (!valide) throw new AssertionError("Migration acceptée : " + scenario); }
        catch (IllegalStateException e) { if (valide) throw e; }
        var apres = scenario.equals("recuperation_absente") ? ReinitialisationMdpMigration.photographie(c) : InscriptionEssaiMigration.photographie(c);
        if (!avant.equals(apres) || !essais.equals(InscriptionEssaiMigration.essais(c))
                || !inscriptions.equals(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.INSCRIPTION))
                || !parametres.equals(InscriptionEssaiMigration.donnees(c, InscriptionEssaiMigration.PARAMETRES))) throw new AssertionError("Données existantes modifiées.");
        if (!valide && (!tablesAvant.equals(BoutiquesMigration.tables(c)) || !colonnesAvant.equals(InscriptionEssaiMigration.colonnes(c, "boutique")))) throw new AssertionError("DDL avant refus.");
        if (valide) {
            InscriptionEssaiMigration.migrer(c, false); InscriptionEssaiMigration.migrer(c, true);
            if (!avant.equals(InscriptionEssaiMigration.photographie(c)) || !essais.equals(InscriptionEssaiMigration.essais(c))) throw new AssertionError("Données modifiées à la relance.");
            c.setReadOnly(false);
            if (inscriptions.isEmpty()) inserer(c);
            insererRefuse(c, "nouveau@example.test", "b".repeat(64));
            insererRefuse(c, "autre@example.test", "a".repeat(64));
        }
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = InscriptionEssaiMigration.photographie(c);
        var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            if (BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + InscriptionEssaiMigration.VERROU + "',0)") != 1) throw new AssertionError("Préparation du verrou impossible.");
            try {
                try { InscriptionEssaiMigration.migrer(c, true); throw new AssertionError("Verrou concurrent ignoré."); }
                catch (IllegalStateException e) { if (!e.getMessage().contains("migration inscription et essai")) throw e; }
                if (!tables.equals(BoutiquesMigration.tables(c)) || !avant.equals(InscriptionEssaiMigration.photographie(c))) throw new AssertionError("Écriture malgré le verrou.");
                InscriptionEssaiMigration.migrer(c, false);
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + InscriptionEssaiMigration.VERROU + "')"); }
        }
        InscriptionEssaiMigration.migrer(c, true);
        if (!avant.equals(InscriptionEssaiMigration.photographie(c))) throw new AssertionError("Données modifiées après libération du verrou.");
    }
    private static void remplirEssai(Connection c) throws SQLException { sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00.123456',essai_fin='2025-01-31 00:00:00.123456' WHERE id=1"); }
    private static void inserer(Connection c) throws SQLException { inserer(c, "nouveau@example.test", "a".repeat(64)); }
    private static void inserer(Connection c, String email, String empreinte) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO inscription_boutique(email,empreinte_jeton,cree_le,expire_le) VALUES(?,?,?,?)")) {
            s.setString(1, email); s.setString(2, empreinte);
            s.setTimestamp(3, Timestamp.valueOf("2025-01-01 00:00:00.123456")); s.setTimestamp(4, Timestamp.valueOf("2025-01-01 00:30:00.123456")); s.executeUpdate();
        }
    }
    private static void insererRefuse(Connection c, String email, String empreinte) throws SQLException {
        try { inserer(c, email, empreinte); throw new AssertionError("Unicité d'inscription absente."); }
        catch (SQLException attendu) { /* Ne pas publier l'exception JDBC ni ses valeurs. */ }
    }
    private static void verifierBaseTemporaire(String base) {
        if (base.length() > 64 || !base.matches(PREFIXE + "[a-z0-9_]+")) throw new IllegalStateException("Base temporaire requise.");
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
}
