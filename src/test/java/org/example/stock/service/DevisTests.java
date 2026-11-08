package org.example.stock.service;

import org.example.stock.enums.NatureProduit;
import org.example.stock.enums.Role;
import org.example.stock.enums.StatutDevis;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Devis : proposition de prix sans effet, validité, transformation en vente, isolation par boutique et droits. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:devis-lot2;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class DevisTests {
    private static final String GERANT_A = "devis-gerant-a@example.test";
    private static final String CAISSIER_A = "devis-caissier-a@example.test";
    private static final String GERANT_B = "devis-gerant-b@example.test";
    private static final LocalDate JOUR = LocalDate.of(2026, 11, 7);

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired ClientRepository clients;
    @Autowired VenteRepository ventes;
    @Autowired DevisRepository devisRepo;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired MouvementStockRepository mouvementsStock;
    @Autowired OperationCreationRepository operations;
    @Autowired OperationCreationService creations;
    @Autowired SoldePartenaireService soldes;
    @Autowired TableauDeBordService tableauDeBord;
    @Autowired TransactionTemplate tx;
    @MockitoBean(name = "recoveryClock") Clock horloge;

    Boutique boutiqueA, boutiqueB;
    Client clientA, clientB;
    Produit rizA, huileA, serviceA, rizB;

    @BeforeEach
    void preparer() {
        reglerHorloge(JOUR);
        operations.deleteAll(); mouvementsStock.deleteAll(); caisse.deleteAll(); devisRepo.deleteAll(); ventes.deleteAll();
        produits.deleteAll(); clients.deleteAll(); categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Alimentation A"); boutiqueB = boutique("Alimentation B");
        utilisateur(GERANT_A, boutiqueA, Role.ADMIN); utilisateur(CAISSIER_A, boutiqueA, Role.CAISSIER);
        utilisateur(GERANT_B, boutiqueB, Role.ADMIN);
        Categorie categorieA = categorie(boutiqueA), categorieB = categorie(boutiqueB);
        clientA = client(boutiqueA, "Mamadou"); clientB = client(boutiqueB, "Aissatou");
        rizA = article(boutiqueA, categorieA, "Riz", 100L, "5000");
        huileA = article(boutiqueA, categorieA, "Huile", 50L, "12000");
        serviceA = service(boutiqueA, categorieA, "Livraison", "20000");
        rizB = article(boutiqueB, categorieB, "Riz B", 100L, "5000");
    }

    @AfterEach
    void deconnecter() { SecurityContextHolder.clearContext(); }

    // ----- Aucun effet -----

    @Test
    void unDevisCreeNeChangeNiStockNiTresorerieNiCreanceNiTableauDeBord() throws Exception {
        Object avant = situation();
        creer(GERANT_A, "ADMIN", clientA, null, "Remise de fin d'année", ligne(rizA, 10, "4500"), ligne(serviceA, 1, "20000"))
                .andExpect(redirectedUrlPattern("/devis/*")).andExpect(flash().attribute("success", containsString("FD-000001")));
        creer(CAISSIER_A, "CAISSIER", null, null, null, ligne(huileA, 5, "12000")).andExpect(redirectedUrlPattern("/devis/*"));

        assertThat(situation()).isEqualTo(avant);
        assertThat(stock(rizA)).isEqualTo(100L);
        assertThat(stock(huileA)).isEqualTo(50L);
        assertThat(ventes.count()).isZero();
        assertThat(caisse.count()).isZero();
        assertThat(mouvementsStock.count()).isZero();
        assertThat(operations.count()).isZero();
        Boutique apres = boutiques.findById(boutiqueA.getId()).orElseThrow();
        assertThat(apres.getProchainNumeroVente()).isEqualTo(1L);
        assertThat(apres.isInitialisationTerminee()).isEqualTo(boutiqueA.isInitialisationTerminee());
        Produit riz = produits.findById(rizA.getId()).orElseThrow();
        assertThat(riz.getCoutMoyen()).isEqualByComparingTo("4000");
        assertThat(riz.getPrixVente()).isEqualByComparingTo("5000");
        // Le total est recalculé par le serveur : 10 x 4 500 + 20 000.
        assertThat(devisDe(numeros(boutiqueA).getFirst()).total()).isEqualByComparingTo("65000");
    }

    private Object situation() {
        return comme(GERANT_A, "ADMIN", () -> List.of(soldes.soldeClient(clientA.getId()),
                tableauDeBord.indicateursDuJour(JOUR), tableauDeBord.indicateursDuMois(JOUR),
                tableauDeBord.valeurStock(), tableauDeBord.creancesEtDettes(), caisse.calculerSoldeTotal(boutiqueA.getId())));
    }

    // ----- Numérotation -----

    @Test
    void lesNumerosFDSontConsecutifsEtIndependantsEntreBoutiques() throws Exception {
        for (int i = 0; i < 3; i++) creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));
        creer(CAISSIER_A, "CAISSIER", null, null, null, ligne(rizA, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));
        creer(GERANT_B, "ADMIN", null, null, null, ligne(rizB, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));

        assertThat(numeros(boutiqueA)).containsExactly("FD-000001", "FD-000002", "FD-000003", "FD-000004");
        assertThat(numeros(boutiqueB)).containsExactly("FD-000001");
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroDevis()).isEqualTo(5L);
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getProchainNumeroDevis()).isEqualTo(2L);
        // Les compteurs des ventes et des achats ne bougent pas.
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(1L);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroAchat()).isEqualTo(1L);
    }

    @Test
    void uneCreationRefuseeRendSonNumeroEtUnNumeroManuelEstRefuse() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 0, "5000"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("quantité")));
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroDevis()).isEqualTo(1L);
        // Pas de numéro manuel pour un devis.
        mvc.perform(avec(post("/devis/enregistrer"), GERANT_A, "ADMIN").param("numero", "MON-NUMERO")
                .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1").param("lignes[0].prixUnitaire", "5000"))
                .andExpect(status().isBadRequest());
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "5000"));
        assertThat(numeros(boutiqueA)).containsExactly("FD-000001");
    }

    // ----- Création : contrôles -----

    @Test
    void laValiditeParDefautEstLeJourPlusTrenteJours() throws Exception {
        var page = mvc.perform(avec(get("/devis/nouveau"), CAISSIER_A, "CAISSIER")).andExpect(status().isOk()).andReturn();
        DevisFormView form = new DevisFormView(page.getModelAndView().getModel().get("devisForm"));
        assertThat(form.validite()).isEqualTo(JOUR.plusDays(30));
        assertThat(page.getResponse().getContentAsString()).contains("value=\"2026-12-07\"").contains("min=\"2026-11-07\"");

        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "5000"));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(30));
        // Le prix de vente du produit est proposé dans la liste des produits.
        assertThat(page.getResponse().getContentAsString()).contains("data-prix=\"5000.00\"");
    }

    @Test
    void laValiditeSaisieDoitEtreAuMoinsLaDateDuDevis() throws Exception {
        creer(GERANT_A, "ADMIN", null, JOUR.minusDays(1).toString(), null, ligne(rizA, 1, "5000"))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("antérieure à la date du devis")));
        creer(GERANT_A, "ADMIN", null, JOUR.toString(), null, ligne(rizA, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));
        creer(GERANT_A, "ADMIN", null, JOUR.plusDays(90).toString(), null, ligne(rizA, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR);
        assertThat(devisDe("FD-000002").validite()).isEqualTo(JOUR.plusDays(90));
        assertThat(numeros(boutiqueA)).hasSize(2);
    }

    @Test
    void lesLignesInvalidesSontRefuseesEtLeFormulaireReaffichelesLignes() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("au moins une ligne")));
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, -2, "5000")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("quantité")));
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "-1")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("ne peut pas être négatif")));
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "10.005")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("2 décimales")));
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, null)).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("prix unitaire")));
        creer(GERANT_A, "ADMIN", null, null, "x".repeat(256), ligne(rizA, 1, "5000")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("255 caractères")));
        mvc.perform(avec(post("/devis/enregistrer"), GERANT_A, "ADMIN").param("lignes[0].produit.id", rizA.getId().toString())
                        .param("lignes[0].quantite", "1,5").param("lignes[0].prixUnitaire", "5000"))
                .andExpect(status().isBadRequest());
        var refus = creer(GERANT_A, "ADMIN", null, null, null, ligne(huileA, 3, "-5")).andExpect(status().isOk()).andReturn();
        assertThat(refus.getResponse().getContentAsString()).containsPattern("chargerLigneDevis\\(\\s*" + huileA.getId() + ",\\s*3,\\s*\"-5\"\\s*\\)");
        assertThat(devisRepo.count()).isZero();
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroDevis()).isEqualTo(1L);

        // Un prix nul et un service sont permis ; le prix nul est une saisie volontaire.
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "0"), ligne(serviceA, 2, "15000")).andExpect(redirectedUrlPattern("/devis/*"));
        assertThat(devisDe("FD-000001").total()).isEqualByComparingTo("30000");
    }

    @Test
    void unProduitSansPrixDefiniNePeutPasEtreProposeDansUnDevis() throws Exception {
        Produit nouveau = article(boutiqueA, categories.findAll().getFirst(), "Sucre", 0L, "0");
        nouveau.setPrixDefinis(false); produits.saveAndFlush(nouveau);
        creer(GERANT_A, "ADMIN", null, null, null, ligne(nouveau, 1, "3000")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("ne sont pas encore définis")));
        assertThat(devisRepo.count()).isZero();
    }

    // ----- Expiration et prolongation -----

    @Test
    void unDevisEstExpireSeulementApresSaDateDeValidite() throws Exception {
        creer(GERANT_A, "ADMIN", null, JOUR.plusDays(5).toString(), null, ligne(rizA, 1, "5000"));
        long id = idDe("FD-000001");
        // Le dernier jour de validité, le devis est encore en cours (même convention que le retard des factures).
        reglerHorloge(JOUR.plusDays(5));
        assertThat(listeContient("en-cours", "FD-000001")).isTrue();
        assertThat(listeContient("expire", "FD-000001")).isFalse();
        mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), GERANT_A, "ADMIN")).andExpect(status().isOk());
        // Le lendemain, il est expiré : état calculé, statut stocké inchangé.
        reglerHorloge(JOUR.plusDays(6));
        assertThat(listeContient("en-cours", "FD-000001")).isFalse();
        assertThat(listeContient("expire", "FD-000001")).isTrue();
        assertThat(listeContient(null, "FD-000001")).isTrue();
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);
        mvc.perform(avec(get("/devis/" + id), GERANT_A, "ADMIN")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Expiré"))).andExpect(content().string(org.hamcrest.Matchers.not(containsString("boutonTransformer"))));
    }

    @Test
    void ProlongerUnDevisExpireLeRemetEnCoursEtLaNouvelleDateDoitEtreValide() throws Exception {
        creer(GERANT_A, "ADMIN", null, JOUR.plusDays(2).toString(), null, ligne(rizA, 1, "5000"));
        long id = idDe("FD-000001");
        reglerHorloge(JOUR.plusDays(10));
        // Avant le jour : refusé ; pas postérieure à l'actuelle : refusé.
        mvc.perform(prolonger(CAISSIER_A, "CAISSIER", id, JOUR.plusDays(9).toString())).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("antérieure")));
        mvc.perform(prolonger(CAISSIER_A, "CAISSIER", id, "")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("Choisissez")));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(2));

        mvc.perform(prolonger(CAISSIER_A, "CAISSIER", id, JOUR.plusDays(20).toString())).andExpect(redirectedUrl("/devis/" + id));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(20));
        assertThat(listeContient("en-cours", "FD-000001")).isTrue();
        assertThat(listeContient("expire", "FD-000001")).isFalse();

        // Un devis encore valable ne se raccourcit pas.
        mvc.perform(prolonger(GERANT_A, "ADMIN", id, JOUR.plusDays(15).toString())).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("postérieure à la validité actuelle")));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(20));
        assertThat(devisDe("FD-000001").total()).isEqualByComparingTo("5000");
    }

    // ----- Annulation -----

    @Test
    void unDevisAnnuleNeSeModifiePlusNeSeTransformePlusEtNeSeReannulePas() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 2, "5000"));
        long id = idDe("FD-000001");
        mvc.perform(avec(post("/devis/" + id + "/annuler"), CAISSIER_A, "CAISSIER")).andExpect(redirectedUrl("/devis/" + id));
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.ANNULE);
        assertThat(listeContient("annule", "FD-000001")).isTrue();

        mvc.perform(avec(post("/devis/" + id + "/annuler"), GERANT_A, "ADMIN")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("annulé")));
        mvc.perform(prolonger(GERANT_A, "ADMIN", id, JOUR.plusDays(60).toString())).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("ne peut plus être modifié")));
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(30));
        mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), GERANT_A, "ADMIN"))
                .andExpect(redirectedUrl("/devis/" + id)).andExpect(flash().attribute("error", containsString("annulé")));
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 2, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("annulé")));
        assertThat(ventes.count()).isZero();
        assertThat(stock(rizA)).isEqualTo(100L);
    }

    // ----- Transformation en vente -----

    @Test
    void laTransformationCreeLaVenteAuxPrixDuDevisMemeSiLePrixDuProduitAChange() throws Exception {
        creer(GERANT_A, "ADMIN", clientA, null, null, ligne(rizA, 3, "4500"), ligne(serviceA, 1, "18000"));
        long id = idDe("FD-000001");
        // Le prix de vente du produit change après l'établissement du devis.
        rizA.setPrixVente(new BigDecimal("6000")); produits.saveAndFlush(rizA);

        var formulaire = mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), CAISSIER_A, "CAISSIER"))
                .andExpect(status().isOk()).andReturn();
        String page = formulaire.getResponse().getContentAsString();
        assertThat(page).containsPattern("chargerLigneVente\\(\\s*" + rizA.getId() + ",\\s*3,\\s*\"4500\\.00\"\\s*\\)")
                .containsPattern("chargerLigneVente\\(\\s*" + serviceA.getId() + ",\\s*1,\\s*\"18000\\.00\"\\s*\\)")
                .contains("name=\"devis\"").contains("data-devis=\"true\"").contains("FD-000001");
        Vente prerempli = (Vente) formulaire.getModelAndView().getModel().get("vente");
        assertThat(prerempli.getClient().getId()).isEqualTo(clientA.getId());
        String jeton = (String) formulaire.getModelAndView().getModel().get("jetonCreation");

        transformer(CAISSIER_A, "CAISSIER", jeton, id, ligne(rizA, 3, null), ligne(serviceA, 1, null))
                .andExpect(redirectedUrl("/ventes")).andExpect(flash().attribute("success", containsString("Vente enregistrée")));

        assertThat(ventes.count()).isEqualTo(1);
        Vente vente = ventes.findAll().getFirst();
        assertThat(prixLignesVente(vente.getId())).containsExactlyInAnyOrder("4500.00", "18000.00");
        assertThat(vente.getMontantTotal()).isEqualByComparingTo("31500");
        assertThat(vente.getNumero()).isEqualTo("FV-000001");
        // Devis établi par le gérant, vente validée par le caissier : le vendeur est celui qui valide la vente.
        assertThat(vente.getVendeur().getId()).isEqualTo(utilisateurs.findByEmail(CAISSIER_A).orElseThrow().getId());
        assertThat(stock(rizA)).isEqualTo(97L);
        assertThat(caisse.count()).isEqualTo(1);
        DevisVue devis = devisDe("FD-000001");
        assertThat(devis.statut()).isEqualTo(StatutDevis.TRANSFORME);
        assertThat(devis.venteId()).isEqualTo(vente.getId());
        assertThat(devis.total()).isEqualByComparingTo("31500");
        // Le lien vers la vente apparaît sur le devis transformé, qui ne se modifie plus.
        mvc.perform(avec(get("/devis/" + id), GERANT_A, "ADMIN")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/factures/vente/" + vente.getId())))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("boutonTransformer"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("boutonAnnulerDevis"))));
        assertThat(listeContient("transforme", "FD-000001")).isTrue();
        mvc.perform(avec(get("/devis").param("etat", "transforme"), GERANT_A, "ADMIN"))
                .andExpect(content().string(containsString("/factures/vente/" + vente.getId())));
        mvc.perform(avec(post("/devis/" + id + "/annuler"), GERANT_A, "ADMIN")).andExpect(status().isOk())
                .andExpect(model().attribute("error", containsString("transformé en vente")));
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.TRANSFORME);
    }

    @Test
    void lesPrixEnvoyesParLeNavigateurNeSontJamaisPris() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "4500"));
        long id = idDe("FD-000001");
        mvc.perform(avec(post("/ventes/enregistrer"), GERANT_A, "ADMIN").param("jetonCreation", jetonVente(GERANT_A, "ADMIN"))
                        .param("devis", Long.toString(id)).param("comptePaiement", "CAISSE")
                        .param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1")
                        .param("lignes[0].prixUnitaire", "1"))
                .andExpect(status().isBadRequest());
        assertThat(ventes.count()).isZero();
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);
    }

    @Test
    void uneSecondeTransformationEstRefuseeEtNeCreePasDeSecondeVente() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 2, "4500"));
        long id = idDe("FD-000001");
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 2, null)).andExpect(redirectedUrl("/ventes"));

        transformer(CAISSIER_A, "CAISSIER", jetonVente(CAISSIER_A, "CAISSIER"), id, ligne(rizA, 2, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("déjà été transformé")));
        mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), GERANT_A, "ADMIN"))
                .andExpect(redirectedUrl("/devis/" + id)).andExpect(flash().attribute("error", containsString("déjà été transformé")));
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(stock(rizA)).isEqualTo(98L);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(2L);
    }

    @Test
    void unDevisExpireEstRefuseSaufApresProlongation() throws Exception {
        creer(GERANT_A, "ADMIN", null, JOUR.plusDays(3).toString(), null, ligne(rizA, 2, "4500"));
        long id = idDe("FD-000001");
        // Formulaire ouvert avant l'expiration, envoyé après : le service revérifie sous verrou.
        String jeton = jetonVente(GERANT_A, "ADMIN");
        reglerHorloge(JOUR.plusDays(4));
        transformer(GERANT_A, "ADMIN", jeton, id, ligne(rizA, 2, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("a expiré le 10/11/2026")));
        mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), GERANT_A, "ADMIN"))
                .andExpect(redirectedUrl("/devis/" + id)).andExpect(flash().attribute("error", containsString("prolongez")));
        assertThat(ventes.count()).isZero();
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);

        // Le même formulaire aboutit après la prolongation (le jeton n'a pas été consommé).
        mvc.perform(prolonger(GERANT_A, "ADMIN", id, JOUR.plusDays(30).toString())).andExpect(redirectedUrl("/devis/" + id));
        transformer(GERANT_A, "ADMIN", jeton, id, ligne(rizA, 2, null)).andExpect(redirectedUrl("/ventes"));
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.TRANSFORME);
    }

    @Test
    void unManqueDeStockRefuseLaVenteEtLeDevisResteEnCours() throws Exception {
        creer(GERANT_A, "ADMIN", clientA, null, null, ligne(rizA, 500, "4500"), ligne(huileA, 1, "12000"));
        long id = idDe("FD-000001");
        String jeton = jetonVente(GERANT_A, "ADMIN");
        var refus = transformer(GERANT_A, "ADMIN", jeton, id, ligne(rizA, 500, null), ligne(huileA, 1, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString("Stock insuffisant pour Riz"))).andReturn();
        // Le formulaire réaffiche les lignes et les prix du devis.
        assertThat(refus.getResponse().getContentAsString()).containsPattern("chargerLigneVente\\(\\s*" + rizA.getId() + ",\\s*500,\\s*\"4500\\.00\"\\s*\\)")
                .contains("name=\"devis\"");
        assertThat(ventes.count()).isZero();
        assertThat(stock(rizA)).isEqualTo(100L);
        assertThat(stock(huileA)).isEqualTo(50L);
        assertThat(caisse.count()).isZero();
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);
        assertThat(devisDe("FD-000001").venteId()).isNull();
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(1L);

        // Le stock revient : le devis, resté en cours, se transforme.
        Produit riz = produits.findById(rizA.getId()).orElseThrow(); riz.setQuantite(600L); produits.saveAndFlush(riz);
        transformer(GERANT_A, "ADMIN", jeton, id, ligne(rizA, 500, null), ligne(huileA, 1, null)).andExpect(redirectedUrl("/ventes"));
        assertThat(stock(rizA)).isEqualTo(100L);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.TRANSFORME);
    }

    @Test
    void desLignesModifieesDansLeFormulaireSontRefusees() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 3, "4500"), ligne(huileA, 2, "12000"));
        long id = idDe("FD-000001");
        String message = "exactement ses lignes";
        // Ligne retirée, ajoutée, quantité changée, produit remplacé, ligne dédoublée.
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 3, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString(message)));
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 3, null), ligne(huileA, 2, null), ligne(serviceA, 1, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString(message)));
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 4, null), ligne(huileA, 2, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString(message)));
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 3, null), ligne(serviceA, 2, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString(message)));
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(rizA, 3, null), ligne(huileA, 1, null), ligne(huileA, 1, null))
                .andExpect(status().isOk()).andExpect(model().attribute("error", containsString(message)));
        assertThat(ventes.count()).isZero();
        assertThat(stock(rizA)).isEqualTo(100L);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);

        // Les mêmes lignes dans un autre ordre restent exactement celles du devis.
        transformer(GERANT_A, "ADMIN", jetonVente(GERANT_A, "ADMIN"), id, ligne(huileA, 2, null), ligne(rizA, 3, null))
                .andExpect(redirectedUrl("/ventes"));
        assertThat(prixLignesVente(ventes.findAll().getFirst().getId())).containsExactlyInAnyOrder("4500.00", "12000.00");
    }

    @Test
    void unDoubleEnvoiDuMemeFormulaireNeCreePasDeSecondeVente() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 2, "4500"));
        long id = idDe("FD-000001");
        String jeton = jetonVente(GERANT_A, "ADMIN");
        for (int i = 0; i < 2; i++) {
            transformer(GERANT_A, "ADMIN", jeton, id, ligne(rizA, 2, null)).andExpect(redirectedUrl("/ventes"))
                    .andExpect(flash().attribute("success", i == 0 ? containsString("Vente enregistrée") : containsString("déjà enregistrée")));
        }
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(stock(rizA)).isEqualTo(98L);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.TRANSFORME);
        // Le même jeton pour un autre devis serait un autre contenu : conflit, rien n'est créé.
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "4500"));
        transformer(GERANT_A, "ADMIN", jeton, idDe("FD-000002"), ligne(rizA, 1, null)).andExpect(status().isConflict());
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(devisDe("FD-000002").statut()).isEqualTo(StatutDevis.EN_COURS);
    }

    @Test
    void deuxTransformationsSimultanementDuMemeDevisNeCreentQuUneVente() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 4, "4500"));
        long id = idDe("FD-000001");
        String jetonUn = comme(GERANT_A, "ADMIN", () -> creations.ouvrir(org.example.stock.enums.TypeOperationCreation.VENTE));
        String jetonDeux = comme(CAISSIER_A, "CAISSIER", () -> creations.ouvrir(org.example.stock.enums.TypeOperationCreation.VENTE));
        CyclicBarrier depart = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Object> premier = () -> {
                depart.await(10, TimeUnit.SECONDS);
                return comme(GERANT_A, "ADMIN", () -> creations.creerVente(jetonUn, venteDeLignes(rizA, 4), id));
            };
            Callable<Object> second = () -> {
                depart.await(10, TimeUnit.SECONDS);
                return comme(CAISSIER_A, "CAISSIER", () -> creations.creerVente(jetonDeux, venteDeLignes(rizA, 4), id));
            };
            Future<Object> a = pool.submit(premier), b = pool.submit(second);
            List<Object> succes = new ArrayList<>(); List<Throwable> echecs = new ArrayList<>();
            for (Future<Object> f : List.of(a, b)) {
                try { succes.add(f.get(20, TimeUnit.SECONDS)); } catch (ExecutionException e) { echecs.add(e.getCause()); }
            }
            assertThat(succes).hasSize(1);
            assertThat(echecs).hasSize(1);
            assertThat(echecs.getFirst()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("déjà été transformé");
        }
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(stock(rizA)).isEqualTo(96L);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.TRANSFORME);
        assertThat(boutiques.findById(boutiqueA.getId()).orElseThrow().getProchainNumeroVente()).isEqualTo(2L);
    }

    @Test
    void uneAnnulationEtUneTransformationSimultaneesNeLaissentQuUneIssue() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 4, "4500"));
        long id = idDe("FD-000001");
        String jeton = comme(GERANT_A, "ADMIN", () -> creations.ouvrir(org.example.stock.enums.TypeOperationCreation.VENTE));
        CyclicBarrier depart = new CyclicBarrier(2);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Object> vente = pool.submit(() -> {
                depart.await(10, TimeUnit.SECONDS);
                try { return comme(GERANT_A, "ADMIN", () -> creations.creerVente(jeton, venteDeLignes(rizA, 4), id)); }
                catch (IllegalArgumentException e) { return e; }
            });
            Future<Object> annulation = pool.submit(() -> {
                depart.await(10, TimeUnit.SECONDS);
                try { comme(CAISSIER_A, "CAISSIER", () -> { devisService.annuler(id); return null; }); return "annule"; }
                catch (IllegalArgumentException e) { return e; }
            });
            Object resultatVente = vente.get(20, TimeUnit.SECONDS), resultatAnnulation = annulation.get(20, TimeUnit.SECONDS);
            // Exactement une des deux opérations aboutit.
            assertThat(resultatVente instanceof IllegalArgumentException).isNotEqualTo(resultatAnnulation instanceof IllegalArgumentException);
        }
        DevisVue devis = devisDe("FD-000001");
        assertThat(devis.statut()).isIn(StatutDevis.TRANSFORME, StatutDevis.ANNULE);
        assertThat(ventes.count()).isEqualTo(devis.statut() == StatutDevis.TRANSFORME ? 1 : 0);
        assertThat(stock(rizA)).isEqualTo(devis.statut() == StatutDevis.TRANSFORME ? 96L : 100L);
    }

    @Autowired DevisService devisService;

    // ----- Impression -----

    @Test
    void lImpressionNeContientAucuneInformationDePaiement() throws Exception {
        creer(GERANT_A, "ADMIN", clientA, JOUR.plusDays(15).toString(), "Livraison incluse", ligne(rizA, 2, "4500"), ligne(serviceA, 1, "20000"));
        long id = idDe("FD-000001");
        String page = mvc.perform(avec(get("/devis/" + id + "/imprimer"), CAISSIER_A, "CAISSIER")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Devis FD-000001").contains("Ce devis n'est pas une facture")
                .contains("22/11/2026").contains("Mamadou").contains("Livraison incluse").contains("29 000.00 GNF")
                .contains("4 500.00 GNF").contains("Riz");
        assertThat(page.toLowerCase()).doesNotContain("versé").doesNotContain("reste à payer").doesNotContain("réglée")
                .doesNotContain("échéance").doesNotContain("à régler").doesNotContain("avance").doesNotContain("paiement");
        assertThat(page).doesNotContain("Facture de vente");
    }

    // ----- Isolation entre boutiques -----

    @Test
    void unDevisUnClientOuUnProduitDUneAutreBoutiqueSontIntrouvables() throws Exception {
        creer(GERANT_A, "ADMIN", clientA, null, null, ligne(rizA, 2, "4500"));
        long id = idDe("FD-000001");
        for (String chemin : List.of("/devis/" + id, "/devis/" + id + "/imprimer")) {
            mvc.perform(avec(get(chemin), GERANT_B, "ADMIN")).andExpect(status().isNotFound());
        }
        mvc.perform(prolonger(GERANT_B, "ADMIN", id, JOUR.plusDays(40).toString())).andExpect(status().isNotFound());
        mvc.perform(avec(post("/devis/" + id + "/annuler"), GERANT_B, "ADMIN")).andExpect(status().isNotFound());
        mvc.perform(avec(get("/ventes/nouveau").param("devis", Long.toString(id)), GERANT_B, "ADMIN")).andExpect(status().isNotFound());
        transformer(GERANT_B, "ADMIN", jetonVente(GERANT_B, "ADMIN"), id, ligne(rizB, 2, null)).andExpect(status().isNotFound());
        mvc.perform(avec(get("/devis"), GERANT_B, "ADMIN")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("FD-000001"))));
        assertThat(ventes.count()).isZero();
        assertThat(stock(rizB)).isEqualTo(100L);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);

        // Client ou produit d'une autre boutique dans un devis : 404, rien n'est créé, aucun numéro consommé.
        creer(GERANT_B, "ADMIN", clientA, null, null, ligne(rizB, 1, "5000")).andExpect(status().isNotFound());
        creer(GERANT_B, "ADMIN", null, null, null, ligne(rizA, 1, "5000")).andExpect(status().isNotFound());
        creer(GERANT_A, "ADMIN", clientB, null, null, ligne(rizA, 1, "5000")).andExpect(status().isNotFound());
        assertThat(numeros(boutiqueB)).isEmpty();
        assertThat(boutiques.findById(boutiqueB.getId()).orElseThrow().getProchainNumeroDevis()).isEqualTo(1L);
        assertThat(numeros(boutiqueA)).containsExactly("FD-000001");
    }

    // ----- Droits, CSRF, champs inconnus, menu -----

    @Test
    void legerantEtLeCaissierPeuventUtiliserTousLesEcransEtUnAnonymeEstRenvoyeVersLaConnexion() throws Exception {
        for (String[] compte : new String[][]{{GERANT_A, "ADMIN"}, {CAISSIER_A, "CAISSIER"}}) {
            String email = compte[0], role = compte[1];
            creer(email, role, null, null, null, ligne(rizA, 1, "5000")).andExpect(redirectedUrlPattern("/devis/*"));
            long id = idDe(numeros(boutiqueA).getLast());
            mvc.perform(avec(get("/devis"), email, role)).andExpect(status().isOk());
            mvc.perform(avec(get("/devis/nouveau"), email, role)).andExpect(status().isOk());
            mvc.perform(avec(get("/devis/" + id), email, role)).andExpect(status().isOk());
            mvc.perform(avec(get("/devis/" + id + "/imprimer"), email, role)).andExpect(status().isOk());
            mvc.perform(prolonger(email, role, id, JOUR.plusDays(45).toString())).andExpect(redirectedUrl("/devis/" + id));
            mvc.perform(avec(post("/devis/" + id + "/annuler"), email, role)).andExpect(redirectedUrl("/devis/" + id));
        }
        assertThat(numeros(boutiqueA)).hasSize(2);
        mvc.perform(get("/devis")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/devis/nouveau")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/devis/1/imprimer")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/devis/enregistrer").with(csrf())).andExpect(status().is3xxRedirection());
    }

    @Test
    void lesFormulairesDeDevisExigentLeJetonCsrf() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "5000"));
        long id = idDe("FD-000001");
        mvc.perform(post("/devis/enregistrer").with(user(GERANT_A).roles("ADMIN")).param("lignes[0].produit.id", rizA.getId().toString())
                .param("lignes[0].quantite", "1").param("lignes[0].prixUnitaire", "5")).andExpect(status().isForbidden());
        mvc.perform(post("/devis/" + id + "/prolonger").with(user(GERANT_A).roles("ADMIN")).param("dateValidite", JOUR.plusDays(50).toString()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/devis/" + id + "/annuler").with(user(GERANT_A).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/devis/" + id + "/annuler").with(user(GERANT_A).roles("ADMIN")).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(numeros(boutiqueA)).hasSize(1);
        assertThat(devisDe("FD-000001").statut()).isEqualTo(StatutDevis.EN_COURS);
    }

    @Test
    void lesChampsInconnusDesNouveauxFormulairesSontRejetesEn400() throws Exception {
        creer(GERANT_A, "ADMIN", null, null, null, ligne(rizA, 1, "5000"));
        long id = idDe("FD-000001");
        for (String[] intrus : new String[][]{{"statut", "TRANSFORME"}, {"montantTotal", "1"}, {"vente.id", "1"}, {"dateDevis", "2020-01-01"},
                {"numero", "X"}, {"utilisateur", "1"}, {"boutique.id", "9"}, {"lignes[0].produit.nom", "Riz"}, {"id", "3"}}) {
            mvc.perform(avec(post("/devis/enregistrer"), GERANT_A, "ADMIN").param("lignes[0].produit.id", rizA.getId().toString())
                    .param("lignes[0].quantite", "1").param("lignes[0].prixUnitaire", "5").param(intrus[0], intrus[1])).andExpect(status().isBadRequest());
            mvc.perform(avec(post("/devis/" + id + "/prolonger"), GERANT_A, "ADMIN").param("dateValidite", JOUR.plusDays(50).toString())
                    .param(intrus[0], intrus[1])).andExpect(status().isBadRequest());
        }
        mvc.perform(avec(post("/devis/" + id + "/annuler"), GERANT_A, "ADMIN").param("statut", "ANNULE")).andExpect(status().isBadRequest());
        // Valeurs multiples d'un champ unique.
        mvc.perform(avec(post("/devis/enregistrer"), GERANT_A, "ADMIN").param("lignes[0].produit.id", rizA.getId().toString())
                .param("lignes[0].quantite", "1").param("lignes[0].prixUnitaire", "5")
                .param("dateValidite", JOUR.plusDays(3).toString(), JOUR.plusDays(4).toString())).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/devis/" + id + "/prolonger"), GERANT_A, "ADMIN")
                .param("dateValidite", JOUR.plusDays(50).toString(), JOUR.plusDays(60).toString())).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/devis/enregistrer"), GERANT_A, "ADMIN").param("lignes[0].produit.id", rizA.getId().toString())
                .param("lignes[0].quantite", "1").param("lignes[0].prixUnitaire", "5").param("lignes[300].quantite", "1")).andExpect(status().isBadRequest());
        // Vente : le paramètre devis est unique et numérique.
        String jeton = jetonVente(GERANT_A, "ADMIN");
        mvc.perform(avec(post("/ventes/enregistrer"), GERANT_A, "ADMIN").param("jetonCreation", jeton).param("comptePaiement", "CAISSE")
                .param("devis", "1", "2").param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1")).andExpect(status().isBadRequest());
        mvc.perform(avec(post("/ventes/enregistrer"), GERANT_A, "ADMIN").param("jetonCreation", jeton).param("comptePaiement", "CAISSE")
                .param("devis", "abc").param("lignes[0].produit.id", rizA.getId().toString()).param("lignes[0].quantite", "1")).andExpect(status().isBadRequest());
        // Le paramètre devis n'existe pas sur la création d'un achat ni sur un règlement.
        mvc.perform(avec(post("/ventes/regler/1"), GERANT_A, "ADMIN").param("devis", "1")).andExpect(status().isBadRequest());
        assertThat(ventes.count()).isZero();
        assertThat(devisDe("FD-000001").validite()).isEqualTo(JOUR.plusDays(30));
        assertThat(numeros(boutiqueA)).hasSize(1);
    }

    @Test
    void leMenuLateralPropose_Devis_PresDeVentes() throws Exception {
        String page = mvc.perform(avec(get("/devis"), CAISSIER_A, "CAISSIER")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int ventesIndex = page.indexOf("/ventes\"");
        int devisIndex = page.indexOf("href=\"/devis\"");
        assertThat(ventesIndex).isPositive();
        assertThat(devisIndex).isGreaterThan(ventesIndex);
        assertThat(page).containsPattern("href=\"/devis\"[^>]*aria-current=\"page\"");
    }

    // ----- Outils -----

    record Ligne(Produit produit, int quantite, String prix) {}
    record DevisVue(String numero, StatutDevis statut, Long venteId, LocalDate validite, BigDecimal total) {}
    record DevisFormView(Object form) {
        LocalDate validite() {
            try { return (LocalDate) form.getClass().getMethod("getDateValidite").invoke(form); } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }

    private static Ligne ligne(Produit produit, int quantite, String prix) { return new Ligne(produit, quantite, prix); }

    private ResultActions creer(String email, String role, Client client, String validite, String note, Ligne... lignes) throws Exception {
        MockHttpServletRequestBuilder requete = avec(post("/devis/enregistrer"), email, role);
        if (client != null) requete.param("client", client.getId().toString());
        if (validite != null) requete.param("dateValidite", validite);
        if (note != null) requete.param("note", note);
        for (int i = 0; i < lignes.length; i++) {
            requete.param("lignes[" + i + "].produit.id", lignes[i].produit().getId().toString())
                    .param("lignes[" + i + "].quantite", Integer.toString(lignes[i].quantite()));
            if (lignes[i].prix() != null) requete.param("lignes[" + i + "].prixUnitaire", lignes[i].prix());
        }
        return mvc.perform(requete);
    }

    private ResultActions transformer(String email, String role, String jeton, long devisId, Ligne... lignes) throws Exception {
        MockHttpServletRequestBuilder requete = avec(post("/ventes/enregistrer"), email, role)
                .param("jetonCreation", jeton).param("devis", Long.toString(devisId)).param("comptePaiement", "CAISSE");
        for (int i = 0; i < lignes.length; i++) {
            requete.param("lignes[" + i + "].produit.id", lignes[i].produit().getId().toString())
                    .param("lignes[" + i + "].quantite", Integer.toString(lignes[i].quantite()));
        }
        return mvc.perform(requete);
    }

    private MockHttpServletRequestBuilder prolonger(String email, String role, long id, String date) {
        return avec(post("/devis/" + id + "/prolonger"), email, role).param("dateValidite", date);
    }

    private String jetonVente(String email, String role) throws Exception {
        return (String) mvc.perform(avec(get("/ventes/nouveau"), email, role)).andExpect(status().isOk())
                .andReturn().getModelAndView().getModel().get("jetonCreation");
    }

    private boolean listeContient(String etat, String numero) throws Exception {
        MockHttpServletRequestBuilder requete = avec(get("/devis"), GERANT_A, "ADMIN");
        if (etat != null) requete.param("etat", etat);
        return mvc.perform(requete).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().contains(">" + numero + "<");
    }

    private MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) {
        return requete.with(user(email).roles(role)).with(csrf());
    }

    private List<String> numeros(Boutique boutique) {
        return devisRepo.findAll().stream().filter(d -> d.getBoutique().getId().equals(boutique.getId())).map(Devis::getNumero).sorted().toList();
    }

    private long idDe(String numero) {
        return devisRepo.findAll().stream().filter(d -> d.getNumero().equals(numero) && d.getBoutique().getId().equals(boutiqueA.getId()))
                .findFirst().orElseThrow().getId();
    }

    private DevisVue devisDe(String numero) {
        return tx.execute(status -> {
            Devis d = devisRepo.findAll().stream().filter(x -> x.getNumero().equals(numero) && x.getBoutique().getId().equals(boutiqueA.getId()))
                    .findFirst().orElseThrow();
            return new DevisVue(d.getNumero(), d.getStatut(), d.getVente() == null ? null : d.getVente().getId(), d.getDateValidite(), d.getMontantTotal());
        });
    }

    private List<String> prixLignesVente(Long venteId) {
        return tx.execute(status -> ventes.findById(venteId).orElseThrow().getLignes().stream()
                .map(l -> l.getPrixUnitaire().toPlainString()).sorted().toList());
    }

    private Vente venteDeLignes(Produit produit, int quantite) {
        Vente vente = new Vente();
        DetailVente ligne = new DetailVente(); Produit reference = new Produit(); reference.setId(produit.getId());
        ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private long stock(Produit produit) { return produits.findById(produit.getId()).orElseThrow().getQuantite(); }

    private void reglerHorloge(LocalDate jour) {
        Instant instant = jour.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        when(horloge.instant()).thenReturn(instant);
        when(horloge.getZone()).thenReturn(ZoneId.systemDefault());
        when(horloge.withZone(any())).thenReturn(horloge);
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
    private Client client(Boutique boutique, String nom) {
        Client c = new Client(); c.setNom(nom); c.setBoutique(boutique); return clients.saveAndFlush(c);
    }
    private Produit article(Boutique boutique, Categorie categorie, String nom, Long quantite, String prixVente) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(nom.toUpperCase());
        p.setQuantite(quantite); p.setUnite("sac"); p.setPrixDefinis(true);
        p.setPrixAchat(new BigDecimal("4000")); p.setPrixVente(new BigDecimal(prixVente)); p.setCoutMoyen(new BigDecimal("4000"));
        return produits.saveAndFlush(p);
    }
    private Produit service(Boutique boutique, Categorie categorie, String nom, String prixVente) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom); p.setReference(nom.toUpperCase());
        p.setNature(NatureProduit.SERVICE); p.setQuantite(0L); p.setPrixDefinis(true);
        p.setPrixAchat(BigDecimal.ZERO); p.setPrixVente(new BigDecimal(prixVente)); p.setCoutMoyen(BigDecimal.ZERO);
        return produits.saveAndFlush(p);
    }
}
