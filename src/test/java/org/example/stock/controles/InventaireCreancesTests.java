package org.example.stock.controles;

import org.example.stock.enums.Role;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.export.ExportTestOutils;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.InventaireCreancesService;
import org.example.stock.service.SoldePartenaireService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Inventaire des créances et dettes (étape 3b2) : écarts, aucun effet sur les soldes, lien d'ajustement, droits, isolation et exports. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:inventaire-creances;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class InventaireCreancesTests {
    static final String GERANT = "gerant-inv@example.test", CAISSIER = "caissier-inv@example.test", AUTRE_GERANT = "gerant2-inv@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired PartenaireFinancierRepository financiers;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired InventaireCreancesRepository inventaires;
    @Autowired AjustementSoldeDepartRepository ajustements;
    @Autowired SoldePartenaireService soldes;
    @Autowired InventaireCreancesService service;
    @Autowired MockMvc mvc;

    Boutique boutique, autre;
    Client aminata, voisin;
    Fournisseur sidi;
    PartenaireFinancier banque;

    @BeforeEach
    void preparer() {
        inventaires.deleteAll(); ajustements.deleteAll(); mouvements.deleteAll(); financiers.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique inventaire"); autre = boutique("Autre boutique");
        utilisateur(GERANT, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(AUTRE_GERANT, Role.ADMIN, autre);
        aminata = client(boutique, "Aminata", "50000"); voisin = client(autre, "Client voisin", "1000");
        sidi = new Fournisseur(); sidi.setBoutique(boutique); sidi.setNom("Sidi"); sidi.setSoldeInitial(new BigDecimal("-80000")); sidi = fournisseurs.saveAndFlush(sidi);
        banque = new PartenaireFinancier(); banque.setBoutique(boutique); banque.setNom("Banque du Nord"); banque.setNomCle("banque du nord"); banque.setType(TypePartenaireFinancier.BANQUE); banque.setDateCreation(java.time.LocalDateTime.now());
        banque = financiers.saveAndFlush(banque);
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    @Test
    void l_inventaireGardeLEcartEtLaRaisonSansModifierAucunSolde() throws Exception {
        BigDecimal avantClient = soldeClient(), avantFournisseur = soldeFournisseur();
        long mouvementsAvant = mouvements.count();
        enregistrer("j-1", "2026-11-10",
                new String[]{"CLIENT:" + aminata.getId(), "45000", "IL_ME_DOIT", "Il a payé 5 000 en main propre"},
                new String[]{"FOURNISSEUR:" + sidi.getId(), "80000", "JE_LUI_DOIS", ""},
                new String[]{"FINANCIER:" + banque.getId(), "", "IL_ME_DOIT", ""}).andExpect(status().is3xxRedirection());

        InventaireCreances inventaire = inventaires.findByBoutiqueIdAndJeton(boutique.getId(), "j-1").orElseThrow();
        authentifier(GERANT);
        var lignes = service.trouver(inventaire.getId()).getLignes();
        assertThat(lignes).hasSize(2);
        LigneInventaire client = lignes.get(0), fournisseur = lignes.get(1);
        assertThat(client.getSoldeAppli()).isEqualByComparingTo("50000");
        assertThat(client.getSoldeConfirme()).isEqualByComparingTo("45000");
        assertThat(client.getEcart()).isEqualByComparingTo("-5000");
        assertThat(client.getRaison()).isEqualTo("Il a payé 5 000 en main propre");
        assertThat(client.aEcart()).isTrue();
        assertThat(fournisseur.getSoldeAppli()).as("la boutique lui doit 80 000 : solde négatif").isEqualByComparingTo("-80000");
        assertThat(fournisseur.getSoldeConfirme()).isEqualByComparingTo("-80000");
        assertThat(fournisseur.getEcart()).isZero();
        assertThat(fournisseur.aEcart()).isFalse();
        assertThat(inventaire.getUtilisateur().getEmail()).isEqualTo(GERANT);
        // Aucun effet sur les soldes ni sur la trésorerie.
        assertThat(soldeClient()).isEqualByComparingTo(avantClient);
        assertThat(soldeFournisseur()).isEqualByComparingTo(avantFournisseur);
        assertThat(clients.findById(aminata.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("50000");
        assertThat(mouvements.count()).isEqualTo(mouvementsAvant);
        assertThat(ajustements.count()).isZero();
    }

    @Test
    void unAutrePartenaireFinancierSInventorieAvecLeSensDeLaBoutique() throws Exception {
        // La banque ne doit rien et on ne lui doit rien : un solde confirmé « je lui dois » 10 000 donne un écart de -10 000.
        enregistrer("j-2", "2026-11-10", new String[]{"FINANCIER:" + banque.getId(), "10000", "JE_LUI_DOIS", "Frais non enregistrés"}).andExpect(status().is3xxRedirection());
        authentifier(GERANT);
        LigneInventaire l = service.trouver(inventaires.findByBoutiqueIdAndJeton(boutique.getId(), "j-2").orElseThrow().getId()).getLignes().get(0);
        assertThat(l.getSoldeAppli()).isZero();
        assertThat(l.getEcart()).isEqualByComparingTo("-10000");
    }

    @Test
    void laPageDuDetailProposeUnLienDAjustementSeulementPourLesLignesAvecEcart() throws Exception {
        enregistrer("j-3", "2026-11-10",
                new String[]{"CLIENT:" + aminata.getId(), "45000", "IL_ME_DOIT", "Écart"},
                new String[]{"FOURNISSEUR:" + sidi.getId(), "80000", "JE_LUI_DOIS", ""}).andExpect(status().is3xxRedirection());
        Long id = inventaires.findByBoutiqueIdAndJeton(boutique.getId(), "j-3").orElseThrow().getId();
        String page = mvc.perform(get("/controles/creances/" + id).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("IC-" + id, "Aminata", "Sidi", "/clients/solde/ajuster/" + aminata.getId(), "− 5 000,00 GNF", "aucun solde");
        assertThat(page).doesNotContain("/fournisseurs/solde/ajuster/" + sidi.getId());
        String liste = mvc.perform(get("/controles/creances").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("IC-" + id);
        String formulaire = mvc.perform(get("/controles/creances/nouveau").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(formulaire).contains("Aminata", "Sidi", "Banque du Nord", "50 000,00 GNF (il me doit)", "80 000,00 GNF (je lui dois)").doesNotContain("Client voisin");
        String recherche = mvc.perform(get("/controles/creances/nouveau").param("q", "sid").with(user(GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(recherche).contains("Sidi").doesNotContain("Aminata");
    }

    @Test
    void lesExportsExcelEtPdfDonnentLesLignesEtSaisiParEnDernier() throws Exception {
        enregistrer("j-4", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "45000", "IL_ME_DOIT", "Écart"}).andExpect(status().is3xxRedirection());
        Long id = inventaires.findByBoutiqueIdAndJeton(boutique.getId(), "j-4").orElseThrow().getId();
        MockHttpServletResponse excel = mvc.perform(get("/controles/creances/" + id + "/export/excel").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        var feuille = ExportTestOutils.classeur(excel).getSheetAt(0);
        List<String> entetes = ExportTestOutils.entetes(feuille, "Type");
        assertThat(entetes).containsExactly("Type", "Partenaire", "Solde de l'application", "Solde confirmé", "Écart", "Raison de l'écart", "Saisi par");
        assertThat(ExportTestOutils.texteDeLaFeuille(feuille)).contains("Aminata", GERANT);
        MockHttpServletResponse pdf = mvc.perform(get("/controles/creances/" + id + "/export/pdf").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void validationsDuFormulaireEtDoubleEnvoi() throws Exception {
        enregistrer("j-5", "2026-11-10").andExpect(status().isOk());                                                  // aucune ligne saisie
        enregistrer("j-5", "2999-01-01", new String[]{"CLIENT:" + aminata.getId(), "1", "IL_ME_DOIT", ""}).andExpect(status().isOk());   // date future
        enregistrer("j-5", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "1", "IL_ME_DOIT", ""}, new String[]{"CLIENT:" + aminata.getId(), "2", "IL_ME_DOIT", ""})
                .andExpect(status().isOk());                                                                          // même partenaire deux fois
        enregistrer("j-5", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "-4", "IL_ME_DOIT", ""}).andExpect(status().isOk());
        enregistrer("j-5", "2026-11-10", new String[]{"CLIENT:abc", "4", "IL_ME_DOIT", ""}).andExpect(status().isOk());
        assertThat(inventaires.count()).isZero();
        enregistrer("j-6", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "50000", "IL_ME_DOIT", ""}).andExpect(status().is3xxRedirection());
        enregistrer("j-6", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "50000", "IL_ME_DOIT", ""}).andExpect(status().is3xxRedirection());
        assertThat(inventaires.count()).isEqualTo(1);
    }

    @Test
    void reserveAuGerantEtIsoleeEntreBoutiques() throws Exception {
        for (String url : List.of("/controles/creances", "/controles/creances/nouveau", "/controles/creances/1", "/controles/creances/1/export/pdf")) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        mvc.perform(post("/controles/creances").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("dateInventaire", "2026-11-10").param("jeton", "j-7"))
                .andExpect(status().isForbidden());
        // Un partenaire d'une autre boutique est introuvable, et rien n'est enregistré.
        enregistrer("j-8", "2026-11-10", new String[]{"CLIENT:" + voisin.getId(), "1000", "IL_ME_DOIT", ""}).andExpect(status().isNotFound());
        assertThat(inventaires.count()).isZero();
        enregistrer("j-9", "2026-11-10", new String[]{"CLIENT:" + aminata.getId(), "50000", "IL_ME_DOIT", ""}).andExpect(status().is3xxRedirection());
        Long id = inventaires.findByBoutiqueIdAndJeton(boutique.getId(), "j-9").orElseThrow().getId();
        mvc.perform(get("/controles/creances/" + id).with(user(AUTRE_GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/controles/creances/" + id + "/export/excel").with(user(AUTRE_GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        String formulaire = mvc.perform(get("/controles/creances/nouveau").with(user(AUTRE_GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(formulaire).contains("Client voisin").doesNotContain("Aminata");
    }

    @Test
    void seulsLesChampsDeclaresSontAcceptes() throws Exception {
        mvc.perform(post("/controles/creances").with(csrf()).with(user(GERANT).roles("ADMIN")).param("dateInventaire", "2026-11-10").param("jeton", "j-10")
                .param("cles[0]", "CLIENT:" + aminata.getId()).param("montants[0]", "1").param("utilisateur", "9")).andExpect(status().isBadRequest());
        mvc.perform(post("/controles/creances").with(csrf()).with(user(GERANT).roles("ADMIN")).param("dateInventaire", "2026-11-10").param("jeton", "j-10")
                .param("cles[0]", "CLIENT:" + aminata.getId()).param("montants[0]", "1").param("montants[0]", "2")).andExpect(status().isBadRequest());
        mvc.perform(post("/controles/creances").with(csrf()).with(user(GERANT).roles("ADMIN")).param("dateInventaire", "2026-11-10").param("jeton", "j-10")
                .param("cles[0]", "CLIENT:" + aminata.getId()).param("ecarts[0]", "1")).andExpect(status().isBadRequest());
        assertThat(inventaires.count()).isZero();
    }

    // ---- Outils ---------------------------------------------------------------------------------------------

    /** Chaque ligne : { clé, montant, sens, raison }. */
    private ResultActions enregistrer(String jeton, String date, String[]... lignes) throws Exception {
        MockHttpServletRequestBuilder r = post("/controles/creances").with(csrf()).with(user(GERANT).roles("ADMIN")).param("dateInventaire", date).param("jeton", jeton);
        for (int i = 0; i < lignes.length; i++) {
            r.param("cles[" + i + "]", lignes[i][0]).param("montants[" + i + "]", lignes[i][1]).param("sens[" + i + "]", lignes[i][2]).param("raisons[" + i + "]", lignes[i][3]);
        }
        return mvc.perform(r);
    }

    private BigDecimal soldeClient() { authentifier(GERANT); return soldes.soldeClient(aminata.getId()).soldeNet(); }

    private BigDecimal soldeFournisseur() { authentifier(GERANT); return soldes.soldeFournisseur(sidi.getId()).soldeNet(); }

    private void authentifier(String email) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.save(u);
    }

    private Client client(Boutique b, String nom, String solde) {
        Client c = new Client(); c.setBoutique(b); c.setNom(nom); c.setSoldeInitial(new BigDecimal(solde)); return clients.saveAndFlush(c);
    }
}
