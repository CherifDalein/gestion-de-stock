package org.example.stock.migration;

import org.example.stock.enums.FormatRecu;
import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class Lot4MariaDbTest {
    private Lot4MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT4_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot4MigrationScenarios.VALIDES); tous.addAll(Lot4MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot2_requis", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 4 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot4_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot4MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot2_requis")) { Lot1MigrationScenarios.schema(c, false); }
                    else if (scenario.equals("schema_neuf") || scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c);
                    else Lot4MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot2_requis")) verifierLot2Requis(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { Lot4MigrationScenarios.preparer(c, scenario); Lot4MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 4 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot4MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 4 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 4 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration (historique ou neuve), Hibernate en mode validate accepte les entités ; les valeurs par défaut d'une boutique
     * créée par JPA (A4, bouton proposé), puis un réglage modifié (ticket 80 mm, bouton retiré) sont enregistrés et relus,
     * et une boutique historique migrée est relue avec A4 et le bouton proposé.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        Lot2Migration.migrer(c, true);
        Lot4Migration.migrer(c, true);
        // Les entités décrivent aussi les comptes de trésorerie multiples du lot 3 : même exigence pour Hibernate.
        Lot3Migration.migrer(c, true);
        boolean historique = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=1") == 1;
        int query = serveur.indexOf('?');
        String url = query < 0 ? serveur + base : serveur.substring(0, query) + base + serveur.substring(query);
        var source = new DriverManagerDataSource(url, utilisateur, motDePasse); source.setDriverClassName("org.mariadb.jdbc.Driver");
        var fabrique = new LocalContainerEntityManagerFactoryBean();
        fabrique.setDataSource(source); fabrique.setPackagesToScan("org.example.stock.model");
        fabrique.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        fabrique.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false", "hibernate.jdbc.time_zone", "UTC",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        fabrique.afterPropertiesSet();
        long boutiqueId;
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA lot 4"); em.persist(b);
            em.getTransaction().commit();
            boutiqueId = b.getId();
            em.clear();
            var relue = em.find(Boutique.class, boutiqueId);
            Lot4MigrationScenarios.exiger(relue.getFormatRecu() == FormatRecu.A4 && relue.isProposerImpressionRecu(), "Valeurs par défaut de la boutique non relues");
            em.getTransaction().begin();
            relue.setFormatRecu(FormatRecu.TICKET_80); relue.setProposerImpressionRecu(false);
            em.getTransaction().commit();
            em.clear();
            var modifiee = em.find(Boutique.class, boutiqueId);
            Lot4MigrationScenarios.exiger(modifiee.getFormatRecu() == FormatRecu.TICKET_80 && !modifiee.isProposerImpressionRecu(), "Réglages modifiés non relus");
            if (historique) {
                var migree = em.find(Boutique.class, 1L);
                Lot4MigrationScenarios.exiger(migree.getFormatRecu() == FormatRecu.A4 && migree.isProposerImpressionRecu(), "Boutique migrée non relue avec les valeurs par défaut");
            }
        }
        Lot4MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=" + boutiqueId
                + " AND format_recu='TICKET_80' AND proposer_impression_recu=0") == 1, "Réglages non enregistrés en base");
        // Une relance n'écrase pas les réglages choisis depuis la migration.
        var avant = Lot4MigrationScenarios.etatComplet(c);
        Lot4Migration.migrer(c, true);
        Lot4MigrationScenarios.exiger(avant.equals(Lot4MigrationScenarios.etatComplet(c)), "La relance a modifié les réglages JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; format et bouton de reçu relus.");
    }

    private static void verifierLot2Requis(Connection c) throws SQLException {
        var avant = Lot4Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { Lot4Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lot 2 doit être terminée"); }
            Lot4MigrationScenarios.exiger(refuse && avant.equals(Lot4Migration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Lot 4 appliqué sans le lot 2.");
        }
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot4MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot4MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot4Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot4Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot4MigrationScenarios.exiger(refuse && avant.equals(Lot4MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot4Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot4MigrationScenarios.exiger(base.matches("stock_lot4_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
