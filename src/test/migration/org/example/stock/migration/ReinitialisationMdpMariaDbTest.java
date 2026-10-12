package org.example.stock.migration;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Vérifie le stockage des réinitialisations dans des bases MariaDB locales jetables ; jamais stock_pro. */
public final class ReinitialisationMdpMariaDbTest {
    private static final String PREFIXE = "stock_reinitialisation_mdp_test_";
    private static final Set<String> VALIDES = Set.of("ancien", "relance", "conforme", "vide");
    private static final List<String> SCENARIOS = List.of("ancien", "relance", "conforme", "vide", "partiel", "colonne_absente", "colonne_supplementaire",
            "taille_jeton", "taille_mdp", "type_empreinte", "type_identifiant", "nullable", "precision", "type_date", "defaut", "defaut_texte_null",
            "primaire", "unique", "unicite_mdp", "foreignkey", "cascade", "mauvais_parent", "jeton_invalide", "mdp_invalide", "expiration",
            "orphelin", "parametres_absents", "parametres_invalides", "acces_invalide", "plateforme", "relation", "verrou");

    private ReinitialisationMdpMariaDbTest() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(r); }
            String url = AccountEmailMigration.resolve(config.getProperty("spring.datasource.url"));
            URI cible = URI.create(url.substring(5));
            if (!"mariadb".equals(cible.getScheme()) || !Set.of("localhost", "127.0.0.1").contains(cible.getHost())
                    || !Set.of(-1, 3306).contains(cible.getPort()) || !"/stock_pro".equals(cible.getPath()) || cible.getUserInfo() != null) {
                throw new IllegalStateException("Serveur MariaDB local requis ; seules des bases de test seront utilisées.");
            }
            String serveur = url.replace("/stock_pro?", "/?").replace("/stock_pro", "/")
                    .replace("createDatabaseIfNotExist=true", "createDatabaseIfNotExist=false");
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                for (String scenario : SCENARIOS) {
                    c.setReadOnly(false);
                    String base = PREFIXE + scenario.substring(0, Math.min(16, scenario.length())) + "_"
                            + Long.toUnsignedString(System.nanoTime(), 36);
                    verifierBaseTemporaire(base);
                    sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base);
                        boolean vide = scenario.equals("vide");
                        preparerBase(c, vide);
                        preparerScenario(c, scenario);
                        if (scenario.equals("verrou")) {
                            verifierVerrou(c, serveur, utilisateur, motDePasse);
                        } else {
                            verifierScenario(c, scenario);
                        }
                        reussis++;
                        System.out.println("OK réinitialisation mot de passe MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null);
                        verifierBaseTemporaire(base);
                        sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios de réinitialisation MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification de la réinitialisation MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "connexion ou opération SQL impossible ; aucun détail JDBC publié."));
            System.exit(1);
        }
    }

    private static void preparerBase(Connection c, boolean vide) throws SQLException {
        BoutiquesMariaDbTest.schema(c, !vide);
        BoutiquesMigration.migrer(c, true);
        if (!vide) {
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
        }
        PlateformeMigration.migrer(c, true, vide ? null : "admin@example.test");
        AccesUtilisateurMigration.migrer(c, true);
        ParametresBoutiqueMigration.migrer(c, true);
        c.setReadOnly(false);
        if (!vide) {
            sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE,adresse='Kaloum, Conakry',telephone='+224 600 00 00 00',email='contact@example.test' WHERE id=2");
            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=3");
        }
    }

    private static void preparerScenario(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "relance", "vide", "verrou").contains(scenario)) return;
        if (scenario.equals("partiel")) {
            sql(c, "CREATE TABLE reinitialisation_mot_de_passe(utilisateur_id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
            return;
        }
        ReinitialisationMdpMigration.creerTable(c);
        if (scenario.equals("conforme")) {
            inserer(c, 1, "a".repeat(64), "b".repeat(64));
            inserer(c, 3, "c".repeat(64), "b".repeat(64));
        }
        if (scenario.equals("colonne_absente")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP COLUMN expire_le");
        if (scenario.equals("colonne_supplementaire")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ADD COLUMN inconnu VARCHAR(50)");
        if (scenario.equals("taille_jeton")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN empreinte_jeton VARCHAR(63) NOT NULL");
        if (scenario.equals("taille_mdp")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN empreinte_mot_de_passe VARCHAR(65) NOT NULL");
        if (scenario.equals("type_empreinte")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN empreinte_mot_de_passe CHAR(64) NOT NULL");
        if (scenario.equals("type_identifiant")) {
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP FOREIGN KEY fk_reinitialisation_mdp_utilisateur");
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN utilisateur_id INT NOT NULL");
        }
        if (scenario.equals("nullable")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN empreinte_mot_de_passe VARCHAR(64) NULL");
        if (scenario.equals("precision")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN cree_le DATETIME(3) NOT NULL");
        if (scenario.equals("type_date")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN expire_le TIMESTAMP(6) NOT NULL");
        if (scenario.equals("defaut")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe SET DEFAULT ''");
        if (scenario.equals("defaut_texte_null")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ALTER COLUMN empreinte_mot_de_passe SET DEFAULT 'NULL'");
        if (scenario.equals("primaire")) {
            // MariaDB exige encore un index pour la FK lorsque sa clé primaire est supprimée.
            sql(c, "CREATE INDEX ix_reinitialisation_utilisateur ON reinitialisation_mot_de_passe(utilisateur_id)");
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP PRIMARY KEY");
        }
        if (scenario.equals("unique")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP INDEX uk_reinitialisation_mdp_jeton");
        if (scenario.equals("unicite_mdp")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ADD CONSTRAINT uk_mdp_incorrect UNIQUE(empreinte_mot_de_passe)");
        if (Set.of("foreignkey", "cascade", "mauvais_parent").contains(scenario)) {
            sql(c, "ALTER TABLE reinitialisation_mot_de_passe DROP FOREIGN KEY fk_reinitialisation_mdp_utilisateur");
            if (scenario.equals("cascade")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ADD CONSTRAINT fk_incorrecte FOREIGN KEY(utilisateur_id) REFERENCES utilisateur(id) ON DELETE CASCADE");
            if (scenario.equals("mauvais_parent")) sql(c, "ALTER TABLE reinitialisation_mot_de_passe ADD CONSTRAINT fk_incorrecte FOREIGN KEY(utilisateur_id) REFERENCES boutique(id)");
        }
        if (Set.of("jeton_invalide", "mdp_invalide", "expiration").contains(scenario)) {
            inserer(c, 1, "a".repeat(64), "b".repeat(64));
            if (scenario.equals("jeton_invalide")) sql(c, "UPDATE reinitialisation_mot_de_passe SET empreinte_jeton=REPEAT('z',64)");
            if (scenario.equals("mdp_invalide")) sql(c, "UPDATE reinitialisation_mot_de_passe SET empreinte_mot_de_passe='court'");
            if (scenario.equals("expiration")) sql(c, "UPDATE reinitialisation_mot_de_passe SET expire_le=cree_le");
        }
        if (scenario.equals("orphelin")) {
            sql(c, "SET FOREIGN_KEY_CHECKS=0");
            try { inserer(c, 999, "a".repeat(64), "b".repeat(64)); }
            finally { sql(c, "SET FOREIGN_KEY_CHECKS=1"); }
        }
        if (scenario.equals("parametres_absents")) sql(c, "ALTER TABLE boutique DROP COLUMN adresse, DROP COLUMN telephone, DROP COLUMN email");
        if (scenario.equals("parametres_invalides")) sql(c, "ALTER TABLE boutique MODIFY COLUMN adresse VARCHAR(501) NULL");
        if (scenario.equals("acces_invalide")) sql(c, "ALTER TABLE utilisateur ALTER COLUMN actif SET DEFAULT FALSE");
        if (scenario.equals("plateforme")) sql(c, "UPDATE utilisateur SET administrateur_plateforme=TRUE WHERE id=3");
        if (scenario.equals("relation")) sql(c, "UPDATE produit SET boutique_id=2 WHERE id=1");
    }

    private static void verifierScenario(Connection c, String scenario) throws SQLException {
        boolean valide = VALIDES.contains(scenario);
        var tablesAvant = BoutiquesMigration.tables(c);
        var colonnesAvant = ReinitialisationMdpMigration.colonnes(c);
        var avant = ReinitialisationMdpMigration.photographie(c);
        var demandesAvant = ReinitialisationMdpMigration.demandes(c);
        try {
            ReinitialisationMdpMigration.migrer(c, false);
            if (!valide) throw new AssertionError("Contrôle accepté : " + scenario);
        } catch (IllegalStateException e) { if (valide) throw e; }
        if (!tablesAvant.equals(BoutiquesMigration.tables(c)) || !colonnesAvant.equals(ReinitialisationMdpMigration.colonnes(c))
                || !avant.equals(ReinitialisationMdpMigration.photographie(c)) || !demandesAvant.equals(ReinitialisationMdpMigration.demandes(c))) {
            throw new AssertionError("Écriture pendant le contrôle.");
        }
        try {
            ReinitialisationMdpMigration.migrer(c, true);
            if (!valide) throw new AssertionError("Migration acceptée : " + scenario);
        } catch (IllegalStateException e) { if (valide) throw e; }
        if (!avant.equals(ReinitialisationMdpMigration.photographie(c))) throw new AssertionError("Anciennes colonnes ou lignes modifiées.");
        if (!valide && (!tablesAvant.equals(BoutiquesMigration.tables(c)) || !colonnesAvant.equals(ReinitialisationMdpMigration.colonnes(c))
                || !demandesAvant.equals(ReinitialisationMdpMigration.demandes(c)))) throw new AssertionError("Schéma ou demandes modifiés avant refus.");
        if (valide) {
            if (!scenario.equals("conforme") && !ReinitialisationMdpMigration.demandes(c).isEmpty()) throw new AssertionError("Demandes inventées.");
            if (scenario.equals("conforme") && !demandesAvant.equals(ReinitialisationMdpMigration.demandes(c))) throw new AssertionError("Demandes réécrites.");
            c.setReadOnly(false);
            if (!scenario.equals("vide") && !scenario.equals("conforme")) inserer(c, 1, "a".repeat(64), "b".repeat(64));
            var demandes = ReinitialisationMdpMigration.demandes(c);
            ReinitialisationMdpMigration.migrer(c, false);
            ReinitialisationMdpMigration.migrer(c, true);
            if (!avant.equals(ReinitialisationMdpMigration.photographie(c)) || !demandes.equals(ReinitialisationMdpMigration.demandes(c))) {
                throw new AssertionError("Données modifiées à la relance.");
            }
            if (!scenario.equals("vide")) verifierContraintes(c);
        }
    }

    private static void verifierContraintes(Connection c) throws SQLException {
        c.setReadOnly(false);
        insererRefuse(c, 1, "d".repeat(64), "e".repeat(64));
        insererRefuse(c, 2, "a".repeat(64), "e".repeat(64));
        insererRefuse(c, 999, "d".repeat(64), "e".repeat(64));
        inserer(c, 2, "d".repeat(64), "b".repeat(64));
        // Ni l'intégrité référentielle ni les demandes ne doivent entraîner une modification d'utilisateur.
        if (BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM utilisateur WHERE id=3 AND actif=FALSE") != 1) {
            throw new AssertionError("Compte réactivé.");
        }
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = ReinitialisationMdpMigration.photographie(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            if (BoutiquesMigration.compter(autre, "SELECT GET_LOCK('stock_reinitialisation_mdp_migration',0)") != 1) {
                throw new AssertionError("Impossible de préparer le verrou de migration.");
            }
            try {
                try { ReinitialisationMdpMigration.migrer(c, true); throw new AssertionError("Verrou concurrent ignoré."); }
                catch (IllegalStateException attendu) {
                    if (!attendu.getMessage().contains("migration de réinitialisation")) throw attendu;
                }
                if (BoutiquesMigration.tables(c).containsKey(ReinitialisationMdpMigration.TABLE)
                        || !avant.equals(ReinitialisationMdpMigration.photographie(c))) throw new AssertionError("Écriture malgré le verrou.");
                // --check reste sans verrou ni écriture, même si --apply est occupé.
                ReinitialisationMdpMigration.migrer(c, false);
            } finally {
                BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('stock_reinitialisation_mdp_migration')");
            }
        }
        ReinitialisationMdpMigration.migrer(c, true);
        if (!avant.equals(ReinitialisationMdpMigration.photographie(c)) || !ReinitialisationMdpMigration.demandes(c).isEmpty()) {
            throw new AssertionError("Données modifiées après libération du verrou.");
        }
    }

    private static void insererRefuse(Connection c, long utilisateur, String jeton, String mdp) throws SQLException {
        try { inserer(c, utilisateur, jeton, mdp); throw new AssertionError("Contrainte de réinitialisation absente."); }
        catch (SQLException attendu) { /* L'exception JDBC reste privée, sans valeur ou empreinte affichée. */ }
    }
    private static void inserer(Connection c, long utilisateur, String jeton, String mdp) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO reinitialisation_mot_de_passe(utilisateur_id,empreinte_jeton,empreinte_mot_de_passe,cree_le,expire_le) VALUES(?,?,?,?,?)")) {
            s.setLong(1, utilisateur); s.setString(2, jeton); s.setString(3, mdp);
            s.setTimestamp(4, Timestamp.valueOf("2025-01-01 00:00:00.123456"));
            s.setTimestamp(5, Timestamp.valueOf("2025-01-01 00:30:00.123456")); s.executeUpdate();
        }
    }
    private static void verifierBaseTemporaire(String base) {
        if (base.length() > 64 || !base.matches(PREFIXE + "[a-z0-9_]+")) throw new IllegalStateException("Base temporaire requise.");
    }
    private static void sql(Connection c, String sql) throws SQLException { BoutiquesMigration.executer(c, sql); }
}
