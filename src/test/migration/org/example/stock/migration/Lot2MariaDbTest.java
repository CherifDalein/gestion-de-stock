package org.example.stock.migration;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.StatutDevis;
import org.example.stock.enums.TypeRetour;
import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.math.BigDecimal;
import java.nio.file.*;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class Lot2MariaDbTest {
    private Lot2MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT2_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot2MigrationScenarios.VALIDES); tous.addAll(Lot2MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot1_requis", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 2 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot2_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot2MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot1_requis")) IdentiteBoutiqueMigrationScenarios.schema(c, false);
                    else if (scenario.equals("schema_neuf") || scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeuf(c);
                    else Lot2MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot1_requis")) verifierLot1Requis(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { Lot2MigrationScenarios.preparer(c, scenario); Lot2MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 2 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot2MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 2 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 2 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration (historique ou neuve), Hibernate en mode validate accepte les entités ; un devis, un retour, un vendeur, un remboursement
     * et son annulation sont enregistrés et relus, et les contraintes uniques protègent la vente d'un devis et l'annulation d'un mouvement.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        Lot1Migration.migrer(c, true);
        Lot2Migration.migrer(c, true);
        // Les entités actuelles portent aussi les colonnes du lot 4 (reçus) : Hibernate les exige en mode validate.
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
        long boutiqueId, venteId, devisId, retourId, remboursementId, annulationId, achatId;
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA lot 2"); em.persist(b);
            var u = new Utilisateur(); u.setBoutique(b); u.setNom("Vendeur JPA"); u.setEmail("vendeur-lot2@example.test"); em.persist(u);
            var categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie JPA"); em.persist(categorie);
            var p = new Produit(); p.setBoutique(b); p.setNom("Produit JPA"); p.setReference("JPA-2"); p.setCategorie(categorie); em.persist(p);
            var k = new Client(); k.setBoutique(b); k.setNom("Client JPA"); em.persist(k);
            var v = new Vente(); v.setBoutique(b); v.setClient(k); v.setVendeur(u); v.setDateVente(LocalDateTime.of(2026, 1, 1, 0, 0));
            v.setMontantTotal(new BigDecimal("10.00")); v.setMontantVerse(new BigDecimal("10.00"));
            var ligneVente = new DetailVente(); ligneVente.setVente(v); ligneVente.setProduit(p); ligneVente.setQuantite(1); ligneVente.setPrixUnitaire(new BigDecimal("10.00"));
            v.getLignes().add(ligneVente); em.persist(v);
            var a = new Achat(); a.setBoutique(b); a.setUtilisateur(u); a.setDateAchat(LocalDateTime.of(2026, 1, 1, 0, 0)); a.setMontantTotal(new BigDecimal("5.00")); em.persist(a);
            var d = new Devis(); d.setBoutique(b); d.setNumero("DV-000001"); d.setClient(k); d.setUtilisateur(u); d.setDateDevis(LocalDateTime.of(2026, 1, 2, 9, 30));
            d.setDateValidite(LocalDate.of(2026, 2, 1)); d.setMontantTotal(new BigDecimal("20.00")); d.setNote("Devis JPA");
            var ligneDevis = new DetailDevis(); ligneDevis.setDevis(d); ligneDevis.setProduit(p); ligneDevis.setQuantite(2); ligneDevis.setPrixUnitaire(new BigDecimal("10.00"));
            d.getLignes().add(ligneDevis); em.persist(d);
            var r = new Retour(); r.setBoutique(b); r.setNumero("RV-000001"); r.setType(TypeRetour.VENTE); r.setVente(v); r.setClient(k); r.setUtilisateur(u);
            r.setDateRetour(LocalDateTime.of(2026, 1, 3, 10, 0)); r.setMontantTotal(new BigDecimal("10.00")); r.setMontantRembourse(new BigDecimal("6.00"));
            r.setCompteTresorerie(CompteTresorerie.ORANGE_MONEY); r.setMontantAvoir(new BigDecimal("4.00")); r.setMotif("Défectueux");
            var ligneRetour = new DetailRetour(); ligneRetour.setRetour(r); ligneRetour.setProduit(p); ligneRetour.setDetailVente(ligneVente); ligneRetour.setQuantite(1);
            ligneRetour.setPrixUnitaire(new BigDecimal("10.00")); r.getLignes().add(ligneRetour); em.persist(r);
            var rembourse = new MouvementCaisse(); rembourse.setBoutique(b); rembourse.setUtilisateur(u); rembourse.setRetour(r); rembourse.setCompte(CompteTresorerie.ORANGE_MONEY);
            rembourse.setDateMouvement(LocalDateTime.of(2026, 1, 3, 10, 1)); rembourse.setMontant(new BigDecimal("-6.00")); rembourse.setType("DECAISSEMENT"); rembourse.setSource("RETOUR");
            em.persist(rembourse);
            var annulation = new MouvementCaisse(); annulation.setBoutique(b); annulation.setUtilisateur(u); annulation.setAnnulationDe(rembourse); annulation.setCompte(CompteTresorerie.ORANGE_MONEY);
            annulation.setDateMouvement(LocalDateTime.of(2026, 1, 4, 10, 1)); annulation.setMontant(new BigDecimal("6.00")); annulation.setType("ENCAISSEMENT"); annulation.setSource("ANNULATION");
            em.persist(annulation);
            em.getTransaction().commit();
            boutiqueId = b.getId(); venteId = v.getId(); devisId = d.getId(); retourId = r.getId(); remboursementId = rembourse.getId(); annulationId = annulation.getId(); achatId = a.getId();
            em.clear();
            var b2 = em.find(Boutique.class, boutiqueId);
            Lot2MigrationScenarios.exiger(b2.getProchainNumeroDevis() == 1L && b2.getProchainNumeroRetourVente() == 1L && b2.getProchainNumeroRetourAchat() == 1L, "Compteurs par défaut de la boutique non relus");
            var relu = em.find(Devis.class, devisId);
            Lot2MigrationScenarios.exiger(relu.getStatut() == StatutDevis.EN_COURS && relu.getVersion() == 0L && relu.getLignes().size() == 1 && relu.getVente() == null
                    && relu.getMontantTotal().compareTo(new BigDecimal("20.00")) == 0 && relu.getDateValidite().equals(LocalDate.of(2026, 2, 1)), "Devis non relu par JPA");
            var retour = em.find(Retour.class, retourId);
            Lot2MigrationScenarios.exiger(retour.getType() == TypeRetour.VENTE && retour.getCompteTresorerie() == CompteTresorerie.ORANGE_MONEY && retour.getLignes().size() == 1
                    && retour.getMontantAvoir().compareTo(new BigDecimal("4.00")) == 0 && retour.getLignes().getFirst().getCoutUnitaire() == null, "Retour non relu par JPA");
            Lot2MigrationScenarios.exiger(em.find(Vente.class, venteId).getVendeur() != null && em.find(Achat.class, achatId).getUtilisateur() != null, "Vendeur ou auteur non relu");
            Lot2MigrationScenarios.exiger(em.find(MouvementCaisse.class, annulationId).getAnnulationDe().getId().equals(remboursementId)
                    && em.find(MouvementCaisse.class, remboursementId).getRetour().getId().equals(retourId), "Remboursement ou annulation non relu");
            if (historique) {
                var migree = em.find(Vente.class, 1L);
                Lot2MigrationScenarios.exiger(migree.getVendeur() != null && migree.getVendeur().getId() == 1L, "Vendeur migré non relu par JPA");
            }
        }
        // Les contraintes uniques refusent une seconde vente pour le même devis et une seconde annulation, même hors de JPA.
        Lot2MigrationScenarios.sql(c, "UPDATE devis SET vente_id=" + venteId + ",statut='TRANSFORME' WHERE id=" + devisId);
        Lot2MigrationScenarios.exiger(BoutiquesMigration.compter(c, "SELECT COUNT(*) FROM devis WHERE id=" + devisId + " AND vente_id=" + venteId + " AND statut='TRANSFORME'") == 1, "Transformation du devis non enregistrée");
        boolean doublonDevis = false;
        try {
            Lot2MigrationScenarios.sql(c, "INSERT INTO devis(boutique_id,numero,date_devis,date_validite,montant_total,statut,vente_id) VALUES(" + boutiqueId
                    + ",'DV-000002','2026-01-02 09:30:00.000000','2026-02-01',1.00,'EN_COURS'," + venteId + ")");
        } catch (SQLException e) { doublonDevis = e.getSQLState().startsWith("23"); }
        boolean doublonAnnulation = false;
        try {
            Lot2MigrationScenarios.sql(c, "INSERT INTO mouvement_caisse(boutique_id,date_mouvement,montant,type,source,annulation_de_id) VALUES(" + boutiqueId
                    + ",'2026-01-05 10:00:00.000000',6.00,'ENCAISSEMENT','ANNULATION'," + remboursementId + ")");
        } catch (SQLException e) { doublonAnnulation = e.getSQLState().startsWith("23"); }
        Lot2MigrationScenarios.exiger(doublonDevis && doublonAnnulation, "Les contraintes uniques du devis ou de l'annulation n'ont pas refusé le doublon");
        // Les créations par le code actuel ne sont pas réécrites par une relance.
        var avant = Lot2MigrationScenarios.etatComplet(c);
        Lot2Migration.migrer(c, true);
        Lot2MigrationScenarios.exiger(avant.equals(Lot2MigrationScenarios.etatComplet(c)), "La relance a modifié les créations JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; devis, retour, vendeur, remboursement et annulation relus.");
    }

    private static void verifierLot1Requis(Connection c) throws SQLException {
        var avant = Lot2Migration.photographie(c); var tables = BoutiquesMigration.tables(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { Lot2Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lot 1 doit être terminée"); }
            Lot2MigrationScenarios.exiger(refuse && avant.equals(Lot2Migration.photographie(c)) && tables.equals(BoutiquesMigration.tables(c)), "Lot 2 appliqué sans le lot 1.");
        }
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot2MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot2MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot2Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot2Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot2MigrationScenarios.exiger(refuse && avant.equals(Lot2MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot2Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot2MigrationScenarios.exiger(base.matches("stock_lot2_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
