package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retour de vente : calcul de la dette imputée et du remboursement, avoir, stock et coût moyen, tableau de bord net,
 * droits, isolation entre boutiques, numérotation, double envoi et concurrence. Vraies transactions, H2 avec délai de verrou.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:retours-vente;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RetourVenteTests {
    static final String ADMIN = "gerant-retours@example.test", CAISSIER = "caissier-retours@example.test", ADMIN2 = "gerant2-retours@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired RetourRepository retours;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationCreationRepository operations;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired RetourVenteService retourService;
    @Autowired SoldePartenaireService soldesService;
    @Autowired TableauDeBordService tableau;
    @Autowired CoutMoyenService coutMoyen;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;

    Boutique boutique, autreBoutique;
    Produit produit, service, produitAutre;
    Client client, clientAutre;
    Fournisseur fournisseur, fournisseurAutre;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); retours.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique des retours"); autreBoutique = boutique("Autre boutique");
        utilisateur(ADMIN, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autreBoutique);
        client = client(boutique, "Client retours"); clientAutre = client(autreBoutique, "Client de l'autre boutique");
        fournisseur = fournisseur(boutique, "Fournisseur retours"); fournisseurAutre = fournisseur(autreBoutique, "Fournisseur de l'autre boutique");
        produit = produit(boutique, fournisseur, "REF-RV", NatureProduit.STOCK);
        service = produit(boutique, fournisseur, "SERV-RV", NatureProduit.SERVICE);
        produitAutre = produit(autreBoutique, fournisseurAutre, "REF-RV-AUTRE", NatureProduit.STOCK);
        authentifierGerant();
    }

    @AfterEach
    void nettoyerContexte() { SecurityContextHolder.clearContext(); }

    // ---- Montants : dette imputée, remboursement, avoir -----------------------------------------------------

    @Test
    void uneVenteSoldeeEstRembourseeEnArgentEtLeChiffreAffairesNetBaisse() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("100000");

        envoyer(vente, ADMIN, "3", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());

        Retour retour = unique(vente);
        assertThat(retour.getNumero()).isEqualTo("RV-000001");
        assertThat(retour.getMontantTotal()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("0");
        MouvementCaisse sortie = caisse.findAll().stream().filter(m -> "RETOUR_VENTE".equals(m.getSource())).findFirst().orElseThrow();
        assertThat(sortie.getMontant()).isEqualByComparingTo("-30000");
        assertThat(sortie.getCompte()).isEqualTo(CompteTresorerie.CAISSE);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("70000");
        Vente apres = ventes.findById(vente.getId()).orElseThrow();
        assertThat(apres.getMontantRetours()).isEqualByComparingTo("30000");
        assertThat(apres.getResteAPayer()).isEqualByComparingTo("0");
        assertThat(tableau().chiffreAffairesDuJour(LocalDate.now())).isEqualByComparingTo("70000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(93L);
    }

    @Test
    void uneVenteACreditVoitSaDetteDiminuerSansAucunMouvementDeTresorerie() throws Exception {
        Vente vente = vente(client, produit, 10, "20000");
        long mouvements = caisse.count();
        assertThat(soldes().soldeClient(client.getId()).resteFactures()).isEqualByComparingTo("80000");

        // Reste 80 000 : le retour de 30 000 est entièrement imputé sur la dette, aucun choix n'est demandé.
        envoyer(vente, ADMIN, "3", null, null).andExpect(status().is3xxRedirection());

        Retour retour = unique(vente);
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("0");
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("0");
        assertThat(retour.getMontantImpute()).isEqualByComparingTo("30000");
        assertThat(caisse.count()).isEqualTo(mouvements);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("50000");
        assertThat(soldes().soldeClient(client.getId()).resteFactures()).isEqualByComparingTo("50000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("0");
    }

    @Test
    void unRetourPlusGrandQueLaDetteImputeLaDetteEtRembourseLExcedent() throws Exception {
        Vente vente = vente(client, produit, 10, "80000"); // reste 20 000
        envoyer(vente, ADMIN, "5", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        Retour retour = unique(vente);
        assertThat(retour.getMontantTotal()).isEqualByComparingTo("50000");
        assertThat(retour.getMontantImpute()).isEqualByComparingTo("20000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("30000");
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("0");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("50000");
    }

    @Test
    void lExcedentGardeEnAvoirAugmenteLAvanceQuiEstConsommeeParLaVenteSuivante() throws Exception {
        Vente vente = vente(client, produit, 10, "120000"); // surplus 20 000
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("20000");
        long mouvements = caisse.count();

        envoyer(vente, ADMIN, "3", ModeRemboursement.AVOIR, null).andExpect(status().is3xxRedirection());

        Retour retour = unique(vente);
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("0");
        assertThat(retour.estAvoir()).isTrue();
        assertThat(caisse.count()).isEqualTo(mouvements);
        // Le surplus d'origine (20 000) n'est pas compté deux fois : 20 000 + 30 000.
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("50000");

        Vente suivante = vente(client, produit, 5, "0");
        assertThat(suivante.getAvanceUtilisee()).isEqualByComparingTo("50000");
        assertThat(suivante.getResteAPayer()).isEqualByComparingTo("0");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("0");
        assertThat(caisse.count()).isEqualTo(mouvements);
    }

    @Test
    void uneVentePayeeParAvanceSeRetourneEnArgentOuEnAvoir() throws Exception {
        vente(client, produit, 10, "120000"); // avance 20 000
        Vente payeeParAvance = vente(client, produit, 2, "0"); // 20 000 couverts par l'avance
        assertThat(payeeParAvance.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(payeeParAvance.getResteAPayer()).isEqualByComparingTo("0");
        BigDecimal caisseAvant = caisse.calculerSoldeTotal(boutique.getId());

        envoyer(payeeParAvance, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo(caisseAvant.subtract(new BigDecimal("10000")));

        envoyer(payeeParAvance, ADMIN, "1", ModeRemboursement.AVOIR, null).andExpect(status().is3xxRedirection());
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo(caisseAvant.subtract(new BigDecimal("10000")));
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("10000");
    }

    @Test
    void uneVenteSansClientNeSeRembourseQuEnArgent() throws Exception {
        Vente vente = vente(null, produit, 10, "100000");

        envoyer(vente, ADMIN, "2", ModeRemboursement.AVOIR, null).andExpect(status().isOk());
        assertThat(retours.count()).isZero();
        envoyer(vente, ADMIN, "2", null, null).andExpect(status().isOk());
        assertThat(retours.count()).isZero();

        envoyer(vente, ADMIN, "2", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        assertThat(retours.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("80000");
    }

    // ---- Quantités ------------------------------------------------------------------------------------------

    @Test
    void uneQuantiteAuDessusDuResteEstRefuseeEtDeuxRetoursSuccessifsSontCumules() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");

        envoyer(vente, ADMIN, "11", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isOk());
        envoyer(vente, ADMIN, "0", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isOk());
        envoyer(vente, ADMIN, "-1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isOk());
        assertThat(retours.count()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(90L);

        envoyer(vente, ADMIN, "6", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        envoyer(vente, ADMIN, "5", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isOk());
        envoyer(vente, ADMIN, "4", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        assertThat(retours.count()).isEqualTo(2);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantRetours()).isEqualByComparingTo("100000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(100L);
        assertThat(retours.findAll().stream().map(Retour::getNumero)).containsExactlyInAnyOrder("RV-000001", "RV-000002");
    }

    @Test
    void deuxRetoursSimultanesDepassantLaQuantiteVendueNePassentPasTousLesDeux() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        Long ligne = vente.getLignes().get(0).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barriere = new CyclicBarrier(2);
        try {
            List<Future<Boolean>> resultats = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                resultats.add(pool.submit(() -> authentifie(ADMIN, "ADMIN", () -> {
                    RetourVenteForm form = formulaire(ligne, 7, ModeRemboursement.ARGENT, CompteTresorerie.CAISSE, 0L);
                    attendre(barriere);
                    try { retourService.enregistrer(vente.getId(), form); return true; }
                    catch (RuntimeException refus) { return false; }
                })));
            }
            int acceptes = 0;
            for (Future<Boolean> resultat : resultats) if (resultat.get(30, TimeUnit.SECONDS)) acceptes++;
            assertThat(acceptes).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(retours.count()).isEqualTo(1);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantRetours()).isEqualByComparingTo("70000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(97L);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("30000");
    }

    // ---- Stock et coût moyen --------------------------------------------------------------------------------

    @Test
    void leStockRentreAuCoutFigeEtLeRecalculDepuisLHistoriqueDonneLaMemeValeur() throws Exception {
        // Historique complet : stock à zéro, deux achats à des prix différents, deux ventes, puis un retour de la première.
        Produit p0 = produits.findById(produit.getId()).orElseThrow(); p0.setQuantite(0L); p0.setCoutMoyen(Montants.ZERO); produits.saveAndFlush(p0);
        achat(100, "5000.00");
        Vente premiere = vente(client, produit, 10, "100000"); // coût figé 5 000
        achat(100, "8000.00"); // CMP = (90 × 5 000 + 100 × 8 000) / 190 = 6 578,95
        vente(client, produit, 10, "100000"); // coût figé 6 578,95
        BigDecimal avant = produits.findById(produit.getId()).orElseThrow().getCoutMoyen();
        assertThat(avant).isEqualByComparingTo("6578.95");

        envoyer(premiere, ADMIN, "4", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());

        Produit apres = produits.findById(produit.getId()).orElseThrow();
        assertThat(apres.getQuantite()).isEqualTo(184L);
        // Entrée au coût FIGÉ de 5 000 (et non au CMP du jour) : (180 × 6 578,95 + 4 × 5 000) / 184 = 6 544,63.
        assertThat(apres.getCoutMoyen()).isEqualByComparingTo("6544.63");
        BigDecimal live = apres.getCoutMoyen();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            Produit p = produits.findById(produit.getId()).orElseThrow();
            coutMoyen.recalculer(List.of(p));
            produits.saveAndFlush(p);
        });
        assertThat(produits.findById(produit.getId()).orElseThrow().getCoutMoyen()).isEqualByComparingTo(live);
    }

    private Achat achat(int quantite, String prix) {
        return authentifie(ADMIN, "ADMIN", () -> {
            Achat achat = new Achat(); achat.setBoutique(boutique);
            Fournisseur ref = new Fournisseur(); ref.setId(fournisseur.getId()); achat.setFournisseur(ref);
            achat.setMontantVerse(BigDecimal.ZERO);
            Produit r = new Produit(); r.setId(produit.getId());
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(r); ligne.setQuantite(quantite);
            ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(new BigDecimal("10000.00")); achat.getLignes().add(ligne);
            return achatService.enregistrerAchat(achat);
        });
    }

    @Test
    void leRetourEntreDansLHistoriqueDuCoutMoyenCommeUneEntreeAuCoutFige() throws Exception {
        fixerCout(produit, "5000.00");
        Vente vente = vente(client, produit, 10, "100000");
        envoyer(vente, ADMIN, "10", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        // Tout est revenu : 100 unités au coût de 5 000, avant comme après le rejeu.
        assertThat(produits.findById(produit.getId()).orElseThrow().getCoutMoyen()).isEqualByComparingTo("5000.00");
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            Produit p = produits.findById(produit.getId()).orElseThrow();
            coutMoyen.recalculer(List.of(p));
            produits.saveAndFlush(p);
        });
        Produit apres = produits.findById(produit.getId()).orElseThrow();
        assertThat(apres.getCoutMoyen()).isEqualByComparingTo("5000.00");
        assertThat(apres.getQuantite()).isEqualTo(100L);
    }

    @Test
    void unServiceRetourneNeToucheNiAuStockNiAuCout() throws Exception {
        Vente vente = vente(client, service, 2, "20000");
        Long quantiteAvant = produits.findById(service.getId()).orElseThrow().getQuantite();
        BigDecimal coutAvant = produits.findById(service.getId()).orElseThrow().getCoutMoyen();

        envoyer(vente, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());

        Produit apres = produits.findById(service.getId()).orElseThrow();
        assertThat(apres.getQuantite()).isEqualTo(quantiteAvant);
        assertThat(apres.getCoutMoyen()).isEqualByComparingTo(coutAvant);
        assertThat(unique(vente).getMontantTotal()).isEqualByComparingTo("10000");
    }

    // ---- Tableau de bord et état par vendeur ----------------------------------------------------------------

    @Test
    void leTableauDeBordEtLEtatParVendeurSontNetsDesRetoursALeurDate() throws Exception {
        fixerCout(produit, "5000.00");
        Vente vente = vente(client, produit, 10, "100000");
        // La vente date du mois dernier : le retour d'aujourd'hui vient en négatif aujourd'hui, pas dans le passé.
        LocalDateTime ancienne = LocalDate.now().minusMonths(1).withDayOfMonth(10).atTime(10, 0);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            Vente v = ventes.findById(vente.getId()).orElseThrow(); v.setDateVente(ancienne); ventes.saveAndFlush(v);
        });
        LocalDate aujourdHui = LocalDate.now();
        envoyer(vente, ADMIN, "3", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());

        // Le jour du retour : CA = −30 000, bénéfice brut = −(10 000 − 5 000) × 3.
        assertThat(tableau().chiffreAffairesDuJour(aujourdHui)).isEqualByComparingTo("-30000");
        TableauDeBordService.Indicateurs jour = tableau().indicateursDuJour(aujourdHui);
        assertThat(jour.chiffreAffaires()).isEqualByComparingTo("-30000");
        assertThat(jour.beneficeBrut()).isEqualByComparingTo("-15000");
        // Le jour de la vente : inchangé.
        LocalDate dateVente = ancienne.toLocalDate();
        assertThat(tableau().chiffreAffairesDuJour(dateVente)).isEqualByComparingTo("100000");
        assertThat(tableau().indicateursDuJour(dateVente).beneficeBrut()).isEqualByComparingTo("50000");

        // Par vendeur : le retour est porté par le vendeur de la vente d'origine ; le total reste égal au CA.
        TableauDeBordService.VentesParVendeur etat = tableau().ventesParVendeur(aujourdHui, aujourdHui);
        TableauDeBordService.LigneVendeur gerant = etat.lignes().stream().filter(l -> ADMIN.equals(l.nom()) || l.nombreVentes() == 0 && l.chiffreAffaires().signum() < 0)
                .findFirst().orElseThrow();
        assertThat(gerant.chiffreAffaires()).isEqualByComparingTo("-30000");
        assertThat(gerant.beneficeBrut()).isEqualByComparingTo("-15000");
        assertThat(etat.total().chiffreAffaires()).isEqualByComparingTo("-30000");
        TableauDeBordService.VentesParVendeur mois = tableau().ventesParVendeur(dateVente, aujourdHui);
        assertThat(mois.total().chiffreAffaires()).isEqualByComparingTo("70000");
        assertThat(mois.total().beneficeBrut()).isEqualByComparingTo("35000");
    }

    // ---- Droits, isolation, champs inconnus, numéros, double envoi ------------------------------------------

    @Test
    void leCaissierEstRefuseSurLeFormulaireLEnregistrementEtLaListe() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        mvc.perform(get("/ventes/" + vente.getId() + "/retour").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        envoyer(vente, CAISSIER, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isForbidden());
        mvc.perform(get("/retours").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        assertThat(retours.count()).isZero();
        // Le gérant voit l'écran.
        mvc.perform(get("/ventes/" + vente.getId() + "/retour").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/retours").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void uneVenteOuUnRetourDUneAutreBoutiqueEstIntrouvable() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        envoyer(vente, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().is3xxRedirection());
        Retour retour = unique(vente);

        mvc.perform(get("/ventes/" + vente.getId() + "/retour").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        envoyer(vente, ADMIN2, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE).andExpect(status().isNotFound());
        mvc.perform(get("/retours/" + retour.getId() + "/imprimer").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/retours/" + retour.getId() + "/imprimer").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        String autre = mvc.perform(get("/retours").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(autre).doesNotContain(retour.getNumero());
        assertThat(retours.count()).isEqualTo(1);
    }

    @Test
    void unChampInconnuOuRepeteEstRefuseAvecUneErreur400() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        Long ligne = vente.getLignes().get(0).getId();
        mvc.perform(post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailVenteId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE").param("montantTotal", "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailVenteId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("retoursConnus", "0")
                .param("lignes[0].prixUnitaire", "1").param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE")).andExpect(status().isBadRequest());
        mvc.perform(post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailVenteId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("lignes[0].quantite", "2").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE")).andExpect(status().isBadRequest());
        mvc.perform(post("/ventes/" + vente.getId() + "/retour").param("lignes[0].detailVenteId", String.valueOf(ligne))
                .param("lignes[0].quantite", "1").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isForbidden()); // sans jeton CSRF
        assertThat(retours.count()).isZero();
    }

    @Test
    void lesNumerosRVSontConsecutifsParBoutique() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        Vente venteAutre = authentifie(ADMIN2, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(clientAutre, produitAutre, 5, "50000")));
        envoyer(vente, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE);
        envoyer(vente, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE);
        envoyer(venteAutre, ADMIN2, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE);
        envoyer(vente, ADMIN, "1", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE);

        List<String> premiere = retours.findAll().stream().filter(r -> r.getBoutique().getId().equals(boutique.getId())).map(Retour::getNumero).sorted().toList();
        List<String> seconde = retours.findAll().stream().filter(r -> r.getBoutique().getId().equals(autreBoutique.getId())).map(Retour::getNumero).sorted().toList();
        assertThat(premiere).containsExactly("RV-000001", "RV-000002", "RV-000003");
        assertThat(seconde).containsExactly("RV-000001");
    }

    @Test
    void unDoubleEnvoiDuMemeFormulaireNEnregistrePasUnSecondRetour() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        Long ligne = vente.getLignes().get(0).getId();
        MockHttpServletRequestBuilder envoi = post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailVenteId", String.valueOf(ligne)).param("lignes[0].quantite", "2").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE");
        mvc.perform(envoi).andExpect(status().is3xxRedirection());
        mvc.perform(envoi).andExpect(status().isConflict());
        assertThat(retours.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("80000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(92L);
    }

    @Test
    void laFactureEtLeDetailAfficheLesRetoursEtLeTotalNet() throws Exception {
        Vente vente = vente(client, produit, 10, "100000");
        envoyer(vente, ADMIN, "3", ModeRemboursement.ARGENT, CompteTresorerie.CAISSE);
        String facture = mvc.perform(get("/factures/vente/" + vente.getId()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(facture).contains("Retours", "Total net", "RV-000001", "70 000.00");
    }

    // ---- Outils -----------------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions envoyer(Vente vente, String compte, String quantite, ModeRemboursement mode, CompteTresorerie tresorerie) throws Exception {
        long connus = retours.countByVenteId(vente.getId());
        String role = CAISSIER.equals(compte) ? "CAISSIER" : "ADMIN";
        MockHttpServletRequestBuilder requete = post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(compte).roles(role))
                .param("lignes[0].detailVenteId", String.valueOf(vente.getLignes().get(0).getId())).param("lignes[0].quantite", quantite)
                .param("retoursConnus", String.valueOf(connus));
        if (mode != null) requete.param("modeRemboursement", mode.name());
        if (tresorerie != null) requete.param("comptePaiement", tresorerie.name());
        return mvc.perform(requete);
    }

    private static RetourVenteForm formulaire(Long ligne, int quantite, ModeRemboursement mode, CompteTresorerie compte, Long connus) {
        RetourVenteForm form = new RetourVenteForm();
        RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(ligne); l.setQuantite(quantite);
        form.setLignes(new ArrayList<>(List.of(l)));
        form.setModeRemboursement(mode); form.setComptePaiement(compte); form.setRetoursConnus(connus);
        return form;
    }

    private Retour unique(Vente vente) {
        return new TransactionTemplate(transactions).execute(s -> {
            List<Retour> trouves = retours.findAll().stream().filter(r -> r.getVente() != null && r.getVente().getId().equals(vente.getId())).toList();
            assertThat(trouves).hasSize(1);
            Retour r = trouves.get(0); r.getLignes().size(); return r;
        });
    }

    private void fixerCout(Produit cible, String cout) {
        Produit p = produits.findById(cible.getId()).orElseThrow(); p.setCoutMoyen(new BigDecimal(cout)); produits.saveAndFlush(p);
    }

    private TableauDeBordService tableau() { authentifierGerant(); return tableau; }

    private SoldePartenaireService soldes() { authentifierGerant(); return soldesService; }

    private void authentifierGerant() {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Vente vente(Client acheteur, Produit article, int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(acheteur, article, quantite, verse)));
    }

    private Vente nouvelleVente(Client acheteur, Produit article, int quantite, String verse) {
        Vente vente = new Vente(); vente.setBoutique(article.getBoutique());
        if (acheteur != null) { Client reference = new Client(); reference.setId(acheteur.getId()); vente.setClient(reference); }
        vente.setMontantVerse(new BigDecimal(verse));
        Produit reference = new Produit(); reference.setId(article.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private Boutique boutique(String nom) {
        Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b);
    }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.save(u);
    }

    private Client client(Boutique b, String nom) {
        Client c = new Client(); c.setBoutique(b); c.setNom(nom); return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique b, String nom) {
        Fournisseur f = new Fournisseur(); f.setBoutique(b); f.setNom(nom); return fournisseurs.saveAndFlush(f);
    }

    private Produit produit(Boutique b, Fournisseur f, String reference, NatureProduit nature) {
        Categorie categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie " + reference); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(b); p.setNom(reference); p.setReference(reference); p.setCategorie(categorie); p.setFournisseur(f);
        p.setNature(nature);
        p.setQuantite(100L); p.setPrixAchat(new BigDecimal("5000.00")); p.setPrixVente(new BigDecimal("10000.00")); p.setPrixDefinis(true);
        p.setCoutMoyen(new BigDecimal("5000.00"));
        return produits.saveAndFlush(p);
    }

    private <T> T authentifie(String email, String role, Callable<T> action) {
        var precedent = SecurityContextHolder.getContext(); var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.call(); }
        catch (RuntimeException erreur) { throw erreur; }
        catch (Exception erreur) { throw new IllegalStateException(erreur); }
        finally { SecurityContextHolder.setContext(precedent); }
    }

    private static void attendre(CyclicBarrier barriere) {
        try { barriere.await(15, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
