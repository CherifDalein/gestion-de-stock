package org.example.stock.migration;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.*;
import org.example.stock.pays.Monnaie;
import org.example.stock.pays.Pays;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. Lancé par la tâche gradle paysMonnaieMariaDbTest. */
public final class PaysMonnaieMariaDbTest {
    private PaysMonnaieMariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("PAYS_MONNAIE_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(PaysMonnaieMigrationScenarios.VALIDES); tous.addAll(PaysMonnaieMigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot5_requis", "lot6_requis", "sans_sauvegarde", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario pays-monnaie inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_pays_monnaie_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    PaysMonnaieMigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot5_requis")) Lot5MigrationScenarios.schema(c, false);
                    else if (scenario.equals("lot6_requis")) Lot6MigrationScenarios.schema(c, false);
                    else if (scenario.equals("schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufPaysMonnaie(c);
                    else if (scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufLot5(c);
                    else PaysMonnaieMigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot5_requis") || scenario.equals("lot6_requis")) verifierLotRequis(c);
                    else if (scenario.equals("sans_sauvegarde")) verifierSansSauvegarde(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { PaysMonnaieMigrationScenarios.preparer(c, scenario); PaysMonnaieMigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK pays-monnaie MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); PaysMonnaieMigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios pays-monnaie MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB pays-monnaie interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration (historique ou neuve), Hibernate en mode validate accepte les entités. Puis, avec la vraie base : une boutique créée par JPA
     * au Sénégal reçoit XOF et ses trois comptes de base dont le mobile money « Orange Money » ; une boutique créée sans pays est en Guinée ;
     * le pays et la monnaie ne changent jamais (refus côté code, valeur inchangée en base même si l'entité est modifiée) ; un compte Wave est
     * enregistré et relu ; une relance n'écrase rien.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        Lot2Migration.migrer(c, true);
        Lot4Migration.migrer(c, true);
        Lot3Migration.migrer(c, true);
        PaysMonnaieMigration.migrer(c, true);
        Lot5Migration.migrer(c, true);
        PaysMonnaieMigration.migrer(c, true);
        int query = serveur.indexOf('?');
        String url = query < 0 ? serveur + base : serveur.substring(0, query) + base + serveur.substring(query);
        var source = new DriverManagerDataSource(url, utilisateur, motDePasse); source.setDriverClassName("org.mariadb.jdbc.Driver");
        var fabrique = new LocalContainerEntityManagerFactoryBean();
        fabrique.setDataSource(source); fabrique.setPackagesToScan("org.example.stock.model");
        fabrique.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        fabrique.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
                "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                "hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
        fabrique.afterPropertiesSet();
        long dakarId, guineeId;
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var dakar = new Boutique(Pays.SENEGAL); dakar.setNom("Boutique JPA Dakar"); em.persist(dakar);
            var conakry = new Boutique(); conakry.setNom("Boutique JPA Conakry"); em.persist(conakry);
            em.getTransaction().commit();
            dakarId = dakar.getId(); guineeId = conakry.getId();
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=" + dakarId + " AND pays='SN' AND monnaie='XOF'") == 1, "Pays et monnaie d'une boutique du Sénégal mal écrits");
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=" + guineeId + " AND pays='GN' AND monnaie='GNF'") == 1, "Une boutique sans pays n'est pas en Guinée");
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=" + dakarId + " AND principal_type=type") == 3
                    && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE boutique_id=" + dakarId + " AND type='MOBILE_MONEY' AND operateur='Orange Money' AND nom='Orange Money'") == 1,
                    "Comptes de base d'une boutique du Sénégal inattendus");
            PaysMonnaieMigrationScenarios.exiger(em.createQuery("select b.pays from Boutique b where b.id = :id", Pays.class).setParameter("id", dakarId).getSingleResult() == Pays.SENEGAL
                    && em.createQuery("select b.monnaie from Boutique b where b.id = :id", Monnaie.class).setParameter("id", dakarId).getSingleResult() == Monnaie.XOF, "Pays ou monnaie mal relus par JPA");
            // Le pays ne change jamais : le code refuse, et rien n'est écrit même si l'on force l'entité.
            var relue = em.find(Boutique.class, dakarId);
            boolean refuse = false;
            try { relue.definirPays(Pays.GUINEE); } catch (IllegalStateException | IllegalArgumentException e) { refuse = true; }
            PaysMonnaieMigrationScenarios.exiger(refuse, "Changement de pays accepté");
            em.getTransaction().begin(); relue.setNom("Dakar renommée"); em.getTransaction().commit();
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=" + dakarId + " AND pays='SN' AND monnaie='XOF'") == 1, "Pays ou monnaie modifiés par une mise à jour");
            // Un compte Wave et un compte MTN MoMo.
            em.getTransaction().begin();
            var wave = new CompteTresorerieBoutique(em.find(Boutique.class, dakarId), TypeCompteTresorerie.MOBILE_MONEY, "Wave Dakar", false, 5); wave.definirOperateur("Wave"); em.persist(wave);
            em.getTransaction().commit();
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM compte_tresorerie_boutique WHERE id=" + wave.getId() + " AND type='MOBILE_MONEY' AND operateur='Wave' AND principal_type IS NULL") == 1,
                    "Compte Wave mal enregistré");
            em.clear();
            PaysMonnaieMigrationScenarios.exiger("Mobile money · Wave".equals(em.find(CompteTresorerieBoutique.class, wave.getId()).getLibelleType()), "Libellé du compte Wave inattendu");
            // Les requêtes JPQL sur le type MOBILE_MONEY relisent les comptes de la boutique.
            PaysMonnaieMigrationScenarios.exiger(em.createQuery("select count(c) from CompteTresorerieBoutique c where c.boutique.id = :b and c.type = :t", Long.class)
                    .setParameter("b", dakarId).setParameter("t", TypeCompteTresorerie.MOBILE_MONEY).getSingleResult() == 2, "Comptes de mobile money mal comptés");
        }
        // Une relance n'écrase pas ce que le code actuel a créé.
        var avant = PaysMonnaieMigrationScenarios.etatComplet(c);
        PaysMonnaieMigration.migrer(c, true);
        PaysMonnaieMigrationScenarios.exiger(avant.equals(PaysMonnaieMigrationScenarios.etatComplet(c)), "La relance a modifié les boutiques créées par JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; pays, monnaie, opérateur et immuabilité relus.");
    }

    private static void verifierLotRequis(Connection c) throws SQLException {
        var avant = PaysMonnaieMigration.photographie(c); var tables = BoutiquesMigration.tables(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { PaysMonnaieMigration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lots 5 et 6"); }
            PaysMonnaieMigrationScenarios.exiger(refuse && avant.equals(PaysMonnaieMigration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Lot 6 appliqué sans les lots 5 et 6.");
        }
    }

    /** Sans sauvegarde vérifiée, l'application est refusée avant toute connexion ; le contrôle seul reste possible. */
    private static void verifierSansSauvegarde(Connection c) throws Exception {
        var avant = PaysMonnaieMigrationScenarios.etatComplet(c);
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        boolean sansVariable = false, dossierVide = false;
        try { PaysMonnaieMigration.verifierAutorisationApplication(url, null); } catch (IllegalStateException e) { sansVariable = e.getMessage().contains("PAYS_MONNAIE_BACKUP_DIR"); }
        try { PaysMonnaieMigration.verifierAutorisationApplication(url, Files.createTempDirectory("pays-monnaie-sans-dump").toString()); } catch (IllegalStateException e) { dossierVide = e.getMessage().contains("Sauvegarde SQL complète"); }
        PaysMonnaieMigrationScenarios.exiger(sansVariable && dossierVide && avant.equals(PaysMonnaieMigrationScenarios.etatComplet(c)), "Application autorisée sans sauvegarde vérifiée.");
        PaysMonnaieMigration.migrer(c, false);
        PaysMonnaieMigrationScenarios.exiger(avant.equals(PaysMonnaieMigrationScenarios.etatComplet(c)), "Le contrôle a modifié la base.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = PaysMonnaieMigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            PaysMonnaieMigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + PaysMonnaieMigration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { PaysMonnaieMigration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                PaysMonnaieMigrationScenarios.exiger(refuse && avant.equals(PaysMonnaieMigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + PaysMonnaieMigration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        PaysMonnaieMigrationScenarios.exiger(base.matches("stock_pays_monnaie_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
