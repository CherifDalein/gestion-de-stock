package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reglements-vente-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@WithMockUser(username = "paiement@example.test", roles = "CAISSIER")
class ReglementVenteTests {
    @Autowired MockMvc mvc;
    @Autowired VenteService venteService;
    @Autowired VenteRepository ventes;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @MockitoSpyBean CaisseService caisseService;
    Produit produit;
    Vente vente;

    @BeforeEach
    void preparer() {
        caisse.deleteAll(); ventes.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        Utilisateur admin = new Utilisateur(); admin.setNom("Caissier test");
        admin.setEmail("paiement@example.test"); admin.setRole(Role.CAISSIER); utilisateurs.save(admin);
        Categorie categorie = new Categorie(); categorie.setNom("Catégorie"); categorie = categories.save(categorie);
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur test"); fournisseur = fournisseurs.save(fournisseur);
        produit = new Produit(); produit.setNom("Produit test"); produit.setReference("REF-PAIEMENT");
        produit.setQuantite(10L); produit.setPrixAchat(new BigDecimal("5000.00")); produit.setPrixVente(new BigDecimal("10000.00"));
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); produit = produits.saveAndFlush(produit);
        Client client = new Client(); client.setNom("Client test"); client = clients.save(client);
        Vente nouveau = new Vente(); nouveau.setClient(client); nouveau.setMontantVerse(new BigDecimal("50000.00"));
        nouveau.getLignes().add(ligne(10)); vente = venteService.effectuerVente(nouveau);
    }

    @Test
    void laVentePeutEtreSoldeeSansChangerLaFactureNiLeStock() throws Exception {
        Produit avant = produits.findById(produit.getId()).orElseThrow();
        List<Long> lignesAvant = idsLignes();
        mvc.perform(versement("50000", "50000").with(csrf()))
                .andExpect(redirectedUrl("/ventes/regler/" + vente.getId()))
                .andExpect(flash().attribute("success", "Versement client enregistré avec succès."));
        Vente apres = ventes.findById(vente.getId()).orElseThrow();
        assertThat(apres.getMontantVerse()).isEqualByComparingTo("100000.0");
        assertThat(apres.getResteAPayer()).isZero();
        assertThat(apres.getDateVente()).isEqualTo(vente.getDateVente());
        assertThat(apres.getClient().getId()).isEqualTo(vente.getClient().getId());
        assertThat(idsLignes()).isEqualTo(lignesAvant);
        Produit stock = produits.findById(produit.getId()).orElseThrow();
        assertThat(stock.getQuantite()).isZero();
        assertThat(stock.getVersion()).isEqualTo(avant.getVersion());
        assertThat(stock.getPrixVente()).isEqualByComparingTo("10000.0");
        var reglements = caisse.findByVenteIdOrderByDateMouvementDescIdDesc(vente.getId());
        assertThat(reglements).hasSize(2);
        assertThat(reglements.getFirst().getMontant()).isEqualByComparingTo("50000.0");
        assertThat(reglements.getFirst().getMotif()).contains("Règlement Vente #" + vente.getId());
        assertThat(reglements.getFirst().getDateMouvement()).isNotNull();
        assertThat(reglements.getFirst().getUtilisateur().getEmail()).isEqualTo("paiement@example.test");
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("100000.0");
        String html = mvc.perform(get("/ventes/regler/" + vente.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Cette vente est entièrement réglée", "Caissier test")
                .doesNotContain("id=\"montantReglement\"");
        mvc.perform(get("/factures/vente/" + vente.getId())).andExpect(status().isOk());
    }

    @Test
    void unVersementPartielAjouteSeulementLaSommePayeeMaintenant() throws Exception {
        mvc.perform(versement("20000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("70000.0");
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("30000.0");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(caisse.findByVenteIdOrderByDateMouvementDescIdDesc(vente.getId()).getFirst().getMontant())
                .isEqualByComparingTo("20000.0");
    }

    @Test
    void repeterLeMemeFormulaireNeDoublePasLeVersement() throws Exception {
        mvc.perform(versement("10000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(versement("10000", "50000").with(csrf()))
                .andExpect(status().isConflict()).andExpect(model().attributeHasErrors("reglementVente"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Un versement a déjà été enregistré")));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("60000.0");
        assertThat(caisse.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("60000.0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "50000.01", "9999999999999999", "1.001", ""})
    void unMontantInvalideNeChangeNiDetteNiCaisse(String montant) throws Exception {
        mvc.perform(versement(montant, "50000").with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("reglementVente"));
        verifierInitial();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "abc"})
    void uneConversionNumeriqueInvalideEstRefusee(String montant) throws Exception {
        mvc.perform(versement(montant, "50000").with(csrf())).andExpect(status().isBadRequest());
        verifierInitial();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "montantVerse", "montantTotal", "vente.id", "client.id", "lignes[0].quantite"})
    void leReglementRefuseLesChampsInternes(String champ) throws Exception {
        mvc.perform(versement("10000", "50000").with(csrf()).param(champ, "1"))
                .andExpect(status().isBadRequest());
        verifierInitial();
    }

    @Test
    void leFormulaireEtLeJournalProposentLeReglementAvecJetonCsrf() throws Exception {
        String html = mvc.perform(get("/ventes/regler/" + vente.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Somme payée maintenant", "name=\"_csrf\"", "name=\"montantVerseAttendu\"", "50000.00")
                .doesNotContain("name=\"montantVerse\"", "name=\"lignes[0].quantite\"");
        String journal = mvc.perform(get("/ventes")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(journal).contains("/ventes/regler/" + vente.getId(), "Régler");
        verifierInitial();
    }

    @Test
    void unPostSansCsrfEstRefuse() throws Exception {
        mvc.perform(versement("10000", "50000")).andExpect(status().isForbidden());
        verifierInitial();
    }

    @Test
    void unJetonInvalideOuUneSessionAnonymeNePeutPasEncaisser() throws Exception {
        mvc.perform(versement("10000", "50000").with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(get("/ventes/regler/" + vente.getId()).with(SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is3xxRedirection());
        mvc.perform(versement("10000", "50000").with(csrf()).with(SecurityMockMvcRequestPostProcessors.anonymous()))
                .andExpect(status().is3xxRedirection());
        verifierInitial();
    }

    @Test
    void unAutreCaissierPeutEncaisserEtDevientAuteurDuVersement() throws Exception {
        Utilisateur autre = new Utilisateur(); autre.setNom("Autre caissier"); autre.setEmail("autre@example.test");
        autre.setRole(Role.CAISSIER); utilisateurs.save(autre);
        mvc.perform(versement("10000", "50000").with(csrf())
                .with(SecurityMockMvcRequestPostProcessors.user("autre@example.test").roles("CAISSIER")))
                .andExpect(status().is3xxRedirection());
        assertThat(caisse.findByVenteIdOrderByDateMouvementDescIdDesc(vente.getId()).getFirst()
                .getUtilisateur().getEmail()).isEqualTo("autre@example.test");
    }

    @Test
    void unAuteurAbsentAnnuleLeVersement() {
        SecurityContext contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "absent@example.test", "", List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CAISSIER"))));
        SecurityContext precedent = SecurityContextHolder.getContext();
        try {
            assertThatThrownBy(() -> authentifie(contexte, () -> venteService.reglerVente(vente.getId(),
                    new BigDecimal("10000"), new BigDecimal("50000"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Utilisateur introuvable");
        } finally { SecurityContextHolder.setContext(precedent); }
        verifierInitial();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-1", "50000.001", "1000000000000000"})
    void unEtatAttenduInvalideEstRefuse(String attendu) throws Exception {
        mvc.perform(versement("10000", attendu).with(csrf())).andExpect(status().isOk())
                .andExpect(model().attributeHasErrors("reglementVente"));
        verifierInitial();
    }

    @Test
    void deuxCopiesConcurrentesNeCreentQuUneEntreeDeCaisse() throws Exception {
        SecurityContext contexte = SecurityContextHolder.getContext();
        CountDownLatch verrouPris = new CountDownLatch(1), liberer = new CountDownLatch(1), secondDemarre = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> premier = pool.submit(() -> authentifie(contexte, () ->
                    new TransactionTemplate(transactions).executeWithoutResult(status -> {
                        entityManager.find(Vente.class, vente.getId(), LockModeType.PESSIMISTIC_WRITE);
                        verrouPris.countDown(); attendre(liberer);
                        venteService.reglerVente(vente.getId(), new BigDecimal("10000"), new BigDecimal("50000"));
                    })));
            attendre(verrouPris);
            Future<?> second = pool.submit(() -> authentifie(contexte, () -> {
                secondDemarre.countDown();
                venteService.reglerVente(vente.getId(), new BigDecimal("10000"), new BigDecimal("50000"));
            }));
            try {
                attendre(secondDemarre);
                assertThatThrownBy(() -> second.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { liberer.countDown(); }
            premier.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ReglementVenteObsoleteException.class);
        } finally { liberer.countDown(); }
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("60000");
        assertThat(caisse.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("60000");
    }

    @Test
    void unProduitVerrouilleNeBloquePasLeReglement() throws Exception {
        SecurityContext contexte = SecurityContextHolder.getContext();
        CountDownLatch verrouPris = new CountDownLatch(1), liberer = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> stock = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                entityManager.find(Produit.class, produit.getId(), LockModeType.PESSIMISTIC_WRITE);
                verrouPris.countDown(); attendre(liberer);
            }));
            attendre(verrouPris);
            try {
                pool.submit(() -> authentifie(contexte, () -> venteService.reglerVente(vente.getId(),
                        new BigDecimal("10000"), new BigDecimal("50000")))).get(3, TimeUnit.SECONDS);
            } finally { liberer.countDown(); }
            stock.get(10, TimeUnit.SECONDS);
        } finally { liberer.countDown(); }
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
    }

    @Test
    void leCumulDejaChargeEstReluSousVerrou() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            Vente ancienne = ventes.findById(vente.getId()).orElseThrow();
            TransactionTemplate autre = new TransactionTemplate(transactions);
            autre.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            autre.executeWithoutResult(s -> venteService.reglerVente(vente.getId(), new BigDecimal("10000"), new BigDecimal("50000")));
            assertThat(ancienne.getMontantVerse()).isEqualByComparingTo("50000");
            assertThatThrownBy(() -> venteService.reglerVente(vente.getId(), new BigDecimal("10000"), new BigDecimal("50000")))
                    .isInstanceOf(ReglementVenteObsoleteException.class);
            // L'appel rejeté marque cette transaction pour annulation.
            status.setRollbackOnly();
        });
        assertThat(caisse.count()).isEqualTo(2);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("60000");
    }

    @Test
    void uneVenteSansClientAccepteLeReglementEtSonHistorique() throws Exception {
        vente.setClient(null); ventes.saveAndFlush(vente);
        mvc.perform(get("/ventes/regler/" + vente.getId())).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Client de passage")));
        mvc.perform(versement("50000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(versement("1", "100000").with(csrf())).andExpect(status().isOk())
                .andExpect(model().attributeHasErrors("reglementVente"));
        assertThat(caisse.count()).isEqualTo(2);
    }

    @Test
    @WithMockUser(username = "paiement@example.test", roles = "ADMIN")
    void administrateurPeutAussiEnregistrerUnReglement() throws Exception {
        mvc.perform(get("/ventes/regler/" + vente.getId())).andExpect(status().isOk());
        mvc.perform(versement("10000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("60000");
    }

    @Test
    @WithMockUser(roles = "AUTRE")
    void unRoleInconnuNePeutNiVoirNiPayerUneVente() throws Exception {
        mvc.perform(get("/ventes/regler/" + vente.getId())).andExpect(status().isForbidden());
        mvc.perform(versement("10000", "50000").with(csrf())).andExpect(status().isForbidden());
        verifierInitial();
    }

    @Test
    void uneVenteInexistanteNeCreeAucunMouvement() throws Exception {
        mvc.perform(get("/ventes/regler/9999999")).andExpect(status().isNotFound());
        mvc.perform(post("/ventes/regler/9999999").with(csrf()).param("montant", "1").param("montantVerseAttendu", "0"))
                .andExpect(status().isNotFound());
        verifierInitial();
    }

    @Test
    void uneDetteAvecAncienVersementNullPeutEtrePayee() {
        vente.setMontantVerse(null); ventes.saveAndFlush(vente);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("100000.0");
        venteService.reglerVente(vente.getId(), new BigDecimal("10000"), BigDecimal.ZERO);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("10000.0");
    }

    @Test
    void leServiceRefuseAussiZeroEtLesVersementsExcessifs() {
        assertThatThrownBy(() -> venteService.reglerVente(vente.getId(), BigDecimal.ZERO, new BigDecimal("50000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> venteService.reglerVente(vente.getId(), new BigDecimal("50001"), new BigDecimal("50000")))
                .hasMessageContaining("reste à payer");
        verifierInitial();
    }

    @Test
    void uneErreurApresEcritureDeCaisseAnnuleAussiLeVersement() {
        CaisseService cible = AopTestUtils.getUltimateTargetObject(caisseService);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            caisse.flush();
            throw new IllegalStateException("Erreur de caisse simulée");
        }).when(cible).enregistrerEntreeVente(any(BigDecimal.class), anyString(), any(Vente.class), any(Utilisateur.class));
        assertThatThrownBy(() -> venteService.reglerVente(vente.getId(), new BigDecimal("10000"), new BigDecimal("50000")))
                .hasMessage("Erreur de caisse simulée");
        verifierInitial();
    }

    @Test
    void lesPetitsVersementsDecimauxSAdditionnentSansArrondiIntermediaire() throws Exception {
        mvc.perform(versement("0.10", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(versement("0.20", "50000.10").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("50000.3");
        assertThat(caisse.findByVenteIdOrderByDateMouvementDescIdDesc(vente.getId()))
                .extracting(MouvementCaisse::getMontant).containsExactly(new BigDecimal("0.20"), new BigDecimal("0.10"), new BigDecimal("50000.00"));
    }

    @Test
    void unGrandPaiementConserveDesCentimesQuiEtaientPerdusEnDouble() {
        vente.setMontantTotal(new BigDecimal("999999999999999.99")); ventes.saveAndFlush(vente);
        venteService.reglerVente(vente.getId(), new BigDecimal("999999999949999.99"), new BigDecimal("50000"));
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse())
                .isEqualByComparingTo("999999999999999.99");
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isZero();
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("999999999999999.99");
    }

    private MockHttpServletRequestBuilder versement(String montant, String attendu) {
        return post("/ventes/regler/" + vente.getId()).param("montant", montant).param("montantVerseAttendu", attendu);
    }

    private DetailVente ligne(int quantite) {
        DetailVente ligne = new DetailVente(); Produit reference = new Produit(); reference.setId(produit.getId());
        ligne.setProduit(reference); ligne.setQuantite(quantite); return ligne;
    }

    private List<Long> idsLignes() {
        return new TransactionTemplate(transactions).execute(status -> {
            var lignes = ventes.findById(vente.getId()).orElseThrow().getLignes();
            assertThat(lignes).hasSize(1);
            assertThat(lignes.getFirst().getQuantite()).isEqualTo(10);
            assertThat(lignes.getFirst().getPrixUnitaire()).isEqualByComparingTo("10000.0");
            return lignes.stream().map(DetailVente::getId).toList();
        });
    }

    private void verifierInitial() {
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("50000.0");
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualByComparingTo("50000.0");
    }

    private void attendre(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Synchronisation expirée"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }

    private void authentifie(SecurityContext contexte, Runnable action) {
        SecurityContextHolder.setContext(contexte);
        try { action.run(); } finally { SecurityContextHolder.clearContext(); }
    }
}
