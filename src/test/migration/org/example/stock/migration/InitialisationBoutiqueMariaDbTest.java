package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Chaque scénario crée et supprime son propre schéma contrôlé ; jamais stock_pro. */
public final class InitialisationBoutiqueMariaDbTest {
    private InitialisationBoutiqueMariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String scenarioCourant = "préparation";
        try {
            var config = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")), System.getenv("INITIALISATION_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(InitialisationBoutiqueMigrationScenarios.VALIDES); tous.addAll(InitialisationBoutiqueMigrationScenarios.INVALIDES.keySet()); tous.add("verrou");
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario du démarrage inconnu.");
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                for (String scenario : scenarios) {
                    scenarioCourant = scenario; c.setReadOnly(false);
                    String base = "stock_initialisation_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                    InitialisationBoutiqueMigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base); InitialisationBoutiqueMigrationScenarios.schema(c, scenario.equals("vide"));
                        if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                        else { InitialisationBoutiqueMigrationScenarios.preparer(c, scenario); InitialisationBoutiqueMigrationScenarios.verifier(c, scenario); }
                        reussis++; System.out.println("OK démarrage MariaDB : " + scenario);
                    } finally { c.setReadOnly(false); c.setCatalog(null); verifierBase(base); InitialisationBoutiqueMigrationScenarios.sql(c, "DROP DATABASE " + base); }
                }
            }
            System.out.println(reussis + " scénarios du démarrage MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB du démarrage interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + scenarioCourant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "")
                    + " ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }
    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = InitialisationBoutiqueMigration.photographie(c); var etats = InitialisationBoutiqueMigration.etats(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            InitialisationBoutiqueMigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + InitialisationBoutiqueMigration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { InitialisationBoutiqueMigration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                InitialisationBoutiqueMigrationScenarios.exiger(refuse && avant.equals(InitialisationBoutiqueMigration.photographie(c)) && etats.equals(InitialisationBoutiqueMigration.etats(c)), "Migration concurrente non refusée.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + InitialisationBoutiqueMigration.VERROU + "')"); }
        }
        InitialisationBoutiqueMigrationScenarios.verifier(c, "ancien");
    }
    private static void verifierBase(String base) {
        InitialisationBoutiqueMigrationScenarios.exiger(base.matches("stock_initialisation_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
