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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:reglements-test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
@WithMockUser(username = "paiement@example.test", roles = "ADMIN")
class ReglementAchatTests {
    @Autowired MockMvc mvc;
    @Autowired AchatService achatService;
    @Autowired VenteService venteService;
    @Autowired AchatRepository achats;
    @Autowired VenteRepository ventes;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired PlatformTransactionManager transactions;
    @SpyBean CaisseService caisseService;
    Produit produit;
    Achat achat;

    @BeforeEach
    void preparer() {
        ventes.deleteAll(); caisse.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); utilisateurs.deleteAll();
        Utilisateur admin = new Utilisateur(); admin.setNom("Administrateur test");
        admin.setEmail("paiement@example.test"); admin.setRole(Role.ADMIN); utilisateurs.save(admin);
        Categorie categorie = new Categorie(); categorie.setNom("Catégorie"); categorie = categories.save(categorie);
        Fournisseur fournisseur = new Fournisseur(); fournisseur.setNom("Fournisseur test"); fournisseur = fournisseurs.save(fournisseur);
        produit = new Produit(); produit.setNom("Produit test"); produit.setReference("REF-PAIEMENT");
        produit.setQuantite(0L); produit.setPrixAchat(10000.0); produit.setPrixVente(20000.0);
        produit.setCategorie(categorie); produit.setFournisseur(fournisseur); produit = produits.saveAndFlush(produit);
        Achat nouveau = new Achat(); nouveau.setFournisseur(fournisseur); nouveau.setMontantVerse(50000.0);
        nouveau.getLignes().add(ligne(10)); achat = achatService.enregistrerAchat(nouveau);
    }

    @Test
    void leFournisseurPeutEtreSoldeApresLaVenteDeHuitProduits() throws Exception {
        vendre(8);
        Produit avant = produits.findById(produit.getId()).orElseThrow();
        List<Long> lignesAvant = idsLignes();
        mvc.perform(versement("50000", "50000").with(csrf()))
                .andExpect(redirectedUrl("/achats/regler/" + achat.getId()))
                .andExpect(flash().attribute("success", "Versement fournisseur enregistré avec succès."));
        Achat apres = achats.findById(achat.getId()).orElseThrow();
        assertThat(apres.getMontantVerse()).isEqualTo(100000.0);
        assertThat(apres.getResteAPayer()).isZero();
        assertThat(idsLignes()).isEqualTo(lignesAvant);
        Produit stock = produits.findById(produit.getId()).orElseThrow();
        assertThat(stock.getQuantite()).isEqualTo(2L);
        assertThat(stock.getVersion()).isEqualTo(avant.getVersion());
        assertThat(stock.getPrixAchat()).isEqualTo(10000.0);
        var reglements = caisse.findByAchatIdOrderByDateMouvementDescIdDesc(achat.getId());
        assertThat(reglements).hasSize(2);
        assertThat(reglements.getFirst().getMontant()).isEqualTo(-50000.0);
        assertThat(reglements.getFirst().getMotif()).contains("Règlement Achat #" + achat.getId());
        assertThat(reglements.getFirst().getDateMouvement()).isNotNull();
        assertThat(reglements.getFirst().getUtilisateur().getEmail()).isEqualTo("paiement@example.test");
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(60000.0);
        String html = mvc.perform(get("/achats/regler/" + achat.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Cet achat est entièrement réglé", "Administrateur test")
                .doesNotContain("id=\"montantReglement\"");
        mvc.perform(get("/factures/achat/" + achat.getId())).andExpect(status().isOk());
    }

    @Test
    void unVersementPartielAjouteSeulementLaSommePayeeMaintenant() throws Exception {
        vendre(10);
        mvc.perform(versement("20000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(70000.0);
        assertThat(achats.findById(achat.getId()).orElseThrow().getResteAPayer()).isEqualTo(30000.0);
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isZero();
        assertThat(caisse.findByAchatIdOrderByDateMouvementDescIdDesc(achat.getId()).getFirst().getMontant())
                .isEqualTo(-20000.0);
    }

    @Test
    void repeterLeMemeFormulaireNeDoublePasLeVersement() throws Exception {
        mvc.perform(versement("10000", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(versement("10000", "50000").with(csrf()))
                .andExpect(status().isConflict()).andExpect(model().attributeHasErrors("reglementAchat"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Un versement a déjà été enregistré")));
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(60000.0);
        assertThat(caisse.count()).isEqualTo(2);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(-60000.0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "50000.01", "9999999999999999", "1.001", ""})
    void unMontantInvalideNeChangeNiDetteNiCaisse(String montant) throws Exception {
        mvc.perform(versement(montant, "50000").with(csrf()))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("reglementAchat"));
        verifierInitial();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "abc"})
    void uneConversionNumeriqueInvalideEstRefusee(String montant) throws Exception {
        mvc.perform(versement(montant, "50000").with(csrf())).andExpect(status().isBadRequest());
        verifierInitial();
    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "montantVerse", "montantTotal", "achat.id", "fournisseur.id", "lignes[0].quantite"})
    void leReglementRefuseLesChampsInternes(String champ) throws Exception {
        mvc.perform(versement("10000", "50000").with(csrf()).param(champ, "1"))
                .andExpect(status().isBadRequest());
        verifierInitial();
    }

    @Test
    void leFormulaireEtLeJournalProposentLeReglementAvecJetonCsrf() throws Exception {
        String html = mvc.perform(get("/achats/regler/" + achat.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Somme payée maintenant", "name=\"_csrf\"", "name=\"montantVerseAttendu\"", "50000.0")
                .doesNotContain("name=\"montantVerse\"", "name=\"lignes[0].quantite\"");
        String journal = mvc.perform(get("/achats")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(journal).contains("/achats/regler/" + achat.getId(), "Régler");
        verifierInitial();
    }

    @Test
    void unPostSansCsrfEstRefuse() throws Exception {
        mvc.perform(versement("10000", "50000")).andExpect(status().isForbidden());
        verifierInitial();
    }

    @Test
    @WithMockUser(username = "paiement@example.test", roles = "CAISSIER")
    void leCaissierNePeutNiVoirNiPayerUnAchat() throws Exception {
        mvc.perform(get("/achats/regler/" + achat.getId())).andExpect(status().isForbidden());
        mvc.perform(versement("10000", "50000").with(csrf())).andExpect(status().isForbidden());
        verifierInitial();
    }

    @Test
    void unAchatInexistantNeCreeAucunMouvement() throws Exception {
        mvc.perform(get("/achats/regler/9999999")).andExpect(status().isNotFound());
        mvc.perform(post("/achats/regler/9999999").with(csrf()).param("montant", "1").param("montantVerseAttendu", "0"))
                .andExpect(status().isNotFound());
        verifierInitial();
    }

    @Test
    void uneDetteAvecAncienVersementNullPeutEtrePayee() {
        achat.setMontantVerse(null); achats.saveAndFlush(achat);
        assertThat(achats.findById(achat.getId()).orElseThrow().getResteAPayer()).isEqualTo(100000.0);
        achatService.reglerAchat(achat.getId(), new BigDecimal("10000"), BigDecimal.ZERO);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(10000.0);
    }

    @Test
    void modifierLesLignesNeChangePasLePaiement() throws Exception {
        mvc.perform(modification(12).with(csrf())).andExpect(redirectedUrl("/achats?success=modifie"));
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(12L);
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(50000.0);
        assertThat(caisse.count()).isEqualTo(1);
        String html = mvc.perform(get("/achats/modifier/" + achat.getId())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("name=\"montantVerse\"").contains("Enregistrer un nouveau versement");
    }

    @Test
    void laModificationRefuseLesChangementsDePaiementEtUnTotalInferieurAuDejaPaye() throws Exception {
        mvc.perform(modification(10).with(csrf()).param("montantVerse", "100000"))
                .andExpect(status().isBadRequest());
        mvc.perform(modification(4).with(csrf())).andExpect(redirectedUrl("/achats/modifier/" + achat.getId()))
                .andExpect(flash().attribute("error", "Le total modifié ne peut pas être inférieur au montant déjà payé."));
        verifierInitial();
    }

    @Test
    void leServiceRefuseAussiZeroEtLesVersementsExcessifs() {
        assertThatThrownBy(() -> achatService.reglerAchat(achat.getId(), BigDecimal.ZERO, new BigDecimal("50000")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> achatService.reglerAchat(achat.getId(), new BigDecimal("50001"), new BigDecimal("50000")))
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
        }).when(cible).enregistrerSortieAchat(anyDouble(), anyString(), any(Achat.class), any(Utilisateur.class));
        assertThatThrownBy(() -> achatService.reglerAchat(achat.getId(), new BigDecimal("10000"), new BigDecimal("50000")))
                .hasMessage("Erreur de caisse simulée");
        verifierInitial();
    }

    @Test
    void lesPetitsVersementsDecimauxSAdditionnentSansArrondiIntermediaire() throws Exception {
        mvc.perform(versement("0.10", "50000").with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(versement("0.20", "50000.10").with(csrf())).andExpect(status().is3xxRedirection());
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(50000.3);
        assertThat(caisse.findByAchatIdOrderByDateMouvementDescIdDesc(achat.getId()))
                .extracting(MouvementCaisse::getMontant).containsExactly(-0.2, -0.1, -50000.0);
    }

    @Test
    void unPaiementQuiPerdraitSaPrecisionEstRefuseSansEcriture() {
        achat.setMontantTotal(1.0e15); achats.saveAndFlush(achat);
        assertThatThrownBy(() -> achatService.reglerAchat(achat.getId(), new BigDecimal("999999999949999.99"),
                new BigDecimal("50000"))).hasMessageContaining("précision");
        verifierInitial();
    }

    private MockHttpServletRequestBuilder versement(String montant, String attendu) {
        return post("/achats/regler/" + achat.getId()).param("montant", montant).param("montantVerseAttendu", attendu);
    }

    private MockHttpServletRequestBuilder modification(int quantite) {
        return post("/achats/modifier/" + achat.getId()).param("fournisseur", achat.getFournisseur().getId().toString())
                .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", Integer.toString(quantite))
                .param("lignes[0].prixAchatUnitaire", "10000");
    }

    private void vendre(int quantite) {
        Vente vente = new Vente(); DetailVente ligne = new DetailVente();
        Produit reference = new Produit(); reference.setId(produit.getId());
        ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne); venteService.effectuerVente(vente);
    }

    private DetailAchat ligne(int quantite) {
        DetailAchat ligne = new DetailAchat(); Produit reference = new Produit(); reference.setId(produit.getId());
        ligne.setProduit(reference); ligne.setQuantite(quantite); ligne.setPrixAchatUnitaire(10000.0); return ligne;
    }

    private List<Long> idsLignes() {
        return new TransactionTemplate(transactions).execute(status -> {
            var lignes = achats.findById(achat.getId()).orElseThrow().getLignes();
            assertThat(lignes).hasSize(1);
            assertThat(lignes.getFirst().getQuantite()).isEqualTo(10);
            assertThat(lignes.getFirst().getPrixAchatUnitaire()).isEqualTo(10000.0);
            return lignes.stream().map(DetailAchat::getId).toList();
        });
    }

    private void verifierInitial() {
        assertThat(achats.findById(achat.getId()).orElseThrow().getMontantVerse()).isEqualTo(50000.0);
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(10L);
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal()).isEqualTo(-50000.0);
    }
}
