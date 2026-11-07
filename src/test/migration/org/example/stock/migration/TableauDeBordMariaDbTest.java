package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.example.stock.config.TrialProperties;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.TableauDeBordService;
import org.example.stock.service.TableauDeBordService.EvolutionAnnuelle;
import org.example.stock.service.TableauDeBordService.Indicateurs;
import org.example.stock.service.TableauDeBordService.MoisActivite;
import org.example.stock.service.TableauDeBordService.ProduitASurveiller;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Requêtes agrégées du tableau de bord sur MariaDB, dans une base jetable créée depuis le schéma
 * documenté : sommes décimales, COALESCE, EXTRACT(MONTH) et GROUP BY, tri des produits à surveiller
 * avec LIMIT, bornes [début, fin) autour de minuit et du changement de mois, isolation des boutiques.
 * La connexion reprend l'URL et les réglages Hibernate de l'application (aucun fuseau JDBC imposé).
 */
public final class TableauDeBordMariaDbTest {
    private static final String PREFIXE = "stock_tableau_de_bord_test_";
    private static final LocalDate JOUR = LocalDate.of(2026, 3, 1);
    private final TableauDeBordService tableau;
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final CategorieRepository categories;
    private final ProduitRepository produits;
    private final FournisseurRepository fournisseurs;
    private final AchatRepository achats;
    private final VenteRepository ventes;
    private final OperationTresorerieRepository operations;
    private final ClientRepository clients;
    private final MouvementCaisseRepository mouvements;
    private int scenarios;

    private TableauDeBordMariaDbTest(AnnotationConfigApplicationContext contexte) {
        tableau = contexte.getBean(TableauDeBordService.class);
        boutiques = contexte.getBean(BoutiqueRepository.class);
        utilisateurs = contexte.getBean(UtilisateurRepository.class);
        categories = contexte.getBean(CategorieRepository.class);
        produits = contexte.getBean(ProduitRepository.class);
        fournisseurs = contexte.getBean(FournisseurRepository.class);
        achats = contexte.getBean(AchatRepository.class);
        ventes = contexte.getBean(VenteRepository.class);
        operations = contexte.getBean(OperationTresorerieRepository.class);
        clients = contexte.getBean(ClientRepository.class);
        mouvements = contexte.getBean(MouvementCaisseRepository.class);
    }

    public static void main(String[] args) {
        BoutiquesMigration.masquerLogsJdbc();
        try {
            Properties config = new Properties();
            try (var lecteur = Files.newBufferedReader(Path.of("src/main/resources/application.properties"))) {
                config.load(lecteur);
            }
            String serveur = InscriptionEssaiMariaDbTest.urlServeurTest(
                    AccountEmailMigration.resolve(config.getProperty("spring.datasource.url")),
                    System.getenv("TABLEAU_DE_BORD_TEST_DB_URL"));
            String utilisateur = AccountEmailMigration.resolve(config.getProperty("spring.datasource.username"));
            String secret = AccountEmailMigration.resolve(config.getProperty("spring.datasource.password"));
            String base = PREFIXE + Long.toUnsignedString(System.nanoTime(), 36);
            verifierBase(base);
            try (Connection administration = DriverManager.getConnection(serveur, utilisateur, secret)) {
                try (var s = administration.createStatement()) {
                    s.execute("CREATE DATABASE " + base + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                }
                try {
                    administration.setCatalog(base);
                    creerSchema(administration);
                    DriverManagerDataSource source = new DriverManagerDataSource(urlCatalogue(serveur, base), utilisateur, secret);
                    source.setDriverClassName("org.mariadb.jdbc.Driver");
                    try (var contexte = new AnnotationConfigApplicationContext()) {
                        contexte.getEnvironment().setActiveProfiles("tableau-de-bord-mariadb-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        TableauDeBordMariaDbTest test = new TableauDeBordMariaDbTest(contexte);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios du tableau de bord MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire du tableau de bord supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test du tableau de bord MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : "opération impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    private void verifier() {
        Env a = environnement("A");
        Env b = environnement("B");

        // Boutique A, jour du 1er mars 2026 (premier jour du mois : borne basse du jour et du mois).
        LocalDateTime minuit = JOUR.atStartOfDay();
        vente(a, minuit, "1000", ligne(a.riz(), 2, "300", "200"), ligne(a.service(), 1, "400", "100"));
        vente(a, minuit.withHour(18), "500.55", ligne(a.huile(), 1, "500.55", null));
        vente(a, JOUR.plusDays(1).atStartOfDay(), "333", ligne(a.riz(), 1, "333", "1"));           // lendemain : exclu du jour
        vente(a, minuit.minusNanos(1_000), "777", ligne(a.riz(), 1, "777", "7"));                  // 28/02 23:59:59.999999
        depense(a, minuit.withHour(9), "150.10");
        depense(a, minuit.minusNanos(1_000), "999");
        transfert(a, minuit.withHour(10), "5000");
        achat(a, minuit.withHour(8), "8000");
        // Boutique B, même jour.
        vente(b, minuit.withHour(12), "90000", ligne(b.riz(), 1, "90000", "1"));
        depense(b, minuit.withHour(12), "4444");

        connecter(a.gerant());
        Indicateurs jour = tableau.indicateursDuJour(JOUR);
        exigerMontant(jour.chiffreAffaires(), "1500.55", "CA du jour");
        exigerMontant(jour.beneficeBrut(), "1000.55", "bénéfice brut du jour");
        exigerMontant(jour.depenses(), "150.10", "dépenses du jour");
        exigerMontant(jour.beneficeNet(), "850.45", "bénéfice net du jour");
        exiger(jour.lignesSansCout() == 1, "Une ligne sans coût attendue.");
        exigerMontant(tableau.chiffreAffairesDuJour(JOUR), "1500.55", "CA du jour partagé");
        Indicateurs mois = tableau.indicateursDuMois(JOUR);
        exigerMontant(mois.chiffreAffaires(), "1833.55", "CA du mois");
        exigerMontant(mois.beneficeBrut(), "1332.55", "bénéfice brut du mois");
        exigerMontant(mois.depenses(), "150.10", "dépenses du mois");
        reussi("indicateurs du jour et du mois, bornes de minuit et du changement de mois");

        var stock = tableau.valeurStock();
        exigerMontant(stock.valeur(), "2003.00", "valeur du stock");   // 10 × 200,30 + 0 × 1 000 ; service exclu
        exiger(stock.nombreProduits() == 3, "Trois articles en stock attendus : " + stock.nombreProduits());
        var surveiller = tableau.produitsASurveiller();
        exiger(surveiller.produits().stream().map(ProduitASurveiller::nom).toList().equals(List.of("Huile A", "Sel A")),
                "Ordre des produits à surveiller inattendu : " + surveiller.produits());
        exiger(surveiller.total() == 2, "Deux produits à surveiller attendus.");
        reussi("valeur du stock sans les services et produits à surveiller");

        // Évolution : ventes de part et d'autre de chaque changement de mois de 2025.
        vente(a, LocalDateTime.of(2025, 1, 31, 23, 59, 59, 999_999_000), "100", ligne(a.riz(), 1, "100", "60"));
        vente(a, LocalDateTime.of(2025, 2, 1, 0, 0), "200", ligne(a.riz(), 2, "100", "30"));
        vente(a, LocalDateTime.of(2025, 12, 31, 23, 59, 59), "300", ligne(a.huile(), 3, "100", null));
        depense(a, LocalDateTime.of(2025, 3, 1, 0, 0), "500");
        depense(a, LocalDateTime.of(2025, 2, 28, 23, 59, 59), "20");
        vente(b, LocalDateTime.of(2025, 6, 1, 0, 0), "5555", ligne(b.riz(), 1, "5555", "5"));
        EvolutionAnnuelle evolution = tableau.evolutionMensuelle(2025, JOUR);
        exigerMois(evolution, 1, "100", "40", "0");
        exigerMois(evolution, 2, "200", "140", "20");
        exigerMois(evolution, 3, "0", "0", "500");
        exigerMois(evolution, 6, "0", "0", "0");
        exigerMois(evolution, 12, "300", "300", "0");
        exigerMontant(evolution.totalResultat(), "-40", "résultat 2025");
        for (int m = 1; m <= 12; m++) {
            MoisActivite activite = evolution.mois().get(m - 1);
            Indicateurs borne = tableau.indicateursDuMois(LocalDate.of(2025, m, 1));
            exigerMontant(activite.ventes(), borne.chiffreAffaires().toPlainString(), "GROUP BY et bornes du mois " + m);
            exigerMontant(activite.beneficeBrut(), borne.beneficeBrut().toPlainString(), "marge GROUP BY du mois " + m);
            exigerMontant(activite.depenses(), borne.depenses().toPlainString(), "dépenses GROUP BY du mois " + m);
        }
        EvolutionAnnuelle courante = tableau.evolutionMensuelle(2026, JOUR);
        exigerMois(courante, 2, "777", "770", "999");
        exigerMois(courante, 3, "1833.55", "1332.55", "150.10");
        exiger(tableau.evolutionMensuelle(2019, JOUR).vide(), "2019 devrait être vide.");
        reussi("évolution mensuelle groupée par mois, cohérente avec les bornes [début, fin)");

        connecter(b.gerant());
        exigerMontant(tableau.indicateursDuJour(JOUR).chiffreAffaires(), "90000", "CA de B");
        exigerMontant(tableau.indicateursDuJour(JOUR).depenses(), "4444", "dépenses de B");
        exigerMontant(tableau.evolutionMensuelle(2025, JOUR).totalVentes(), "5555", "ventes 2025 de B");
        exiger(tableau.produitsASurveiller().produits().stream().noneMatch(p -> p.nom().endsWith(" A")),
                "B voit un produit de A.");
        reussi("isolation des deux boutiques");

        verifierCreancesEtDettes();
    }

    /**
     * Créances, dettes, avances et retards : les requêtes agrégées (SUM/CASE, GROUP BY, jointures, LEFT JOIN) sur MariaDB,
     * avec des centimes, l'échéance du jour (pas en retard) et une seconde boutique qui ne doit jamais apparaître.
     */
    private void verifierCreancesEtDettes() {
        LocalDate aujourdHui = LocalDate.now();
        Env c = environnement("C");
        Env d = environnement("D");
        Client x = client(c, "Client X", "0", null);
        vente(c, x, "100000", "30000", "0", aujourdHui.minusDays(30));                  // reste 70 000, en retard
        vente(c, x, "40000", "0", "0", aujourdHui.plusDays(5));                           // reste 40 000
        vente(c, x, "10000", "0", "0", aujourdHui);                                       // échéance du jour : pas en retard
        Client y = client(c, "Client Y", "200000.50", aujourdHui.minusDays(60));
        reglement(c, y, null, "50000.25", "SOLDE_CLIENT");                               // reste 150 000,25 en retard
        Client z = client(c, "Client Z", "-20000", null);                                // avance de départ 20 000
        vente(c, z, "10000", "60000", "0", null);                                        // surplus 50 000 : avance 70 000
        vente(c, null, "30000", "0", "0", aujourdHui.minusDays(5));                      // vente sans client en retard
        Fournisseur sidi = fournisseur(c, "Sidi", "0", null);
        achat(c, sidi, "80000", "20000", aujourdHui.minusDays(20));                      // reste 60 000, en retard
        Fournisseur kaba = fournisseur(c, "Kaba", "-90000", aujourdHui.minusDays(3));
        reglement(c, null, kaba, "-40000", "SOLDE_FOURNISSEUR");                         // reste 50 000, en retard
        Fournisseur diallo = fournisseur(c, "Diallo", "30000", null);                    // avance de départ 30 000
        achat(c, diallo, "10000", "15000", null);                                        // surplus 5 000 : avance 35 000
        achat(c, null, "7000", "0", aujourdHui.minusDays(1));                            // achat sans fournisseur en retard
        // Autre boutique : tout en gros retard.
        Client secret = client(d, "Client secret", "999000", aujourdHui.minusDays(300));
        vente(d, secret, "888000", "0", "0", aujourdHui.minusDays(200));
        achat(d, fournisseur(d, "Fournisseur secret", "-666000", aujourdHui.minusDays(300)), "555000", "0", aujourdHui.minusDays(200));

        connecter(c.gerant());
        var cd = tableau.creancesEtDettes();
        exigerMontant(cd.creances(), "300000.25", "créances");
        exigerMontant(cd.creancesEnRetard(), "250000.25", "créances en retard");
        exigerMontant(cd.dettes(), "117000", "dettes");
        exigerMontant(cd.dettesEnRetard(), "117000", "dettes en retard");
        exigerMontant(cd.avancesClients(), "70000", "avances des clients");
        exigerMontant(cd.avancesFournisseurs(), "35000", "avances chez les fournisseurs");
        exigerMontant(cd.positionNette(), "148000.25", "position nette");
        reussi("créances, dettes, avances et position nette");

        var clientsRetard = cd.clientsEnRetard();
        exiger(clientsRetard.partenaires().stream().map(p -> p.nom()).toList().equals(List.of("Client Y", "Client X")),
                "Ordre des clients en retard inattendu : " + clientsRetard.partenaires());
        exigerMontant(clientsRetard.partenaires().get(0).montant(), "150000.25", "retard de Client Y");
        exiger(clientsRetard.partenaires().get(0).plusAncienneEcheance().equals(aujourdHui.minusDays(60)), "Échéance de Client Y inattendue.");
        exigerMontant(clientsRetard.partenaires().get(1).montant(), "70000", "retard de Client X");
        exiger(clientsRetard.partenaires().get(1).plusAncienneEcheance().equals(aujourdHui.minusDays(30)), "Échéance de Client X inattendue.");
        exigerMontant(clientsRetard.sansPartenaire(), "30000", "ventes sans client en retard");
        var fournisseursRetard = cd.fournisseursEnRetard();
        exiger(fournisseursRetard.partenaires().stream().map(p -> p.nom()).toList().equals(List.of("Sidi", "Kaba")),
                "Ordre des fournisseurs en retard inattendu : " + fournisseursRetard.partenaires());
        exiger(fournisseursRetard.partenaires().get(1).plusAncienneEcheance().equals(aujourdHui.minusDays(3)), "Échéance de Kaba inattendue.");
        exigerMontant(fournisseursRetard.sansPartenaire(), "7000", "achats sans fournisseur en retard");
        reussi("listes de retards triées, plus ancienne échéance, montants sans partenaire");

        connecter(d.gerant());
        var autre = tableau.creancesEtDettes();
        exigerMontant(autre.creances(), "1887000", "créances de la boutique D");
        exigerMontant(autre.dettes(), "1221000", "dettes de la boutique D");
        exiger(autre.clientsEnRetard().partenaires().size() == 1 && autre.clientsEnRetard().partenaires().get(0).nom().equals("Client secret"),
                "La boutique D voit un client d'une autre boutique.");
        exiger(autre.fournisseursEnRetard().partenaires().stream().noneMatch(p -> List.of("Sidi", "Kaba").contains(p.nom())),
                "La boutique D voit un fournisseur d'une autre boutique.");
        reussi("isolation des boutiques pour les créances et les dettes");
    }

    private Client client(Env e, String nom, String soldeInitial, LocalDate echeance) {
        var client = new Client(); client.setBoutique(e.boutique()); client.setNom(nom);
        client.setSoldeInitial(new BigDecimal(soldeInitial)); client.setEcheanceSoldeInitial(echeance);
        return clients.saveAndFlush(client);
    }

    private Fournisseur fournisseur(Env e, String nom, String soldeInitial, LocalDate echeance) {
        var fournisseur = new Fournisseur(); fournisseur.setBoutique(e.boutique()); fournisseur.setNom(nom);
        fournisseur.setSoldeInitial(new BigDecimal(soldeInitial)); fournisseur.setEcheanceSoldeInitial(echeance);
        return fournisseurs.saveAndFlush(fournisseur);
    }

    private void vente(Env e, Client client, String total, String verse, String avanceUtilisee, LocalDate echeance) {
        var vente = new Vente(); vente.setBoutique(e.boutique()); vente.setClient(client); vente.setDateVente(LocalDateTime.now().minusDays(90));
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(new BigDecimal(verse));
        vente.setAvanceUtilisee(new BigDecimal(avanceUtilisee)); vente.setDateEcheance(echeance);
        ventes.saveAndFlush(vente);
    }

    private void achat(Env e, Fournisseur fournisseur, String total, String verse, LocalDate echeance) {
        var achat = new Achat(); achat.setBoutique(e.boutique()); achat.setFournisseur(fournisseur); achat.setDateAchat(LocalDateTime.now().minusDays(90));
        achat.setMontantTotal(new BigDecimal(total)); achat.setMontantVerse(new BigDecimal(verse)); achat.setDateEcheance(echeance);
        achats.saveAndFlush(achat);
    }

    private void reglement(Env e, Client client, Fournisseur fournisseur, String montant, String source) {
        var mouvement = new MouvementCaisse(); mouvement.setBoutique(e.boutique()); mouvement.setClient(client); mouvement.setFournisseur(fournisseur);
        mouvement.setDateMouvement(LocalDateTime.now().minusDays(10)); mouvement.setMontant(new BigDecimal(montant));
        mouvement.setType(montant.startsWith("-") ? "SORTIE" : "ENTREE"); mouvement.setSource(source); mouvement.setMotif("Règlement du solde de départ");
        mouvement.setUtilisateur(e.gerant());
        mouvements.saveAndFlush(mouvement);
    }

    private record Env(Boutique boutique, Utilisateur gerant, Produit riz, Produit huile, Produit service) {}

    private Env environnement(String suffixe) {
        var boutique = new Boutique(); boutique.setNom("Boutique tableau " + suffixe); boutique = boutiques.saveAndFlush(boutique);
        var gerant = new Utilisateur(); gerant.setBoutique(boutique); gerant.setNom("Gérant " + suffixe); gerant.setRole(Role.ADMIN);
        gerant.setEmail(UUID.randomUUID() + "@tableau-de-bord-mariadb.test"); gerant = utilisateurs.saveAndFlush(gerant);
        var categorie = new Categorie(); categorie.setBoutique(boutique); categorie.setNom("Rayon"); categorie = categories.saveAndFlush(categorie);
        Produit riz = produit(boutique, categorie, "Riz " + suffixe, NatureProduit.STOCK, 10, "200.30");
        Produit huile = produit(boutique, categorie, "Huile " + suffixe, NatureProduit.STOCK, 0, "1000");
        produit(boutique, categorie, "Sel " + suffixe, NatureProduit.STOCK, 4, "0");
        Produit service = produit(boutique, categorie, "Livraison " + suffixe, NatureProduit.SERVICE, 3, "100");
        return new Env(boutique, gerant, riz, huile, service);
    }

    private Produit produit(Boutique boutique, Categorie categorie, String nom, NatureProduit nature, long quantite, String cout) {
        var produit = new Produit(); produit.setBoutique(boutique); produit.setCategorie(categorie); produit.setNom(nom);
        produit.setReference("TDB-" + UUID.randomUUID()); produit.setNature(nature); produit.setQuantite(quantite);
        produit.setCoutMoyen(new BigDecimal(cout)); produit.setPrixAchat(new BigDecimal(cout)); produit.setPrixVente(new BigDecimal("1000"));
        return produits.saveAndFlush(produit);
    }

    private static DetailVente ligne(Produit produit, int quantite, String prix, String cout) {
        var ligne = new DetailVente(); ligne.setProduit(produit); ligne.setQuantite(quantite);
        ligne.setPrixUnitaire(new BigDecimal(prix)); ligne.setCoutUnitaire(cout == null ? null : new BigDecimal(cout));
        return ligne;
    }

    private void vente(Env e, LocalDateTime date, String total, DetailVente... lignes) {
        var vente = new Vente(); vente.setBoutique(e.boutique()); vente.setDateVente(date);
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(Montants.ZERO);
        for (DetailVente ligne : lignes) { ligne.setVente(vente); vente.getLignes().add(ligne); }
        ventes.saveAndFlush(vente);
    }

    private void achat(Env e, LocalDateTime date, String total) {
        var fournisseur = new Fournisseur(); fournisseur.setBoutique(e.boutique()); fournisseur.setNom("Fournisseur");
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        var achat = new Achat(); achat.setBoutique(e.boutique()); achat.setFournisseur(fournisseur); achat.setDateAchat(date);
        achat.setMontantTotal(new BigDecimal(total)); achat.setMontantVerse(new BigDecimal(total));
        var ligne = new DetailAchat(); ligne.setAchat(achat); ligne.setProduit(e.riz()); ligne.setQuantite(1);
        ligne.setPrixAchatUnitaire(new BigDecimal(total)); achat.getLignes().add(ligne);
        achats.saveAndFlush(achat);
    }

    private void depense(Env e, LocalDateTime date, String montant) {
        operation(e, date, montant, TypeOperationTresorerie.DEPENSE, null);
    }

    private void transfert(Env e, LocalDateTime date, String montant) {
        operation(e, date, montant, TypeOperationTresorerie.TRANSFERT, CompteTresorerie.BANQUE);
    }

    private void operation(Env e, LocalDateTime date, String montant, TypeOperationTresorerie type, CompteTresorerie destination) {
        var operation = new OperationTresorerie(); operation.setBoutique(e.boutique()); operation.setUtilisateur(e.gerant());
        operation.setJeton(UUID.randomUUID().toString()); operation.setType(type); operation.setCompteSource(CompteTresorerie.CAISSE);
        operation.setCompteDestination(destination); operation.setMontant(new BigDecimal(montant)); operation.setMotif("Opération");
        operation.setDateOperation(date); operations.saveAndFlush(operation);
    }

    private static void exigerMois(EvolutionAnnuelle evolution, int mois, String ventes, String brut, String depenses) {
        MoisActivite activite = evolution.mois().get(mois - 1);
        exigerMontant(activite.ventes(), ventes, "ventes du mois " + mois + "/" + evolution.annee());
        exigerMontant(activite.beneficeBrut(), brut, "bénéfice brut du mois " + mois + "/" + evolution.annee());
        exigerMontant(activite.depenses(), depenses, "dépenses du mois " + mois + "/" + evolution.annee());
    }

    private static void exigerMontant(BigDecimal valeur, String attendu, String libelle) {
        exiger(valeur != null && valeur.compareTo(new BigDecimal(attendu)) == 0, libelle + " : attendu " + attendu + ", obtenu " + valeur);
    }

    private static void connecter(Utilisateur compte) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(compte.getEmail(), "",
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    // --- Base temporaire

    private static String urlCatalogue(String serveur, String base) {
        verifierBase(base);
        int query = serveur.indexOf('?');
        return query < 0 ? serveur + base : serveur.substring(0, query) + base + serveur.substring(query);
    }

    private static void creerSchema(Connection c) throws Exception {
        String script = Files.readString(Path.of("docs/database-schema.sql"));
        script = script.replaceAll("(?m)^\\s*--[^\\r\\n]*", "");
        for (String partie : script.split(";")) {
            String instruction = partie.strip();
            if (instruction.isEmpty() || instruction.startsWith("CREATE DATABASE IF NOT EXISTS stock_pro")
                    || instruction.equals("USE stock_pro")) continue;
            exiger(instruction.startsWith("CREATE TABLE IF NOT EXISTS "), "La fixture de schéma doit uniquement créer des tables.");
            try (var s = c.createStatement()) { s.execute(instruction); }
        }
    }

    private static void verifierBase(String base) {
        exiger(base != null && base.startsWith(PREFIXE) && base.matches("stock_tableau_de_bord_test_[a-z0-9]+")
                && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK tableau de bord MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("tableau-de-bord-mariadb-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import({TableauDeBordService.class, BoutiqueService.class, EssaiBoutiqueService.class,
            org.example.stock.service.SoldePartenaireService.class, org.example.stock.repository.SoldePartenaireRepository.class,
            org.example.stock.service.EcheanceService.class})
    static class ConfigurationJpa {
        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source); factory.setPackagesToScan("org.example.stock.model");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            // Mêmes réglages que l'application : pas de fuseau JDBC imposé, URL avec timezone=UTC.
            factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto", "validate", "hibernate.show_sql", "false",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.implicit_naming_strategy", "org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy"));
            return factory;
        }

        @Bean @Primary
        EntityManager entityManager(EntityManagerFactory factory) { return SharedEntityManagerCreator.createSharedEntityManager(factory); }
        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) { return new JpaTransactionManager(factory); }
        @Bean
        TrialProperties trialProperties() { return new TrialProperties(); }
        @Bean("recoveryClock")
        Clock clock() { return Clock.systemUTC(); }
    }
}
