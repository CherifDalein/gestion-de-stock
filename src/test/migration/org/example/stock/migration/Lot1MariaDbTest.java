package org.example.stock.migration;

import org.example.stock.enums.NatureProduit;
import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class Lot1MariaDbTest {
    private Lot1MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT1_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot1MigrationScenarios.VALIDES); tous.addAll(Lot1MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 1 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot1_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot1MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("schema_neuf") || scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c);
                    else Lot1MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { Lot1MigrationScenarios.preparer(c, scenario); Lot1MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 1 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot1MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 1 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 1 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration d'une base historique, Hibernate en mode validate accepte les entités ; une nouvelle vente,
     * un produit et un client créés par le code actuel reçoivent les valeurs par défaut du lot 1.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        // Les entités décrivent aussi le lot 2 : Hibernate en mode validate exige ses tables et colonnes sur la base migrée.
        Lot2Migration.migrer(c, true);
        // Les entités décrivent aussi le lot 4 (reçus) : même exigence pour ses deux colonnes.
        Lot4Migration.migrer(c, true);
        boolean historique = BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=1") == 1;
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
        long vente, produit, client, boutique;
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA factice"); em.persist(b);
            var categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie JPA"); em.persist(categorie);
            var p = new Produit(); p.setBoutique(b); p.setNom("Produit JPA"); p.setReference("JPA-1"); p.setCategorie(categorie); em.persist(p);
            var k = new Client(); k.setBoutique(b); k.setNom("Client JPA"); em.persist(k);
            var v = new Vente(); v.setBoutique(b); v.setClient(k); v.setDateVente(LocalDateTime.of(2026, 1, 1, 0, 0));
            v.setMontantTotal(new BigDecimal("10.00")); v.setMontantVerse(Montants.ZERO);
            var ligne = new DetailVente(); ligne.setVente(v); ligne.setProduit(p); ligne.setQuantite(1); ligne.setPrixUnitaire(new BigDecimal("10.00"));
            v.getLignes().add(ligne); em.persist(v); em.persist(ligne);
            em.getTransaction().commit();
            vente = v.getId(); produit = p.getId(); client = k.getId(); boutique = b.getId();
            em.clear();
            var relu = em.find(Produit.class, produit);
            Lot1MigrationScenarios.exiger(relu.getNature() == NatureProduit.STOCK && relu.getSeuilAlerte() == 5L && relu.getCoutMoyen().compareTo(BigDecimal.ZERO) == 0,
                    "Valeurs par défaut du produit non relues");
            var b2 = em.find(Boutique.class, boutique);
            Lot1MigrationScenarios.exiger(b2.getProchainNumeroVente() == 1L && b2.getDelaiClientsJours() == 30, "Valeurs par défaut de la boutique non relues");
            if (historique) {
                var migree = em.find(Vente.class, 1L);
                Lot1MigrationScenarios.exiger("FV-000001".equals(migree.getNumero()) && migree.getDateEcheance() != null, "Vente migrée non relue par JPA");
            }
        }
        Lot1MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM vente WHERE id=" + vente + " AND numero IS NULL AND date_echeance IS NULL AND avance_utilisee=0") == 1
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM produit WHERE id=" + produit + " AND nature='STOCK' AND seuil_alerte=5 AND cout_moyen=0 AND unite IS NULL") == 1
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM client WHERE id=" + client + " AND solde_initial=0 AND echeance_solde_initial IS NULL") == 1
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM boutique WHERE id=" + boutique + " AND prochain_numero_vente=1 AND prochain_numero_achat=1 AND delai_clients_jours=30 AND delai_fournisseurs_jours=30") == 1
                && BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM detail_vente WHERE vente_id=" + vente + " AND cout_unitaire IS NULL") == 1,
                "Valeurs par défaut des nouvelles lignes JPA inattendues");
        // Les créations par le code actuel ne sont pas réécrites par une relance.
        var avant = Lot1MigrationScenarios.etatComplet(c);
        Lot1Migration.migrer(c, true);
        Lot1MigrationScenarios.exiger(avant.equals(Lot1MigrationScenarios.etatComplet(c)), "La relance a modifié les créations JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; valeurs par défaut des nouvelles lignes vérifiées.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot1MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot1MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot1Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot1Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot1MigrationScenarios.exiger(refuse && avant.equals(Lot1MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot1Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot1MigrationScenarios.exiger(base.matches("stock_lot1_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
