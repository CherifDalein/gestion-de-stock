package org.example.stock.controles;

import org.example.stock.enums.Role;
import org.example.stock.enums.SensAjustement;
import org.example.stock.export.TableauExport;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.AjustementSoldeDepartService;
import org.example.stock.service.EtatsPartenairesService;
import org.example.stock.service.EtatsPartenairesService.Cote;
import org.example.stock.service.SoldePartenaire;
import org.example.stock.service.SoldePartenaireService;
import org.example.stock.service.PeriodeEtat;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Ajustement du solde de départ (étape 3b2) : excédent devenu avance, cas sans règlement, historique, relevé, droits, isolation. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:ajustement-solde;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AjustementSoldeDepartTests {
    static final String GERANT = "gerant-ajust@example.test", CAISSIER = "caissier-ajust@example.test", AUTRE = "autre-ajust@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired AjustementSoldeDepartRepository ajustements;
    @Autowired SoldePartenaireService soldes;
    @Autowired AjustementSoldeDepartService service;
    @Autowired EtatsPartenairesService etats;
    @Autowired MockMvc mvc;

    Boutique boutique, autreBoutique;
    Client client, clientAutre;
    Fournisseur fournisseur;

    @BeforeEach
    void preparer() {
        ajustements.deleteAll(); caisse.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique ajustements"); autreBoutique = boutique("Autre boutique");
        utilisateur(GERANT, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(AUTRE, Role.ADMIN, autreBoutique);
        client = client(boutique, "Aminata", "50000"); clientAutre = client(autreBoutique, "Client voisin", "1000");
        fournisseur = new Fournisseur(); fournisseur.setBoutique(boutique); fournisseur.setNom("Sidi"); fournisseur.setSoldeInitial(new BigDecimal("-80000"));
        fournisseur = fournisseurs.saveAndFlush(fournisseur);
        authentifier(GERANT);
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    @Test
    void sousLeMontantDejaRegleLExcedentDevientUneAvanceSansMouvementDeTresorerie() throws Exception {
        regler(client.getId(), "30000");
        long mouvements = caisse.count();
        SoldePartenaire avant = soldes().soldeClient(client.getId());
        assertThat(avant.resteSoldeDepart()).isEqualByComparingTo("20000");

        // Le dû passe de 50 000 à 10 000 : 30 000 ont été réglés, 20 000 de trop.
        poster("/clients/solde/ajuster/" + client.getId(), "10000", "IL_ME_DOIT", "Erreur de report", "j-1").andExpect(status().is3xxRedirection());

        SoldePartenaire apres = soldes().soldeClient(client.getId());
        assertThat(apres.soldeDepart()).isEqualByComparingTo("10000");
        assertThat(apres.soldeDepartRegle()).as("les règlements passés ne sont pas réécrits").isEqualByComparingTo("30000");
        assertThat(apres.resteSoldeDepart()).isZero();
        assertThat(apres.avanceDisponible()).as("trop-réglé = avance").isEqualByComparingTo("20000");
        assertThat(apres.soldeNet()).isEqualByComparingTo("-20000");
        assertThat(caisse.count()).as("aucun mouvement de trésorerie").isEqualTo(mouvements);
        assertThat(soldes().avancesClients()).isEqualByComparingTo("20000");
        assertThat(soldes().creancesClients().total()).isZero();
    }

    @Test
    void sansReglementLeResteSuitLeNouveauSoldeEtLeSensPeutBasculerEnAvance() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "70000", "IL_ME_DOIT", "Reprise corrigée", "j-2").andExpect(status().is3xxRedirection());
        assertThat(soldes().soldeClient(client.getId()).resteSoldeDepart()).isEqualByComparingTo("70000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isZero();

        poster("/clients/solde/ajuster/" + client.getId(), "15000", "AVANCE", "Il avait versé d'avance", "j-3").andExpect(status().is3xxRedirection());
        SoldePartenaire s = soldes().soldeClient(client.getId());
        assertThat(s.soldeDepart()).isEqualByComparingTo("-15000");
        assertThat(s.resteSoldeDepart()).isZero();
        assertThat(s.avanceDisponible()).isEqualByComparingTo("15000");
    }

    @Test
    void lHistoriqueConserveDateAuteurAncienEtNouveauMontantEtMotif() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "40000", "IL_ME_DOIT", "Premier motif", "j-4").andExpect(status().is3xxRedirection());
        poster("/clients/solde/ajuster/" + client.getId(), "5000", "JE_LUI_DOIS", "Second motif", "j-5").andExpect(status().is3xxRedirection());
        List<AjustementSoldeDepart> historique = service().historiqueClient(client.getId());
        assertThat(historique).hasSize(2);
        assertThat(historique.get(0).getAncienSolde()).isEqualByComparingTo("50000");
        assertThat(historique.get(0).getNouveauSolde()).isEqualByComparingTo("40000");
        assertThat(historique.get(1).getAncienSolde()).isEqualByComparingTo("40000");
        assertThat(historique.get(1).getNouveauSolde()).isEqualByComparingTo("-5000");
        assertThat(historique.get(1).getMotif()).isEqualTo("Second motif");
        assertThat(historique.get(1).getUtilisateur().getEmail()).isEqualTo(GERANT);
        assertThat(historique.get(1).getDateAjustement()).isNotNull();

        String page = mvc.perform(get("/clients/modifier/" + client.getId()).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Ajustements du solde de départ", "Premier motif", "Second motif", "/clients/solde/ajuster/" + client.getId());
        String caissier = mvc.perform(get("/clients/modifier/" + client.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(caissier).doesNotContain("Premier motif", "Ajuster le solde de départ");
    }

    @Test
    void leReleveCommenceAuSoldeDOrigineEtMontreChaqueAjustement() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "30000", "IL_ME_DOIT", "Correction de reprise", "j-6").andExpect(status().is3xxRedirection());
        TableauExport t = etats().releve(Cote.CLIENT, client.getId(), new PeriodeEtat(LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))).sections().get(0);
        assertThat(t.getLignes().get(0).get(2)).isEqualTo("Solde de départ de la fiche");
        assertThat((BigDecimal) t.getLignes().get(0).get(6)).as("solde de départ d'origine").isEqualByComparingTo("50000");
        assertThat(t.getLignes()).anySatisfy(l -> assertThat(String.valueOf(l.get(2))).contains("Ajustement du solde de départ").contains("Correction de reprise"));
        assertThat((BigDecimal) t.getTotaux().get(6)).as("solde final = solde de la fiche").isEqualByComparingTo("30000");
        assertThat(t.getNotes()).anySatisfy(n -> assertThat(n).contains("Concordance"));
    }

    @Test
    void unFournisseurSAjusteAvecLeSensDuFournisseur() throws Exception {
        poster("/fournisseurs/solde/ajuster/" + fournisseur.getId(), "20000", "AVANCE", "Il nous doit un acompte", "j-7").andExpect(status().is3xxRedirection());
        SoldePartenaire s = soldes().soldeFournisseur(fournisseur.getId());
        assertThat(s.soldeDepart()).as("avance chez un fournisseur = solde positif").isEqualByComparingTo("20000");
        assertThat(s.avanceDisponible()).isEqualByComparingTo("20000");
        assertThat(s.resteSoldeDepart()).isZero();
    }

    @Test
    void laFicheNeModifiePlusLeSoldeUneFoisAjuste() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "45000", "IL_ME_DOIT", "Ajusté", "j-8").andExpect(status().is3xxRedirection());
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(GERANT).roles("ADMIN")).param("nom", "Aminata")
                        .param("montantSoldeDepart", "1000").param("sensSoldeDepart", "IL_ME_DOIT").param("confirmationSoldeDepart", "true"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ajustement de solde")));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("45000");
    }

    @Test
    void motifObligatoireSensObligatoireEtMontantIdentiqueRefuses() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "10", "IL_ME_DOIT", "", "j-9").andExpect(status().isOk());
        poster("/clients/solde/ajuster/" + client.getId(), "10", "IL_ME_DOIT", "ab", "j-9").andExpect(status().isOk());
        poster("/clients/solde/ajuster/" + client.getId(), "50000", "IL_ME_DOIT", "Rien ne change", "j-9").andExpect(status().isOk());
        poster("/clients/solde/ajuster/" + client.getId(), "-5", "IL_ME_DOIT", "Négatif", "j-9").andExpect(status().isOk());
        assertThat(ajustements.count()).isZero();
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("50000");
    }

    @Test
    void unDoubleEnvoiAvecLeMemeJetonNEnregistreQuUnAjustement() throws Exception {
        poster("/clients/solde/ajuster/" + client.getId(), "20000", "IL_ME_DOIT", "Une seule fois", "j-10").andExpect(status().is3xxRedirection());
        poster("/clients/solde/ajuster/" + client.getId(), "20000", "IL_ME_DOIT", "Une seule fois", "j-10").andExpect(status().is3xxRedirection());
        assertThat(ajustements.count()).isEqualTo(1);
    }

    @Test
    void leCaissierNAjustePasEtUnPartenaireDUneAutreBoutiqueEstIntrouvable() throws Exception {
        mvc.perform(get("/clients/solde/ajuster/" + client.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(post("/clients/solde/ajuster/" + client.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER"))
                .param("montant", "1").param("sens", "IL_ME_DOIT").param("motif", "Essai").param("jeton", "j-11")).andExpect(status().isForbidden());
        mvc.perform(get("/fournisseurs/solde/ajuster/" + fournisseur.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/clients/solde/ajuster/" + clientAutre.getId()).with(user(GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        poster("/clients/solde/ajuster/" + clientAutre.getId(), "5", "IL_ME_DOIT", "Voisin", "j-12").andExpect(status().isNotFound());
        assertThat(ajustements.count()).isZero();
        assertThatThrownBy(() -> { authentifier(CAISSIER, "CAISSIER"); service.ajusterClient(client.getId(), BigDecimal.ONE, SensAjustement.IL_ME_DOIT, "Essai", "j-13"); })
                .hasMessageContaining("réservé au gérant");
    }

    @Test
    void seuleLaListeBlancheDeChampsEstAcceptee() throws Exception {
        mvc.perform(post("/clients/solde/ajuster/" + client.getId()).with(csrf()).with(user(GERANT).roles("ADMIN"))
                .param("montant", "1").param("sens", "IL_ME_DOIT").param("motif", "Essai").param("jeton", "j-14").param("soldeInitial", "999"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/clients/solde/ajuster/" + client.getId()).with(csrf()).with(user(GERANT).roles("ADMIN"))
                .param("montant", "1").param("montant", "2").param("sens", "IL_ME_DOIT").param("motif", "Essai").param("jeton", "j-15"))
                .andExpect(status().isBadRequest());
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("50000");
    }

    private SoldePartenaireService soldes() { authentifier(GERANT); return soldes; }
    private AjustementSoldeDepartService service() { authentifier(GERANT); return service; }
    private EtatsPartenairesService etats() { authentifier(GERANT); return etats; }

    // ---- Outils ---------------------------------------------------------------------------------------------

    private org.springframework.test.web.servlet.ResultActions poster(String url, String montant, String sens, String motif, String jeton) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(user(GERANT).roles("ADMIN")).param("montant", montant).param("sens", sens).param("motif", motif).param("jeton", jeton));
    }

    private void regler(Long clientId, String montant) throws Exception {
        mvc.perform(post("/clients/solde/regler/" + clientId).with(csrf()).with(user(GERANT).roles("ADMIN"))
                .param("montant", montant).param("reglementAttendu", "0").param("comptePaiement", "CAISSE")).andExpect(status().is3xxRedirection());
    }

    private void authentifier(String email) { authentifier(email, "ADMIN"); }

    private void authentifier(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
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
