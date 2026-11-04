package org.example.stock.migration;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class IdentiteBoutiqueMariaDbTest {
    private IdentiteBoutiqueMariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("IDENTITE_BOUTIQUE_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(IdentiteBoutiqueMigrationScenarios.VALIDES); tous.addAll(IdentiteBoutiqueMigrationScenarios.INVALIDES); tous.add("verrou");
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario identité boutique inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_identite_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    IdentiteBoutiqueMigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c);
                    else IdentiteBoutiqueMigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("logo_trop_grand") && BoutiquesMigration.compter(c, "SELECT @@max_allowed_packet") < IdentiteBoutiqueMigration.TAILLE_MAX + 65536L)
                        verifierLimitePaquet(c, serveur, utilisateur, motDePasse, base);
                    else { IdentiteBoutiqueMigrationScenarios.preparer(c, scenario); IdentiteBoutiqueMigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK identité boutique MariaDB : " + scenario);
                } finally {
                    // Une erreur de paquet peut fermer la connexion testée : le nettoyage utilise sa propre connexion.
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); IdentiteBoutiqueMigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios identité boutique MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB identité boutique interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    private static void verifierLimitePaquet(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        IdentiteBoutiqueMigration.migrer(c, true);
        var avant = IdentiteBoutiqueMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
        var schemas = IdentiteBoutiqueMigration.schemasAnciens(c); var logos = IdentiteBoutiqueMigration.logos(c);
        boolean refuse = false;
        try { IdentiteBoutiqueMigrationScenarios.insererLogo(c, new byte[IdentiteBoutiqueMigration.TAILLE_MAX + 1], UUID.randomUUID().toString()); }
        catch (SQLException e) { refuse = e.getErrorCode() == 1220 || e.getErrorCode() == 1153 || e.getSQLState() != null && e.getSQLState().startsWith("08"); }
        IdentiteBoutiqueMigrationScenarios.exiger(refuse, "Le serveur n'a pas refusé le logo dépassant sa limite de transport.");
        try (var verification = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            verification.setCatalog(base);
            IdentiteBoutiqueMigrationScenarios.exiger(avant.equals(IdentiteBoutiqueMigration.photographie(verification))
                    && schemas.equals(IdentiteBoutiqueMigration.schemasAnciens(verification)) && tables.equals(BoutiquesMigration.tables(verification))
                    && logos.equals(IdentiteBoutiqueMigration.logos(verification)), "Le refus du paquet a modifié la base.");
            IdentiteBoutiqueMigration.migrer(verification, false);
        }
        System.out.println("Logo surdimensionné refusé avant insertion par max_allowed_packet ; garde de taille du PNG stocké aussi couverte strictement sur H2.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = IdentiteBoutiqueMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            IdentiteBoutiqueMigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + IdentiteBoutiqueMigration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { IdentiteBoutiqueMigration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                IdentiteBoutiqueMigrationScenarios.exiger(refuse && avant.equals(IdentiteBoutiqueMigration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + IdentiteBoutiqueMigration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        IdentiteBoutiqueMigrationScenarios.exiger(base.matches("stock_identite_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
