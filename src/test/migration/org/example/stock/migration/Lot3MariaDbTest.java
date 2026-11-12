package org.example.stock.migration;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.*;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Bases locales jetables contrôlées ; aucune opération sur stock_pro. */
public final class Lot3MariaDbTest {
    private Lot3MariaDbTest() {}
    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        String courant = "préparation";
        try {
            var configuration = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) { configuration.load(lecteur); }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.url")), System.getenv("LOT3_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.username"));
            String motDePasse = AccountEmailMigration.resolve(configuration.getProperty("spring.datasource.password"));
            var tous = new ArrayList<>(Lot3MigrationScenarios.VALIDES); tous.addAll(Lot3MigrationScenarios.INVALIDES);
            tous.addAll(List.of("verrou", "lot4_requis", "sans_sauvegarde", "validation_jpa", "validation_jpa_schema_neuf"));
            List<String> scenarios = args.length == 0 ? tous : List.of(args);
            if (!tous.containsAll(scenarios)) throw new IllegalStateException("Scénario lot 3 inconnu.");
            int reussis = 0;
            for (String scenario : scenarios) {
                courant = scenario;
                String base = "stock_lot3_test_" + Long.toUnsignedString(System.nanoTime(), 36); verifierBase(base);
                boolean creee = false;
                try (var c = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                    Lot3MigrationScenarios.sql(c, "CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    creee = true;
                    c.setCatalog(base);
                    if (scenario.equals("lot4_requis")) { Lot4MigrationScenarios.schema(c, false); }
                    else if (scenario.equals("schema_neuf") || scenario.equals("validation_jpa_schema_neuf")) IdentiteBoutiqueMigrationScenarios.schemaNeufLot3(c);
                    else Lot3MigrationScenarios.schema(c, scenario.equals("vide"));
                    if (scenario.equals("verrou")) verifierVerrou(c, serveur, utilisateur, motDePasse);
                    else if (scenario.equals("lot4_requis")) verifierLot4Requis(c);
                    else if (scenario.equals("sans_sauvegarde")) verifierSansSauvegarde(c);
                    else if (scenario.startsWith("validation_jpa")) verifierValidationJpa(c, serveur, utilisateur, motDePasse, base);
                    else { Lot3MigrationScenarios.preparer(c, scenario); Lot3MigrationScenarios.verifier(c, scenario); }
                    reussis++; System.out.println("OK lot 3 MariaDB : " + scenario);
                } finally {
                    if (creee) try (var nettoyage = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
                        verifierBase(base); Lot3MigrationScenarios.sql(nettoyage, "DROP DATABASE " + base);
                    }
                }
            }
            System.out.println(reussis + " scénarios lot 3 MariaDB réussis ; bases temporaires supprimées.");
        } catch (Exception e) {
            System.err.println("Vérification MariaDB lot 3 interrompue : " + (e instanceof IllegalStateException ? e.getMessage()
                    : "Opération impossible pour " + courant + (e instanceof SQLException sql ? " (SQLState=" + sql.getSQLState() + ", code=" + sql.getErrorCode() + ")" : "") + " ; aucune donnée privée publiée."));
            System.exit(1);
        }
    }

    /**
     * Après migration (historique ou neuve), Hibernate en mode validate accepte les entités. Une boutique créée par JPA reçoit ses trois comptes
     * principaux ; un deuxième compte Orange Money, un mouvement, une opération, un remboursement et l'autorisation d'un caissier sont enregistrés et
     * relus ; les contraintes d'unicité refusent un nom en double (casse comprise) et un second principal ; une relance ne réécrit rien.
     */
    private static void verifierValidationJpa(Connection c, String serveur, String utilisateur, String motDePasse, String base) throws Exception {
        // Une base historique reçoit d'abord les lots précédents ; le schéma neuf contient déjà tout.
        if (Lot3Migration.ancien(c)) { Lot1Migration.migrer(c, true); Lot2Migration.migrer(c, true); Lot4Migration.migrer(c, true); }
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
        long boutiqueId, orange2Id, mouvementId, caissierId;
        try (var emf = fabrique.getObject(); var em = emf.createEntityManager()) {
            em.getTransaction().begin();
            var b = new Boutique(); b.setNom("Boutique JPA lot 3"); em.persist(b);
            var u = new Utilisateur(); u.setBoutique(b); u.setNom("Caissier JPA"); u.setEmail("caissier-lot3@example.test"); em.persist(u);
            em.getTransaction().commit();
            boutiqueId = b.getId(); caissierId = u.getId();
            em.clear();
            var comptes = em.createQuery("select c from CompteTresorerieBoutique c where c.boutique.id = :b order by c.ordre", CompteTresorerieBoutique.class).setParameter("b", boutiqueId).getResultList();
            Lot3MigrationScenarios.exiger(comptes.size() == 3 && comptes.stream().allMatch(x -> x.isPrincipal() && x.isActif())
                    && comptes.stream().map(CompteTresorerieBoutique::getNom).toList().equals(List.of("Caisse", "Orange Money", "Banque")), "Comptes de base de la boutique JPA non relus");
            em.getTransaction().begin();
            var b2 = em.find(Boutique.class, boutiqueId);
            var orange = comptes.get(1);
            var orange2 = new CompteTresorerieBoutique(b2, TypeCompteTresorerie.ORANGE_MONEY, "Orange Money Labé", false, 3);
            orange2.renommer("Orange Money Labé", "622 00 00 00"); em.persist(orange2);
            em.flush();
            var m = new MouvementCaisse(); m.setBoutique(b2); m.setCompte(orange2); m.setMontant(new java.math.BigDecimal("1500.00")); m.setType("ENTREE"); m.setSource("VENTE");
            m.setDateMouvement(java.time.LocalDateTime.of(2026, 3, 1, 8, 0)); em.persist(m);
            var op = new OperationTresorerie(); op.setBoutique(b2); op.setUtilisateur(em.find(Utilisateur.class, caissierId)); op.setJeton(java.util.UUID.randomUUID().toString());
            op.setType(org.example.stock.enums.TypeOperationTresorerie.TRANSFERT); op.setCompteSource(orange); op.setCompteDestination(orange2);
            op.setMontant(new java.math.BigDecimal("10.00")); op.setMotif("Transfert JPA"); op.setDateOperation(java.time.LocalDateTime.of(2026, 3, 1, 9, 0)); em.persist(op);
            em.persist(new CompteTresorerieAutorise(em.find(Utilisateur.class, caissierId), orange2));
            em.getTransaction().commit();
            orange2Id = orange2.getId(); mouvementId = m.getId();
            em.clear();
            var relu = em.find(MouvementCaisse.class, mouvementId);
            Lot3MigrationScenarios.exiger(relu.getCompte().getId().equals(orange2Id) && relu.getCompte().getType() == TypeCompteTresorerie.ORANGE_MONEY
                    && "Orange Money Labé".equals(relu.getCompte().getNom()) && "622 00 00 00".equals(relu.getCompte().getNumero()) && !relu.getCompte().isPrincipal(), "Mouvement ou compte non relu par JPA");
            // Financements (3b1) : partenaire, emprunt, échéancier, remboursement et imputation relus par JPA.
            em.getTransaction().begin();
            var bf = em.find(Boutique.class, boutiqueId);
            var pf = new PartenaireFinancier(); pf.setBoutique(bf); pf.renommer("BICIGUI JPA"); pf.setType(org.example.stock.enums.TypePartenaireFinancier.BANQUE);
            pf.setActif(true); pf.setDateCreation(java.time.LocalDateTime.of(2026, 3, 1, 8, 0)); em.persist(pf);
            var emprunt = new Emprunt(); emprunt.setBoutique(bf); emprunt.setPartenaire(pf); emprunt.setUtilisateur(em.find(Utilisateur.class, caissierId)); emprunt.setCompte(orange2);
            emprunt.setNumero("EM-000001"); emprunt.setSens(org.example.stock.enums.SensEmprunt.EMPRUNT); emprunt.setDateEmprunt(java.time.LocalDate.of(2026, 3, 1));
            emprunt.setCapital(new java.math.BigDecimal("1200.00")); emprunt.setTauxAnnuel(new java.math.BigDecimal("12.00")); emprunt.setNombreEcheances(2);
            emprunt.setPeriodicite(org.example.stock.enums.Periodicite.MENSUELLE); emprunt.setDatePremiereEcheance(java.time.LocalDate.of(2026, 4, 1));
            emprunt.setFraisDossier(java.math.BigDecimal.ZERO); emprunt.setJeton(java.util.UUID.randomUUID().toString()); emprunt.setDateCreation(java.time.LocalDateTime.of(2026, 3, 1, 8, 0));
            em.persist(emprunt);
            var echeance = new EcheanceEmprunt(); echeance.setEmprunt(emprunt); echeance.setRang(1); echeance.setDateEcheance(java.time.LocalDate.of(2026, 4, 1));
            echeance.setCapital(new java.math.BigDecimal("600.00")); echeance.setInteret(new java.math.BigDecimal("12.00")); em.persist(echeance);
            var remboursement = new OperationTresorerie(); remboursement.setBoutique(b2); remboursement.setUtilisateur(em.find(Utilisateur.class, caissierId));
            remboursement.setJeton(java.util.UUID.randomUUID().toString()); remboursement.setType(org.example.stock.enums.TypeOperationTresorerie.REMBOURSEMENT);
            remboursement.setCompteSource(orange2); remboursement.setMontant(new java.math.BigDecimal("100.00")); remboursement.setMotif("Remboursement JPA");
            remboursement.setDateOperation(java.time.LocalDateTime.of(2026, 4, 1, 9, 0)); remboursement.setPartenaireFinancier(pf); remboursement.setEmprunt(emprunt); em.persist(remboursement);
            var imputation = new ImputationEcheance(); imputation.setOperation(remboursement); imputation.setEcheance(echeance);
            imputation.setCapital(new java.math.BigDecimal("88.00")); imputation.setInteret(new java.math.BigDecimal("12.00")); em.persist(imputation);
            em.getTransaction().commit();
            long empruntId = emprunt.getId(), imputationId = imputation.getId();
            em.clear();
            var empruntRelu = em.find(Emprunt.class, empruntId);
            Lot3MigrationScenarios.exiger(empruntRelu.getPartenaire().getNomCle().equals("bicigui jpa") && empruntRelu.getCapital().compareTo(new java.math.BigDecimal("1200")) == 0
                    && empruntRelu.getPeriodicite() == org.example.stock.enums.Periodicite.MENSUELLE && em.find(ImputationEcheance.class, imputationId).getOperation().getEmprunt().getId().equals(empruntId),
                    "Emprunt, échéancier ou imputation non relus par JPA");
            // Contrôles (3b2) : comptage avec billetage, mouvement d'écart lié, ajustement de solde de départ, inventaire des créances relus par JPA.
            em.getTransaction().begin();
            var bc = em.find(Boutique.class, boutiqueId); var auteur = em.find(Utilisateur.class, caissierId);
            var comptage = new ComptageCaisse(); comptage.setBoutique(bc); comptage.setCompte(orange2); comptage.setUtilisateur(auteur);
            comptage.setDateComptage(java.time.LocalDateTime.of(2026, 5, 2, 18, 0)); comptage.setMontantCompte(new java.math.BigDecimal("1490.00"));
            comptage.setSoldeTheorique(new java.math.BigDecimal("1500.00")); comptage.setEcart(new java.math.BigDecimal("-10.00"));
            comptage.setStatut(org.example.stock.enums.StatutComptage.VALIDE); comptage.setDecideur(auteur); comptage.setDateDecision(java.time.LocalDateTime.of(2026, 5, 3, 9, 0));
            comptage.setMotifDecision("Erreur de rendu"); comptage.setJeton(java.util.UUID.randomUUID().toString());
            comptage.ajouterLigne(1000, 1); comptage.ajouterLigne(500, 0); em.persist(comptage);
            var ecart = new MouvementCaisse(); ecart.setBoutique(bc); ecart.setCompte(orange2); ecart.setMontant(new java.math.BigDecimal("-10.00")); ecart.setType("SORTIE");
            ecart.setSource("AUTRE_CHARGE"); ecart.setMotif("Écart de caisse"); ecart.setDateMouvement(java.time.LocalDateTime.of(2026, 5, 3, 9, 0)); ecart.setComptageCaisse(comptage); em.persist(ecart);
            var client = new Client(); client.setBoutique(bc); client.setNom("Client JPA 3b2"); em.persist(client);
            var ajustement = new AjustementSoldeDepart(); ajustement.setBoutique(bc); ajustement.setClient(client); ajustement.setUtilisateur(auteur);
            ajustement.setDateAjustement(java.time.LocalDateTime.of(2026, 5, 4, 9, 0)); ajustement.setAncienSolde(new java.math.BigDecimal("100.00"));
            ajustement.setNouveauSolde(new java.math.BigDecimal("-50.00")); ajustement.setMotif("Erreur de saisie"); ajustement.setJeton(java.util.UUID.randomUUID().toString()); em.persist(ajustement);
            var inventaire = new InventaireCreances(); inventaire.setBoutique(bc); inventaire.setUtilisateur(auteur); inventaire.setDateInventaire(java.time.LocalDate.of(2026, 5, 5));
            inventaire.setDateCreation(java.time.LocalDateTime.of(2026, 5, 5, 10, 0)); inventaire.setJeton(java.util.UUID.randomUUID().toString());
            var ligne = new LigneInventaire(); ligne.setInventaire(inventaire); ligne.setType(org.example.stock.enums.TypeLigneInventaire.CLIENT); ligne.setClient(client);
            ligne.setNom("Client JPA 3b2"); ligne.setSoldeAppli(new java.math.BigDecimal("-50.00")); ligne.setSoldeConfirme(new java.math.BigDecimal("-30.00"));
            ligne.setEcart(new java.math.BigDecimal("20.00")); ligne.setRaison("Paiement non enregistré"); inventaire.getLignes().add(ligne); em.persist(inventaire);
            em.getTransaction().commit();
            long comptageId = comptage.getId(), ecartId = ecart.getId(), inventaireId = inventaire.getId(), ajustementId = ajustement.getId();
            em.clear();
            var comptageRelu = em.find(ComptageCaisse.class, comptageId);
            Lot3MigrationScenarios.exiger(comptageRelu.getStatut() == org.example.stock.enums.StatutComptage.VALIDE && comptageRelu.getEcart().compareTo(new java.math.BigDecimal("-10")) == 0
                    && comptageRelu.getBilletage().size() == 2 && em.find(MouvementCaisse.class, ecartId).getComptageCaisse().getId().equals(comptageId)
                    && em.find(AjustementSoldeDepart.class, ajustementId).getVariation().compareTo(new java.math.BigDecimal("-150")) == 0
                    && em.find(InventaireCreances.class, inventaireId).getLignes().get(0).getEcart().compareTo(new java.math.BigDecimal("20")) == 0,
                    "Comptage, billetage, ajustement ou inventaire non relus par JPA");
            if (historique) {
                var migre = em.createQuery("select c from CompteTresorerieBoutique c where c.boutique.id = 1 and c.type = :t", CompteTresorerieBoutique.class)
                        .setParameter("t", TypeCompteTresorerie.BANQUE).getSingleResult();
                Lot3MigrationScenarios.exiger(migre.isPrincipal() && "Banque".equals(migre.getNom()), "Compte migré non relu par JPA");
                Lot3MigrationScenarios.exiger(em.find(MouvementCaisse.class, 301L).getCompte().getType() == TypeCompteTresorerie.BANQUE, "Mouvement migré non rattaché à son compte");
            }
        }
        // Les unicités protègent aussi hors de JPA : nom en double (casse comprise) et second principal du même type.
        boolean doublonNom = false, doublonPrincipal = false;
        try {
            Lot3MigrationScenarios.sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,type,nom,nom_cle,actif,ordre) VALUES(" + boutiqueId + ",'BANQUE','ORANGE MONEY LABÉ','orange money labé',TRUE,5)");
        } catch (SQLException e) { doublonNom = e.getSQLState().startsWith("23"); }
        try {
            Lot3MigrationScenarios.sql(c, "INSERT INTO compte_tresorerie_boutique(boutique_id,type,nom,nom_cle,principal_type,actif,ordre) VALUES(" + boutiqueId + ",'ORANGE_MONEY','Autre principal','autre principal','ORANGE_MONEY',TRUE,5)");
        } catch (SQLException e) { doublonPrincipal = e.getSQLState().startsWith("23"); }
        Lot3MigrationScenarios.exiger(doublonNom && doublonPrincipal, "Les unicités du nom ou du compte principal n'ont pas refusé le doublon");
        boolean autorisationDouble = false;
        try { Lot3MigrationScenarios.sql(c, "INSERT INTO compte_tresorerie_autorise(utilisateur_id,compte_id) VALUES(" + caissierId + "," + orange2Id + ")"); }
        catch (SQLException e) { autorisationDouble = e.getSQLState().startsWith("23"); }
        Lot3MigrationScenarios.exiger(autorisationDouble, "L'unicité des autorisations n'a pas refusé le doublon");
        // Une relance n'écrase rien de ce que le code actuel a créé.
        var avant = Lot3MigrationScenarios.etatComplet(c);
        Lot3Migration.migrer(c, true);
        Lot3MigrationScenarios.exiger(avant.equals(Lot3MigrationScenarios.etatComplet(c)), "La relance a modifié les créations JPA");
        System.out.println("Hibernate validate accepte le schéma migré ; comptes multiples, mouvement, opération et autorisation relus.");
    }

    private static void verifierLot4Requis(Connection c) throws SQLException {
        var avant = Lot3MigrationScenarios.etatComplet(c);
        for (boolean appliquer : List.of(false, true)) {
            boolean refuse = false;
            try { Lot3Migration.migrer(c, appliquer); } catch (IllegalStateException e) { refuse = e.getMessage().contains("lot 4 doit être terminée"); }
            Lot3MigrationScenarios.exiger(refuse && avant.equals(Lot3MigrationScenarios.etatComplet(c)), "Lot 3 appliqué sans le lot 4.");
        }
    }

    /** Sans sauvegarde vérifiée, l'application est refusée avant toute connexion ; le contrôle seul reste possible. */
    private static void verifierSansSauvegarde(Connection c) throws Exception {
        var avant = Lot3MigrationScenarios.etatComplet(c);
        String url = "jdbc:mariadb://localhost:3306/stock_pro";
        boolean sansVariable = false, dossierVide = false;
        try { Lot3Migration.verifierAutorisationApplication(url, null); } catch (IllegalStateException e) { sansVariable = e.getMessage().contains("LOT3_BACKUP_DIR"); }
        try { Lot3Migration.verifierAutorisationApplication(url, Files.createTempDirectory("lot3-sans-dump").toString()); } catch (IllegalStateException e) { dossierVide = e.getMessage().contains("Sauvegarde SQL complète"); }
        Lot3MigrationScenarios.exiger(sansVariable && dossierVide && avant.equals(Lot3MigrationScenarios.etatComplet(c)), "Application autorisée sans sauvegarde vérifiée.");
        Lot3Migration.migrer(c, false);
        Lot3MigrationScenarios.exiger(avant.equals(Lot3MigrationScenarios.etatComplet(c)), "Le contrôle a modifié la base.");
    }

    private static void verifierVerrou(Connection c, String serveur, String utilisateur, String motDePasse) throws SQLException {
        var avant = Lot3MigrationScenarios.etatComplet(c); var tables = BoutiquesMigration.tables(c);
        try (var autre = DriverManager.getConnection(serveur, utilisateur, motDePasse)) {
            Lot3MigrationScenarios.exiger(BoutiquesMigration.compter(autre, "SELECT GET_LOCK('" + Lot3Migration.VERROU + "',0)") == 1, "Verrou de test indisponible.");
            try {
                boolean refuse = false;
                try { Lot3Migration.migrer(c, true); } catch (IllegalStateException e) { refuse = e.getMessage().contains("déjà en cours"); }
                Lot3MigrationScenarios.exiger(refuse && avant.equals(Lot3MigrationScenarios.etatComplet(c)) && tables.equals(BoutiquesMigration.tables(c)), "Migration concurrente non refusée sans écriture.");
            } finally { BoutiquesMigration.compter(autre, "SELECT RELEASE_LOCK('" + Lot3Migration.VERROU + "')"); }
        }
    }
    private static void verifierBase(String base) {
        Lot3MigrationScenarios.exiger(base.matches("stock_lot3_test_[a-z0-9]+") && base.length() < 64, "Nom de schéma temporaire inattendu ; suppression refusée.");
    }
}
