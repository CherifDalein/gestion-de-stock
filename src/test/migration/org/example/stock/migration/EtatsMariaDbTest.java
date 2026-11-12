package org.example.stock.migration;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.example.stock.config.TrialProperties;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.BilanService;
import org.example.stock.service.EtatsListesService;
import org.example.stock.service.EtatsPartenairesService;
import org.example.stock.service.PeriodeEtat;
import org.example.stock.service.TableauDeBordService;
import org.example.stock.export.TableauExport;
import org.example.stock.enums.TypeRetour;
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
 * Requêtes des états du gérant (lot 4, tâche C) sur MariaDB, dans une base jetable créée depuis le schéma documenté :
 * flux de trésorerie par compte et par source (SUM/CASE, GROUP BY), soldes avant une date (mouvement sans date compris),
 * encaissements avec NOT IN et jointures, annulations (annulation_de_id), dépenses, restes à payer décimaux, relevés,
 * état du stock, bornes [début, fin) autour de minuit et isolation des boutiques.
 */
public final class EtatsMariaDbTest {
    private static final String PREFIXE = "stock_etats_test_";
    private static final LocalDate DU = LocalDate.of(2026, 3, 1), AU = LocalDate.of(2026, 3, 31);
    private final TableauDeBordService tableau;
    private final BilanService bilan;
    private final EtatsPartenairesService partenaires;
    private final EtatsListesService listes;
    private final RetourRepository retours;
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

    private EtatsMariaDbTest(AnnotationConfigApplicationContext contexte) {
        tableau = contexte.getBean(TableauDeBordService.class);
        bilan = contexte.getBean(BilanService.class);
        partenaires = contexte.getBean(EtatsPartenairesService.class);
        listes = contexte.getBean(EtatsListesService.class);
        retours = contexte.getBean(RetourRepository.class);
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
                    System.getenv("ETATS_TEST_DB_URL"));
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
                        contexte.getEnvironment().setActiveProfiles("etats-mariadb-test");
                        contexte.registerBean(DataSource.class, () -> source);
                        contexte.register(ConfigurationJpa.class);
                        contexte.refresh();
                        EtatsMariaDbTest test = new EtatsMariaDbTest(contexte);
                        test.verifier();
                        System.out.println(test.scenarios + " scénarios des états MariaDB réussis.");
                    } finally { SecurityContextHolder.clearContext(); }
                } finally {
                    administration.setCatalog(null); verifierBase(base);
                    try (var s = administration.createStatement()) { s.execute("DROP DATABASE " + base); }
                    System.out.println("Base temporaire des états supprimée.");
                }
            }
        } catch (Exception e) {
            System.err.println("Test des états MariaDB interrompu : "
                    + (e instanceof IllegalStateException ? e.getMessage() : "opération impossible ; aucune valeur JDBC publiée."));
            System.exit(1);
        }
    }

    private void verifier() {
        Env a = environnement("E");
        Env b = environnement("F");
        Client x = client(a, "Client X", "0", null);
        Client y = client(a, "Client Y", "200.50", LocalDate.now().minusDays(60));
        Client z = client(a, "Client Z", "-20", null);
        Fournisseur sidi = fournisseur(a, "Sidi", "0", null);
        Fournisseur kaba = fournisseur(a, "Kaba", "-90.50", LocalDate.now().minusDays(3));
        Fournisseur diallo = fournisseur(a, "Diallo", "30", null);

        // --- Journal de trésorerie de A (mars 2026 : [01/03 00:00, 01/04 00:00))
        mouvement(a, "OUVERTURE", "SOLDE_INITIAL", TypeCompteTresorerie.ESPECES, "1000.50", LocalDateTime.of(2026, 2, 1, 8, 0), null);
        mouvement(a, "ENTREE", "SOLDE_CLIENT", TypeCompteTresorerie.ORANGE_MONEY, "40", LocalDateTime.of(2026, 2, 20, 8, 0), m -> m.setClient(y));
        mouvement(a, "ENTREE", "DIVERS", TypeCompteTresorerie.ESPECES, "5", null, null);                                   // sans date : compté avant tout
        Vente v = venteClient(a, x, "100.10", "30", "20.05", LocalDateTime.of(2026, 3, 5, 9, 0), LocalDate.now().minusDays(10));
        MouvementCaisse paiement = mouvement(a, "ENTREE", "VENTE", TypeCompteTresorerie.ESPECES, "200.25", DU.atStartOfDay(), m -> m.setVente(v));       // borne basse incluse
        mouvement(a, "ENTREE", "VENTE", TypeCompteTresorerie.ORANGE_MONEY, "99.75", AU.plusDays(1).atStartOfDay().minusNanos(1_000), null);              // dernier instant
        mouvement(a, "ENTREE", "REGLEMENT_VENTE", TypeCompteTresorerie.BANQUE, "50", AU.plusDays(1).atStartOfDay(), null);                              // 01/04 : exclu
        OperationTresorerie loyer = operation(a, LocalDateTime.of(2026, 3, 10, 9, 0), "30.10", TypeOperationTresorerie.DEPENSE, TypeCompteTresorerie.ESPECES, null);
        mouvement(a, "SORTIE", "DEPENSE", TypeCompteTresorerie.ESPECES, "-30.10", LocalDateTime.of(2026, 3, 10, 9, 0), m -> m.setOperationTresorerie(loyer));
        mouvement(a, "SORTIE", "ANNULATION", TypeCompteTresorerie.ESPECES, "-200.25", LocalDateTime.of(2026, 3, 12, 9, 0), m -> m.setAnnulationDe(paiement));
        OperationTresorerie transfert = operation(a, LocalDateTime.of(2026, 3, 15, 9, 0), "100", TypeOperationTresorerie.TRANSFERT, TypeCompteTresorerie.ESPECES, TypeCompteTresorerie.BANQUE);
        mouvement(a, "SORTIE", "TRANSFERT", TypeCompteTresorerie.ESPECES, "-100", LocalDateTime.of(2026, 3, 15, 9, 0), m -> m.setOperationTresorerie(transfert));
        mouvement(a, "ENTREE", "TRANSFERT", TypeCompteTresorerie.BANQUE, "100", LocalDateTime.of(2026, 3, 15, 9, 0), m -> m.setOperationTresorerie(transfert));
        Retour remboursement = retourAchat(a, sidi, "10.05", LocalDateTime.of(2026, 3, 20, 9, 0));
        mouvement(a, "ENTREE", "RETOUR_ACHAT", TypeCompteTresorerie.BANQUE, "10.05", LocalDateTime.of(2026, 3, 20, 9, 0), m -> m.setRetour(remboursement));
        Achat achatSidi = achatFournisseur(a, sidi, "80.10", "20.05", LocalDateTime.of(2026, 3, 2, 9, 0), LocalDate.now().minusDays(20));
        MouvementCaisse reglementAchat = mouvement(a, "SORTIE", "REGLEMENT_ACHAT", TypeCompteTresorerie.BANQUE, "-20", LocalDateTime.of(2026, 3, 22, 9, 0), m -> m.setAchat(achatSidi));
        mouvement(a, "ENTREE", "ANNULATION", TypeCompteTresorerie.BANQUE, "20", LocalDateTime.of(2026, 3, 25, 9, 0), m -> m.setAnnulationDe(reglementAchat));
        OperationTresorerie transport = operation(a, LocalDateTime.of(2026, 3, 18, 9, 0), "40.40", TypeOperationTresorerie.DEPENSE, TypeCompteTresorerie.BANQUE, null);
        MouvementCaisse sortieTransport = mouvement(a, "SORTIE", "DEPENSE", TypeCompteTresorerie.BANQUE, "-40.40", LocalDateTime.of(2026, 3, 18, 9, 0), m -> m.setOperationTresorerie(transport));
        mouvement(a, "ENTREE", "ANNULATION", TypeCompteTresorerie.BANQUE, "40.40", LocalDateTime.of(2026, 3, 19, 9, 0), m -> m.setAnnulationDe(sortieTransport));
        operation(a, AU.plusDays(1).atStartOfDay(), "15", TypeOperationTresorerie.DEPENSE, TypeCompteTresorerie.ESPECES, null);                          // 01/04 : exclue
        // Autre boutique : mêmes sources, gros montants.
        mouvement(b, "OUVERTURE", "SOLDE_INITIAL", TypeCompteTresorerie.ESPECES, "99999", LocalDateTime.of(2026, 2, 1, 8, 0), null);
        mouvement(b, "ENTREE", "VENTE", TypeCompteTresorerie.ESPECES, "77777", LocalDateTime.of(2026, 3, 10, 8, 0), null);
        operation(b, LocalDateTime.of(2026, 3, 10, 9, 0), "5555", TypeOperationTresorerie.DEPENSE, TypeCompteTresorerie.ESPECES, null);

        connecter(a.gerant());
        var mars = bilan.calculer(new PeriodeEtat(DU, AU));
        var caisse = mars.tresorerie().get(0); var orange = mars.tresorerie().get(1); var banque = mars.tresorerie().get(2);
        exigerMontant(caisse.debut(), "1005.50", "caisse au début (1 000,50 + 5 sans date)");
        exigerMontant(caisse.encaissements(), "200.25", "caisse encaissements");
        exigerMontant(caisse.decaissements(), "-30.10", "caisse décaissements");
        exigerMontant(caisse.annulations(), "-200.25", "caisse annulations");
        exigerMontant(caisse.transferts(), "-100", "caisse transferts");
        exigerMontant(caisse.fin(), "875.40", "caisse à la fin");
        exigerMontant(orange.debut(), "40", "Orange Money au début");
        exigerMontant(orange.encaissements(), "99.75", "Orange Money encaissements (dernier instant du mois)");
        exigerMontant(orange.fin(), "139.75", "Orange Money à la fin");
        exigerMontant(banque.debut(), "0", "banque au début");
        exigerMontant(banque.encaissements(), "10.05", "banque encaissements (le règlement du 01/04 est exclu)");
        exigerMontant(banque.decaissements(), "-60.40", "banque décaissements");
        exigerMontant(banque.annulations(), "60.40", "banque annulations");
        exigerMontant(banque.transferts(), "100", "banque transferts");
        exigerMontant(banque.fin(), "110.05", "banque à la fin");
        exigerMontant(mars.total(t -> t.transferts()), "0", "transferts de somme nulle");
        reussi("trésorerie par compte et par source, bornes de minuit, mouvement sans date, annulations");

        var avril = bilan.calculer(new PeriodeEtat(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30)));
        exigerMontant(avril.tresorerie().get(2).debut(), "110.05", "banque au 1er avril");
        exigerMontant(avril.tresorerie().get(2).fin(), "160.05", "banque au 30 avril");
        for (var t : avril.tresorerie()) exigerMontant(t.fin(), mouvements.calculerSoldeCompte(a.boutique().getId(), compte(a, t.type()).getId()).toPlainString(), "fin = solde du journal " + t.type());
        reussi("la fin de période égale le solde du journal");

        // Dépenses : les deux de mars (une annulée par écriture inverse), pas celle d'avril.
        var depenses = listes.depenses(new PeriodeEtat(DU, AU)).sections().get(0);
        exiger(depenses.getLignes().size() == 2, "Deux dépenses attendues en mars : " + depenses.getLignes().size());
        exigerMontant((BigDecimal) depenses.getTotaux().get(4), "70.50", "total des dépenses");
        exigerMontant(tableau.indicateursDeLaPeriode(DU, AU).depenses(), "70.50", "dépenses du tableau de bord");
        exiger(String.valueOf(depenses.getLignes().get(1).get(5)).startsWith("Annulée le 19/03/2026"), "Dépense annulée non signalée : " + depenses.getLignes().get(1));
        reussi("liste des dépenses, annulation par mouvement inverse, total égal au tableau de bord");

        // Encaissements : paiement 200,25 (annulé), 99,75 et 10,05 ; ni fonds, ni transfert, ni annulation, ni 01/04.
        var etatEncaissements = listes.encaissements(new PeriodeEtat(DU, AU));
        var encaissements = etatEncaissements.sections().get(0);
        exiger(encaissements.getLignes().size() == 3, "Trois encaissements attendus : " + encaissements.getLignes().size());
        exigerMontant((BigDecimal) encaissements.getTotaux().get(5), "310.05", "total des encaissements");
        exiger(encaissements.getLignes().stream().anyMatch(l -> String.valueOf(l.get(7)).startsWith("Annulé le 12/03/2026")), "Encaissement annulé non signalé.");
        var annulations = etatEncaissements.sections().get(1);
        exiger(annulations.getLignes().size() == 1, "Une annulation d'encaissement attendue.");
        exigerMontant((BigDecimal) annulations.getTotaux().get(5), "-200.25", "annulations d'encaissements");
        exiger(String.join(" ", annulations.getNotes()).contains("2 annulations de décaissements"), "Les annulations de décaissements devraient être mentionnées : " + annulations.getNotes());
        reussi("liste des encaissements : NOT IN, jointures, annulations des deux sens");

        // Créances et dettes : restes décimaux, soldes de départ, ventes et achats sans partenaire.
        venteClient(a, null, "30.30", "0", "0", LocalDateTime.of(2026, 3, 3, 9, 0), LocalDate.now().minusDays(5));
        venteClient(a, x, "50", "50", "0", LocalDateTime.of(2026, 3, 4, 9, 0), null);                                    // soldée : absente
        venteClient(a, z, "10", "60.50", "0", LocalDateTime.of(2026, 3, 6, 9, 0), null);                                // surplus 50,50 : avance 70,50
        mouvement(a, "ENTREE", "SOLDE_CLIENT", TypeCompteTresorerie.ESPECES, "50.25", LocalDateTime.of(2026, 3, 7, 9, 0), m -> m.setClient(y));
        mouvement(a, "SORTIE", "SOLDE_FOURNISSEUR", TypeCompteTresorerie.ESPECES, "-40.25", LocalDateTime.of(2026, 3, 7, 9, 0), m -> m.setFournisseur(kaba));
        achatFournisseur(a, null, "7.07", "0", LocalDateTime.of(2026, 3, 8, 9, 0), LocalDate.now().minusDays(1));
        achatFournisseur(a, diallo, "10", "15", LocalDateTime.of(2026, 3, 9, 9, 0), null);                              // surplus 5 : avance 35
        // Y : solde de départ 200,50 réglé de 40 (février) puis 50,25 (07/03) : reste 110,25.
        var cd = tableau.creancesEtDettes();
        var creances = partenaires.creancesOuDettes(EtatsPartenairesService.Cote.CLIENT, LocalDate.now()).sections().get(0);
        exigerMontant((BigDecimal) creances.getTotaux().get(7), cd.creances().toPlainString(), "total des créances de l'état");
        exigerMontant(cd.creances(), "190.60", "créances (X 50,05 + Y 110,25 + ventes sans client 30,30)");
        exigerMontant(cd.avancesClients(), "70.50", "avances des clients");
        var dettes = partenaires.creancesOuDettes(EtatsPartenairesService.Cote.FOURNISSEUR, LocalDate.now()).sections().get(0);
        exigerMontant((BigDecimal) dettes.getTotaux().get(7), cd.dettes().toPlainString(), "total des dettes de l'état");
        exigerMontant(cd.dettes(), "117.37", "dettes (Sidi 60,05 + Kaba 50,25 + sans fournisseur 7,07)");
        exigerMontant(cd.avancesFournisseurs(), "35", "avances chez les fournisseurs");
        reussi("créances et dettes de l'état égales aux totaux du tableau de bord (restes décimaux, retours déduits)");

        // Relevés : solde final = solde de la fiche, sans note d'alerte.
        retourVente(a, x, v, "20.05", LocalDateTime.of(2026, 3, 11, 9, 0));
        for (Client c : List.of(x, y, z)) {
            TableauExport releve = partenaires.releve(EtatsPartenairesService.Cote.CLIENT, c.getId(), new PeriodeEtat(DU, AU)).sections().get(0);
            exiger(String.join(" ", releve.getNotes()).contains("Concordance"), "Relevé du client " + c.getNom() + " sans concordance : " + releve.getNotes());
        }
        for (Fournisseur f : List.of(sidi, kaba, diallo)) {
            TableauExport releve = partenaires.releve(EtatsPartenairesService.Cote.FOURNISSEUR, f.getId(), new PeriodeEtat(DU, AU)).sections().get(0);
            exiger(String.join(" ", releve.getNotes()).contains("Concordance"), "Relevé du fournisseur " + f.getNom() + " sans concordance : " + releve.getNotes());
        }
        reussi("relevés des partenaires concordant avec les fiches");

        // État du stock : somme décimale, services exclus, tri par catégorie.
        TableauExport stock = listes.stock().sections().get(0);
        exigerMontant((BigDecimal) stock.getTotaux().get(6), tableau.valeurStock().valeur().toPlainString(), "valeur du stock de l'état");
        exigerMontant(tableau.valeurStock().valeur(), "2003.00", "valeur du stock");
        exiger(stock.getLignes().stream().noneMatch(l -> String.valueOf(l.get(1)).startsWith("Livraison")), "Un service figure dans l'état du stock.");
        reussi("état du stock, services exclus");

        // Isolation.
        connecter(b.gerant());
        var autre = bilan.calculer(new PeriodeEtat(DU, AU));
        exigerMontant(autre.total(t -> t.fin()), "177776", "trésorerie de B (99 999 + 77 777)");
        exigerMontant(autre.indicateurs().depenses(), "5555", "dépenses de B");
        exiger(listes.encaissements(new PeriodeEtat(DU, AU)).sections().get(0).getLignes().size() == 1, "B voit les encaissements de A.");
        exiger(partenaires.creancesOuDettes(EtatsPartenairesService.Cote.CLIENT, LocalDate.now()).sections().get(0).getLignes().isEmpty(), "B voit les clients de A.");
        try { partenaires.releve(EtatsPartenairesService.Cote.CLIENT, x.getId(), new PeriodeEtat(DU, AU)); exiger(false, "B a pu lire un relevé de A."); }
        catch (org.springframework.web.server.ResponseStatusException attendu) { exiger(attendu.getStatusCode().value() == 404, "404 attendu pour un client d'une autre boutique."); }
        reussi("isolation des boutiques");
    }

    private static CompteTresorerieBoutique compte(Env e, TypeCompteTresorerie type) {
        return e.boutique().getComptesTresorerie().stream().filter(x -> x.getType() == type).findFirst().orElseThrow();
    }

    private MouvementCaisse mouvement(Env e, String type, String source, TypeCompteTresorerie compte, String montant, LocalDateTime date,
                                      java.util.function.Consumer<MouvementCaisse> complement) {
        var m = new MouvementCaisse(); m.setBoutique(e.boutique()); m.setType(type); m.setSource(source); m.setCompte(compte(e, compte));
        m.setMontant(new BigDecimal(montant)); m.setDateMouvement(date); m.setMotif("Mouvement de test"); m.setUtilisateur(e.gerant());
        if (complement != null) complement.accept(m);
        return mouvements.saveAndFlush(m);
    }

    private OperationTresorerie operation(Env e, LocalDateTime date, String montant, TypeOperationTresorerie type, TypeCompteTresorerie source, TypeCompteTresorerie destination) {
        var operation = new OperationTresorerie(); operation.setBoutique(e.boutique()); operation.setUtilisateur(e.gerant());
        operation.setJeton(UUID.randomUUID().toString()); operation.setType(type); operation.setCompteSource(compte(e, source));
        operation.setCompteDestination(destination == null ? null : compte(e, destination)); operation.setMontant(new BigDecimal(montant)); operation.setMotif("Motif " + montant);
        operation.setDateOperation(date); return operations.saveAndFlush(operation);
    }

    private Vente venteClient(Env e, Client client, String total, String verse, String retoursMontant, LocalDateTime date, LocalDate echeance) {
        var vente = new Vente(); vente.setBoutique(e.boutique()); vente.setClient(client); vente.setDateVente(date);
        vente.setMontantTotal(new BigDecimal(total)); vente.setMontantVerse(new BigDecimal(verse)); vente.setAvanceUtilisee(Montants.ZERO);
        vente.setMontantRetours(new BigDecimal(retoursMontant)); vente.setDateEcheance(echeance);
        return ventes.saveAndFlush(vente);
    }

    private Achat achatFournisseur(Env e, Fournisseur fournisseur, String total, String verse, LocalDateTime date, LocalDate echeance) {
        var achat = new Achat(); achat.setBoutique(e.boutique()); achat.setFournisseur(fournisseur); achat.setDateAchat(date);
        achat.setMontantTotal(new BigDecimal(total)); achat.setMontantVerse(new BigDecimal(verse)); achat.setDateEcheance(echeance);
        return achats.saveAndFlush(achat);
    }

    private Retour retourAchat(Env e, Fournisseur fournisseur, String montant, LocalDateTime date) {
        var r = new Retour(); r.setBoutique(e.boutique()); r.setNumero("RA-" + UUID.randomUUID().toString().substring(0, 8)); r.setType(TypeRetour.ACHAT);
        r.setFournisseur(fournisseur); r.setDateRetour(date); r.setMontantTotal(new BigDecimal(montant)); r.setMontantRembourse(new BigDecimal(montant));
        r.setCompteTresorerie(compte(e, TypeCompteTresorerie.BANQUE)); r.setUtilisateur(e.gerant());
        return retours.saveAndFlush(r);
    }

    private void retourVente(Env e, Client client, Vente vente, String montant, LocalDateTime date) {
        var r = new Retour(); r.setBoutique(e.boutique()); r.setNumero("RV-" + UUID.randomUUID().toString().substring(0, 8)); r.setType(TypeRetour.VENTE);
        r.setClient(client); r.setVente(vente); r.setDateRetour(date); r.setMontantTotal(new BigDecimal(montant)); r.setUtilisateur(e.gerant());
        retours.saveAndFlush(r);
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

    private record Env(Boutique boutique, Utilisateur gerant, Produit riz, Produit huile, Produit service) {}

    private Env environnement(String suffixe) {
        var boutique = new Boutique(); boutique.setNom("Boutique états " + suffixe); boutique = boutiques.saveAndFlush(boutique);
        var gerant = new Utilisateur(); gerant.setBoutique(boutique); gerant.setNom("Gérant " + suffixe); gerant.setRole(Role.ADMIN);
        gerant.setEmail(UUID.randomUUID() + "@etats-mariadb.test"); gerant = utilisateurs.saveAndFlush(gerant);
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
        exiger(base != null && base.startsWith(PREFIXE) && base.matches("stock_etats_test_[a-z0-9]+")
                && base.length() <= 64, "Nom temporaire inattendu ; suppression refusée.");
    }

    private void reussi(String nom) { scenarios++; System.out.println("OK états MariaDB : " + nom); }
    private static void exiger(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    @Configuration(proxyBeanMethods = false)
    @Profile("etats-mariadb-test")
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = BoutiqueRepository.class)
    @Import({TableauDeBordService.class, BoutiqueService.class, EssaiBoutiqueService.class, BilanService.class,
            EtatsPartenairesService.class, EtatsListesService.class, org.example.stock.export.LimiteExport.class,
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
