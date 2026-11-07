package org.example.stock.service;

import jakarta.persistence.EntityManager;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.Role;
import org.example.stock.enums.SensSolde;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.model.*;
import org.example.stock.repository.*;
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
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Avances et soldes de départ des partenaires : trop-payé, avance utilisée automatiquement, règlement du solde de départ,
 * droits, isolation entre deux boutiques, concurrence et doubles envois. Vraies transactions, H2 avec délai de verrou.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:avances-soldes;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class AvancesSoldesPartenairesTests {
    static final String ADMIN = "gerant-avances@example.test", CAISSIER = "caissier-avances@example.test", ADMIN2 = "gerant2-avances@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired ProduitRepository produits;
    @Autowired CategorieRepository categories;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired ClientRepository clients;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationCreationRepository operations;
    @Autowired VenteService venteService;
    @Autowired AchatService achatService;
    @Autowired OperationCreationService creations;
    @Autowired SoldePartenaireService soldesService;
    @Autowired EcheanceService echeances;
    @Autowired PlatformTransactionManager transactions;
    @Autowired EntityManager entityManager;
    @Autowired MockMvc mvc;

    Boutique boutique, autreBoutique;
    Produit produit, produitAutre;
    Client client, clientAutre;
    Fournisseur fournisseur, fournisseurAutre;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); ventes.deleteAll(); achats.deleteAll(); produits.deleteAll();
        categories.deleteAll(); fournisseurs.deleteAll(); clients.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique des avances"); autreBoutique = boutique("Autre boutique");
        utilisateur(ADMIN, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(ADMIN2, Role.ADMIN, autreBoutique);
        client = client(boutique, "Client avances"); clientAutre = client(autreBoutique, "Client de l'autre boutique");
        fournisseur = fournisseur(boutique, "Fournisseur avances"); fournisseurAutre = fournisseur(autreBoutique, "Fournisseur de l'autre boutique");
        produit = produit(boutique, fournisseur, "REF-AV"); produitAutre = produit(autreBoutique, fournisseurAutre, "REF-AUTRE");
        // Les lectures directes des services (soldes, échéances) se font en tant que gérant de la première boutique.
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    private SoldePartenaireService soldes() { authentifierGerant(); return soldesService; }

    private void authentifierGerant() {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(ADMIN, "", List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        SecurityContextHolder.setContext(contexte);
    }

    @org.junit.jupiter.api.AfterEach
    void nettoyerContexte() { SecurityContextHolder.clearContext(); }

    // ---- Trop-payé et utilisation automatique de l'avance ---------------------------------------------------

    @Test
    void unClientQuiPayeTropAUneAvanceUtiliseeAutomatiquementALaVenteSuivante() {
        Vente premiere = vente(client, 10, "120000");
        assertThat(premiere.getResteAPayer()).isZero();
        assertThat(premiere.getSurplus()).isEqualByComparingTo("20000");
        assertThat(premiere.getAvanceUtilisee()).isEqualByComparingTo("0");
        assertThat(premiere.getDateEcheance()).isNull();
        // Toute la somme versée entre en trésorerie, y compris le surplus.
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("120000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("20000");
        long mouvements = caisse.count();

        Vente suivante = vente(client, 5, "0");
        assertThat(suivante.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(suivante.getResteAPayer()).isEqualByComparingTo("30000");
        assertThat(suivante.getDateEcheance()).isNotNull();
        // L'argent est déjà entré : aucun mouvement de trésorerie pour l'avance.
        assertThat(caisse.count()).isEqualTo(mouvements);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("120000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("0");
        assertThat(soldes().soldeClient(client.getId()).resteFactures()).isEqualByComparingTo("30000");
    }

    @Test
    void lAvanceEstPlafonneeAuResteEtLeSurplusRestantResteDisponible() {
        vente(client, 10, "150000");                         // 50 000 d'avance
        Vente petite = vente(client, 2, "5000");             // total 20 000, versé 5 000 : reste 15 000 couvert par l'avance
        assertThat(petite.getAvanceUtilisee()).isEqualByComparingTo("15000");
        assertThat(petite.getResteAPayer()).isZero();
        assertThat(petite.getDateEcheance()).as("couverte par l'avance : pas d'échéance").isNull();
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("35000");
    }

    @Test
    void unFournisseurPayeTropEtSonAvanceEstUtiliseeALAchatSuivant() {
        Achat premier = achat(fournisseur, 10, "120000");
        assertThat(premier.getSurplus()).isEqualByComparingTo("20000");
        assertThat(premier.getResteAPayer()).isZero();
        // Toute la somme payée sort de la trésorerie.
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-120000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("20000");
        long mouvements = caisse.count();

        Achat suivant = achat(fournisseur, 5, "0");
        assertThat(suivant.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(suivant.getResteAPayer()).isEqualByComparingTo("30000");
        assertThat(caisse.count()).isEqualTo(mouvements);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("-120000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("0");
    }

    @Test
    void sansClientUnTropPayeResteRefuseEtRienNEstEnregistre() {
        assertThatThrownBy(() -> vente(null, 10, "120000")).hasMessageContaining("ne peut pas depasser le total");
        assertThat(ventes.count()).isZero();
        assertThat(caisse.count()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(100L);
    }

    @Test
    void unReglementUlterieurResteLimiteAuResteAPayerEtUneFactureTropPayeeNEnAccepteAucun() throws Exception {
        Vente surpayee = vente(client, 10, "120000");
        mvc.perform(reglementVente(surpayee, "1", "120000").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(view().name("dashboard"));
        assertThat(ventes.findById(surpayee.getId()).orElseThrow().getMontantVerse()).isEqualByComparingTo("120000");

        Vente credit = vente(client, 10, "40000");           // total 100 000, versé 40 000, avance 20 000 : reste 40 000
        assertThat(credit.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(credit.getResteAPayer()).isEqualByComparingTo("40000");
        mvc.perform(reglementVente(credit, "40001", "40000").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ne peut pas dépasser le reste à payer")));
        mvc.perform(reglementVente(credit, "40000", "40000").with(csrf()).with(user(CAISSIER).roles("CAISSIER")))
                .andExpect(status().is3xxRedirection());
        Vente reglee = ventes.findById(credit.getId()).orElseThrow();
        assertThat(reglee.getMontantVerse()).isEqualByComparingTo("80000");
        assertThat(reglee.getResteAPayer()).isZero();
    }

    // ---- Solde de départ ------------------------------------------------------------------------------------

    @Test
    void leSoldeDepartDUnClientSeRegleEnPlusieursFoisSansDepasserLeReste() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Debiteur")
                        .param("montantSoldeDepart", "50000").param("sensSoldeDepart", "IL_ME_DOIT").param("echeanceSoldeInitial", "2030-01-15"))
                .andExpect(status().is3xxRedirection());
        Client debiteur = clients.findAllByBoutiqueId(boutique.getId()).stream().filter(c -> c.getNom().equals("Debiteur")).findFirst().orElseThrow();
        assertThat(debiteur.getSoldeInitial()).isEqualByComparingTo("50000");
        assertThat(debiteur.getEcheanceSoldeInitial()).isEqualTo(LocalDate.of(2030, 1, 15));
        assertThat(caisse.count()).as("un solde de départ n'est pas de l'argent en caisse").isZero();
        SoldePartenaire avant = soldes().soldeClient(debiteur.getId());
        assertThat(avant.resteSoldeDepart()).isEqualByComparingTo("50000");
        assertThat(avant.soldeNet()).isEqualByComparingTo("50000");
        assertThat(avant.avanceDisponible()).isZero();

        mvc.perform(get("/clients/solde/regler/" + debiteur.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Debiteur")));
        mvc.perform(reglementSolde("/clients/solde/regler/" + debiteur.getId(), "20000", "0", "BANQUE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        SoldePartenaire apres = soldes().soldeClient(debiteur.getId());
        assertThat(apres.resteSoldeDepart()).isEqualByComparingTo("30000");
        assertThat(apres.soldeDepartRegle()).isEqualByComparingTo("20000");
        assertThat(apres.soldeDepartModifiable()).isFalse();
        var mouvements = caisse.findAll();
        assertThat(mouvements).hasSize(1);
        MouvementCaisse mouvement = mouvements.getFirst();
        assertThat(mouvement.getMontant()).isEqualByComparingTo("20000");
        assertThat(mouvement.getCompte()).isEqualTo(CompteTresorerie.BANQUE);
        assertThat(mouvement.getSource()).isEqualTo("SOLDE_CLIENT");
        assertThat(mouvement.getClient().getId()).isEqualTo(debiteur.getId());
        assertThat(mouvement.getVente()).isNull();

        // Au-delà du reste : refusé, rien n'est enregistré.
        mvc.perform(reglementSolde("/clients/solde/regler/" + debiteur.getId(), "30000.01", "20000", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ne peut pas dépasser le reste du solde de départ")));
        assertThat(caisse.count()).isEqualTo(1);
        // Un formulaire périmé (règlement déjà fait depuis son ouverture) donne 409.
        mvc.perform(reglementSolde("/clients/solde/regler/" + debiteur.getId(), "10000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isConflict());
        assertThat(caisse.count()).isEqualTo(1);

        // Réglé en partie : le solde de départ ne peut plus être modifié.
        mvc.perform(post("/clients/modifier/" + debiteur.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Debiteur")
                        .param("montantSoldeDepart", "10000").param("sensSoldeDepart", "IL_ME_DOIT").param("echeanceSoldeInitial", "2030-01-15"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("a déjà servi (règlement ou avance utilisée)")));
        assertThat(clients.findById(debiteur.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("50000");
        // Le caissier règle aussi un solde de client, comme il règle une vente ; solder complètement est possible.
        mvc.perform(reglementSolde("/clients/solde/regler/" + debiteur.getId(), "30000", "20000", "CAISSE").with(csrf()).with(user(CAISSIER).roles("CAISSIER")))
                .andExpect(status().is3xxRedirection());
        assertThat(soldes().soldeClient(debiteur.getId()).resteSoldeDepart()).isZero();
        assertThat(soldes().soldeClient(debiteur.getId()).soldeNet()).isZero();
        assertThat(soldes().creancesClients().total()).isZero();
    }

    @Test
    void unSoldeDepartNonRegleResteModifiableEtSonEcheanceFaitLeRetard() throws Exception {
        parametrerSolde(client, "10000", SensSolde.IL_ME_DOIT, LocalDate.of(2020, 1, 1));
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", client.getNom())
                        .param("montantSoldeDepart", "25000").param("sensSoldeDepart", "IL_ME_DOIT").param("echeanceSoldeInitial", "2020-02-02")
                        .param("confirmationSoldeDepart", "true"))
                .andExpect(status().is3xxRedirection());
        Client modifie = clients.findById(client.getId()).orElseThrow();
        assertThat(modifie.getSoldeInitial()).isEqualByComparingTo("25000");
        assertThat(modifie.getEcheanceSoldeInitial()).isEqualTo(LocalDate.of(2020, 2, 2));
    }

    @Test
    void unSoldeJeLuiDoisDUnClientDevientUneAvanceUtiliseeALaVenteSuivante() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Avance depart")
                        .param("montantSoldeDepart", "10000").param("sensSoldeDepart", "JE_LUI_DOIS"))
                .andExpect(status().is3xxRedirection());
        Client avance = clients.findAllByBoutiqueId(boutique.getId()).stream().filter(c -> c.getNom().equals("Avance depart")).findFirst().orElseThrow();
        assertThat(avance.getSoldeInitial()).isEqualByComparingTo("-10000");
        assertThat(avance.getEcheanceSoldeInitial()).as("une avance n'a pas d'échéance").isNull();
        assertThat(soldes().soldeClient(avance.getId()).avanceDisponible()).isEqualByComparingTo("10000");
        assertThat(soldes().soldeClient(avance.getId()).soldeNet()).isEqualByComparingTo("-10000");
        assertThat(soldes().soldeClient(avance.getId()).resteSoldeDepart()).isZero();

        Vente vente = vente(avance, 3, "0");                 // total 30 000, versé 0
        assertThat(vente.getAvanceUtilisee()).isEqualByComparingTo("10000");
        assertThat(vente.getResteAPayer()).isEqualByComparingTo("20000");
        assertThat(caisse.count()).isZero();
        assertThat(soldes().soldeClient(avance.getId()).avanceDisponible()).isZero();
        assertThat(soldes().soldeClient(avance.getId()).soldeNet()).isEqualByComparingTo("20000");
        // L'avance d'un solde de départ ne peut plus être retirée une fois consommée (solde figé, même sans confirmation).
        mvc.perform(post("/clients/modifier/" + avance.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Avance depart")
                        .param("montantSoldeDepart", "0").param("sensSoldeDepart", "IL_ME_DOIT"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("a déjà servi (règlement ou avance utilisée)")));
        assertThat(clients.findById(avance.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-10000");
    }

    @Test
    void lesListesDesVentesEtDesAchatsMontrentReglesLesFacturesCouvertesParLAvance() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        Vente vente = vente(client, 2, "0");
        assertThat(vente.getAvanceUtilisee()).isEqualByComparingTo(vente.getMontantTotal());
        assertThat(vente.getResteAPayer()).isZero();
        String ventes = mvc.perform(get("/ventes").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(ventes).contains("Réglée").contains("+ avance").doesNotContain("À régler");

        parametrerSolde(fournisseur, "1000000", SensSolde.IL_ME_DOIT, null);
        Achat achat = achat(fournisseur, 2, "0");
        assertThat(achat.getResteAPayer()).isZero();
        String achats = mvc.perform(get("/achats").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(achats).contains("Réglé").contains("+ avance").doesNotContain("À régler");
    }

    @Test
    void laBoutiqueRegleUnSoldeDeDepartEnversUnFournisseurParUneSortieDeTresorerie() throws Exception {
        mvc.perform(post("/fournisseurs/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Creancier")
                        .param("montantSoldeDepart", "100000").param("sensSoldeDepart", "JE_LUI_DOIS"))
                .andExpect(status().is3xxRedirection());
        Fournisseur creancier = fournisseurs.findAllByBoutiqueId(boutique.getId()).stream().filter(f -> f.getNom().equals("Creancier")).findFirst().orElseThrow();
        assertThat(creancier.getSoldeInitial()).isEqualByComparingTo("-100000");
        assertThat(caisse.count()).isZero();
        assertThat(soldes().soldeFournisseur(creancier.getId()).resteSoldeDepart()).isEqualByComparingTo("100000");
        assertThat(soldes().soldeFournisseur(creancier.getId()).soldeNet()).isEqualByComparingTo("-100000");
        assertThat(soldes().dettesFournisseurs().total()).isEqualByComparingTo("100000");

        mvc.perform(reglementSolde("/fournisseurs/solde/regler/" + creancier.getId(), "30000", "0", "ORANGE_MONEY").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        var mouvements = caisse.findAll();
        assertThat(mouvements).hasSize(1);
        assertThat(mouvements.getFirst().getMontant()).isEqualByComparingTo("-30000");
        assertThat(mouvements.getFirst().getCompte()).isEqualTo(CompteTresorerie.ORANGE_MONEY);
        assertThat(mouvements.getFirst().getSource()).isEqualTo("SOLDE_FOURNISSEUR");
        assertThat(mouvements.getFirst().getFournisseur().getId()).isEqualTo(creancier.getId());
        assertThat(soldes().soldeFournisseur(creancier.getId()).resteSoldeDepart()).isEqualByComparingTo("70000");
        assertThat(caisse.calculerSoldeCompte(boutique.getId(), CompteTresorerie.ORANGE_MONEY)).isEqualByComparingTo("-30000");
        mvc.perform(reglementSolde("/fournisseurs/solde/regler/" + creancier.getId(), "70000.01", "30000", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk());
        assertThat(caisse.count()).isEqualTo(1);
    }

    @Test
    void unSoldeDepartPositifChezUnFournisseurEstUneAvanceUtiliseeALAchatSuivant() {
        parametrerSolde(fournisseur, "40000", SensSolde.IL_ME_DOIT, null);
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isEqualByComparingTo("40000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).soldeNet()).isEqualByComparingTo("40000");
        Achat achat = achat(fournisseur, 10, "0");           // total 100 000
        assertThat(achat.getAvanceUtilisee()).isEqualByComparingTo("40000");
        assertThat(achat.getResteAPayer()).isEqualByComparingTo("60000");
        assertThat(caisse.count()).isZero();
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).soldeNet()).isEqualByComparingTo("-60000");
    }

    // ---- Solde de départ figé après usage et confirmation du changement ------------------------------------------

    static final String MESSAGE_FIGE = "Ce solde de départ a déjà servi (règlement ou avance utilisée) : il ne peut plus être modifié. "
            + "Une correction se fera par un ajustement de solde.";
    static final String MESSAGE_CONFIRMATION = "Confirmez le changement du solde de départ.";

    private org.springframework.test.web.servlet.ResultActions modifier(String type, Long id, String nom, String montant, String sens, boolean confirme) throws Exception {
        var requete = post("/" + type + "/modifier/" + id).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", nom)
                .param("montantSoldeDepart", montant).param("sensSoldeDepart", sens);
        if (confirme) requete.param("confirmationSoldeDepart", "true");
        return mvc.perform(requete);
    }

    private static String echapper(String texte) {
        return texte.replace("'", "&#39;");
    }

    @Test
    void unSoldeClientDontLAvanceAEteConsommeeNePeutPlusEtreModifie() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        Vente vente = vente(client, 20, "0");                       // total 200 000, couvert par l'avance du solde de départ
        assertThat(vente.getAvanceUtilisee()).isEqualByComparingTo("200000");

        modifier("clients", client.getId(), client.getNom(), "10000000", "JE_LUI_DOIS", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-1000000");
        assertThat(soldes().soldeClient(client.getId()).soldeDepartModifiable()).isFalse();
        // Le sens ne peut pas non plus être inversé.
        modifier("clients", client.getId(), client.getNom(), "1000000", "IL_ME_DOIT", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-1000000");
    }

    @Test
    void unSoldeFournisseurDontLAvanceAEteConsommeeNePeutPlusEtreModifie() throws Exception {
        parametrerSolde(fournisseur, "1000000", SensSolde.IL_ME_DOIT, null);     // avance chez le fournisseur
        Achat achat = achat(fournisseur, 10, "0");                  // total 100 000
        assertThat(achat.getAvanceUtilisee()).isEqualByComparingTo("100000");

        modifier("fournisseurs", fournisseur.getId(), fournisseur.getNom(), "10000000", "IL_ME_DOIT", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(fournisseurs.findById(fournisseur.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("1000000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).soldeDepartModifiable()).isFalse();
    }

    @Test
    void uneAvanceVenueDUnTropPayeFigeAussiLeSoldeDeDepart() throws Exception {
        // Quelle que soit l'origine de l'avance consommée, le solde de départ est figé.
        parametrerSolde(client, "5000", SensSolde.IL_ME_DOIT, null);
        vente(client, 10, "150000");                                // 50 000 de trop-payé
        vente(client, 1, "0");                                      // consomme 10 000 de l'avance du trop-payé
        modifier("clients", client.getId(), client.getNom(), "6000", "IL_ME_DOIT", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("5000");
    }

    @Test
    void unSoldeJamaisUtiliseSeModifieAvecConfirmationEtPasSans() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        modifier("clients", client.getId(), client.getNom(), "10000000", "JE_LUI_DOIS", false)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(MESSAGE_CONFIRMATION)));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-1000000");
        modifier("clients", client.getId(), client.getNom(), "1000000", "IL_ME_DOIT", false)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(MESSAGE_CONFIRMATION)));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-1000000");
        modifier("clients", client.getId(), client.getNom(), "10000000", "JE_LUI_DOIS", true).andExpect(status().is3xxRedirection());
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-10000000");

        parametrerSolde(fournisseur, "2000", SensSolde.JE_LUI_DOIS, null);
        modifier("fournisseurs", fournisseur.getId(), fournisseur.getNom(), "20000", "JE_LUI_DOIS", false)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(MESSAGE_CONFIRMATION)));
        assertThat(fournisseurs.findById(fournisseur.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-2000");
        modifier("fournisseurs", fournisseur.getId(), fournisseur.getNom(), "20000", "JE_LUI_DOIS", true).andExpect(status().is3xxRedirection());
        assertThat(fournisseurs.findById(fournisseur.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-20000");
    }

    @Test
    void laCreationNExigePasLaConfirmationEtLeChampEstInterditAilleurs() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Sans confirmation")
                .param("montantSoldeDepart", "7000").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().is3xxRedirection());
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Avec confirmation")
                .param("montantSoldeDepart", "7000").param("sensSoldeDepart", "IL_ME_DOIT").param("confirmationSoldeDepart", "true"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Nom")
                .param("confirmationSoldeDepart", "true").param("confirmationSoldeDepart", "true")).andExpect(status().isBadRequest());
    }

    @Test
    void renvoyerLesMemesValeursDeSoldeNEstPasUneModificationMemeSansConfirmationOuSoldeFige() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        modifier("clients", client.getId(), "Nouveau nom", "1000000", "JE_LUI_DOIS", false).andExpect(status().is3xxRedirection());
        assertThat(clients.findById(client.getId()).orElseThrow().getNom()).isEqualTo("Nouveau nom");
        vente(client, 20, "0");                                     // le solde est maintenant figé
        modifier("clients", client.getId(), "Encore un nom", "1000000", "JE_LUI_DOIS", false).andExpect(status().is3xxRedirection());
        Client relu = clients.findById(client.getId()).orElseThrow();
        assertThat(relu.getNom()).isEqualTo("Encore un nom");
        assertThat(relu.getSoldeInitial()).isEqualByComparingTo("-1000000");
    }

    @Test
    void lesAutresChampsDUneFicheAuSoldeFigeResteModifiables() throws Exception {
        parametrerSolde(fournisseur, "1000000", SensSolde.IL_ME_DOIT, null);
        achat(fournisseur, 10, "0");
        // Le formulaire d'un solde figé n'envoie plus les champs du solde : seuls le nom et le téléphone changent.
        mvc.perform(post("/fournisseurs/modifier/" + fournisseur.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN"))
                        .param("nom", "Fournisseur renommé").param("telephone", "620000000"))
                .andExpect(status().is3xxRedirection());
        Fournisseur relu = fournisseurs.findById(fournisseur.getId()).orElseThrow();
        assertThat(relu.getNom()).isEqualTo("Fournisseur renommé");
        assertThat(relu.getTelephone()).isEqualTo("620000000");
        assertThat(relu.getSoldeInitial()).isEqualByComparingTo("1000000");
        parametrerSolde(client, "3000", SensSolde.IL_ME_DOIT, null);
        vente(client, 1, "0");
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN"))
                        .param("nom", client.getNom()).param("telephone", "611111111"))
                .andExpect(status().is3xxRedirection());
        assertThat(clients.findById(client.getId()).orElseThrow().getTelephone()).isEqualTo("611111111");
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("3000");
    }

    @Test
    void apresUnReglementPartielLeSoldeResteRefuseMemeAvecConfirmation() throws Exception {
        parametrerSolde(client, "50000", SensSolde.IL_ME_DOIT, null);
        mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "20000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        modifier("clients", client.getId(), client.getNom(), "90000", "IL_ME_DOIT", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("50000");

        parametrerSolde(fournisseur, "50000", SensSolde.JE_LUI_DOIS, null);
        mvc.perform(reglementSolde("/fournisseurs/solde/regler/" + fournisseur.getId(), "10000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().is3xxRedirection());
        modifier("fournisseurs", fournisseur.getId(), fournisseur.getNom(), "90000", "JE_LUI_DOIS", true)
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
        assertThat(fournisseurs.findById(fournisseur.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-50000");
    }

    @Test
    void uneAvanceUtiliseeDansUneAutreBoutiqueNeFigePasLeSolde() throws Exception {
        parametrerSolde(client, "1000", SensSolde.IL_ME_DOIT, null);
        parametrerSolde(fournisseur, "1000", SensSolde.IL_ME_DOIT, null);
        // Un partenaire de l'autre boutique consomme son avance : cela ne concerne pas la boutique courante.
        parametrerSolde(clientAutre, "1000000", SensSolde.JE_LUI_DOIS, null);
        Vente factureAutre = new Vente(); factureAutre.setBoutique(autreBoutique); factureAutre.setClient(clientAutre);
        factureAutre.setMontantTotal(new BigDecimal("200000")); factureAutre.setMontantVerse(BigDecimal.ZERO);
        factureAutre.setAvanceUtilisee(new BigDecimal("200000")); ventes.saveAndFlush(factureAutre);
        assertThat(soldes().soldeClient(client.getId()).soldeDepartModifiable()).isTrue();
        modifier("clients", client.getId(), client.getNom(), "2000", "IL_ME_DOIT", true).andExpect(status().is3xxRedirection());
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("2000");
        modifier("fournisseurs", fournisseur.getId(), fournisseur.getNom(), "2000", "IL_ME_DOIT", true).andExpect(status().is3xxRedirection());
        // Et le solde figé de l'autre boutique reste figé pour son gérant.
        mvc.perform(post("/clients/modifier/" + clientAutre.getId()).with(csrf()).with(user(ADMIN2).roles("ADMIN")).param("nom", "Client de l'autre boutique")
                .param("montantSoldeDepart", "5").param("sensSoldeDepart", "JE_LUI_DOIS").param("confirmationSoldeDepart", "true"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString(echapper(MESSAGE_FIGE))));
    }

    @Test
    void lEcranMontreLeSoldeFigeEnLectureSeuleAvecSonExplication() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        vente(client, 20, "0");
        parametrerSolde(fournisseur, "1000000", SensSolde.IL_ME_DOIT, null);
        achat(fournisseur, 10, "0");
        for (String url : List.of("/clients/modifier/" + client.getId(), "/fournisseurs/modifier/" + fournisseur.getId())) {
            String html = mvc.perform(get(url).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("soldeDepartFige", "soldeDepartLectureSeule", "1 000 000,00 GNF", "ajustement de solde")
                    .doesNotContain("inputMontantSoldeDepart", "inputConfirmationSoldeDepart", "name=\"montantSoldeDepart\"", "name=\"sensSoldeDepart\"");
        }
        // Après une tentative refusée, l'écran montre toujours le solde enregistré, pas la valeur forgée.
        String html = modifier("clients", client.getId(), client.getNom(), "10000000", "JE_LUI_DOIS", true).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("1 000 000,00 GNF").doesNotContain("10 000 000");
    }

    @Test
    void lEcranDUnSoldeModifiableProposeLaCaseDeConfirmationEtLApercu() throws Exception {
        parametrerSolde(client, "1000000", SensSolde.JE_LUI_DOIS, null);
        parametrerSolde(fournisseur, "400", SensSolde.IL_ME_DOIT, null);
        for (String url : List.of("/clients/modifier/" + client.getId(), "/fournisseurs/modifier/" + fournisseur.getId())) {
            String html = mvc.perform(get(url).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(html).contains("inputMontantSoldeDepart", "soldeDepartApercu", "inputConfirmationSoldeDepart", "name=\"confirmationSoldeDepart\"",
                            "data-ancien-solde", "/js/solde-depart.js")
                    .doesNotContain("soldeDepartFige", "disabled");
        }
        assertThat(mvc.perform(get("/clients/modifier/" + client.getId()).with(user(ADMIN).roles("ADMIN"))).andReturn().getResponse().getContentAsString())
                .contains("data-ancien-solde=\"-1000000.00\"");
        // La création n'a pas de case de confirmation.
        assertThat(mvc.perform(get("/clients/nouveau").with(user(ADMIN).roles("ADMIN"))).andReturn().getResponse().getContentAsString())
                .doesNotContain("inputConfirmationSoldeDepart");
        // Le caissier ne voit rien de la section.
        assertThat(mvc.perform(get("/clients/modifier/" + client.getId()).with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString())
                .doesNotContain("soldeDepartSection", "inputConfirmationSoldeDepart");
    }

    // ---- Reste à payer, échéance et retard ------------------------------------------------------------------

    @Test
    void leResteLEcheanceEtLeRetardTiennentCompteDeLAvance() {
        parametrerSolde(client, "30000", SensSolde.JE_LUI_DOIS, null);
        Vente couverte = vente(client, 2, "0");               // 20 000 couverts par 30 000 d'avance
        assertThat(couverte.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(couverte.getResteAPayer()).isZero();
        assertThat(couverte.getDateEcheance()).isNull();
        Vente partielle = vente(client, 3, "0");              // 30 000 dont 10 000 d'avance restante
        assertThat(partielle.getAvanceUtilisee()).isEqualByComparingTo("10000");
        assertThat(partielle.getResteAPayer()).isEqualByComparingTo("20000");
        assertThat(partielle.getDateEcheance()).isNotNull();

        // Avec une échéance dépassée, seule la facture qui reste à payer après l'avance est en retard.
        LocalDate hier = echeances.aujourdHui().minusDays(1);
        for (Vente v : List.of(couverte, partielle)) {
            Vente relue = ventes.findById(v.getId()).orElseThrow(); relue.setDateEcheance(hier); ventes.saveAndFlush(relue);
        }
        assertThat(ventes.findById(couverte.getId()).orElseThrow().estEnRetard(echeances.aujourdHui())).isFalse();
        assertThat(ventes.findById(partielle.getId()).orElseThrow().estEnRetard(echeances.aujourdHui())).isTrue();
        assertThat(authentifie(ADMIN, "ADMIN", () -> venteService.listerEchuesNonSoldees())).extracting(Vente::getId).containsExactly(partielle.getId());
        assertThat(soldes().creancesClients().total()).isEqualByComparingTo("20000");
        assertThat(soldes().creancesClients().enRetard()).isEqualByComparingTo("20000");
    }

    @Test
    void lesAgregatsDeLaBoutiqueSeparentCreancesDettesEtRetards() {
        parametrerSolde(client, "10000", SensSolde.IL_ME_DOIT, LocalDate.of(2020, 1, 1));       // créance échue
        Client autre = client(boutique, "Autre client"); parametrerSolde(autre, "5000", SensSolde.IL_ME_DOIT, LocalDate.of(2999, 1, 1));
        parametrerSolde(fournisseur, "7000", SensSolde.JE_LUI_DOIS, LocalDate.of(2020, 1, 1));  // dette échue
        Vente credit = vente(null, 2, "0");                                                     // client de passage : 20 000 à crédit
        Vente relue = ventes.findById(credit.getId()).orElseThrow(); relue.setDateEcheance(echeances.aujourdHui().minusDays(2)); ventes.saveAndFlush(relue);
        vente(client(boutique, "Passager 2"), 1, "10000");                                      // soldée
        achat(fournisseur, 4, "10000");                                                         // achat 40 000, reste 30 000, échéance future
        // Données de l'autre boutique : jamais comptées.
        parametrerSolde(clientAutre, "99999", SensSolde.IL_ME_DOIT, LocalDate.of(2020, 1, 1));
        var creances = soldes().creancesClients();
        assertThat(creances.total()).isEqualByComparingTo("35000");   // 10 000 + 5 000 + 20 000
        assertThat(creances.enRetard()).isEqualByComparingTo("30000"); // solde échu 10 000 + vente échue 20 000
        var dettes = soldes().dettesFournisseurs();
        assertThat(dettes.total()).isEqualByComparingTo("37000");     // 7 000 + 30 000
        assertThat(dettes.enRetard()).isEqualByComparingTo("7000");
    }

    // ---- Concurrence et doubles envois ----------------------------------------------------------------------

    @Test
    void deuxVentesSimultaneesNeConsommentJamaisDeuxFoisLaMemeAvance() throws Exception {
        parametrerSolde(client, "20000", SensSolde.JE_LUI_DOIS, null);
        CountDownLatch premiereEnregistree = new CountDownLatch(1), autoriserCommit = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Vente> a = pool.submit(() -> authentifie(ADMIN, "ADMIN", () -> new TransactionTemplate(transactions).execute(status -> {
                Vente v = venteService.effectuerVente(nouvelleVente(client, 2, "0"));
                entityManager.flush(); premiereEnregistree.countDown(); attendre(autoriserCommit); return v;
            })));
            assertThat(premiereEnregistree.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Vente> b = pool.submit(() -> authentifie(ADMIN, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(client, 2, "0"))));
            // La seconde vente attend le verrou du client (ou de la boutique) tant que la première n'est pas validée.
            assertThatThrownBy(() -> b.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            autoriserCommit.countDown();
            Vente premiere = a.get(10, TimeUnit.SECONDS), seconde = b.get(10, TimeUnit.SECONDS);
            assertThat(premiere.getAvanceUtilisee().add(seconde.getAvanceUtilisee())).isEqualByComparingTo("20000");
            assertThat(premiere.getAvanceUtilisee()).isEqualByComparingTo("20000");
            assertThat(seconde.getAvanceUtilisee()).isEqualByComparingTo("0");
        } finally { autoriserCommit.countDown(); }
        assertThat(ventes.findAll().stream().map(Vente::getAvanceUtilisee).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("20000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isZero();
    }

    @Test
    void deuxVentesLanceesEnMemeTempsConsommentExactementLAvanceDisponible() throws Exception {
        vente(client, 10, "120000");                          // 20 000 d'avance
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier depart = new CyclicBarrier(2);
            List<Future<Vente>> taches = List.of(
                    pool.submit(() -> { attendre(depart); return authentifie(ADMIN, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(client, 2, "0"))); }),
                    pool.submit(() -> { attendre(depart); return authentifie(ADMIN, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(client, 2, "0"))); }));
            for (Future<Vente> tache : taches) tache.get(15, TimeUnit.SECONDS);
        }
        BigDecimal consomme = ventes.findAll().stream().map(Vente::getAvanceUtilisee).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(consomme).isEqualByComparingTo("20000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isZero();
    }

    @Test
    void deuxAchatsSimultanesNeConsommentJamaisDeuxFoisLAvanceDuFournisseur() throws Exception {
        achat(fournisseur, 10, "120000");                     // 20 000 d'avance
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CyclicBarrier depart = new CyclicBarrier(2);
            List<Future<Achat>> taches = List.of(
                    pool.submit(() -> { attendre(depart); return authentifie(ADMIN, "ADMIN", () -> achatService.enregistrerAchat(nouvelAchat(fournisseur, 2, "0"))); }),
                    pool.submit(() -> { attendre(depart); return authentifie(ADMIN, "ADMIN", () -> achatService.enregistrerAchat(nouvelAchat(fournisseur, 2, "0"))); }));
            for (Future<Achat> tache : taches) tache.get(15, TimeUnit.SECONDS);
        }
        assertThat(achats.findAll().stream().map(Achat::getAvanceUtilisee).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("20000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isZero();
    }

    @Test
    void unDoubleEnvoiNeConsommePasDeuxFoisLAvanceNiNeCreeUnSecondMouvement() {
        parametrerSolde(client, "20000", SensSolde.JE_LUI_DOIS, null);
        String jeton = authentifie(ADMIN, "ADMIN", () -> creations.ouvrir(TypeOperationCreation.VENTE));
        var premier = authentifie(ADMIN, "ADMIN", () -> creations.creerVente(jeton, nouvelleVente(client, 5, "10000")));
        long mouvements = caisse.count();
        var copie = authentifie(ADMIN, "ADMIN", () -> creations.creerVente(jeton, nouvelleVente(client, 5, "10000")));
        assertThat(premier.dejaEnregistre()).isFalse();
        assertThat(copie.dejaEnregistre()).isTrue();
        assertThat(copie.documentId()).isEqualTo(premier.documentId());
        assertThat(ventes.count()).isEqualTo(1);
        assertThat(caisse.count()).isEqualTo(mouvements).isEqualTo(1);
        Vente vente = ventes.findById(premier.documentId()).orElseThrow();
        assertThat(vente.getAvanceUtilisee()).isEqualByComparingTo("20000");
        assertThat(vente.getResteAPayer()).isEqualByComparingTo("20000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isZero();
        assertThat(produits.findById(produit.getId()).orElseThrow().getQuantite()).isEqualTo(95L);
    }

    @Test
    void unDoubleEnvoiDUnTropPayeNeCreeQuUnSeulMouvementDeCaisse() {
        String jeton = authentifie(ADMIN, "ADMIN", () -> creations.ouvrir(TypeOperationCreation.VENTE));
        authentifie(ADMIN, "ADMIN", () -> creations.creerVente(jeton, nouvelleVente(client, 10, "120000")));
        authentifie(ADMIN, "ADMIN", () -> creations.creerVente(jeton, nouvelleVente(client, 10, "120000")));
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(caisse.calculerSoldeTotal(boutique.getId())).isEqualByComparingTo("120000");
        assertThat(soldes().soldeClient(client.getId()).avanceDisponible()).isEqualByComparingTo("20000");
    }

    @Test
    void unReglementDeSoldeLanceDeuxFoisAvecLeMemeFormulaireNEnregistreQuUneEntree() throws Exception {
        parametrerSolde(client, "50000", SensSolde.IL_ME_DOIT, null);
        for (int i = 0; i < 2; i++) {
            mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "20000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")));
        }
        assertThat(caisse.count()).isEqualTo(1);
        assertThat(soldes().soldeClient(client.getId()).resteSoldeDepart()).isEqualByComparingTo("30000");
    }

    // ---- Modification d'un achat ----------------------------------------------------------------------------

    @Test
    void laModificationDUnAchatRefuseUnTotalInferieurAuDejaPayeAvanceUtiliseeComprise() {
        parametrerSolde(fournisseur, "40000", SensSolde.IL_ME_DOIT, null);
        Achat achat = achat(fournisseur, 10, "30000");        // total 100 000, versé 30 000, avance 40 000 : reste 30 000
        assertThat(achat.getAvanceUtilisee()).isEqualByComparingTo("40000");
        Achat modifie = nouvelAchat(fournisseur, 6, null);    // 60 000 < 70 000 déjà payés
        assertThatThrownBy(() -> authentifie(ADMIN, "ADMIN", () -> { achatService.modifierAchat(achat.getId(), modifie); return null; }))
                .hasMessageContaining("inférieur au montant déjà payé").hasMessageContaining("avance utilisée comprise");
        Achat accepte = nouvelAchat(fournisseur, 7, null);    // 70 000 : tout est couvert
        authentifie(ADMIN, "ADMIN", () -> { achatService.modifierAchat(achat.getId(), accepte); return null; });
        Achat relu = achats.findById(achat.getId()).orElseThrow();
        assertThat(relu.getResteAPayer()).isZero();
        assertThat(relu.getAvanceUtilisee()).isEqualByComparingTo("40000");
    }

    @Test
    void unAchatTropPayeNePeutPlusSeModifierSiSonSurplusAEteUtilise() {
        Achat tropPaye = achat(fournisseur, 10, "120000");    // 20 000 d'avance
        achat(fournisseur, 2, "0");                           // consomme les 20 000
        Achat modifie = nouvelAchat(fournisseur, 12, null);   // 120 000 : le surplus disparaîtrait
        assertThatThrownBy(() -> authentifie(ADMIN, "ADMIN", () -> { achatService.modifierAchat(tropPaye.getId(), modifie); return null; }))
                .hasMessageContaining("déjà en partie utilisé");
        assertThat(achats.findById(tropPaye.getId()).orElseThrow().getMontantTotal()).isEqualByComparingTo("100000");
        assertThat(soldes().soldeFournisseur(fournisseur.getId()).avanceDisponible()).isZero();
    }

    // ---- Cohérence ------------------------------------------------------------------------------------------

    @Test
    void uneAvanceNegativeEstUneIncoherenceSignaleeParUneExceptionClaire() {
        Vente v = vente(client, 2, "0");
        Vente incoherente = ventes.findById(v.getId()).orElseThrow();
        incoherente.setAvanceUtilisee(new BigDecimal("500.00")); ventes.saveAndFlush(incoherente);
        assertThatThrownBy(() -> soldes().soldeClient(client.getId())).isInstanceOf(IncoherenceAvanceException.class).hasMessageContaining("négative");
        assertThatThrownBy(() -> vente(client, 1, "0")).isInstanceOf(IncoherenceAvanceException.class);
        assertThat(ventes.count()).isEqualTo(1);
    }

    @Test
    void leResteAPayerNEstJamaisNegatif() {
        Vente v = new Vente(); v.setMontantTotal(new BigDecimal("100.00")); v.setMontantVerse(new BigDecimal("120.00"));
        assertThat(v.getResteAPayer()).isEqualByComparingTo("0"); assertThat(v.getSurplus()).isEqualByComparingTo("20");
        v.setMontantVerse(new BigDecimal("30.00")); v.setAvanceUtilisee(new BigDecimal("80.00"));
        assertThat(v.getResteAPayer()).isEqualByComparingTo("0");
        v.setAvanceUtilisee(new BigDecimal("20.00"));
        assertThat(v.getResteAPayer()).isEqualByComparingTo("50");
        Achat a = new Achat(); a.setMontantTotal(new BigDecimal("100.00")); a.setMontantVerse(null);
        assertThat(a.getResteAPayer()).isEqualByComparingTo("100");
    }

    // ---- Isolation entre boutiques --------------------------------------------------------------------------

    @Test
    void unPartenaireDUneAutreBoutiqueEstIntrouvableEtSonAvanceInvisible() throws Exception {
        parametrerSolde(clientAutre, "80000", SensSolde.JE_LUI_DOIS, null);
        parametrerSolde(fournisseurAutre, "80000", SensSolde.IL_ME_DOIT, null);
        mvc.perform(get("/clients/" + clientAutre.getId() + "/avance").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/fournisseurs/" + fournisseurAutre.getId() + "/avance").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/clients/solde/regler/" + clientAutre.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(get("/fournisseurs/solde/regler/" + fournisseurAutre.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(reglementSolde("/clients/solde/regler/" + clientAutre.getId(), "1", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isNotFound());
        mvc.perform(post("/clients/modifier/" + clientAutre.getId()).with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Pirate")
                .param("montantSoldeDepart", "1").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().isNotFound());
        assertThat(clients.findById(clientAutre.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("-80000");
        // L'avance de l'autre boutique ne peut pas être utilisée : le client est refusé et rien n'est enregistré.
        assertThatThrownBy(() -> vente(clientAutre, 1, "0")).hasMessageContaining("Client introuvable");
        assertThatThrownBy(() -> achat(fournisseurAutre, 1, "0")).hasMessageContaining("Fournisseur introuvable");
        assertThat(ventes.count()).isZero(); assertThat(achats.count()).isZero();
        // Et l'inverse : l'autre boutique voit sa propre avance.
        mvc.perform(get("/clients/" + clientAutre.getId() + "/avance").with(user(ADMIN2).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avance").value("80000.00"));
    }

    // ---- Droits, binding et CSRF ---------------------------------------------------------------------------

    @Test
    void leCaissierNePeutNiSaisirNiModifierUnSoldeDeDepart() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", "Interdit")
                .param("montantSoldeDepart", "5000").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().isForbidden());
        assertThat(clients.findAllByBoutiqueId(boutique.getId())).extracting(Client::getNom).doesNotContain("Interdit");
        parametrerSolde(client, "10000", SensSolde.IL_ME_DOIT, null);
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", client.getNom())
                .param("montantSoldeDepart", "0").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().isForbidden());
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", client.getNom())
                .param("echeanceSoldeInitial", "2031-01-01")).andExpect(status().isForbidden());
        assertThat(clients.findById(client.getId()).orElseThrow().getSoldeInitial()).isEqualByComparingTo("10000");
        // Sans la section solde de départ, le caissier garde ses droits habituels et le solde enregistré est conservé.
        mvc.perform(post("/clients/modifier/" + client.getId()).with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", "Renommé par le caissier"))
                .andExpect(status().is3xxRedirection());
        Client relu = clients.findById(client.getId()).orElseThrow();
        assertThat(relu.getNom()).isEqualTo("Renommé par le caissier");
        assertThat(relu.getSoldeInitial()).isEqualByComparingTo("10000");
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", "Simple client"))
                .andExpect(status().is3xxRedirection());
        // Les fiches fournisseur restent réservées au gérant.
        mvc.perform(post("/fournisseurs/enregistrer").with(csrf()).with(user(CAISSIER).roles("CAISSIER")).param("nom", "Interdit")
                .param("montantSoldeDepart", "5000").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().isForbidden());
        // Le formulaire du caissier ne propose pas la section ; celui du gérant la propose.
        mvc.perform(get("/clients/nouveau").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("inputMontantSoldeDepart"))));
        mvc.perform(get("/clients/nouveau").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("inputMontantSoldeDepart")));
        mvc.perform(get("/fournisseurs/nouveau").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("inputMontantSoldeDepart")));
    }

    @Test
    void lesDroitsDeReglementDuSoldeSuiventCeuxDesReglementsDeFactures() throws Exception {
        parametrerSolde(client, "10000", SensSolde.IL_ME_DOIT, null);
        parametrerSolde(fournisseur, "10000", SensSolde.JE_LUI_DOIS, null);
        // Client : gérant et caissier, comme les règlements de ventes.
        mvc.perform(get("/clients/solde/regler/" + client.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk());
        mvc.perform(get("/clients/" + client.getId() + "/avance").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk());
        // Fournisseur : gérant seulement, comme les règlements d'achats.
        mvc.perform(get("/fournisseurs/solde/regler/" + fournisseur.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(reglementSolde("/fournisseurs/solde/regler/" + fournisseur.getId(), "1000", "0", "CAISSE").with(csrf()).with(user(CAISSIER).roles("CAISSIER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/fournisseurs/" + fournisseur.getId() + "/avance").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        assertThat(caisse.count()).isZero();
        // CSRF obligatoire.
        mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "1000", "0", "CAISSE").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isForbidden());
        assertThat(caisse.count()).isZero();
    }

    @Test
    void lesChampsCalculesOuInconnusNeSontJamaisLiesDepuisLeNavigateur() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Triche").param("soldeInitial", "-999999"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/fournisseurs/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Triche").param("soldeInitial", "999999"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Triche").param("soldeDepartSoumis", "true")
                .param("montantSoldeDepart", "1").param("montantSoldeDepart", "2")).andExpect(status().isBadRequest());
        String jeton = authentifie(ADMIN, "ADMIN", () -> creations.ouvrir(TypeOperationCreation.VENTE));
        mvc.perform(post("/ventes/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("jetonCreation", jeton).param("client", client.getId().toString())
                        .param("lignes[0].produit.id", produit.getId().toString()).param("lignes[0].quantite", "1").param("avanceUtilisee", "99999"))
                .andExpect(status().isBadRequest());
        mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "1", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("soldeInitial", "1"))
                .andExpect(status().isBadRequest());
        assertThat(clients.findAllByBoutiqueId(boutique.getId())).extracting(Client::getNom).doesNotContain("Triche");
        assertThat(fournisseurs.findAllByBoutiqueId(boutique.getId())).extracting(Fournisseur::getNom).doesNotContain("Triche");
        assertThat(ventes.count()).isZero();
    }

    @Test
    void unSensInconnuOuUnMontantNegatifSontRefuses() throws Exception {
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Mauvais sens")
                .param("montantSoldeDepart", "100").param("sensSoldeDepart", "AUTRE")).andExpect(status().isBadRequest());
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Negatif")
                .param("montantSoldeDepart", "-100").param("sensSoldeDepart", "IL_ME_DOIT")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("ne peut pas être négatif")));
        mvc.perform(post("/clients/enregistrer").with(csrf()).with(user(ADMIN).roles("ADMIN")).param("nom", "Sans sens")
                .param("montantSoldeDepart", "100")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Choisissez le sens")));
        assertThat(clients.findAllByBoutiqueId(boutique.getId())).extracting(Client::getNom).doesNotContain("Mauvais sens", "Negatif", "Sans sens");
    }

    // ---- Lecture et affichage ----------------------------------------------------------------------------------

    @Test
    void lePointDAccesRenvoieLAvanceEnJsonPourLaBoutiqueCourante() throws Exception {
        vente(client, 10, "120000");
        achat(fournisseur, 10, "130000");
        mvc.perform(get("/clients/" + client.getId() + "/avance").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.avance").value("20000.00"));
        mvc.perform(get("/fournisseurs/" + fournisseur.getId() + "/avance").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.avance").value("30000.00"));
    }

    @Test
    void lesListesLesFacturesEtLesReleveCumulesAfficheSoldeEtAvance() throws Exception {
        parametrerSolde(client, "10000", SensSolde.IL_ME_DOIT, null);
        mvc.perform(reglementSolde("/clients/solde/regler/" + client.getId(), "4000", "0", "CAISSE").with(csrf()).with(user(ADMIN).roles("ADMIN")));
        Vente tropPayee = vente(client, 10, "120000");
        Vente creditAvecAvance = vente(client, 5, "0");
        parametrerSolde(fournisseur, "5000", SensSolde.JE_LUI_DOIS, null);
        Achat achat = achat(fournisseur, 10, "110000");

        mvc.perform(get("/clients").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Solde")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Régler le solde de départ")));
        mvc.perform(get("/fournisseurs").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Il me doit")));
        String factureSurplus = mvc.perform(get("/factures/vente/" + tropPayee.getId()).with(user(CAISSIER).roles("CAISSIER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(factureSurplus).contains("Surplus versé").contains("20 000.00");
        String factureAvance = mvc.perform(get("/factures/vente/" + creditAvecAvance.getId()).with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(factureAvance).contains("Avance utilisée");
        mvc.perform(get("/factures/achat/" + achat.getId()).with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Surplus versé")));
        String releve = mvc.perform(get("/factures/client/" + client.getId() + "/cumule").with(user(CAISSIER).roles("CAISSIER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(releve).contains("Solde de départ", "Règlement du solde de départ", "Reste du solde de départ", "Avance disponible");
        String releveFournisseur = mvc.perform(get("/factures/fournisseur/" + fournisseur.getId() + "/cumule").with(user(ADMIN).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(releveFournisseur).contains("Solde de départ", "Avance disponible");
        mvc.perform(get("/ventes/nouveau").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("avanceDisponible")));
        mvc.perform(get("/achats/nouveau").with(user(ADMIN).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("avanceDisponible")));
    }

    // ---- Outils -----------------------------------------------------------------------------------------------

    private MockHttpServletRequestBuilder reglementVente(Vente vente, String montant, String dejaVerse) {
        return post("/ventes/regler/" + vente.getId()).param("montant", montant).param("montantVerseAttendu", dejaVerse).param("comptePaiement", "CAISSE");
    }

    private MockHttpServletRequestBuilder reglementSolde(String url, String montant, String dejaRegle, String compte) {
        return post(url).param("montant", montant).param("reglementAttendu", dejaRegle).param("comptePaiement", compte);
    }

    private Vente vente(Client acheteur, int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> venteService.effectuerVente(nouvelleVente(acheteur, quantite, verse)));
    }

    private Vente nouvelleVente(Client acheteur, int quantite, String verse) {
        Vente vente = new Vente(); vente.setBoutique(boutique);
        if (acheteur != null) { Client reference = new Client(); reference.setId(acheteur.getId()); vente.setClient(reference); }
        vente.setMontantVerse(new BigDecimal(verse));
        Produit reference = new Produit(); reference.setId(produit.getId());
        DetailVente ligne = new DetailVente(); ligne.setProduit(reference); ligne.setQuantite(quantite); vente.getLignes().add(ligne);
        return vente;
    }

    private Achat achat(Fournisseur vendeur, int quantite, String verse) {
        return authentifie(ADMIN, "ADMIN", () -> achatService.enregistrerAchat(nouvelAchat(vendeur, quantite, verse)));
    }

    /** Achat à 10 000 GNF l'unité : 10 unités = 100 000 GNF. Un versement nul est laissé absent (modification). */
    private Achat nouvelAchat(Fournisseur vendeur, int quantite, String verse) {
        Achat achat = new Achat(); achat.setBoutique(boutique);
        Fournisseur reference = new Fournisseur(); reference.setId(vendeur.getId()); achat.setFournisseur(reference);
        if (verse != null) achat.setMontantVerse(new BigDecimal(verse));
        Produit ref = new Produit(); ref.setId(produit.getId());
        DetailAchat ligne = new DetailAchat(); ligne.setProduit(ref); ligne.setQuantite(quantite);
        ligne.setPrixAchatUnitaire(new BigDecimal("10000.00")); ligne.setPrixVenteUnitaire(new BigDecimal("10000.00")); achat.getLignes().add(ligne);
        return achat;
    }

    /** Pose directement un solde de départ (sans passer par l'écran) pour préparer un scénario. */
    private void parametrerSolde(Client cible, String montant, SensSolde sens, LocalDate echeance) {
        Client c = clients.findById(cible.getId()).orElseThrow();
        c.setSoldeInitial(signe(montant, sens)); c.setEcheanceSoldeInitial(echeance); clients.saveAndFlush(c);
    }

    private void parametrerSolde(Fournisseur cible, String montant, SensSolde sens, LocalDate echeance) {
        Fournisseur f = fournisseurs.findById(cible.getId()).orElseThrow();
        f.setSoldeInitial(signe(montant, sens)); f.setEcheanceSoldeInitial(echeance); fournisseurs.saveAndFlush(f);
    }

    private static BigDecimal signe(String montant, SensSolde sens) {
        BigDecimal valeur = new BigDecimal(montant).setScale(2);
        return sens == SensSolde.JE_LUI_DOIS ? valeur.negate() : valeur;
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

    private static void attendre(CountDownLatch latch) {
        try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static void attendre(CyclicBarrier barriere) {
        try { barriere.await(15, TimeUnit.SECONDS); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
