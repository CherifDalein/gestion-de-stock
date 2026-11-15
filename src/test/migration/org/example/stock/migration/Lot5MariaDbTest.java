package org.example.stock.migration;

import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import org.example.stock.compta.*;
import org.example.stock.enums.TypeCompteTresorerie;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. Lot 5a : migration, validation Hibernate et requêtes groupées de la comptabilité sur MariaDB. */
public final class Lot5MariaDbTest {
    private Lot5MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT5_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot5MigrationScenarios.VALIDES); tous.addAll(Lot5MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot6_requis", "sans_sauvegarde", "validation_jpa", "validation_jpa_schema_neuf", "requetes_groupees"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 5 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot5_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot5MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot6_requis")) Lot6MigrationScenarios.schema(c, false);
                    else if (scenario.equals("schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufLot5(c);
                    else if (scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufLot5(c);
                    else Lot5MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot6_requis")) verifierLotRequis(c);
                    else if (scenario.equals("sans_sauvegarde")) verifierSansSauvegarde(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else if (scenario.equals("requetes_groupees")) verifierRequetesGroupees(c, serveur, utilisateur, motDePasse, base);
                    else { Lot5MigrationScenarios.preparer(c, scenario); Lot5MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 5 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot5MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 5 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 5 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    private static EntityManagerFactoryOuvert ouvrir(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        Lot2Migration.migrer(c, true);
        Lot4Migration.migrer(c, true);
        Lot3Migration.migrer(c, true);
        Lot6Migration.migrer(c, true);
        Lot5Migration.migrer(c, true);
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
        return new EntityManagerFactoryOuvert(fabrique.getObject());
    }

    private record EntityManagerFactoryOuvert(jakarta.persistence.EntityManagerFactory emf) {}

    /** Hibernate en mode validate accepte les entités (y compris les deux nouvelles tables), et les contraintes tiennent avec la vraie base. */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        try (var emf = ouvrir(c, serveur, utilisateur, motDePasse, base).emf(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA lot 5"); em.persist(b);
            em.flush();
            var compte = b.getComptesTresorerie().getFirst();
            em.persist(new SousCompteBoutique(b, "571", "5711", "Caisse", compte, LocalDateTime.of(2026, 11, 14, 9, 30, 15)));
            em.persist(new SousCompteBoutique(b, "658", "6589001", "Pertes diverses", null, LocalDateTime.of(2026, 11, 14, 9, 31, 15)));
            em.persist(new CorrespondanceCompta(b, "VENTES_SERVICES", "707", null));
            em.persist(new CorrespondanceCompta(b, "DEPENSE_LOYER", "623", "loyer, bail"));
            em.getTransaction().commit();
            em.clear();
            Lot5MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM sous_compte_boutique WHERE numero='6589001' AND compte_tresorerie_id IS NULL") == 1, "Sous-compte JPA non relu");
            Lot5MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM correspondance_compta WHERE cle='DEPENSE_LOYER' AND mots_cles='loyer, bail'") == 1, "Correspondance JPA non relue");
            boolean refuse = false;
            em.getTransaction().begin();
            try { em.persist(new CorrespondanceCompta(em.find(Boutique.class, b.getId()), "VENTES_SERVICES", "706", null)); em.flush(); } catch (RuntimeException e) { refuse = true; }
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            Lot5MigrationScenarios.exiger(refuse, "Deux lignes de correspondance de même clé acceptées");
        }
        var avant = Lot5MigrationScenarios.etatComplet(c);
        Lot5Migration.migrer(c, true);
        Lot5MigrationScenarios.exiger(avant.equals(Lot5MigrationScenarios.etatComplet(c)), "La relance a modifié ce que le code actuel a créé");
        System.out.println("Hibernate validate accepte le schéma migré ; sous-comptes et correspondance relus.");
    }

    /**
     * Les requêtes groupées de la comptabilité (JPQL et SQL natif de {@code DonneesComptablesService}) tournent sur MariaDB : un jeu de données réel
     * (vente, achat, retour, mouvements, dépense, stock) donne des écritures équilibrées, et la valeur du stock d'une date passée remonte les
     * opérations postérieures.
     */
    private static void verifierRequetesGroupees(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        try (var emf = ouvrir(c, serveur, utilisateur, motDePasse, base).emf(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique requêtes lot 5"); em.persist(b); em.flush();
            var caisse = b.getComptesTresorerie().stream().filter(x -> x.getType() == TypeCompteTresorerie.ESPECES).findFirst().orElseThrow();
            var client = new Client(); client.setBoutique(b); client.setNom("Client requêtes"); client.setSoldeInitial(new java.math.BigDecimal("5000")); em.persist(client);
            var fournisseur = new Fournisseur(); fournisseur.setBoutique(b); fournisseur.setNom("Fournisseur requêtes"); em.persist(fournisseur);
            var produit = new Produit(); produit.setBoutique(b); produit.setNom("Produit requêtes"); produit.setReference("RQ-1"); produit.setPrixAchat(new java.math.BigDecimal("100"));
            produit.setPrixVente(new java.math.BigDecimal("200")); produit.setQuantite(7L); produit.setCoutMoyen(new java.math.BigDecimal("100")); em.persist(produit);
            var service = new Produit(); service.setBoutique(b); service.setNom("Service requêtes"); service.setReference("RQ-S"); service.setNature(org.example.stock.enums.NatureProduit.SERVICE);
            service.setPrixVente(new java.math.BigDecimal("50")); service.setQuantite(0L); em.persist(service);
            var jour = LocalDateTime.of(2026, 3, 10, 9, 0);
            var vente = new Vente(); vente.setBoutique(b); vente.setNumero("V-RQ-1"); vente.setDateVente(jour); vente.setMontantTotal(new java.math.BigDecimal("450")); vente.setMontantVerse(new java.math.BigDecimal("450")); vente.setClient(client); em.persist(vente);
            var l1 = new DetailVente(); l1.setVente(vente); l1.setProduit(produit); l1.setQuantite(2); l1.setPrixUnitaire(new java.math.BigDecimal("200")); l1.setCoutUnitaire(new java.math.BigDecimal("100")); em.persist(l1);
            var l2 = new DetailVente(); l2.setVente(vente); l2.setProduit(service); l2.setQuantite(1); l2.setPrixUnitaire(new java.math.BigDecimal("50")); l2.setCoutUnitaire(java.math.BigDecimal.ZERO); em.persist(l2);
            var achat = new Achat(); achat.setBoutique(b); achat.setNumero("A-RQ-1"); achat.setDateAchat(jour.plusDays(1)); achat.setMontantTotal(new java.math.BigDecimal("300")); achat.setMontantVerse(new java.math.BigDecimal("100")); achat.setFournisseur(fournisseur); em.persist(achat);
            var l3 = new DetailAchat(); l3.setAchat(achat); l3.setProduit(produit); l3.setQuantite(3); l3.setPrixAchatUnitaire(new java.math.BigDecimal("100")); l3.setPrixVenteUnitaire(new java.math.BigDecimal("200")); em.persist(l3);
            mouvement(em, b, caisse, jour, "450", "VENTE", "Vente", vente, null);
            mouvement(em, b, caisse, jour.plusDays(1), "-100", "ACHAT", "Achat", null, achat);
            mouvement(em, b, caisse, jour.plusDays(2), "-80", "AUTRE_CHARGE", "Charge", null, null);
            em.getTransaction().commit();
            em.clear();
            var donnees = new DonneesComptablesService(em);
            var fin = LocalDateTime.of(2026, 3, 31, 23, 59);
            // Stock à une date passée : 7 en stock aujourd'hui, 3 achetés le 11/03 (donc 4 au 10/03 avant l'achat... remontés), 2 vendus le 10/03.
            var avantAchat = donnees.charger(b.getId(), jour.plusHours(1), LocalDateTime.of(2026, 11, 14, 9, 0), true);
            var apresTout = donnees.charger(b.getId(), fin, LocalDateTime.of(2026, 11, 14, 9, 0), true);
            Lot5MigrationScenarios.exiger(avantAchat.ventes().size() == 1 && avantAchat.achats().isEmpty() && apresTout.achats().size() == 1, "Dates de coupure des requêtes groupées");
            Lot5MigrationScenarios.exiger(apresTout.ventes().getFirst().services().compareTo(new java.math.BigDecimal("50")) == 0, "Part de services de la vente");
            // Stock : 7 aujourd'hui + 2 vendus (10/03) - 3 achetés (11/03) = 6 avant la vente ; après la vente du 10/03 mais avant l'achat : 4.
            Lot5MigrationScenarios.exiger(avantAchat.valeursStock().getFirst().valeur().compareTo(new java.math.BigDecimal("400")) == 0, "Valeur du stock à la date de la vente : " + avantAchat.valeursStock().getFirst().valeur());
            Lot5MigrationScenarios.exiger(apresTout.valeursStock().getFirst().valeur().compareTo(new java.math.BigDecimal("700")) == 0, "Valeur du stock en fin de période : " + apresTout.valeursStock().getFirst().valeur());
            var ecritures = new ConstructeurEcritures(apresTout, Correspondance.parDefaut(Map.of(caisse.getId(), "5711"))).construire();
            Lot5MigrationScenarios.exiger(!ecritures.isEmpty() && ecritures.stream().allMatch(Ecriture::estEquilibree), "Écritures déséquilibrées sur MariaDB");
            Lot5MigrationScenarios.exiger(ecritures.stream().anyMatch(e -> e.origine().startsWith("REPORT:OUV-C")), "Solde de départ du client absent");
            Lot5MigrationScenarios.exiger(donnees.valeurStockAppli(b.getId()).compareTo(new java.math.BigDecimal("700")) == 0, "Valeur du stock de l'application");
        }
        System.out.println("Requêtes groupées de la comptabilité : JPQL et SQL natif acceptés par MariaDB, écritures équilibrées.");
    }

    private static void mouvement(jakarta.persistence.EntityManager em, Boutique b, CompteTresorerieBoutique compte, LocalDateTime date, String montant, String source, String motif, Vente v, Achat a) {
        var m = new MouvementCaisse(); m.setBoutique(b); m.setCompte(compte); m.setDateMouvement(date); m.setMontant(new java.math.BigDecimal(montant));
        m.setType(montant.startsWith("-") ? "SORTIE" : "ENTREE"); m.setSource(source); m.setMotif(motif); m.setVente(v); m.setAchat(a); em.persist(m);
    }

    private static void verifierLotRequis(Connection c) throws SQLException {
        var avant = Lot5Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { Lot5Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lot 6"); }
            Lot5MigrationScenarios.exiger(refuse && avant.equals(Lot5Migration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Lot 5 appliqué sans le lot 6.");
        }
    }

    /** Sans sauvegarde vérifiée, l'application est refusée avant toute connexion ; le contrôle seul reste possible. */
    private static void verifierSansSauvegarde(Connection c) throws Exception {
        var avant = Lot5MigrationScenarios.etatComplet(c);
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        boolean sansVariable = false, dossierVide = false;
        try { Lot5Migration.verifierAutorisationApplication(url, null); } catch (IllegalStateException e) { sansVariable = e.getMessage().contains("LOT5_BACKUP_DIR"); }
        try { Lot5Migration.verifierAutorisationApplication(url, Files.createTempDirectory("lot5-sans-dump").toString()); } catch (IllegalStateException e) { dossierVide = e.getMessage().contains("Sauvegarde SQL complète"); }
        Lot5MigrationScenarios.exiger(sansVariable && dossierVide && avant.equals(Lot5MigrationScenarios.etatComplet(c)), "Application autorisée sans sauvegarde vérifiée.");
        Lot5Migration.migrer(c, false);
        Lot5MigrationScenarios.exiger(avant.equals(Lot5MigrationScenarios.etatComplet(c)), "Le contrôle a modifié la base.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot5MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot5MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot5Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot5Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot5MigrationScenarios.exiger(refuse && avant.equals(Lot5MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot5Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot5MigrationScenarios.exiger(base.matches("stock_lot5_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
