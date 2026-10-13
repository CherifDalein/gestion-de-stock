package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Vérifie les migrations exclusivement dans des schémas temporaires locaux contrôlés. */
public final class AbonnementMariaDbTest {
    private static final String PREFIXE = "stock_abonnement_test_";
    private static final Set<String> VALIDES = Set.of("ancien", "relance", "conforme", "vide", "debut_seul", "fin_seule", "periodes", "historique");
    private static final Map<String, String> MODIFICATIONS = modifications();
    private AbonnementMariaDbTest() {}

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String scenarioEnCours = "préparation";
        try {
            List<String> tous = new ArrayList<>(VALIDES); tous.sort(String::compareTo); tous.addAll(MODIFICATIONS.keySet()); tous.add("verrou");
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario de test des abonnements MariaDB inconnu.");
            Properties configuration = new Properties();
            try (var r = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(r); }
            String serveur = urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("ABONNEMENT_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                for (String scenario : scenarios) {
                    scenarioEnCours = scenario; c.setReadOnly(false);
                    String base = PREFIXE + scenario.substring(0, Math.min(16, scenario.length())) + "_" + Long.toUnsignedString(System.nanoTime(), 36);
                    verifierBaseTemporaire(base); sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base); preparerBase(c, scenario.equals("vide")); preparerScenario(c, scenario);
                        if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                        else verifierScenario(c, scenario);
                        reussis++; System.out.println("OK abonnement MariaDB : " + scenario);
                    } finally {
                        c.setReadOnly(false); c.setCatalog(null); verifierBaseTemporaire(base); sql(c, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios des abonnements MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification des abonnements MariaDB interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour le scénario " + scenarioEnCours
                    + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "")
                    + " ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    static String urlServeurTest(String application, String test) { return InscriptionEssaiMariaDbTest.urlServeurTest(application, test); }
    private static void verifierBaseTemporaire(String base) {
        if (!base.startsWith(PREFIXE) || !base.matches("stock_abonnement_test_[a-z0-9_]+") || base.length() > 64)
            throw new IllegalStateException("Nom de base temporaire inattendu ; suppression refusée.");
    }
    private static void preparerBase(Connection c, boolean vide) throws SQLException {
        BoutiquesMariaDbTest.schema(c, !vide); BoutiquesMigration.migrer(c, true);
        if (!vide) {
            sql(c, "INSERT INTO boutique(nom,date_creation) VALUES('Deuxième',CURRENT_TIMESTAMP)");
            sql(c, "INSERT INTO utilisateur(id,email,mot_de_passe,role,boutique_id) VALUES(2,'gerant@example.test','empreinte-gerant','ADMIN',2),(3,'caissier@example.test','empreinte-caissier','CAISSIER',1)");
        }
        PlateformeMigration.migrer(c, true, vide ? null : "admin@example.test"); AccesUtilisateurMigration.migrer(c, true); ParametresBoutiqueMigration.migrer(c, true);
        if (!vide) {
            sql(c, "UPDATE boutique SET actif=FALSE,acces_offert=TRUE,adresse='Kaloum, Conakry',telephone='+224 600 00 00 00',email='contact@example.test' WHERE id=2");
            sql(c, "UPDATE utilisateur SET actif=FALSE WHERE id=3");
        }
        ReinitialisationMdpMigration.migrer(c, true); c.setReadOnly(false);
        if (!vide) sql(c, "INSERT INTO reinitialisation_mot_de_passe VALUES(3,REPEAT('a',64),REPEAT('b',64),'2025-01-01 00:00:00.123456','2025-01-01 00:30:00.123456')");
        else sql(c, "DELETE FROM boutique");
        InscriptionEssaiMigration.migrer(c, true);
        if (!vide) {
            sql(c, "UPDATE boutique SET essai_debut='2025-01-01 00:00:00.123456',essai_fin='2025-01-31 00:00:00.123456' WHERE id=1");
            sql(c, "INSERT INTO inscription_boutique VALUES('nouveau@example.test',REPEAT('a',64),'2025-01-01 00:00:00.123456','2025-01-01 00:30:00.123456')");
            sql(c, "INSERT INTO parametres_plateforme VALUES(1,45)");
        }
    }
    private static void preparerScenario(Connection c, String scenario) throws SQLException {
        if (Set.of("ancien", "relance", "vide", "verrou").contains(scenario)) return;
        if (scenario.equals("debut_seul") || scenario.equals("fin_seule")) {
            sql(c, "ALTER TABLE boutique ADD COLUMN " + (scenario.equals("debut_seul") ? "abonnement_debut" : "abonnement_fin") + " DATETIME(6) NULL"); return;
        }
        if (scenario.equals("periodes")) { AbonnementMigration.ajouterPeriodes(c); remplirPeriodes(c); return; }
        if (scenario.equals("historique")) { AbonnementMigration.creerHistorique(c); remplirHistorique(c); return; }
        AbonnementMigration.ajouterPeriodes(c); AbonnementMigration.creerHistorique(c);
        if (scenario.equals("conforme") || MODIFICATIONS.containsKey(scenario)) { remplirPeriodes(c); remplirHistorique(c); }
        String modification = MODIFICATIONS.get(scenario);
        if (modification != null) {
            if (scenario.equals("moteur")) {
                sql(c, "ALTER TABLE historique_abonnement DROP FOREIGN KEY fk_historique_abonnement_boutique");
                sql(c, "ALTER TABLE historique_abonnement DROP FOREIGN KEY fk_historique_abonnement_acteur");
            }
            if (scenario.equals("unique_nom") || scenario.equals("unique_prefixe")) sql(c, "ALTER TABLE historique_abonnement DROP INDEX uk_historique_abonnement_jeton");
            if (scenario.equals("unique_prefixe")) sql(c, "DELETE FROM historique_abonnement");
            if (scenario.equals("fk_cascade") || scenario.equals("fk_parent")) sql(c, "ALTER TABLE historique_abonnement DROP FOREIGN KEY fk_historique_abonnement_acteur");
            if (scenario.equals("index_incorrect") || scenario.equals("index_absent")) sql(c, "CREATE INDEX idx_boutique_fk ON historique_abonnement(boutique_id)");
            if (scenario.equals("index_incorrect")) sql(c, "ALTER TABLE historique_abonnement DROP INDEX idx_historique_abonnement_boutique_date");
            if (scenario.equals("periode_isolee")) sql(c, "ALTER TABLE boutique DROP COLUMN abonnement_fin");
            sql(c, modification);
        }
    }
    private static void remplirPeriodes(Connection c) throws SQLException {
        sql(c, "UPDATE boutique SET abonnement_debut='2025-02-01 00:00:00.123456',abonnement_fin='2025-03-01 00:00:00.123456' WHERE id=1");
    }
    private static void remplirHistorique(Connection c) throws SQLException {
        int id = 1;
        for (String action : List.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT", "ACCES_OFFERT", "RETRAIT_ACCES_OFFERT", "SUSPENSION_BOUTIQUE", "REACTIVATION_BOUTIQUE")) {
            boolean periode = Set.of("PROLONGATION_ESSAI", "ACTIVATION_ABONNEMENT").contains(action);
            String fin = action.equals("PROLONGATION_ESSAI") ? "2025-01-08 00:00:00.123456" : "2025-02-01 00:00:00.123456";
            String valeurs = periode ? "'2025-01-01 00:00:00.123456','" + fin + "','2025-01-01 00:00:00.123456'" : "NULL,NULL,NULL";
            String quantite = action.equals("PROLONGATION_ESSAI") ? "7" : action.equals("ACTIVATION_ABONNEMENT") ? "1" : "NULL";
            sql(c, "INSERT INTO historique_abonnement VALUES(" + id + ",2,1,'Administrateur','" + action + "','2025-01-01 00:00:00.123456',"
                    + valeurs + ",'Action manuelle','abcdef01-1234-4567-89ab-" + String.format("%012d", id) + "'," + quantite + ")");
            id++;
        }
    }
    private static void verifierScenario(Connection c, String scenario) throws SQLException {
        boolean valide = VALIDES.contains(scenario);
        var tables = BoutiquesMigration.tables(c); var schemaBoutique = InscriptionEssaiMigration.colonnes(c, "boutique");
        var schemaHistorique = InscriptionEssaiMigration.colonnes(c, AbonnementMigration.TABLE);
        var avant = AbonnementMigration.photographie(c); var periodes = AbonnementMigration.periodes(c); var historique = AbonnementMigration.donnees(c);
        try { AbonnementMigration.migrer(c, false); if (!valide) throw new AssertionError("Contrôle accepté : " + scenario); }
        catch (IllegalStateException e) { if (valide) throw e; }
        if (!tables.equals(BoutiquesMigration.tables(c)) || !schemaBoutique.equals(InscriptionEssaiMigration.colonnes(c, "boutique"))
                || !schemaHistorique.equals(InscriptionEssaiMigration.colonnes(c, AbonnementMigration.TABLE))) throw new AssertionError("DDL pendant le contrôle.");
        try { AbonnementMigration.migrer(c, true); if (!valide) throw new AssertionError("Migration acceptée : " + scenario); }
        catch (IllegalStateException e) { if (valide) throw e; }
        if (!avant.equals(AbonnementMigration.photographie(c)) || !periodes.equals(AbonnementMigration.periodes(c)) || !historique.equals(AbonnementMigration.donnees(c)))
            throw new AssertionError("Des données ont changé.");
        if (valide) {
            AbonnementMigration.migrer(c, true);
            if (!avant.equals(AbonnementMigration.photographie(c)) || !periodes.equals(AbonnementMigration.periodes(c)) || !historique.equals(AbonnementMigration.donnees(c)))
                throw new AssertionError("Des données ont changé pendant la relance.");
            if (BoutiquesMigration.tables(c).size() != 16) throw new AssertionError("Schéma incomplet.");
        } else if (!tables.equals(BoutiquesMigration.tables(c)) || !schemaBoutique.equals(InscriptionEssaiMigration.colonnes(c, "boutique"))
                || !schemaHistorique.equals(InscriptionEssaiMigration.colonnes(c, AbonnementMigration.TABLE))) throw new AssertionError("Un schéma refusé a été modifié.");
    }
    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = AbonnementMigration.photographie(c); var schema = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            if (BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + AbonnementMigration.VERROU + "',0)") != 1) throw new AssertionError("Verrou de test indisponible.");
            try {
                try { AbonnementMigration.migrer(c, true); throw new AssertionError("Migration sans verrou acceptée."); }
                catch (IllegalStateException attendu) { if (!attendu.getMessage().contains("en cours")) throw attendu; }
                if (!avant.equals(AbonnementMigration.photographie(c)) || !schema.equals(BoutiquesMigration.tables(c))) throw new AssertionError("Écriture sans verrou.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + AbonnementMigration.VERROU + "')"); }
        }
        AbonnementMigration.migrer(c, true);
    }
    private static Map<String, String> modifications() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("periode_precision", "ALTER TABLE boutique MODIFY COLUMN abonnement_fin DATETIME(3) NULL");
        m.put("periode_type", "ALTER TABLE boutique MODIFY COLUMN abonnement_debut TIMESTAMP(6) NULL");
        m.put("periode_defaut", "ALTER TABLE boutique ALTER COLUMN abonnement_fin SET DEFAULT CURRENT_TIMESTAMP");
        m.put("periode_isolee", "UPDATE boutique SET abonnement_debut='2025-01-01' WHERE id=1");
        m.put("periode_ordre", "UPDATE boutique SET abonnement_fin=abonnement_debut WHERE id=1");
        m.put("periode_null", "UPDATE boutique SET abonnement_fin=NULL WHERE id=1");
        m.put("table_partielle", "ALTER TABLE historique_abonnement DROP COLUMN motif");
        m.put("colonne_inconnue", "ALTER TABLE historique_abonnement ADD COLUMN paiement VARCHAR(255)");
        m.put("nom_taille", "ALTER TABLE historique_abonnement MODIFY COLUMN acteur_nom VARCHAR(254) NOT NULL");
        m.put("action_type", "ALTER TABLE historique_abonnement MODIFY COLUMN action CHAR(32) NOT NULL");
        m.put("jeton_nullable", "ALTER TABLE historique_abonnement MODIFY COLUMN jeton_action VARCHAR(36) NULL");
        m.put("motif_defaut", "ALTER TABLE historique_abonnement ALTER COLUMN motif SET DEFAULT 'raison'");
        m.put("quantite_type", "ALTER TABLE historique_abonnement MODIFY COLUMN quantite BIGINT NULL");
        m.put("quantite_unsigned", "ALTER TABLE historique_abonnement MODIFY COLUMN quantite INT UNSIGNED NULL");
        m.put("date_precision", "ALTER TABLE historique_abonnement MODIFY COLUMN cree_le DATETIME(3) NOT NULL");
        m.put("date_type", "ALTER TABLE historique_abonnement MODIFY COLUMN ancienne_fin TIMESTAMP(6) NULL");
        m.put("auto_absent", "ALTER TABLE historique_abonnement MODIFY COLUMN id BIGINT NOT NULL");
        m.put("primaire_absente", "ALTER TABLE historique_abonnement DROP PRIMARY KEY, ADD INDEX idx_id(id)");
        m.put("unique_absent", "ALTER TABLE historique_abonnement DROP INDEX uk_historique_abonnement_jeton");
        m.put("unique_nom", "ALTER TABLE historique_abonnement ADD CONSTRAINT nom_inattendu UNIQUE(jeton_action)");
        m.put("unique_date", "ALTER TABLE historique_abonnement ADD CONSTRAINT unique_incorrecte UNIQUE(ancienne_fin,id)");
        m.put("unique_prefixe", "ALTER TABLE historique_abonnement ADD CONSTRAINT uk_historique_abonnement_jeton UNIQUE(jeton_action(35))");
        m.put("index_absent", "ALTER TABLE historique_abonnement DROP INDEX idx_historique_abonnement_boutique_date");
        m.put("index_incorrect", "CREATE INDEX idx_historique_abonnement_boutique_date ON historique_abonnement(boutique_id,id,cree_le)");
        m.put("fk_absente", "ALTER TABLE historique_abonnement DROP FOREIGN KEY fk_historique_abonnement_acteur");
        m.put("fk_cascade", "ALTER TABLE historique_abonnement ADD CONSTRAINT fk_historique_abonnement_acteur FOREIGN KEY(acteur_id) REFERENCES utilisateur(id) ON DELETE CASCADE");
        m.put("fk_parent", "ALTER TABLE historique_abonnement ADD CONSTRAINT fk_historique_abonnement_acteur FOREIGN KEY(acteur_id) REFERENCES boutique(id)");
        m.put("charset", "ALTER TABLE historique_abonnement CONVERT TO CHARACTER SET latin1 COLLATE latin1_swedish_ci");
        m.put("collation", "ALTER TABLE historique_abonnement CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        m.put("moteur", "ALTER TABLE historique_abonnement ENGINE=MyISAM");
        m.put("action_invalide", "UPDATE historique_abonnement SET action='PAIEMENT' WHERE id=1");
        m.put("jeton_invalide", "UPDATE historique_abonnement SET jeton_action='invalid' WHERE id=1");
        m.put("jeton_majuscules", "UPDATE historique_abonnement SET jeton_action=UPPER(jeton_action) WHERE id=1");
        m.put("nom_vide", "UPDATE historique_abonnement SET acteur_nom=' ' WHERE id=1");
        m.put("quantite_zero", "UPDATE historique_abonnement SET quantite=0 WHERE id=1");
        m.put("quantite_max", "UPDATE historique_abonnement SET quantite=3651 WHERE id=1");
        m.put("quantite_duree", "UPDATE historique_abonnement SET quantite=8 WHERE id=1");
        m.put("mois_multiple", "UPDATE historique_abonnement SET quantite=2 WHERE id=2");
        m.put("quantite_absente", "UPDATE historique_abonnement SET quantite=NULL WHERE id=1");
        m.put("historique_ordre", "UPDATE historique_abonnement SET periode_fin=periode_debut WHERE id=1");
        m.put("historique_null", "UPDATE historique_abonnement SET periode_debut=NULL WHERE id=1");
        m.put("ancienne_fin", "UPDATE historique_abonnement SET ancienne_fin='2026-01-01' WHERE id=1");
        m.put("statut_quantite", "UPDATE historique_abonnement SET quantite=1 WHERE id=3");
        m.put("statut_periode", "UPDATE historique_abonnement SET ancienne_fin='2025-01-01' WHERE id=3");
        m.put("motif_vide", "UPDATE historique_abonnement SET motif='' WHERE id=1");
        m.put("inscription_abs", "DROP TABLE inscription_boutique");
        m.put("parametres_abs", "DROP TABLE parametres_plateforme");
        m.put("essai_abs", "ALTER TABLE boutique DROP COLUMN essai_fin");
        m.put("recuperation", "ALTER TABLE reinitialisation_mot_de_passe MODIFY COLUMN cree_le DATETIME(3) NOT NULL");
        m.put("acces", "ALTER TABLE utilisateur ALTER COLUMN actif SET DEFAULT FALSE");
        return m;
    }
    private static void sql(Connection c, String instruction) throws SQLException { c.setReadOnly(false); BoutiquesMigration.executer(c, instruction); }
}
