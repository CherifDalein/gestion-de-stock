package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Numéros de facture et échéances, sur deux boutiques, avec de vrais commits et une horloge fixée. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:numeros-echeances;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class NumerosEcheancesFacturesTests {
    private static final String GERANT_A = "numeros-gerant-a@example.test";
    private static final String CAISSIER_A = "numeros-caissier-a@example.test";
    private static final String GERANT_B = "numeros-gerant-b@example.test";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired OperationCreationRepository operations;
    @Autowired VenteService venteService;
    @MockitoBean(name = "recoveryClock") Clock horloge;

    Boutique boutiqueA, boutiqueB;
    Fournisseur fournisseurA, fournisseurB;
    Client clientA;
    Produit rizA, huileA, rizB;

    @BeforeEach
    void preparer() {
        reglerHorloge(LocalDate.now());
        operations.deleteAll(); mouvementsStock.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        produits.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Alimentation A"); boutiqueB = boutique("Alimentation B");
        utilisateur(GERANT_A, boutiqueA, Role.ADMIN); utilisateur(CAISSIER_A, boutiqueA, Role.CAISSIER);
        utilisateur(GERANT_B, boutiqueB, Role.ADMIN);
        Categorie categorieA = categorie(boutiqueA), categorieB = categorie(boutiqueB);
        fournisseurA = fournisseur(boutiqueA); fournisseurB = fournisseur(boutiqueB);
        clientA = new Client(); clientA.setNom("Mamadou"); clientA.setBoutique(boutiqueA); clientA = clients.saveAndFlush(clientA);
        rizA = article(boutiqueA, categorieA, "Riz", 100L);
        huileA = article(boutiqueA, categorieA, "Huile", 50L);
        rizB = article(boutiqueB, categorieB, "Riz B", 100L);
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    // ----- Numéros automatiques -----

    @Test
    void lesNumerosAutomatiquesSontConsecutifsEtFontAvancerLeCompteur() throws Exception {
        for (int i = 0; i < 3; i++) vendre(GERANT_A, "ADMIN", rizA, 1, null).andExpect(redirectedUrl("/ventes"));
        acheter(GERANT_A, fournisseurA, rizA, 2, null).andExpect(redirectedUrl("/achats"));
        acheter(GERANT_A, fournisseurA, rizA, 2, null).andExpect(redirectedUrl("/achats"));

        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001", "FV-000002", "FV-000003");
        assertThat(numerosAchats(boutiqueA)).containsExactly("FA-000001", "FA-000002");
        Boutique apres = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(apres.getProchainNumeroVente()).isEqualTo(4L);
        assertThat(apres.getProchainNumeroAchat()).isEqualTo(3L);
    }

    @Test
    void chaqueBoutiqueASaPropreNumerotation() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, null);
        vendre(CAISSIER_A, "CAISSIER", rizA, 1, null);
        vendre(GERANT_B, "ADMIN", rizB, 1, null).andExpect(redirectedUrl("/ventes"));
        acheter(GERANT_B, fournisseurB, rizB, 1, null).andExpect(redirectedUrl("/achats"));

        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001", "FV-000002");
        assertThat(numerosVentes(boutiqueB)).containsExactly("FV-000001");
        assertThat(numerosAchats(boutiqueB)).containsExactly("FA-000001");
        assertThat(numerosAchats(boutiqueA)).isEmpty();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(2L);
    }

    @Test
    void leCompteurMigreEstRepriseEtUnGrandNumeroNestJamaisTronque() throws Exception {
        Boutique b = boutiques.findById(boutiqueA.getId()).orElseThrow();
        b.setProchainNumeroVente(42L); b.setProchainNumeroAchat(1_234_567L); boutiques.saveAndFlush(b);
        vendre(GERANT_A, "ADMIN", rizA, 1, null);
        acheter(GERANT_A, fournisseurA, rizA, 1, null);
        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000042");
        assertThat(numerosAchats(boutiqueA)).containsExactly("FA-1234567");
    }

    @Test
    void unNumeroDejaSaisiALaMainEstSauteParLaNumerotationAutomatique() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, "FV-000001").andExpect(redirectedUrl("/ventes"));
        vendre(GERANT_A, "ADMIN", rizA, 1, "FV-000002").andExpect(redirectedUrl("/ventes"));
        // Les numéros manuels ne font pas avancer le compteur, qui reste à 1.
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(1L);
        vendre(GERANT_A, "ADMIN", rizA, 1, null);
        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001", "FV-000002", "FV-000003");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(4L);
    }

    // ----- Numéros manuels -----

    @Test
    void unNumeroManuelEstNettoyeEtNeConsommePasLeCompteur() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, "  CAHIER-2026/17  ").andExpect(redirectedUrl("/ventes"));
        acheter(GERANT_A, fournisseurA, rizA, 1, " BL 4587 ").andExpect(redirectedUrl("/achats"));
        vendre(GERANT_A, "ADMIN", rizA, 1, "   ").andExpect(redirectedUrl("/ventes"));
        assertThat(numerosVentes(boutiqueA)).containsExactlyInAnyOrder("CAHIER-2026/17", "FV-000001");
        assertThat(numerosAchats(boutiqueA)).containsExactly("BL 4587");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroAchat()).isEqualTo(1L);
    }

    @Test
    void unNumeroManuelEnDoubleEstRefuseEtLePanierEstConserve() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, "CAHIER-1").andExpect(redirectedUrl("/ventes"));
        String jeton = jeton("/ventes/nouveau", CAISSIER_A, "CAISSIER");
        MvcResult refus = mvc.perform(formulaireVente(CAISSIER_A, "CAISSIER", jeton, "CAHIER-1")
                        .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "3")
                        .param("lignes[1].produit.id", huileA.getId().toString()).param("lignes[1].quantite", "2"))
                .andExpect(status().isOk()).andExpect(view().name("dashboard"))
                .andExpect(model().attribute("error", containsString("« CAHIER-1 » est déjà utilisé")))
                .andReturn();
        String page = refus.getResponse().getContentAsString();
        // Le panier envoyé est reconstitué, avec le numéro saisi pour correction.
        assertThat(page).containsPattern("chargerLigneVente\\(\\s*" + rizA.getId() + ",\\s*3\\s*\\)")
                .containsPattern("chargerLigneVente\\(\\s*" + huileA.getId() + ",\\s*2\\s*\\)")
                .contains("value=\"CAHIER-1\"");
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(stock(rizA)).isEqualTo(99L);
        assertThat(stock(huileA)).isEqualTo(50L);

        // Le même formulaire, corrigé, est accepté.
        mvc.perform(formulaireVente(CAISSIER_A, "CAISSIER", jeton, "CAHIER-2")
                        .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "3"))
                .andExpect(redirectedUrl("/ventes"));
        assertThat(numerosVentes(boutiqueA)).containsExactlyInAnyOrder("CAHIER-1", "CAHIER-2");
    }

    @Test
    void unNumeroDAchatEnDoubleEstRefuseEtLesLignesSontConservees() throws Exception {
        acheter(GERANT_A, fournisseurA, rizA, 1, "BL-1").andExpect(redirectedUrl("/achats"));
        MvcResult refus = acheter(GERANT_A, fournisseurA, huileA, 7, "BL-1")
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("« BL-1 » est déjà utilisé")))
                .andReturn();
        assertThat(refus.getResponse().getContentAsString()).contains("chargerLigneAchat(").contains("value=\"BL-1\"");
        assertThat(achats.count()).isEqualTo(1);
        assertThat(stock(huileA)).isEqualTo(50L);
    }

    @Test
    void lUniciteDuNumeroManuelEstPropreAChaqueBoutique() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, "FACT-1").andExpect(redirectedUrl("/ventes"));
        vendre(GERANT_B, "ADMIN", rizB, 1, "FACT-1").andExpect(redirectedUrl("/ventes"));
        assertThat(numerosVentes(boutiqueB)).containsExactly("FACT-1");
    }

    @Test
    void unNumeroTropLongEstRefuseAvecUnMessage() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, "N".repeat(31))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("au maximum 30 caractères")));
        vendre(GERANT_A, "ADMIN", rizA, 1, " " + "N".repeat(30) + " ").andExpect(redirectedUrl("/ventes"));
        assertThat(numerosVentes(boutiqueA)).containsExactly("N".repeat(30));
    }

    // ----- Doubles envois -----

    @Test
    void leRenvoiDuMemeFormulaireNeConsommeQuUnSeulNumero() throws Exception {
        String jeton = jeton("/ventes/nouveau", GERANT_A, "ADMIN");
        for (int i = 0; i < 2; i++) {
            mvc.perform(formulaireVente(GERANT_A, "ADMIN", jeton, null)
                            .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "2"))
                    .andExpect(redirectedUrl("/ventes"))
                    .andExpect(flash().attribute("success", i == 0 ? containsString("Vente enregistrée") : containsString("déjà enregistrée")));
        }
        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(2L);

        String jetonAchat = jeton("/achats/nouveau", GERANT_A, "ADMIN");
        for (int i = 0; i < 2; i++) {
            mvc.perform(formulaireAchat(GERANT_A, jetonAchat, fournisseurA, rizA, 1, "BL-77")).andExpect(redirectedUrl("/achats"));
        }
        // Un numéro manuel renvoyé avec le même jeton retrouve la même facture au lieu d'être refusé.
        assertThat(numerosAchats(boutiqueA)).containsExactly("BL-77");
    }

    @Test
    void unRenvoiAvecUnAutreNumeroEstUnConflit() throws Exception {
        String jeton = jeton("/ventes/nouveau", GERANT_A, "ADMIN");
        mvc.perform(formulaireVente(GERANT_A, "ADMIN", jeton, "A-1")
                .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1"))
                .andExpect(redirectedUrl("/ventes"));
        mvc.perform(formulaireVente(GERANT_A, "ADMIN", jeton, "A-2")
                        .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1"))
                .andExpect(status().isConflict());
        assertThat(numerosVentes(boutiqueA)).containsExactly("A-1");
    }

    // ----- Concurrence -----

    @Test
    void deuxVentesSimultaneesRecoiventDeuxNumerosDifferents() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier depart = new CyclicBarrier(2);
            Callable<String> vente = () -> {
                depart.await(10, TimeUnit.SECONDS);
                return comme(CAISSIER_A, "CAISSIER", () -> venteService.effectuerVente(venteService(rizA, 1)).getNumero());
            };
            Future<String> a = pool.submit(vente), b = pool.submit(vente);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("FV-000001", "FV-000002");
        }
        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001", "FV-000002");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(3L);
        assertThat(stock(rizA)).isEqualTo(98L);
    }

    @Test
    void uneCreationAnnuleeRendSonNumero() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 500, null)
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("Stock insuffisant")));
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(1L);
        vendre(GERANT_A, "ADMIN", rizA, 1, null);
        assertThat(numerosVentes(boutiqueA)).containsExactly("FV-000001");
    }

    // ----- Échéances à la création -----

    @Test
    void lEcheanceProposeeEstLaDateDeLaFacturePlusLeDelai() throws Exception {
        Boutique b = boutiques.findById(boutiqueA.getId()).orElseThrow();
        b.setDelaiFournisseursJours(45); boutiques.saveAndFlush(b);
        mvc.perform(avec(get("/ventes/nouveau"), GERANT_A, "ADMIN"))
                .andExpect(model().attribute("echeanceProposee", LocalDate.now().plusDays(30)))
                .andExpect(content().string(containsString("value=\"" + LocalDate.now().plusDays(30) + "\"")));
        vendre(GERANT_A, "ADMIN", rizA, 2, null, "0", null);
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "100", null);
        Vente vente = ventes.findAll().getFirst();
        assertThat(vente.getDateEcheance()).isEqualTo(vente.getDateVente().toLocalDate().plusDays(30));
        Achat achat = achats.findAll().getFirst();
        assertThat(achat.getDateEcheance()).isEqualTo(achat.getDateAchat().toLocalDate().plusDays(45));
    }

    @Test
    void uneEcheancePersonnaliseeEstEnregistree() throws Exception {
        LocalDate choisie = LocalDate.now().plusDays(12);
        vendre(GERANT_A, "ADMIN", rizA, 2, null, "100", choisie.toString()).andExpect(redirectedUrl("/ventes"));
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "0", LocalDate.now().toString()).andExpect(redirectedUrl("/achats"));
        assertThat(ventes.findAll().getFirst().getDateEcheance()).isEqualTo(choisie);
        assertThat(achats.findAll().getFirst().getDateEcheance()).isEqualTo(LocalDate.now());
    }

    @Test
    void uneEcheanceAnterieureALaFactureEstRefusee() throws Exception {
        String hier = LocalDate.now().minusDays(1).toString();
        MvcResult refus = vendre(GERANT_A, "ADMIN", rizA, 4, null, "0", hier)
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("ne peut pas être antérieure")))
                .andReturn();
        assertThat(refus.getResponse().getContentAsString()).contains("value=\"" + hier + "\"").contains("chargerLigneVente(");
        acheter(GERANT_A, fournisseurA, rizA, 1, null, "0", hier)
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("ne peut pas être antérieure")));
        assertThat(ventes.count()).isZero();
        assertThat(achats.count()).isZero();
        assertThat(stock(rizA)).isEqualTo(100L);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(1L);
    }

    @Test
    void uneFactureEntierementPayeeNaPasDEcheance() throws Exception {
        String dans10Jours = LocalDate.now().plusDays(10).toString();
        vendre(GERANT_A, "ADMIN", rizA, 2, null, "10000", dans10Jours).andExpect(redirectedUrl("/ventes"));
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "8000", dans10Jours).andExpect(redirectedUrl("/achats"));
        assertThat(ventes.findAll().getFirst().getDateEcheance()).isNull();
        assertThat(achats.findAll().getFirst().getDateEcheance()).isNull();
    }

    @Test
    void uneEcheanceMalFormeeOuEnDoubleEstRefusee() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 1, null, "0", "07/12/2026").andExpect(status().isBadRequest());
        String jeton = jeton("/ventes/nouveau", GERANT_A, "ADMIN");
        mvc.perform(formulaireVente(GERANT_A, "ADMIN", jeton, null).param("montantVerse", "0")
                        .param("dateEcheance", LocalDate.now().toString(), LocalDate.now().plusDays(1).toString())
                        .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1"))
                .andExpect(status().isBadRequest());
        assertThat(ventes.count()).isZero();
    }

    // ----- Retard -----

    @Test
    void uneVenteACreditDu7NovembreEstEnRetardLe8Decembre() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 2, null, "0", null);
        Vente vente = ventes.findAll().getFirst();
        // Dates fixées dans les données : vente du 7 novembre, échéance proposée au 7 décembre.
        vente.setDateVente(LocalDateTime.of(2026, 11, 7, 10, 30)); vente.setDateEcheance(LocalDate.of(2026, 12, 7));
        ventes.saveAndFlush(vente);

        reglerHorloge(LocalDate.of(2026, 12, 7));
        assertThat(vente.estEnRetard(LocalDate.of(2026, 12, 7))).isFalse();
        mvc.perform(avec(get("/ventes"), CAISSIER_A, "CAISSIER")).andExpect(content().string(not(containsString("En retard"))));
        mvc.perform(avec(get("/ventes").param("filtre", "echues"), CAISSIER_A, "CAISSIER"))
                .andExpect(model().attribute("ventes", org.hamcrest.Matchers.empty()));

        reglerHorloge(LocalDate.of(2026, 12, 8));
        mvc.perform(avec(get("/ventes"), CAISSIER_A, "CAISSIER")).andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/ventes").param("filtre", "echues"), CAISSIER_A, "CAISSIER"))
                .andExpect(model().attribute("ventes", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(content().string(containsString("FV-000001")));
        mvc.perform(avec(get("/factures/vente/" + vente.getId()), CAISSIER_A, "CAISSIER"))
                .andExpect(content().string(containsString("FV-000001"))).andExpect(content().string(containsString("07/12/2026")))
                .andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/factures/client/" + clientA.getId() + "/cumule"), CAISSIER_A, "CAISSIER"))
                .andExpect(content().string(containsString("FV-000001"))).andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/factures/liste"), CAISSIER_A, "CAISSIER")).andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/factures/client/" + clientA.getId()), CAISSIER_A, "CAISSIER"))
                .andExpect(content().string(containsString("FV-000001"))).andExpect(content().string(containsString("Échéance : 07/12/2026")))
                .andExpect(content().string(containsString("En retard")));
        // La boutique B ne voit pas les retards de A.
        mvc.perform(avec(get("/ventes").param("filtre", "echues"), GERANT_B, "ADMIN"))
                .andExpect(model().attribute("ventes", org.hamcrest.Matchers.empty()));

        // Un règlement qui solde la facture garde l'échéance en historique, sans retard.
        mvc.perform(avec(post("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER").param("montant", "10000")
                        .param("montantVerseAttendu", "0").param("comptePaiement", "CAISSE"))
                .andExpect(redirectedUrl("/ventes/regler/" + vente.getId()));
        Vente soldee = ventes.findById(vente.getId()).orElseThrow();
        assertThat(soldee.getResteAPayer()).isZero();
        assertThat(soldee.getDateEcheance()).isEqualTo(LocalDate.of(2026, 12, 7));
        mvc.perform(avec(get("/ventes"), CAISSIER_A, "CAISSIER")).andExpect(content().string(not(containsString("En retard"))));
        mvc.perform(avec(get("/ventes").param("filtre", "echues"), CAISSIER_A, "CAISSIER"))
                .andExpect(model().attribute("ventes", org.hamcrest.Matchers.empty()));
    }

    @Test
    void unAchatEchuNonSoldeApparaitDansLeFiltreDesAchats() throws Exception {
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "1000", null);
        Achat achat = achats.findAll().getFirst();
        achat.setDateAchat(LocalDateTime.of(2026, 1, 5, 9, 0)); achat.setDateEcheance(LocalDate.of(2026, 2, 4));
        achats.saveAndFlush(achat);
        reglerHorloge(LocalDate.of(2026, 2, 5));
        mvc.perform(avec(get("/achats").param("filtre", "echues"), GERANT_A, "ADMIN"))
                .andExpect(model().attribute("achats", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(content().string(containsString("En retard"))).andExpect(content().string(containsString("FA-000001")));
        mvc.perform(avec(get("/factures/achat/" + achat.getId()), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("FA-000001"))).andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/factures/fournisseur/" + fournisseurA.getId() + "/cumule"), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("04/02/2026")));
        mvc.perform(avec(get("/factures/fournisseur/" + fournisseurA.getId()), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("FA-000001"))).andExpect(content().string(containsString("En retard")));
        mvc.perform(avec(get("/achats/modifier/" + achat.getId()), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("FA-000001")))
                .andExpect(content().string(containsString("name=\"dateEcheance\" class=\"form-control\"")))
                .andExpect(content().string(containsString("value=\"2026-02-04\"")));
        mvc.perform(avec(get("/achats").param("filtre", "echues"), CAISSIER_A, "CAISSIER")).andExpect(status().isForbidden());
    }

    // ----- Paramètres de la boutique -----

    @Test
    void lesDelaisModifiesDansLesParametresSontPrisEnCompte() throws Exception {
        mvc.perform(avec(get("/boutique/parametres"), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("Délai accordé aux clients (jours)")))
                .andExpect(content().string(containsString("Délai accordé par les fournisseurs (jours)")));
        mvc.perform(parametres(GERANT_A, "ADMIN", "10", "0")).andExpect(redirectedUrl("/boutique/parametres"));
        Boutique b = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(b.getDelaiClientsJours()).isEqualTo(10);
        assertThat(b.getDelaiFournisseursJours()).isZero();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getDelaiClientsJours()).isEqualTo(30);

        vendre(GERANT_A, "ADMIN", rizA, 1, null, "0", null);
        acheter(GERANT_A, fournisseurA, rizA, 1, null, "0", null);
        Vente vente = ventes.findAll().getFirst();
        assertThat(vente.getDateEcheance()).isEqualTo(vente.getDateVente().toLocalDate().plusDays(10));
        Achat achat = achats.findAll().getFirst();
        assertThat(achat.getDateEcheance()).isEqualTo(achat.getDateAchat().toLocalDate());
        mvc.perform(avec(get("/ventes/nouveau"), GERANT_A, "ADMIN"))
                .andExpect(model().attribute("echeanceProposee", LocalDate.now().plusDays(10)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "366"})
    void unDelaiHorsLimitesEstSignaleSansEnregistrer(String delai) throws Exception {
        mvc.perform(parametres(GERANT_A, "ADMIN", delai, "30"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "delaiClientsJours"))
                .andExpect(content().string(containsString("entre 0 et 365 jours")));
        mvc.perform(parametres(GERANT_A, "ADMIN", "30", delai))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("parametresBoutique", "delaiFournisseursJours"));
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getDelaiClientsJours()).isEqualTo(30);
    }

    @Test
    void lesDelaisRestentReservesAuGerant() throws Exception {
        mvc.perform(parametres(CAISSIER_A, "CAISSIER", "5", "5")).andExpect(status().isForbidden());
        mvc.perform(parametres(GERANT_A, "ADMIN", "abc", "5")).andExpect(status().isBadRequest());
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getDelaiClientsJours()).isEqualTo(30);
    }

    // ----- Modification et règlement -----

    @Test
    void laModificationDUnAchatChangeLEcheanceMaisJamaisLeNumero() throws Exception {
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "0", null);
        Achat achat = achats.findAll().getFirst();
        LocalDate nouvelle = LocalDate.now().plusDays(60);
        mvc.perform(modificationAchat(achat, 3).param("dateEcheance", nouvelle.toString()))
                .andExpect(redirectedUrl("/achats?success=modifie"));
        Achat modifie = achats.findById(achat.getId()).orElseThrow();
        assertThat(modifie.getDateEcheance()).isEqualTo(nouvelle);
        assertThat(modifie.getNumero()).isEqualTo("FA-000001");

        mvc.perform(modificationAchat(achat, 3).param("dateEcheance", LocalDate.now().minusDays(1).toString()))
                .andExpect(redirectedUrl("/achats/modifier/" + achat.getId()))
                .andExpect(flash().attribute("error", containsString("ne peut pas être antérieure")));
        assertThat(achats.findById(achat.getId()).orElseThrow().getDateEcheance()).isEqualTo(nouvelle);
        // Sans échéance envoyée, l'échéance enregistrée est conservée.
        mvc.perform(modificationAchat(achat, 4)).andExpect(redirectedUrl("/achats?success=modifie"));
        assertThat(achats.findById(achat.getId()).orElseThrow().getDateEcheance()).isEqualTo(nouvelle);
    }

    @Test
    void leChampNumeroEstRefuseHorsCreation() throws Exception {
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "0", null);
        vendre(GERANT_A, "ADMIN", rizA, 1, null, "0", null);
        Achat achat = achats.findAll().getFirst();
        Vente vente = ventes.findAll().getFirst();
        mvc.perform(modificationAchat(achat, 2).param("numero", "PIRATE")).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/achats/regler/" + achat.getId()), GERANT_A, "ADMIN").param("montant", "1")
                .param("montantVerseAttendu", "0").param("numero", "PIRATE")).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER").param("montant", "1")
                .param("montantVerseAttendu", "0").param("numero", "PIRATE")).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/ventes/regler/" + vente.getId() + "/echeance"), CAISSIER_A, "CAISSIER")
                .param("dateEcheance", LocalDate.now().toString()).param("numero", "PIRATE")).andExpect(status().isBadRequest());
        assertThat(achats.findById(achat.getId()).orElseThrow().getNumero()).isEqualTo("FA-000001");
        assertThat(ventes.findById(vente.getId()).orElseThrow().getNumero()).isEqualTo("FV-000001");
        assertThat(caisse.count()).isZero();
    }

    @Test
    void lePagesDeReglementPermettentDeChangerLEcheanceTantQuIlResteAPayer() throws Exception {
        vendre(GERANT_A, "ADMIN", rizA, 2, null, "0", null);
        Vente vente = ventes.findAll().getFirst();
        mvc.perform(avec(get("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER"))
                .andExpect(content().string(containsString("FV-000001")))
                .andExpect(content().string(containsString("Modifier seulement l’échéance")));

        LocalDate reportee = LocalDate.now().plusDays(45);
        mvc.perform(avec(post("/ventes/regler/" + vente.getId() + "/echeance"), CAISSIER_A, "CAISSIER")
                        .param("dateEcheance", reportee.toString()).param("montant", "").param("montantVerseAttendu", "0")
                        .param("comptePaiement", "CAISSE"))
                .andExpect(redirectedUrl("/ventes/regler/" + vente.getId()));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getDateEcheance()).isEqualTo(reportee);
        assertThat(caisse.count()).isZero();

        // Un versement partiel peut reporter l'échéance du reste.
        LocalDate apresVersement = LocalDate.now().plusDays(50);
        mvc.perform(avec(post("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER").param("montant", "4000")
                        .param("montantVerseAttendu", "0").param("comptePaiement", "CAISSE").param("dateEcheance", apresVersement.toString()))
                .andExpect(redirectedUrl("/ventes/regler/" + vente.getId()));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getDateEcheance()).isEqualTo(apresVersement);

        mvc.perform(avec(post("/ventes/regler/" + vente.getId() + "/echeance"), CAISSIER_A, "CAISSIER")
                        .param("dateEcheance", LocalDate.now().minusDays(3).toString()))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("reglementVente"))
                .andExpect(content().string(containsString("ne peut pas être antérieure")));
        // Une échéance invalide jointe à un versement annule aussi le versement.
        mvc.perform(avec(post("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER").param("montant", "1000")
                        .param("montantVerseAttendu", "4000").param("comptePaiement", "CAISSE")
                        .param("dateEcheance", LocalDate.now().minusDays(3).toString()))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("reglementVente"));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("4000");

        // Le solde garde l'échéance en historique et ne la rend plus modifiable.
        mvc.perform(avec(post("/ventes/regler/" + vente.getId()), CAISSIER_A, "CAISSIER").param("montant", "6000")
                        .param("montantVerseAttendu", "4000").param("comptePaiement", "CAISSE")
                        .param("dateEcheance", LocalDate.now().plusDays(90).toString()))
                .andExpect(redirectedUrl("/ventes/regler/" + vente.getId()));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getDateEcheance()).isEqualTo(apresVersement);
        mvc.perform(avec(post("/ventes/regler/" + vente.getId() + "/echeance"), CAISSIER_A, "CAISSIER")
                        .param("dateEcheance", LocalDate.now().plusDays(90).toString()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("soldée")));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getDateEcheance()).isEqualTo(apresVersement);
    }

    @Test
    void lEcheanceDUnAchatSeModifieAuReglementEtResteIsoleeParBoutiqueEtParRole() throws Exception {
        acheter(GERANT_A, fournisseurA, rizA, 2, null, "0", null);
        Achat achat = achats.findAll().getFirst();
        LocalDate reportee = LocalDate.now().plusDays(20);
        mvc.perform(avec(post("/achats/regler/" + achat.getId() + "/echeance"), CAISSIER_A, "CAISSIER")
                .param("dateEcheance", reportee.toString())).andExpect(status().isForbidden());
        mvc.perform(avec(post("/achats/regler/" + achat.getId() + "/echeance"), GERANT_B, "ADMIN")
                .param("dateEcheance", reportee.toString())).andExpect(status().isNotFound());
        mvc.perform(avec(post("/achats/regler/" + achat.getId() + "/echeance"), GERANT_A, "ADMIN")
                .param("dateEcheance", reportee.toString())).andExpect(redirectedUrl("/achats/regler/" + achat.getId()));
        assertThat(achats.findById(achat.getId()).orElseThrow().getDateEcheance()).isEqualTo(reportee);
        mvc.perform(avec(post("/achats/regler/" + achat.getId()), GERANT_A, "ADMIN").param("montant", "100")
                        .param("montantVerseAttendu", "0").param("comptePaiement", "CAISSE")
                        .param("dateEcheance", reportee.plusDays(1).toString()))
                .andExpect(redirectedUrl("/achats/regler/" + achat.getId()));
        assertThat(achats.findById(achat.getId()).orElseThrow().getDateEcheance()).isEqualTo(reportee.plusDays(1));
        mvc.perform(avec(get("/achats/regler/" + achat.getId()), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("FA-000001")));

        vendre(GERANT_A, "ADMIN", rizA, 1, null, "0", null);
        Vente vente = ventes.findAll().getFirst();
        mvc.perform(avec(post("/ventes/regler/" + vente.getId() + "/echeance"), GERANT_B, "ADMIN")
                .param("dateEcheance", reportee.toString())).andExpect(status().isNotFound());
    }

    // ----- Outils -----

    private org.springframework.test.web.servlet.ResultActions vendre(String email, String role, Produit produit, int quantite, String numero) throws Exception {
        return vendre(email, role, produit, quantite, numero, null, null);
    }

    private org.springframework.test.web.servlet.ResultActions vendre(String email, String role, Produit produit, int quantite,
                                                                      String numero, String verse, String echeance) throws Exception {
        var requete = formulaireVente(email, role, jeton("/ventes/nouveau", email, role), numero)
                .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite));
        if (produit.getBoutique().getId().equals(boutiqueA.getId())) requete.param("client", clientA.getId().toString());
        if (verse != null) requete.param("montantVerse", verse);
        if (echeance != null) requete.param("dateEcheance", echeance);
        return mvc.perform(requete);
    }

    private MockHttpServletRequestBuilder formulaireVente(String email, String role, String jeton, String numero) {
        var requete = avec(post("/ventes/enregistrer"), email, role).param("jetonCreation", jeton).param("comptePaiement", "CAISSE");
        if (numero != null) requete.param("numero", numero);
        return requete;
    }

    private org.springframework.test.web.servlet.ResultActions acheter(String email, Fournisseur fournisseur, Produit produit, int quantite, String numero) throws Exception {
        return acheter(email, fournisseur, produit, quantite, numero, null, null);
    }

    private org.springframework.test.web.servlet.ResultActions acheter(String email, Fournisseur fournisseur, Produit produit, int quantite,
                                                                       String numero, String verse, String echeance) throws Exception {
        var requete = formulaireAchat(email, jeton("/achats/nouveau", email, "ADMIN"), fournisseur, produit, quantite, numero);
        if (verse != null) requete.param("montantVerse", verse);
        if (echeance != null) requete.param("dateEcheance", echeance);
        return mvc.perform(requete);
    }

    private MockHttpServletRequestBuilder formulaireAchat(String email, String jeton, Fournisseur fournisseur, Produit produit, int quantite, String numero) {
        var requete = avec(post("/achats/enregistrer"), email, "ADMIN").param("jetonCreation", jeton).param("comptePaiement", "CAISSE")
                .param("fournisseur.id", fournisseur.getId().toString())
                .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite))
                .param("lignes[0].prixAchatUnitaire", "4000").param("lignes[0].prixVenteUnitaire", "5000");
        if (numero != null) requete.param("numero", numero);
        return requete;
    }

    private MockHttpServletRequestBuilder modificationAchat(Achat achat, int quantite) {
        return avec(post("/achats/modifier/" + achat.getId()), GERANT_A, "ADMIN").param("fournisseur", fournisseurA.getId().toString())
                .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite))
                .param("lignes[0].prixAchatUnitaire", "4000").param("lignes[0].prixVenteUnitaire", "5000");
    }

    private MockHttpServletRequestBuilder parametres(String email, String role, String delaiClients, String delaiFournisseurs) {
        return avec(post("/boutique/parametres"), email, role).param("nom", "Alimentation A")
                .param("delaiClientsJours", delaiClients).param("delaiFournisseursJours", delaiFournisseurs);
    }

    private String jeton(String route, String email, String role) throws Exception {
        return (String) mvc.perform(avec(get(route), email, role)).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("jetonCreation");
    }

    private MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) {
        return requete.with(user(email).roles(role)).with(csrf());
    }

    private List<String> numerosVentes(Boutique boutique) {
        return ventes.findAll().stream().filter(v -> v.getBoutique().getId().equals(boutique.getId()))
                .map(Vente::getNumero).sorted().toList();
    }

    private List<String> numerosAchats(Boutique boutique) {
        return achats.findAll().stream().filter(a -> a.getBoutique().getId().equals(boutique.getId()))
                .map(Achat::getNumero).sorted().toList();
    }

    private long stock(Produit produit) { return produits.findById(produit.getId()).orElseThrow().getQuantite(); }

    private void reglerHorloge(LocalDate jour) {
        Instant instant = jour.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        when(horloge.instant()).thenReturn(instant);
        when(horloge.getZone()).thenReturn(ZoneId.systemDefault());
        when(horloge.withZone(any())).thenReturn(horloge);
    }

    private Vente venteService(Produit produit, int quantite) {
        Vente vente = new Vente(); vente.setMontantVerse(BigDecimal.ZERO);
        DetailVente ligne = new DetailVente(); Produit reference = new Produit(); reference.setId(produit.getId());
        ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private <T> T comme(String email, String role, Supplier<T> action) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
        try { return action.get(); } finally { SecurityContextHolder.clearContext(); }
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }
    private void utilisateur(String email, Boutique boutique, Role role) {
        Utilisateur u = new Utilisateur(); u.setNom("Utilisateur"); u.setEmail(email); u.setRole(role); u.setBoutique(boutique);
        utilisateurs.saveAndFlush(u);
    }
    private Categorie categorie(Boutique boutique) {
        Categorie c = new Categorie(); c.setNom("Alimentation"); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }
    private Fournisseur fournisseur(Boutique boutique) {
        Fournisseur f = new Fournisseur(); f.setNom("Grossiste"); f.setBoutique(boutique); return fournisseurs.saveAndFlush(f);
    }
    private Produit article(Boutique boutique, Categorie categorie, String nom, Long quantite) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(nom.toUpperCase());
        p.setQuantite(quantite); p.setUnite("sac"); p.setPrixDefinis(true);
        p.setPrixAchat(new BigDecimal("4000")); p.setPrixVente(new BigDecimal("5000")); p.setCoutMoyen(new BigDecimal("4000"));
        return produits.saveAndFlush(p);
    }
}
