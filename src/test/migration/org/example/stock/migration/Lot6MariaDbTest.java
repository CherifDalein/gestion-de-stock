package org.example.stock.migration;

import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class Lot6MariaDbTest {
    private Lot6MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT6_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot6MigrationScenarios.VALIDES); tous.addAll(Lot6MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot3_requis", "lot4_requis", "sans_sauvegarde", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 6 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot6_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot6MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot3_requis")) Lot3MigrationScenarios.schema(c, false);
                    else if (scenario.equals("lot4_requis")) Lot4MigrationScenarios.schema(c, false);
                    else if (scenario.equals("schema_neuf") || scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufLot6(c);
                    else Lot6MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot3_requis") || scenario.equals("lot4_requis")) verifierLotRequis(c);
                    else if (scenario.equals("sans_sauvegarde")) verifierSansSauvegarde(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { Lot6MigrationScenarios.preparer(c, scenario); Lot6MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 6 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot6MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 6 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 6 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration (historique ou neuve), Hibernate en mode validate accepte les entités. Puis, avec la vraie base : une fiche de chaque type
     * mise à la corbeille disparaît des requêtes JPQL mais reste en base ; une catégorie du même nom peut alors être créée (même avec une autre
     * casse, la collation de la base décide) ; deux catégories actives du même nom sont refusées ; la restauration d'un nom repris est refusée par
     * la clé unique et possible une fois l'homonyme renommé.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        Lot2Migration.migrer(c, true);
        Lot4Migration.migrer(c, true);
        Lot3Migration.migrer(c, true);
        Lot6Migration.migrer(c, true);
        Lot5Migration.migrer(c, true);
        // Et le pays et la monnaie des boutiques.
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
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA lot 6"); em.persist(b);
            var categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Boissons"); em.persist(categorie);
            var client = new Client(); client.setBoutique(b); client.setNom("Client JPA lot 6"); em.persist(client);
            var fournisseur = new Fournisseur(); fournisseur.setBoutique(b); fournisseur.setNom("Fournisseur JPA lot 6"); em.persist(fournisseur);
            var produit = new Produit(); produit.setBoutique(b); produit.setNom("Produit JPA lot 6"); produit.setReference("JPA-6"); produit.setCategorie(categorie);
            produit.setPrixAchat(java.math.BigDecimal.ONE); produit.setPrixVente(java.math.BigDecimal.TEN); produit.setQuantite(0L); em.persist(produit);
            em.getTransaction().commit();
            long boutiqueId = b.getId(), categorieId = categorie.getId(), clientId = client.getId(), fournisseurId = fournisseur.getId(), produitId = produit.getId();
            Lot6MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=" + categorieId + " AND nom_cle='Boissons' AND supprime_le IS NULL") == 1,
                    "La clé d'unicité d'une catégorie créée par JPA n'est pas son nom");
            // Mise à la corbeille : par JPA, comme les services.
            em.getTransaction().begin();
            var maintenant = LocalDateTime.of(2026, 11, 14, 9, 30, 15);
            for (ElementSupprimable fiche : List.<ElementSupprimable>of(em.find(Produit.class, produitId), em.find(Client.class, clientId),
                    em.find(Fournisseur.class, fournisseurId), em.find(Categorie.class, categorieId))) fiche.mettreALaCorbeille(maintenant, 1L);
            em.getTransaction().commit();
            em.clear();
            Lot6MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=" + categorieId + " AND nom_cle IS NULL AND supprime_par=1 AND supprime_le='2026-11-14 09:30:15'") == 1,
                    "La mise à la corbeille d'une catégorie n'a pas vidé sa clé ou n'a pas écrit sa date");
            for (String entite : List.of("Client", "Fournisseur", "Produit", "Categorie")) {
                Lot6MigrationScenarios.exiger(em.createQuery("select count(e) from " + entite + " e where e.boutique.id = :b", Long.class).setParameter("b", boutiqueId).getSingleResult() == 0,
                        entite + " de la corbeille encore visible en JPQL");
            }
            Lot6MigrationScenarios.exiger(em.find(Client.class, clientId) == null && em.find(Produit.class, produitId) == null, "Fiche de la corbeille trouvée par identifiant");
            Lot6MigrationScenarios.exiger(em.createQuery("select count(p) from Categorie c left join c.produits p where c.boutique.id = :b", Long.class).setParameter("b", boutiqueId).getSingleResult() == 0,
                    "Jointure sur une catégorie de la corbeille");
            // Même nom, autre casse : la collation de la base décide, comme pour l'ancienne unicité (nom, boutique).
            em.getTransaction().begin();
            var neuve = new Categorie(); neuve.setBoutique(em.find(Boutique.class, boutiqueId)); neuve.setNom("Boissons"); em.persist(neuve);
            em.getTransaction().commit();
            long neuveId = neuve.getId();
            boolean refuse = false;
            em.getTransaction().begin();
            try {
                var doublon = new Categorie(); doublon.setBoutique(em.find(Boutique.class, boutiqueId)); doublon.setNom("BOISSONS"); em.persist(doublon); em.flush();
            } catch (RuntimeException e) { refuse = true; }
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            Lot6MigrationScenarios.exiger(refuse, "Deux catégories actives du même nom (casse différente) acceptées");
            em.clear();
            // Restauration du nom repris : refusée par la clé unique ; possible après renommage de l'homonyme actif.
            boolean restaurationRefusee = false;
            try { Lot6MigrationScenarios.sql(c, "UPDATE categorie SET supprime_le=NULL, supprime_par=NULL, nom_cle=nom WHERE id=" + categorieId); }
            catch (SQLException e) { restaurationRefusee = e.getSQLState() != null && e.getSQLState().startsWith("23"); }
            Lot6MigrationScenarios.exiger(restaurationRefusee, "La restauration d'un nom repris n'a pas été refusée");
            em.getTransaction().begin();
            em.find(Categorie.class, neuveId).setNom("Boissons chaudes");
            em.getTransaction().commit();
            Lot6MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM categorie WHERE id=" + neuveId + " AND nom_cle='Boissons chaudes'") == 1, "Renommage : clé non mise à jour");
            Lot6MigrationScenarios.sql(c, "UPDATE categorie SET supprime_le=NULL, supprime_par=NULL, nom_cle=nom WHERE id=" + categorieId);
            em.clear();
            Lot6MigrationScenarios.exiger(em.find(Categorie.class, categorieId) != null && "Boissons".equals(em.find(Categorie.class, categorieId).getNomCle()), "Catégorie restaurée non relue");
        }
        // Une relance n'écrase pas ce que le code actuel a créé.
        var avant = Lot6MigrationScenarios.etatComplet(c);
        Lot6Migration.migrer(c, true);
        Lot6MigrationScenarios.exiger(avant.equals(Lot6MigrationScenarios.etatComplet(c)), "La relance a modifié les fiches créées par JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; corbeille, clé d'unicité et restauration relues.");
    }

    private static void verifierLotRequis(Connection c) throws SQLException {
        var avant = Lot6Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { Lot6Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lots 3 et 4 doivent être terminées"); }
            Lot6MigrationScenarios.exiger(refuse && avant.equals(Lot6Migration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Lot 6 appliqué sans les lots 3 et 4.");
        }
    }

    /** Sans sauvegarde vérifiée, l'application est refusée avant toute connexion ; le contrôle seul reste possible. */
    private static void verifierSansSauvegarde(Connection c) throws Exception {
        var avant = Lot6MigrationScenarios.etatComplet(c);
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        boolean sansVariable = false, dossierVide = false;
        try { Lot6Migration.verifierAutorisationApplication(url, null); } catch (IllegalStateException e) { sansVariable = e.getMessage().contains("LOT6_BACKUP_DIR"); }
        try { Lot6Migration.verifierAutorisationApplication(url, Files.createTempDirectory("lot6-sans-dump").toString()); } catch (IllegalStateException e) { dossierVide = e.getMessage().contains("Sauvegarde SQL complète"); }
        Lot6MigrationScenarios.exiger(sansVariable && dossierVide && avant.equals(Lot6MigrationScenarios.etatComplet(c)), "Application autorisée sans sauvegarde vérifiée.");
        Lot6Migration.migrer(c, false);
        Lot6MigrationScenarios.exiger(avant.equals(Lot6MigrationScenarios.etatComplet(c)), "Le contrôle a modifié la base.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot6MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot6MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot6Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot6Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot6MigrationScenarios.exiger(refuse && avant.equals(Lot6MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot6Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot6MigrationScenarios.exiger(base.matches("stock_lot6_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
