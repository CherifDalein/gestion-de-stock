package org.example.stock.service;

import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.form.RetourAchatForm;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retour d'achat (retour au fournisseur) : dette imputée, argent rendu par le fournisseur ou avoir, plafonds de quantité et
 * de stock, stock et coût moyen (au fil de l'eau et rejoué), achat figé, droits, isolation entre boutiques, numérotation,
 * double envoi et concurrence. Vraies transactions, H2 avec délai de verrou.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:retours-achat;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RetourAchatTests {
    static final String ADMIN = "gerant-retours-achat@example.test", CAISSIER = "caissier-retours-achat@example.test", ADMIN2 = "gerant2-retours-achat@example.test";

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
    @Autowired RetourAchatService retourService;
    @Autowired RetourVenteService retourVenteService;
    @Autowired SoldePartenaireService soldesService;
    @Autowired TableauDeBordService tableau;
    @Autowired CoutMoyenService coutMoyen;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc mvc;

    Boutique boutique, autreBoutique;
    Produit produit, produitAutre;
    Client client;
    Fournisseur fournisseur, fournisseurAutre;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); retours.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique des retours d'achat"); autreBoutique = boutique("Autre boutique");
        utilisateur(ADMIN, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autreBoutique);
        client = client(boutique, "Client retours");
        fournisseur = fournisseur(boutique, "Fournisseur retours"); fournisseurAutre = fournisseur(autreBoutique, "Fournisseur de l'autre boutique");
        produit = produit(boutique, fournisseur, "REF-RA");
        produitAutre = produit(autreBoutique, fournisseurAutre, "REF-RA-AUTRE");
        authentifierGerant();
    }

    @AfterEach
    void nettoyerContexte() { SecurityContextHolder.clearContext(); }

    // ---- Montants : dette imputée, argent rendu, avoir -----------------------------------------------------

    @Test
    void unAchatPayeEstRembourseParLeFournisseurEnUneEntreeDeTresorerie() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-100000");

        envoyer(achat, ADMIN, "3", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());

        Retour retour = unique(achat);
        assertThat(retour.getNumero()).isEqualTo("RA-000001");
        assertThat(retour.getMontantTotal()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("0");
        assertThat(retour.getCompteTresorerie().getType()).isEqualTo(TypeCompteTresorerie.ESPECES);
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            List<MouvementCaisse> entrees = caisse.findAll().stream().filter(m -> "RETOUR_ACHAT".equals(m.getSource())).toList();
            assertThat(entrees).hasSize(1);
            MouvementCaisse entree = entrees.get(0);
            assertThat(entree.getMontant()).isEqualByComparingTo("30000");
            assertThat(entree.getType()).isEqualTo("ENTREE");
            assertThat(entree.getCompte().getType()).isEqualTo(TypeCompteTresorerie.ESPECES);
            assertThat(entree.getRetour().getId()).isEqualTo(retour.getId());
            assertThat(entree.getUtilisateur().getEmail()).isEqualTo(ADMIN);
            assertThat(entree.getAchat()).isNull(); // pas parmi les versements de l'achat
        });
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-70000");
        Achat apres = achats.findById(achat.getId()).orElseThrow();
        assertThat(apres.getMontantRetours()).isEqualByComparingTo("30000");
        assertThat(apres.getResteAPayer()).isEqualByComparingTo("0");
        assertThat(apres.getTotalEffectif()).isEqualByComparingTo("70000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(107L);
    }

    @Test
    void unRemboursementPeutEntrerSurUnAutreCompte() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "2", ModeRemboursement.ARGENT, TypeCompteTresorerie.ORANGE_MONEY).andExpect(status().is3xxRedirection());
        assertThat(caisse.findAll().stream().filter(m -> "RETOUR_ACHAT".equals(m.getSource())).map(m -> m.getCompte().getType()))
                .containsExactly(TypeCompteTresorerie.ORANGE_MONEY);
        assertThat(unique(achat).getCompteTresorerie().getType()).isEqualTo(TypeCompteTresorerie.ORANGE_MONEY);
    }

    @Test
    void unAchatACreditVoitSaDetteDiminuerSansAucunMouvementDeTresorerie() throws Exception {
        Achat achat = achat(10, "10000", "20000");
        long mouvements = caisse.count();
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).resteFactures()).isEqualByComparingTo("80000");
        assertThat(soldes().dettesFournisseurs().total()).isEqualByComparingTo("80000");

        // Reste 80 000 : le retour de 30 000 est entièrement imputé sur la dette, aucun choix n'est demandé.
        envoyer(achat, ADMIN, "3", null, null).andExpect(status().is3xxRedirection());

        Retour retour = unique(achat);
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("0");
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("0");
        assertThat(retour.getMontantImpute()).isEqualByComparingTo("30000");
        assertThat(caisse.count()).isEqualTo(mouvements);
        assertThat(achats.findById(achat.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("50000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).resteFactures()).isEqualByComparingTo("50000");
        assertThat(soldes().dettesFournisseurs().total()).isEqualByComparingTo("50000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("0");
    }

    @Test
    void unRetourPlusGrandQueLaDetteImputeLaDetteEtRecupereLExcedent() throws Exception {
        Achat achat = achat(10, "10000", "80000"); // reste 20 000
        envoyer(achat, ADMIN, "5", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        Retour retour = unique(achat);
        assertThat(retour.getMontantTotal()).isEqualByComparingTo("50000");
        assertThat(retour.getMontantImpute()).isEqualByComparingTo("20000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("30000");
        assertThat(achats.findById(achat.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("0");
        // 80 000 sortis à l'achat, 30 000 revenus : −50 000.
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-50000");
    }

    @Test
    void lExcedentGardeEnAvoirEstUneAvanceChezLeFournisseurConsommeeParLAchatASuivant() throws Exception {
        Achat achat = achat(10, "10000", "120000"); // trop-payé de 20 000
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("20000");
        long mouvements = caisse.count();

        envoyer(achat, ADMIN, "3", ModeRemboursement.AVOIR, null).andExpect(status().is3xxRedirection());

        Retour retour = unique(achat);
        assertThat(retour.getMontantAvoir()).isEqualByComparingTo("30000");
        assertThat(retour.getMontantRembourse()).isEqualByComparingTo("0");
        assertThat(retour.getCompteTresorerie()).isNull();
        assertThat(retour.estAvoir()).isTrue();
        assertThat(caisse.count()).isEqualTo(mouvements);
        // Le trop-payé d'origine (20 000) n'est pas compté deux fois : 20 000 + 30 000.
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("50000");
        assertThat(soldes().avancesFournisseurs()).isEqualByComparingTo("50000");

        Achat suivant = achat(5, "10000", "0");
        assertThat(suivant.getAvanceUtilisee()).isEqualByComparingTo("50000");
        assertThat(suivant.getResteAPayer()).isEqualByComparingTo("0");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("0");
        assertThat(caisse.count()).isEqualTo(mouvements); // l'avance couvre tout : ni versement ni mouvement
    }

    @Test
    void lAvanceDUnFournisseurNEstPasCelleDUnAutre() throws Exception {
        Fournisseur second = fournisseur(boutique, "Second fournisseur");
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "2", ModeRemboursement.AVOIR, null).andExpect(status().is3xxRedirection());
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("20000");
        assertThat(soldes().soldeFournisseur(second.getId()).avanceDisponible()).isEqualByComparingTo("0");
        assertThat(soldes().soldesFournisseurs().get(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("20000");
    }

    @Test
    void unExcedentSansChoixOuSansCompteEstRefuse() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "2", null, null).andExpect(status().isOk());
        envoyer(achat, ADMIN, "2", ModeRemboursement.ARGENT, null).andExpect(status().isOk());
        assertThat(retours.count()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(110L);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantRetours()).isEqualByComparingTo("0");
    }

    // ---- Quantités et stock ---------------------------------------------------------------------------------

    @Test
    void uneQuantiteAuDessusDuResteEstRefuseeEtDeuxRetoursSuccessifsSontCumules() throws Exception {
        Achat achat = achat(10, "10000", "100000");

        envoyer(achat, ADMIN, "11", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        envoyer(achat, ADMIN, "0", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        envoyer(achat, ADMIN, "-1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        assertThat(retours.count()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(110L);

        envoyer(achat, ADMIN, "6", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        envoyer(achat, ADMIN, "5", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        envoyer(achat, ADMIN, "4", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        assertThat(retours.count()).isEqualTo(2);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantRetours()).isEqualByComparingTo("100000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(100L);
        assertThat(retours.findAll().stream().map(Retour::getNumero)).containsExactlyInAnyOrder("RA-000001", "RA-000002");
    }

    @Test
    void leStockActuelPlafonneLeRetourApresUneVente() throws Exception {
        Achat achat = achat(10, "10000", "100000"); // stock 110
        vente(105, "1050000"); // stock 5 : la marchandise achetée a presque toute été vendue
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(5L);

        // 6 > stock 5 : refusé, même si l'achat en comptait 10.
        String page = envoyer(achat, ADMIN, "6", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("dépasse le stock actuel");
        assertThat(retours.count()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(5L);

        envoyer(achat, ADMIN, "5", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        // Plus de stock : plus rien à renvoyer, même s'il reste 5 unités sur la ligne d'achat.
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        assertThat(retours.count()).isEqualTo(1);
    }

    @Test
    void leFormulaireAfficheLeStockActuelEtLePlafond() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        vente(105, "1050000");
        String page = mvc.perform(get("/achats/" + achat.getId() + "/retour").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Stock actuel", "Retour de l’achat", "Le fournisseur me rembourse", "Garder en avoir chez le fournisseur", "max=\"5\"");
    }

    @Test
    void deuxRetoursSimultanesDepassantLaQuantiteAcheteeNePassentPasTousLesDeux() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        Long ligne = achat.getLignes().get(0).getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barriere = new CyclicBarrier(2);
        try {
            List<Future<Boolean>> resultats = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                resultats.add(pool.submit(() -> authentifie(ADMIN, "ADMIN", () -> {
                    RetourAchatForm form = formulaire(ligne, 7, ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES, 0L);
                    attendre(barriere);
                    try { retourService.enregistrer(achat.getId(), form); return true; }
                    catch (RuntimeException refus) { return false; }
                })));
            }
            int acceptes = 0;
            for (Future<Boolean> resultat : resultats) if (resultat.get(30, TimeUnit.SECONDS)) acceptes++;
            assertThat(acceptes).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        assertThat(retours.count()).isEqualTo(1);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantRetours()).isEqualByComparingTo("70000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(103L);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-30000");
    }

    @Test
    void leServiceRefuseUneQuantiteQuiDepasseLeResteMemeAvecUnFormulaireAJour() {
        Achat achat = achat(10, "10000", "100000");
        authentifierGerant();
        retourService.enregistrer(achat.getId(), formulaire(achat.getLignes().get(0).getId(), 6, ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES, 0L));
        // Formulaire à jour (1 retour connu) mais 5 > 4 restants : le plafond de la ligne est vérifié sous le verrou.
        assertThatThrownBy(() -> retourService.enregistrer(achat.getId(),
                formulaire(achat.getLignes().get(0).getId(), 5, ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES, 1L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dépasse ce qui reste à retourner (4)");
        assertThat(retours.count()).isEqualTo(1);
    }

    // ---- Coût moyen -----------------------------------------------------------------------------------------

    @Test
    void leRetourDuDernierAchatRamenneLeCoutMoyenEtLeRejeuDonneLaMemeValeur() throws Exception {
        viderStock();
        achat(10, "1000", "0");
        Achat second = achat(10, "2000", "0");
        assertThat(cmp()).isEqualByComparingTo("1500.00");

        // S = 20, q = 10, p = 2 000 : (20 × 1 500 − 10 × 2 000) / 10 = 1 000.
        envoyer(second, ADMIN, "10", null, null).andExpect(status().is3xxRedirection());

        assertThat(cmp()).isEqualByComparingTo("1000.00");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(unique(second).getLignes().get(0).getCoutUnitaire()).isEqualByComparingTo("1500.00"); // CMP juste avant, pour information
        assertThat(unique(second).getLignes().get(0).getPrixUnitaire()).isEqualByComparingTo("2000.00");
        rejouer();
        assertThat(cmp()).isEqualByComparingTo("1000.00");
    }

    @Test
    void unRetourPartielEstArrondiUneSeuleFoisEtLeRejeuLeRetrouve() throws Exception {
        viderStock();
        achat(7, "1000", "0");
        Achat second = achat(10, "2000", "0"); // CMP = (7 × 1 000 + 10 × 2 000) / 17 = 1 588,24
        assertThat(cmp()).isEqualByComparingTo("1588.24");
        envoyer(second, ADMIN, "4", null, null).andExpect(status().is3xxRedirection());
        // (17 × 1 588,24 − 4 × 2 000) / 13 = 19 000,08 / 13 = 1 461,54 (le calcul part du CMP déjà arrondi, comme au fil de l'eau).
        BigDecimal live = cmp();
        assertThat(live).isEqualByComparingTo("1461.54");
        rejouer();
        assertThat(cmp()).isEqualByComparingTo(live);
    }

    @Test
    void unRetourQuiVideLeStockLaisseLeCoutInchange() throws Exception {
        viderStock();
        achat(10, "1000", "0");
        Achat second = achat(10, "2000", "0");
        vente(10, "100000"); // stock 10, CMP 1 500
        assertThat(cmp()).isEqualByComparingTo("1500.00");

        // S = 10, q = 10 : le stock est vidé, le CMP reste à 1 500.
        envoyer(second, ADMIN, "10", null, null).andExpect(status().is3xxRedirection());

        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(cmp()).isEqualByComparingTo("1500.00");
        rejouer();
        assertThat(cmp()).isEqualByComparingTo("1500.00");
    }

    @Test
    void unRetourQuiDonneraitUnCoutNegatifLeRamenEnZero() throws Exception {
        viderStock();
        achat(10, "1000", "0");
        Achat second = achat(10, "3000", "0"); // CMP 2 000
        vente(8, "80000"); // stock 12 valorisé 24 000
        assertThat(cmp()).isEqualByComparingTo("2000.00");

        // On rend 10 unités à 3 000 = 30 000 de plus que les 24 000 en stock : (24 000 − 30 000) / 2 < 0 → CMP 0.
        envoyer(second, ADMIN, "10", null, null).andExpect(status().is3xxRedirection());

        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(2L);
        assertThat(cmp()).isEqualByComparingTo("0.00");
        rejouer();
        assertThat(cmp()).isEqualByComparingTo("0.00");
    }

    @Test
    void lesVentesSuivantesSontValoriseesAuCoutMoyenCorrige() throws Exception {
        viderStock();
        achat(10, "1000", "0");
        Achat second = achat(10, "2000", "0");
        envoyer(second, ADMIN, "10", null, null).andExpect(status().is3xxRedirection());
        Vente vente = vente(2, "20000");
        assertThat(vente.getLignes().get(0).getCoutUnitaire()).isEqualByComparingTo("1000.00");
    }

    // ---- Achat figé, tableau de bord -----------------------------------------------------------------------

    @Test
    void unAchatAvecRetourNePeutPlusEtreModifie() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        // Sans retour, la modification fonctionne.
        mvc.perform(get("/achats/modifier/" + achat.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());

        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());

        mvc.perform(get("/achats/modifier/" + achat.getId()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", ModificationAchatRefuseeException.MESSAGE));
        mvc.perform(post("/achats/modifier/" + achat.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN"))
                        .param("fournisseur", String.valueOf(fournisseur.getId()))
                        .param("lignes[0].produit.id", String.valueOf(produit.getId())).param("lignes[0].quantite", "20")
                        .param("lignes[0].prixAchatUnitaire", "10000").param("lignes[0].prixVenteUnitaire", "12000"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("error", ModificationAchatRefuseeException.MESSAGE));
        authentifierGerant();
        Achat modifie = new Achat();
        Fournisseur ref = new Fournisseur(); ref.setId(fournisseur.getId()); modifie.setFournisseur(ref);
        DetailAchat l = new DetailAchat(); Produit p = new Produit(); p.setId(produit.getId()); l.setProduit(p); l.setQuantite(20);
        l.setPrixAchatUnitaire(new BigDecimal("10000")); l.setPrixVenteUnitaire(new BigDecimal("12000")); modifie.getLignes().add(l);
        assertThatThrownBy(() -> achatService.modifierAchat(achat.getId(), modifie)).isInstanceOf(ModificationAchatRefuseeException.class)
                .hasMessageContaining("retour au fournisseur");
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantTotal()).isEqualByComparingTo("100000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(109L);
        // La liste n'offre plus le bouton Modifier de cet achat et montre le retour.
        String liste = mvc.perform(get("/achats").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("Retour au fournisseur", "retours 10 000.00").doesNotContain("/achats/modifier/" + achat.getId());
    }

    @Test
    void unAchatSansRetourResteModifiable() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        authentifierGerant();
        Achat modifie = new Achat();
        Fournisseur ref = new Fournisseur(); ref.setId(fournisseur.getId()); modifie.setFournisseur(ref);
        DetailAchat l = new DetailAchat(); Produit p = new Produit(); p.setId(produit.getId()); l.setProduit(p); l.setQuantite(12);
        l.setPrixAchatUnitaire(new BigDecimal("10000")); l.setPrixVenteUnitaire(new BigDecimal("12000")); modifie.getLignes().add(l);
        achatService.modifierAchat(achat.getId(), modifie);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantTotal()).isEqualByComparingTo("120000");
    }

    @Test
    void leTableauDeBordSuitLeStockSansToucherAuChiffreAffairesNiAuxDepenses() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        LocalDate aujourdHui = LocalDate.now();
        BigDecimal valeurAvant = tableau().valeurStock().valeur();
        TableauDeBordService.Indicateurs avant = tableau().indicateursDuJour(aujourdHui);

        envoyer(achat, ADMIN, "3", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());

        TableauDeBordService.Indicateurs apres = tableau().indicateursDuJour(aujourdHui);
        assertThat(apres.chiffreAffaires()).isEqualByComparingTo(avant.chiffreAffaires());
        assertThat(apres.beneficeBrut()).isEqualByComparingTo(avant.beneficeBrut());
        assertThat(apres.depenses()).isEqualByComparingTo(avant.depenses());
        assertThat(tableau().chiffreAffairesDuJour(aujourdHui)).isEqualByComparingTo("0");
        // 3 unités de moins au coût moyen du produit : 107 × CMP au lieu de 110 × CMP.
        BigDecimal cmp = produits.findById(produit.getId()).orElseThrow().getCoutMoyen();
        assertThat(tableau().valeurStock().valeur()).isEqualByComparingTo(cmp.multiply(BigDecimal.valueOf(107)));
        assertThat(valeurAvant).isNotEqualByComparingTo(tableau().valeurStock().valeur());
    }

    @Test
    void lesDettesDuTableauDeBordDiminuentAvecLaPartImputee() throws Exception {
        Achat achat = achat(10, "10000", "20000");
        authentifierGerant();
        assertThat(tableau.creancesEtDettes().dettes()).isEqualByComparingTo("80000");
        envoyer(achat, ADMIN, "3", null, null).andExpect(status().is3xxRedirection());
        authentifierGerant();
        assertThat(tableau.creancesEtDettes().dettes()).isEqualByComparingTo("50000");
    }

    // ---- Droits, isolation, champs inconnus, numéros, double envoi, documents --------------------------------

    @Test
    void leCaissierEstRefuseSurLeFormulaireLEnregistrementEtLaListe() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        mvc.perform(get("/achats/" + achat.getId() + "/retour").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        envoyer(achat, CAISSIER, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isForbidden());
        mvc.perform(get("/retours").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/retours?type=achat").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        assertThat(retours.count()).isZero();
        // Le service répète la règle.
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(CAISSIER, "", List.of(new SimpleGrantedAuthority("ROLE_CAISSIER"))));
        assertThatThrownBy(() -> retourService.enregistrer(achat.getId(), formulaire(achat.getLignes().get(0).getId(), 1, ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES, 0L)))
                .hasMessageContaining("réservé au gérant");
        // Le gérant voit l'écran.
        mvc.perform(get("/achats/" + achat.getId() + "/retour").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/retours").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
    }

    @Test
    void unAchatOuUnRetourDUneAutreBoutiqueEstIntrouvable() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        Retour retour = unique(achat);

        mvc.perform(get("/achats/" + achat.getId() + "/retour").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        envoyer(achat, ADMIN2, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isNotFound());
        mvc.perform(get("/retours/" + retour.getId() + "/imprimer").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/retours/" + retour.getId() + "/imprimer").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        String autre = mvc.perform(get("/retours").with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(autre).doesNotContain(retour.getNumero());
        assertThat(retours.count()).isEqualTo(1);
    }

    @Test
    void unChampInconnuOuRepeteEstRefuseAvecUneErreur400() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        Long ligne = achat.getLignes().get(0).getId();
        mvc.perform(post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailAchatId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE").param("montantTotal", "1")).andExpect(status().isBadRequest());
        mvc.perform(post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailAchatId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("retoursConnus", "0")
                .param("lignes[0].prixUnitaire", "1").param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE")).andExpect(status().isBadRequest());
        mvc.perform(post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailAchatId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("lignes[0].quantite", "2").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE")).andExpect(status().isBadRequest());
        mvc.perform(post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailVenteId", String.valueOf(ligne)).param("lignes[0].quantite", "1").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE")).andExpect(status().isBadRequest());
        mvc.perform(post("/achats/" + achat.getId() + "/retour").param("lignes[0].detailAchatId", String.valueOf(ligne))
                .param("lignes[0].quantite", "1").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isForbidden()); // sans jeton CSRF
        assertThat(retours.count()).isZero();
    }

    @Test
    void lesNumerosRASontConsecutifsParBoutiqueEtIndependantsDesNumerosRV() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        Achat achatAutre = authentifie(ADMIN2, "ADMIN", () -> achatService.enregistrerAchat(nouvelAchat(fournisseurAutre, produitAutre, 5, "10000", "50000")));
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES);
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES);
        envoyer(achatAutre, ADMIN2, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES);
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES);
        // Un retour de vente dans la première boutique garde son propre compteur.
        Vente vente = vente(2, "20000");
        authentifierGerant();
        RetourVenteForm rv = new RetourVenteForm();
        RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(vente.getLignes().get(0).getId()); l.setQuantite(1);
        rv.setLignes(new ArrayList<>(List.of(l))); rv.setModeRemboursement(ModeRemboursement.ARGENT); rv.setComptePaiement(TypeCompteTresorerie.ESPECES.name()); rv.setRetoursConnus(0L);
        assertThat(retourVenteService.enregistrer(vente.getId(), rv).getNumero()).isEqualTo("RV-000001");

        List<String> premiere = retours.findAll().stream().filter(r -> r.getBoutique().getId().equals(boutique.getId()) && r.getNumero().startsWith("RA-")).map(Retour::getNumero).sorted().toList();
        List<String> seconde = retours.findAll().stream().filter(r -> r.getBoutique().getId().equals(autreBoutique.getId())).map(Retour::getNumero).sorted().toList();
        assertThat(premiere).containsExactly("RA-000001", "RA-000002", "RA-000003");
        assertThat(seconde).containsExactly("RA-000001");
    }

    @Test
    void unRefusRendLeNumeroEtNEnregistreRien() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "11", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().isOk());
        envoyer(achat, ADMIN, "1", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES).andExpect(status().is3xxRedirection());
        assertThat(unique(achat).getNumero()).isEqualTo("RA-000001");
        assertThat(boutiques.findById(boutique.getId()).orElseThrow().getProchainNumeroRetourAchat()).isEqualTo(2L);
    }

    @Test
    void unDoubleEnvoiDuMemeFormulaireNEnregistrePasUnSecondRetour() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        Long ligne = achat.getLignes().get(0).getId();
        MockHttpServletRequestBuilder envoi = post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("lignes[0].detailAchatId", String.valueOf(ligne)).param("lignes[0].quantite", "2").param("retoursConnus", "0")
                .param("modeRemboursement", "ARGENT").param("comptePaiement", "CAISSE");
        mvc.perform(envoi).andExpect(status().is3xxRedirection());
        mvc.perform(envoi).andExpect(status().isConflict());
        assertThat(retours.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-80000");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(108L);
    }

    @Test
    void laFactureLaListeEtLeBonImprimableMontrentLeRetour() throws Exception {
        Achat achat = achat(10, "10000", "100000");
        envoyer(achat, ADMIN, "3", ModeRemboursement.ARGENT, TypeCompteTresorerie.ESPECES);
        envoyer(achat, ADMIN, "1", ModeRemboursement.AVOIR, null);
        String facture = mvc.perform(get("/factures/achat/" + achat.getId()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(facture).contains("Retours", "Total net", "RA-000001", "RA-000002", "60 000.00");

        Vente vente = vente(2, "20000");
        authentifierGerant();
        RetourVenteForm rv = new RetourVenteForm();
        RetourVenteForm.Ligne l = new RetourVenteForm.Ligne(); l.setDetailVenteId(vente.getLignes().get(0).getId()); l.setQuantite(1);
        rv.setLignes(new ArrayList<>(List.of(l))); rv.setModeRemboursement(ModeRemboursement.ARGENT); rv.setComptePaiement(TypeCompteTresorerie.ESPECES.name()); rv.setRetoursConnus(0L);
        retourVenteService.enregistrer(vente.getId(), rv);

        String tous = mvc.perform(get("/retours").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(tous).contains("RA-000001", "RA-000002", "RV-000001");
        String seulementAchats = mvc.perform(get("/retours?type=achat").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(seulementAchats).contains("RA-000001", "RA-000002").doesNotContain("RV-000001");
        String seulementVentes = mvc.perform(get("/retours?type=vente").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(seulementVentes).contains("RV-000001").doesNotContain("RA-000001");

        Retour remboursement = retours.findAll().stream().filter(r -> "RA-000001".equals(r.getNumero())).findFirst().orElseThrow();
        Retour avoir = retours.findAll().stream().filter(r -> "RA-000002".equals(r.getNumero())).findFirst().orElseThrow();
        String bon = mvc.perform(get("/retours/" + remboursement.getId() + "/imprimer").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bon).contains("Bon de retour fournisseur RA-000001", "Fournisseur retours", "Remboursé par le fournisseur", "30 000.00");
        String bonAvoir = mvc.perform(get("/retours/" + avoir.getId() + "/imprimer").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(bonAvoir).contains("Avoir fournisseur RA-000002", "Avoir gardé chez le fournisseur");
    }

    // ---- Outils -----------------------------------------------------------------------------------------------

    private ResultActions envoyer(Achat achat, String compte, String quantite, ModeRemboursement mode, TypeCompteTresorerie tresorerie) throws Exception {
        long connus = retours.countByAchatId(achat.getId());
        String role = CAISSIER.equals(compte) ? "CAISSIER" : "ADMIN";
        MockHttpServletRequestBuilder requete = post("/achats/" + achat.getId() + "/retour").with(csrf()).with(user(compte).roles(role))
                .param("lignes[0].detailAchatId", String.valueOf(achat.getLignes().get(0).getId())).param("lignes[0].quantite", quantite)
                .param("retoursConnus", String.valueOf(connus));
        if (mode != null) requete.param("modeRemboursement", mode.name());
        if (tresorerie != null) requete.param("comptePaiement", tresorerie.name());
        return mvc.perform(requete);
    }

    private static RetourAchatForm formulaire(Long ligne, int quantite, ModeRemboursement mode, TypeCompteTresorerie compte, Long connus) {
        RetourAchatForm form = new RetourAchatForm();
        RetourAchatForm.Ligne l = new RetourAchatForm.Ligne(); l.setDetailAchatId(ligne); l.setQuantite(quantite);
        form.setLignes(new ArrayList<>(List.of(l)));
        form.setModeRemboursement(mode); form.setComptePaiement(compte.name()); form.setRetoursConnus(connus);
        return form;
    }

    private Retour unique(Achat achat) {
        return new TransactionTemplate(transactions).execute(s -> {
            List<Retour> trouves = retours.findAll().stream().filter(r -> r.getAchat() != null && r.getAchat().getId().equals(achat.getId())).toList();
            assertThat(trouves).hasSize(1);
            Retour r = trouves.get(0); r.getLignes().size(); return r;
        });
    }

    /** Stock à zéro et coût à zéro : les achats du test forment tout l'historique. */
    private void viderStock() {
        Produit p = produits.findById(produit.getId()).orElseThrow(); p.setQuantite(0L); p.setCoutMoyen(Montants.ZERO); produits.saveAndFlush(p);
    }

    private BigDecimal cmp() { return produits.findById(produit.getId()).orElseThrow().getCoutMoyen(); }

    private void rejouer() {
        authentifierGerant();
        new TransactionTemplate(transactions).executeWithoutResult(s -> {
            Produit p = produits.findById(produit.getId()).orElseThrow();
            coutMoyen.recalculer(List.of(p));
            produits.saveAndFlush(p);
        });
    }

    private TableauDeBordService tableau() { authentifierGerant(); return tableau; }

    private SoldePartenaireService soldes() { authentifierGerant(); return soldesService; }

    private void authentifierGerant() {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Achat achat(int quantite, String prix, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> achatService.enregistrerAchat(nouvelAchat(fournisseur, produit, quantite, prix, verse)));
    }

    private Achat nouvelAchat(Fournisseur vendeur, Produit article, int quantite, String prix, String verse) {
        Achat achat = new Achat(); achat.setBoutique(article.getBoutique());
        Fournisseur ref = new Fournisseur(); ref.setId(vendeur.getId()); achat.setFournisseur(ref);
        achat.setMontantVerse(new BigDecimal(verse));
        Produit r = new Produit(); r.setId(article.getId());
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(r); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal(prix)); ligne.setPrixVenteUnitaire(new BigDecimal("10000.00")); achat.getLignes().add(ligne);
        return achat;
    }

    private Vente vente(int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> {
            Vente vente = new Vente(); vente.setBoutique(boutique); vente.setMontantVerse(new BigDecimal(verse));
            Produit r = new Produit(); r.setId(produit.getId());
            DetailVente ligne = new DetailVente(); ligne.setProduit(r); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
            return venteService.effectuerVente(vente);
        });
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

    private Produit produit(Boutique b, Fournisseur f, String reference) {
        Categorie categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie " + reference); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(b); p.setNom(reference); p.setReference(reference); p.setCategorie(categorie); p.setFournisseur(f);
        p.setNature(NatureProduit.STOCK);
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
