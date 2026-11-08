package org.example.stock.service;

import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.ModeRemboursement;
import org.example.stock.enums.Role;
import org.example.stock.enums.SensSolde;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Annulation d'un règlement (gérant seulement) : mouvement inverse, facture et solde de départ rétablis, refus, droits,
 * isolation entre boutiques, double envoi concurrent, journal de caisse. Vraies transactions, H2.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:annulation-reglement;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AnnulationReglementTests {
    static final String ADMIN = "gerant-annul@example.test", CAISSIER = "caissier-annul@example.test", ADMIN2 = "gerant2-annul@example.test";

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
    @Autowired SoldePartenaireService soldesService;
    @Autowired MockMvc mvc;

    Boutique boutique, autreBoutique;
    Produit produit;
    Client client;
    Fournisseur fournisseur;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); retours.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique annulation"); autreBoutique = boutique("Autre boutique annulation");
        utilisateur(ADMIN, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autreBoutique);
        client = client(boutique, "Client annulation"); fournisseur = fournisseur(boutique, "Fournisseur annulation");
        produit = produit(boutique, fournisseur, "REF-ANN");
        authentifierGerant();
    }

    @AfterEach
    void nettoyerContexte() { SecurityContextHolder.clearContext(); }

    // ---- Vente ----------------------------------------------------------------------------------------------

    @Test
    void annulerUnEncaissementRetablitLaCreanceEtLaTresorerie() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.BANQUE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        assertThat(ventes.findById(vente.getId()).orElseThrow().getResteAPayer()).isEqualByComparingTo("50000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("50000");

        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId(), ADMIN, "Erreur de saisie").andExpect(status().is3xxRedirection());

        Vente apres = ventes.findById(vente.getId()).orElseThrow();
        assertThat(apres.getResteAPayer()).isEqualByComparingTo("80000");
        assertThat(apres.getMontantVerse()).isEqualByComparingTo("20000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("20000");
        assertThat(soldes().soldeClient(client.getId()).resteFactures()).isEqualByComparingTo("80000");
        MouvementCaisse inverse = unique("ANNULATION");
        assertThat(inverse.getMontant()).isEqualByComparingTo("-30000");
        assertThat(inverse.getCompte()).isEqualTo(CompteTresorerie.BANQUE);
        assertThat(inverse.getMotif()).contains("Erreur de saisie");
        assertThat(inverse.getAnnulationDe().getId()).isEqualTo(reglement.getId());
        assertThat(inverse.getUtilisateur()).isNotNull();
        assertThat(caisse.findById(reglement.getId()).orElseThrow().getMontant()).isEqualByComparingTo("30000");
    }

    @Test
    void uneEcheanceAbsenteEstProposeeQuandLeResteRevientPositif() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "80000", "20000", CompteTresorerie.CAISSE);
        Vente soldee = ventes.findById(vente.getId()).orElseThrow();
        soldee.setDateEcheance(null); ventes.saveAndFlush(soldee);
        MouvementCaisse reglement = unique(SourceVenteReglement());

        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId(), ADMIN, "Mauvais client").andExpect(status().is3xxRedirection());

        Vente apres = ventes.findById(vente.getId()).orElseThrow();
        assertThat(apres.getResteAPayer()).isEqualByComparingTo("80000");
        assertThat(apres.getDateEcheance()).isNotNull().isAfterOrEqualTo(LocalDate.now());
    }

    @Test
    void uneEcheanceExistanteEstConservee() throws Exception {
        Vente vente = vente(10, "20000");
        LocalDate echeance = LocalDate.now().plusDays(45);
        Vente v = ventes.findById(vente.getId()).orElseThrow(); v.setDateEcheance(echeance); ventes.saveAndFlush(v);
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId(), ADMIN, "Erreur").andExpect(status().is3xxRedirection());
        assertThat(ventes.findById(vente.getId()).orElseThrow().getDateEcheance()).isEqualTo(echeance);
    }

    // ---- Achat ----------------------------------------------------------------------------------------------

    @Test
    void annulerUnPaiementFournisseurRetablitLaDetteEtLaTresorerie() throws Exception {
        Achat achat = achat(10, "20000");
        authentifie(ADMIN, "ADMIN", () -> { achatService.reglerAchat(achat.getId(), new BigDecimal("30000"), new BigDecimal("20000"), CompteTresorerie.ORANGE_MONEY, null); return null; });
        MouvementCaisse reglement = unique("REGLEMENT_ACHAT");
        BigDecimal avant = caisse.calculerSoldeTotal(boutique.getId());
        assertThat(avant).isEqualByComparingTo("-50000");

        annuler("/achats/regler/" + achat.getId() + "/annuler/" + reglement.getId(), ADMIN, "Paiement saisi deux fois").andExpect(status().is3xxRedirection());

        Achat apres = achats.findById(achat.getId()).orElseThrow();
        assertThat(apres.getResteAPayer()).isEqualByComparingTo("80000");
        assertThat(apres.getMontantVerse()).isEqualByComparingTo("20000");
        MouvementCaisse inverse = unique("ANNULATION");
        assertThat(inverse.getMontant()).isEqualByComparingTo("30000");
        assertThat(inverse.getCompte()).isEqualTo(CompteTresorerie.ORANGE_MONEY);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-20000");
    }

    // ---- Soldes de départ -----------------------------------------------------------------------------------

    @Test
    void annulerLeReglementDuSoldeDeDepartDUnClientRetablitLeReste() throws Exception {
        parametrerSolde(client, "100000", SensSolde.IL_ME_DOIT);
        mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "30000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        MouvementCaisse reglement = unique("SOLDE_CLIENT");
        assertThat(soldes().soldeClient(client.getId()).resteSoldeDepart()).isEqualByComparingTo("70000");

        annuler("/clients/solde/regler/" + client.getId() + "/annuler/" + reglement.getId(), ADMIN, "Erreur de client").andExpect(status().is3xxRedirection());

        assertThat(soldes().soldeClient(client.getId()).resteSoldeDepart()).isEqualByComparingTo("100000");
        assertThat(unique("ANNULATION").getMontant()).isEqualByComparingTo("-30000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("0");
        // Règle « figé après usage » inchangée : le solde ne redevient pas modifiable.
        assertThat(soldes().soldeClient(client.getId()).soldeDepartModifiable()).isFalse();
    }

    @Test
    void annulerLeReglementDuSoldeDeDepartDUnFournisseurRetablitLeReste() throws Exception {
        parametrerSolde(fournisseur, "100000", SensSolde.JE_LUI_DOIS);
        mvc.perform(reglementSolde("/fournisseurs/solde/regler/" + fournisseur.getId(), "40000", "0", "BANQUE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        MouvementCaisse reglement = unique("SOLDE_FOURNISSEUR");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).resteSoldeDepart()).isEqualByComparingTo("60000");

        annuler("/fournisseurs/solde/regler/" + fournisseur.getId() + "/annuler/" + reglement.getId(), ADMIN, "Erreur fournisseur").andExpect(status().is3xxRedirection());

        assertThat(soldes().soldeFournisseur(fournisseur.getId()).resteSoldeDepart()).isEqualByComparingTo("100000");
        assertThat(unique("ANNULATION").getMontant()).isEqualByComparingTo("40000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("0");
    }

    // ---- Refus ----------------------------------------------------------------------------------------------

    @Test
    void lePaiementSaisiALaCreationNEstPasAnnulable() throws Exception {
        Vente vente = vente(10, "20000");
        MouvementCaisse creation = unique("VENTE");
        long avant = caisse.count();
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + creation.getId(), ADMIN, "Test").andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("20000");
    }

    @Test
    void uneDepenseNEstPasAnnulable() throws Exception {
        Vente vente = vente(10, "20000");
        MouvementCaisse depense = new MouvementCaisse(); depense.setBoutique(boutique); depense.setCompte(CompteTresorerie.CAISSE);
        depense.setDateMouvement(LocalDateTime.now()); depense.setMontant(new BigDecimal("-1000")); depense.setType("SORTIE");
        depense.setMotif("Dépense"); depense.setSource("DEPENSE"); depense = caisse.saveAndFlush(depense);
        long avant = caisse.count();
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + depense.getId(), ADMIN, "Test").andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void unReglementDejaAnnuleNePeutPasEtreAnnuleDeNouveau() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        String url = "/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId();
        annuler(url, ADMIN, "Première").andExpect(status().is3xxRedirection());
        annuler(url, ADMIN, "Seconde").andExpect(status().isConflict()).andExpect(content().string(org.hamcrest.Matchers.containsString("déjà été annulé")));
        assertThat(caisse.findAll().stream().filter(m -> "ANNULATION".equals(m.getSource()))).hasSize(1);
    }

    @Test
    void unMouvementDAnnulationNEstPasAnnulable() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + unique(SourceVenteReglement()).getId(), ADMIN, "Première").andExpect(status().is3xxRedirection());
        MouvementCaisse inverse = unique("ANNULATION");
        long avant = caisse.count();
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + inverse.getId(), ADMIN, "Seconde").andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void unRetourPosterieurAuReglementBloqueLAnnulation() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        long connus = retours.countByVenteId(vente.getId());
        mvc.perform(post("/ventes/" + vente.getId() + "/retour").with(csrf()).with(user(ADMIN).roles("ADMIN"))
                        .param("lignes[0].detailVenteId", String.valueOf(vente.getLignes().get(0).getId())).param("lignes[0].quantite", "1")
                        .param("retoursConnus", String.valueOf(connus)).param("modeRemboursement", ModeRemboursement.ARGENT.name())
                        .param("comptePaiement", CompteTresorerie.CAISSE.name()))
                .andExpect(status().is3xxRedirection());
        assertThat(retours.countByVenteId(vente.getId())).isEqualTo(connus + 1);
        long avant = caisse.count();

        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId(), ADMIN, "Trop tard").andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void unMotifAbsentOuTropCourtEstRefuse() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        long avant = caisse.count();
        String url = "/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId();
        mvc.perform(post(url).with(csrf()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk());
        annuler(url, ADMIN, "  ab ").andExpect(status().isOk());
        annuler(url, ADMIN, "x".repeat(256)).andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void leCaissierReçoitUn403() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        long avant = caisse.count();
        String url = "/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId();
        mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        annuler(url, CAISSIER, "Tentative").andExpect(status().isForbidden());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void unGerantNeVoitPasLesReglementsDUneAutreBoutique() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        long avant = caisse.count();
        String url = "/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId();
        mvc.perform(get(url).with(user(ADMIN2).roles("ADMIN"))).andExpect(status().isNotFound());
        annuler(url, ADMIN2, "Intrusion").andExpect(status().isNotFound());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void unChampInconnuEstRefuseEn400() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        long avant = caisse.count();
        mvc.perform(post("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN"))
                .param("motif", "Motif valable").param("montant", "1")).andExpect(status().isBadRequest());
        assertThat(caisse.count()).isEqualTo(avant);
    }

    @Test
    void sansJetonCsrfLAnnulationEstRefusee() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        mvc.perform(post("/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId()).with(user(ADMIN).roles("ADMIN")).param("motif", "Motif valable"))
                .andExpect(status().isForbidden());
    }

    // ---- Concurrence ----------------------------------------------------------------------------------------

    @Test
    void deuxEnvoisSimultanesNeProduisentQuUneSeuleAnnulation() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        MouvementCaisse reglement = unique(SourceVenteReglement());
        String url = "/ventes/regler/" + vente.getId() + "/annuler/" + reglement.getId();
        CyclicBarrier barriere = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> envoi = () -> { barriere.await(15, TimeUnit.SECONDS); return annuler(url, ADMIN, "Double clic").andReturn().getResponse().getStatus(); };
            Future<Integer> a = pool.submit(envoi), b = pool.submit(envoi);
            List<Integer> statuts = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(statuts.stream().filter(s -> s >= 300 && s < 400)).hasSize(1);
            assertThat(statuts).noneMatch(s -> s >= 500);
        } finally { pool.shutdownNow(); }
        assertThat(caisse.findAll().stream().filter(m -> "ANNULATION".equals(m.getSource()))).hasSize(1);
        assertThat(ventes.findById(vente.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("20000");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("20000");
    }

    // ---- Journal --------------------------------------------------------------------------------------------

    @Test
    void leJournalMentionLAnnulationEtLesTotauxRestentJustes() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        annuler("/ventes/regler/" + vente.getId() + "/annuler/" + unique(SourceVenteReglement()).getId(), ADMIN, "Erreur de montant").andExpect(status().is3xxRedirection());

        String page = mvc.perform(get("/caisse/journal").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Annulé le").contains("Erreur de montant").contains("Annulation du mouvement du");
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("20000");
    }

    @Test
    void laPageDeReglementAfficheLeBoutonAuGerantSeulement() throws Exception {
        Vente vente = vente(10, "20000");
        regler(vente, "30000", "20000", CompteTresorerie.CAISSE);
        mvc.perform(get("/ventes/regler/" + vente.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/annuler/")));
        mvc.perform(get("/ventes/regler/" + vente.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/annuler/"))));
    }

    // ---- Outils ---------------------------------------------------------------------------------------------

    private static String SourceVenteReglement() { return "REGLEMENT_VENTE"; }

    private org.springframework.test.web.servlet.ResultActions annuler(String url, String compte, String motif) throws Exception {
        String role = CAISSIER.equals(compte) ? "CAISSIER" : "ADMIN";
        return mvc.perform(post(url).with(csrf()).with(user(compte).roles(role)).param("motif", motif));
    }

    private MouvementCaisse unique(String source) {
        List<MouvementCaisse> trouves = caisse.findAll().stream().filter(m -> source.equals(m.getSource())).toList();
        assertThat(trouves).hasSize(1);
        return trouves.getFirst();
    }

    private void regler(Vente vente, String montant, String dejaVerse, CompteTresorerie compte) {
        authentifie(ADMIN, "ADMIN", () -> { venteService.reglerVente(vente.getId(), new BigDecimal(montant), new BigDecimal(dejaVerse), compte, null); return null; });
    }

    private MockHttpServletRequestBuilder reglementSolde(String url, String montant, String dejaRegle, String compte) {
        return post(url).param("montant", montant).param("reglementAttendu", dejaRegle).param("comptePaiement", compte);
    }

    private SoldePartenaireService soldes() { authentifierGerant(); return soldesService; }

    private void authentifierGerant() {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private Vente vente(int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> {
            Vente vente = new Vente(); vente.setBoutique(boutique);
            Client c = new Client(); c.setId(client.getId()); vente.setClient(c);
            vente.setMontantVerse(new BigDecimal(verse));
            Produit p = new Produit(); p.setId(produit.getId());
            DetailVente ligne = new DetailVente(); ligne.setProduit(p); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
            return venteService.effectuerVente(vente);
        });
    }

    private Achat achat(int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> {
            Achat achat = new Achat(); achat.setBoutique(boutique);
            Fournisseur f = new Fournisseur(); f.setId(fournisseur.getId()); achat.setFournisseur(f);
            achat.setMontantVerse(new BigDecimal(verse));
            Produit p = new Produit(); p.setId(produit.getId());
            DetailAchat ligne = new DetailAchat(); ligne.setProduit(p); ligne.setQuantite(quantite);
            ligne.setPrixAchatUnitaire(new BigDecimal("10000.00")); ligne.setPrixVenteUnitaire(new BigDecimal("10000.00")); achat.getLignes().add(ligne);
            return achatService.enregistrerAchat(achat);
        });
    }

    private void parametrerSolde(Client cible, String montant, SensSolde sens) {
        Client c = clients.findById(cible.getId()).orElseThrow();
        c.setSoldeInitial(signe(montant, sens)); c.setEcheanceSoldeInitial(LocalDate.now().plusDays(30)); clients.saveAndFlush(c);
    }

    private void parametrerSolde(Fournisseur cible, String montant, SensSolde sens) {
        Fournisseur f = fournisseurs.findById(cible.getId()).orElseThrow();
        f.setSoldeInitial(signe(montant, sens)); f.setEcheanceSoldeInitial(LocalDate.now().plusDays(30)); fournisseurs.saveAndFlush(f);
    }

    private static BigDecimal signe(String montant, SensSolde sens) {
        BigDecimal valeur = new BigDecimal(montant).setScale(2);
        return sens == SensSolde.JE_LUI_DOIS ? valeur.negate() : valeur;
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.save(u);
    }

    private Client client(Boutique b, String nom) { Client c = new Client(); c.setBoutique(b); c.setNom(nom); return clients.saveAndFlush(c); }

    private Fournisseur fournisseur(Boutique b, String nom) { Fournisseur f = new Fournisseur(); f.setBoutique(b); f.setNom(nom); return fournisseurs.saveAndFlush(f); }

    private Produit produit(Boutique b, Fournisseur f, String reference) {
        Categorie categorie = new Categorie(); categorie.setBoutique(b); categorie.setNom("Catégorie " + reference); categorie = categories.save(categorie);
        Produit p = new Produit(); p.setBoutique(b); p.setNom(reference); p.setReference(reference); p.setCategorie(categorie); p.setFournisseur(f);
        p.setQuantite(100L); p.setPrixAchat(new BigDecimal("5000.00")); p.setPrixVente(new BigDecimal("10000.00")); p.setPrixDefinis(true);
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
}
