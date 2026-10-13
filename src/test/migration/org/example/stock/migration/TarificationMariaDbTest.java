package org.example.stock.migration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Chaque scénario crée puis supprime son propre schéma contrôlé, jamais stock_pro. */
public final class TarificationMariaDbTest {
    private TarificationMariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String scenarioCourant = "préparation";
        try {
            var config = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { config.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")), System.getenv("TARIFICATION_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(TarificationMigrationScenarios.VALIDES); tous.addAll(TarificationMigrationScenarios.INVALIDES.keySet()); tous.add("verrou");
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario de test des tarifs inconnu.");
            int reussis = 0;
            try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                for (String scenario : scenarios) {
                    scenarioCourant = scenario; c.setReadOnly(false);
                    String base = "stock_tarification_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                    TarificationMigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    try {
                        c.setCatalog(base); TarificationMigrationScenarios.schema(c, scenario.equals("vide"));
                        if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                        else {
                            TarificationMigrationScenarios.preparer(c, scenario);
                            if (scenario.equals("statut_valeur")) TarificationMigrationScenarios.sql(c, "UPDATE produit SET prix_definis=2 WHERE id=4");
                            if (scenario.endsWith("obligatoire")) {
                                var champ = TarificationMigration.CHAMPS.get(scenario.startsWith("achat_") ? 1 : 2);
                                TarificationMigrationScenarios.sql(c, "UPDATE " + champ.table() + " SET " + champ.nom() + "=0 WHERE " + champ.nom() + " IS NULL");
                            }
                            TarificationMigrationScenarios.verifier(c, scenario);
                        }
                        reussis++; System.out.println("OK tarifs MariaDB : " + scenario);
                    } finally { c.setReadOnly(false); c.setCatalog(null); verifierBase(base); TarificationMigrationScenarios.sql(c, "DROP DATABASE " + base); }
                }
            }
            System.out.println(reussis + " scénarios tarifaires MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB des tarifs interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + scenarioCourant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "")
                    + " ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }
    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = TarificationMigration.photographie(c); var tarifs = TarificationMigration.tarifs(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            TarificationMigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + TarificationMigration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { TarificationMigration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                TarificationMigrationScenarios.exiger(refuse && avant.equals(TarificationMigration.photographie(c)) && tarifs.equals(TarificationMigration.tarifs(c)), "Migration concurrente non refusée.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + TarificationMigration.VERROU + "')"); }
        }
        TarificationMigrationScenarios.verifier(c, "ancien");
    }
    private static void verifierBase(String base) {
        TarificationMigrationScenarios.exiger(base.matches("stock_tarification_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
