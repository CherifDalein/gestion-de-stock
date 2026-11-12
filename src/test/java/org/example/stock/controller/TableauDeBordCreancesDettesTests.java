package org.example.stock.controller;

import org.example.stock.enums.Role;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.SoldePartenaireService;
import org.example.stock.service.TableauDeBordService.CreancesDettes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tableau de bord, seconde partie : créances clients, dettes fournisseurs, avances, position nette et retards.
 * Jeu de données fixe, date fixée par l'horloge injectable de {@code EcheanceService}, deux boutiques (plus une
 * troisième vide) et les rôles gérant et caissier. Chaque chiffre est comparé au calcul fait à la main.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:tableau-creances-dettes;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class TableauDeBordCreancesDettesTests {
    static final LocalDate JOUR = LocalDate.of(2026, 6, 15);
    static final String GERANT_A = "cd-gerant-a@example.test", CAISSIER_A = "cd-caissier-a@example.test",
            GERANT_B = "cd-gerant-b@example.test", GERANT_C = "cd-gerant-c@example.test";

    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationCreationRepository operations;
    @MockitoBean(name = "recoveryClock") Clock horloge;
    @MockitoSpyBean SoldePartenaireService soldes;

    Boutique boutiqueA, boutiqueB, boutiqueC;
    Client aminata, boubacar, mariama;
    Fournisseur sidi, kaba, diallo;

    @BeforeEach
    void preparer() {
        Instant instant = JOUR.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant();
        when(horloge.instant()).thenReturn(instant);
        when(horloge.getZone()).thenReturn(ZoneId.systemDefault());
        when(horloge.withZone(any())).thenReturn(horloge);
        operations.deleteAll(); mouvements.deleteAll(); ventes.deleteAll(); achats.deleteAll();
        clients.deleteAll(); fournisseurs.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();

        boutiqueA = boutique("Boutique créances A"); boutiqueB = boutique("Boutique créances B"); boutiqueC = boutique("Boutique créances C");
        utilisateur(GERANT_A, Role.ADMIN, boutiqueA); utilisateur(CAISSIER_A, Role.CAISSIER, boutiqueA);
        utilisateur(GERANT_B, Role.ADMIN, boutiqueB); utilisateur(GERANT_C, Role.ADMIN, boutiqueC);

        // --- Boutique A, clients
        aminata = client(boutiqueA, "Aminata Camara", "0", null);
        vente(boutiqueA, aminata, "100000", "30000", "0", "2026-05-01");   // reste 70 000, en retard
        vente(boutiqueA, aminata, "40000", "0", "0", "2026-06-20");        // reste 40 000, pas échu
        vente(boutiqueA, aminata, "10000", "0", "0", "2026-06-15");        // échéance aujourd'hui : pas en retard
        boubacar = client(boutiqueA, "Boubacar Bah", "200000", "2026-04-10");   // il me doit 200 000
        reglement(boutiqueA, boubacar, null, "50000", "SOLDE_CLIENT");           // reste 150 000, en retard
        vente(boutiqueA, boubacar, "20000", "20000", "0", null);                  // soldée
        mariama = client(boutiqueA, "Mariama Sow", "-20000", null);               // avance de départ 20 000
        vente(boutiqueA, mariama, "10000", "60000", "0", null);                   // surplus 50 000
        vente(boutiqueA, mariama, "5000", "0", "5000", null);                     // avance utilisée 5 000 : avance 65 000
        vente(boutiqueA, null, "30000", "0", "0", "2026-05-30");                  // vente sans client en retard
        // Créances = 120 000 + 150 000 + 30 000 = 300 000 ; en retard = 70 000 + 150 000 + 30 000 = 250 000.

        // --- Boutique A, fournisseurs
        sidi = fournisseur(boutiqueA, "Sidi Import", "0", null);
        achat(boutiqueA, sidi, "80000", "20000", "2026-05-10");                   // reste 60 000, en retard
        achat(boutiqueA, sidi, "25000", "0", "2026-07-01");                       // reste 25 000, pas échu
        kaba = fournisseur(boutiqueA, "Kaba Grossiste", "-90000", "2026-06-01");  // je lui dois 90 000
        reglement(boutiqueA, null, kaba, "-40000", "SOLDE_FOURNISSEUR");          // reste 50 000, en retard
        diallo = fournisseur(boutiqueA, "Diallo Distribution", "30000", null);    // avance de départ 30 000
        achat(boutiqueA, diallo, "10000", "15000", null);                         // surplus 5 000 : avance 35 000
        achat(boutiqueA, null, "7000", "0", "2026-06-01");                        // achat sans fournisseur en retard
        // Dettes = 85 000 + 50 000 + 7 000 = 142 000 ; en retard = 60 000 + 50 000 + 7 000 = 117 000.
        // Position nette = 300 000 − 142 000 − 65 000 + 35 000 = 128 000.

        // --- Boutique B : jamais visible depuis A
        Client secret = client(boutiqueB, "CLIENT_SECRET_B", "999000", "2026-01-01");
        vente(boutiqueB, secret, "888000", "0", "0", "2026-01-02");
        vente(boutiqueB, null, "777000", "0", "0", "2026-01-03");
        Fournisseur fournisseurSecret = fournisseur(boutiqueB, "FOURNISSEUR_SECRET_B", "-666000", "2026-01-01");
        achat(boutiqueB, fournisseurSecret, "555000", "0", "2026-01-02");
    }

    @Test
    void uneIncoherenceDeSoldeNePriveLeGerantQueDesCreancesEtDettes() throws Exception {
        doThrow(new org.example.stock.service.IncoherenceAvanceException("Solde incohérent de test"))
                .when(soldes).creancesClients();
        MvcResult resultat = mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        assertThat(resultat.getModelAndView().getModel()).doesNotContainKey("creancesDettes")
                .containsEntry("creancesDettesIncoherentes", true)
                .containsKeys("indicateursJour", "valeurStock");
        assertThat(resultat.getResponse().getContentAsString()).contains("Les créances et les dettes ne peuvent pas être affichées");
    }

    @Test
    void lesChiffresDuGerantCorrespondentAuCalculALaMain() throws Exception {
        MvcResult resultat = mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        CreancesDettes cd = attribut(resultat);
        assertThat(cd.creances()).isEqualByComparingTo("300000");
        assertThat(cd.creancesEnRetard()).isEqualByComparingTo("250000");
        assertThat(cd.dettes()).isEqualByComparingTo("142000");
        assertThat(cd.dettesEnRetard()).isEqualByComparingTo("117000");
        assertThat(cd.avancesClients()).isEqualByComparingTo("65000");
        assertThat(cd.avancesFournisseurs()).isEqualByComparingTo("35000");
        assertThat(cd.positionNette()).isEqualByComparingTo("128000");

        assertThat(cd.clientsEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("Boubacar Bah", "Aminata Camara");
        assertThat(cd.clientsEnRetard().partenaires().get(0).montant()).isEqualByComparingTo("150000");
        assertThat(cd.clientsEnRetard().partenaires().get(0).plusAncienneEcheance()).isEqualTo(LocalDate.of(2026, 4, 10));
        assertThat(cd.clientsEnRetard().partenaires().get(1).montant()).isEqualByComparingTo("70000");
        assertThat(cd.clientsEnRetard().partenaires().get(1).plusAncienneEcheance()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(cd.clientsEnRetard().total()).isEqualTo(2);
        assertThat(cd.clientsEnRetard().sansPartenaire()).isEqualByComparingTo("30000");

        assertThat(cd.fournisseursEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("Sidi Import", "Kaba Grossiste");
        assertThat(cd.fournisseursEnRetard().partenaires().get(0).montant()).isEqualByComparingTo("60000");
        assertThat(cd.fournisseursEnRetard().partenaires().get(0).plusAncienneEcheance()).isEqualTo(LocalDate.of(2026, 5, 10));
        assertThat(cd.fournisseursEnRetard().partenaires().get(1).montant()).isEqualByComparingTo("50000");
        assertThat(cd.fournisseursEnRetard().partenaires().get(1).plusAncienneEcheance()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(cd.fournisseursEnRetard().sansPartenaire()).isEqualByComparingTo("7000");

        // Les totaux sont la somme des lignes en retard et des ventes sans client.
        assertThat(cd.clientsEnRetard().partenaires().stream().map(p -> p.montant()).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(cd.clientsEnRetard().sansPartenaire())).isEqualByComparingTo(cd.creancesEnRetard());
        assertThat(cd.fournisseursEnRetard().partenaires().stream().map(p -> p.montant()).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(cd.fournisseursEnRetard().sansPartenaire())).isEqualByComparingTo(cd.dettesEnRetard());
    }

    @Test
    void laPageAfficheLesChiffresLesListesEtLesLiensDesReleves() throws Exception {
        String page = mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Créances clients", "Dettes fournisseurs", "Avances", "Position nette",
                        "300 000.00", "250 000.00", "142 000.00", "117 000.00", "65 000.00", "35 000.00", "128 000.00",
                        "Créances − dettes − avances des clients + avances chez les fournisseurs",
                        "Clients en retard", "Fournisseurs à payer en retard",
                        "Boubacar Bah", "Aminata Camara", "150 000.00", "70 000.00", "10/04/2026", "01/05/2026",
                        "Sidi Import", "Kaba Grossiste", "60 000.00", "50 000.00", "10/05/2026", "01/06/2026",
                        "Ventes sans client en retard", "30 000.00", "Achats sans fournisseur en retard", "7 000.00",
                        "href=\"/factures/client/" + boubacar.getId() + "/cumule\"",
                        "href=\"/factures/client/" + aminata.getId() + "/cumule\"",
                        "href=\"/factures/fournisseur/" + sidi.getId() + "/cumule\"",
                        "href=\"/factures/fournisseur/" + kaba.getId() + "/cumule\"")
                .doesNotContain("Aucun client en retard", "Aucun fournisseur à payer en retard", "CLIENT_SECRET_B",
                        "FOURNISSEUR_SECRET_B", "Mariama Sow", "Diallo Distribution", "888 000.00", "555 000.00");
        // Boubacar (150 000) précède Aminata (70 000), Sidi précède Kaba.
        assertThat(page.indexOf("Boubacar Bah")).isLessThan(page.indexOf("Aminata Camara"));
        assertThat(page.indexOf("Sidi Import")).isLessThan(page.indexOf("Kaba Grossiste"));
    }

    @Test
    void lEcheanceDuJourNEstPasEnRetardEtLeLendemainOui() throws Exception {
        // L'échéance du 15/06 (10 000 de la vente d'Aminata) passe en retard le 16/06.
        fixer(JOUR.plusDays(1));
        CreancesDettes cd = attribut(mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andReturn());
        assertThat(cd.creances()).isEqualByComparingTo("300000");
        assertThat(cd.creancesEnRetard()).isEqualByComparingTo("260000");
        assertThat(cd.clientsEnRetard().partenaires().get(1).montant()).isEqualByComparingTo("80000");
        assertThat(cd.clientsEnRetard().partenaires().get(1).plusAncienneEcheance()).isEqualTo(LocalDate.of(2026, 5, 1));
    }

    @Test
    void lesListesSontLimiteesADixEtTrieesDuPlusGrandAuPlusPetit() throws Exception {
        // 12 clients et 12 fournisseurs en retard, montants 1 000 à 12 000.
        for (int i = 1; i <= 12; i++) {
            Client c = client(boutiqueA, "Retard client " + String.format("%02d", i), "0", null);
            vente(boutiqueA, c, String.valueOf(i * 1000 + 1_000_000), "1000000", "0", "2026-03-" + String.format("%02d", i));
            Fournisseur f = fournisseur(boutiqueA, "Retard fournisseur " + String.format("%02d", i), "0", null);
            achat(boutiqueA, f, String.valueOf(i * 1000 + 1_000_000), "1000000", "2026-03-" + String.format("%02d", i));
        }
        CreancesDettes cd = attribut(mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andReturn());
        // Montants de 1 000 à 12 000 : les 10 plus grands sont 12 000 à 3 000 ; Boubacar (150 000) et Aminata (70 000) passent devant.
        assertThat(cd.clientsEnRetard().partenaires()).hasSize(10);
        assertThat(cd.clientsEnRetard().total()).isEqualTo(14);
        assertThat(cd.clientsEnRetard().autres()).isEqualTo(4);
        assertThat(cd.clientsEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("Boubacar Bah", "Aminata Camara",
                "Retard client 12", "Retard client 11", "Retard client 10", "Retard client 09", "Retard client 08",
                "Retard client 07", "Retard client 06", "Retard client 05");
        assertThat(cd.clientsEnRetard().partenaires()).extracting(p -> p.montant())
                .isSortedAccordingTo(java.util.Comparator.<BigDecimal>reverseOrder());
        assertThat(cd.fournisseursEnRetard().partenaires()).hasSize(10);
        assertThat(cd.fournisseursEnRetard().total()).isEqualTo(14);
        assertThat(cd.fournisseursEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("Sidi Import", "Kaba Grossiste",
                "Retard fournisseur 12", "Retard fournisseur 11", "Retard fournisseur 10", "Retard fournisseur 09",
                "Retard fournisseur 08", "Retard fournisseur 07", "Retard fournisseur 06", "Retard fournisseur 05");
        // Les totaux ne dépendent pas de la limite : 300 000 + 78 000 (1 000 + … + 12 000) de créances en retard en plus.
        assertThat(cd.creancesEnRetard()).isEqualByComparingTo("328000");
        assertThat(cd.dettesEnRetard()).isEqualByComparingTo("195000");
        String page = mvc.perform(get("/").with(user(GERANT_A).roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Retard client 05", "4 autres en retard, non listés").doesNotContain("Retard client 04");
    }

    @Test
    void uneBoutiqueSansRetardAfficheDesMessagesSobres() throws Exception {
        MvcResult resultat = mvc.perform(get("/").with(user(GERANT_C).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        CreancesDettes cd = attribut(resultat);
        assertThat(cd.creances()).isZero();
        assertThat(cd.dettes()).isZero();
        assertThat(cd.positionNette()).isZero();
        assertThat(cd.clientsEnRetard().partenaires()).isEmpty();
        assertThat(cd.fournisseursEnRetard().partenaires()).isEmpty();
        assertThat(resultat.getResponse().getContentAsString()).contains("Aucun client en retard", "Aucun fournisseur à payer en retard")
                .doesNotContain("Ventes sans client en retard", "Achats sans fournisseur en retard", "CLIENT_SECRET_B", "999 000.00");
    }

    @Test
    void chaqueBoutiqueNeVoitQueSesPropresChiffres() throws Exception {
        MvcResult resultat = mvc.perform(get("/").with(user(GERANT_B).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        CreancesDettes cd = attribut(resultat);
        // B : créances 999 000 + 888 000 + 777 000 = 2 664 000 ; dettes 666 000 + 555 000 = 1 221 000 ; tout est en retard.
        assertThat(cd.creances()).isEqualByComparingTo("2664000");
        assertThat(cd.creancesEnRetard()).isEqualByComparingTo("2664000");
        assertThat(cd.dettes()).isEqualByComparingTo("1221000");
        assertThat(cd.dettesEnRetard()).isEqualByComparingTo("1221000");
        assertThat(cd.avancesClients()).isZero();
        assertThat(cd.avancesFournisseurs()).isZero();
        assertThat(cd.positionNette()).isEqualByComparingTo("1443000");
        assertThat(cd.clientsEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("CLIENT_SECRET_B");
        assertThat(cd.clientsEnRetard().partenaires().get(0).montant()).isEqualByComparingTo("1887000");
        assertThat(cd.clientsEnRetard().sansPartenaire()).isEqualByComparingTo("777000");
        assertThat(cd.fournisseursEnRetard().partenaires()).extracting(p -> p.nom()).containsExactly("FOURNISSEUR_SECRET_B");
        assertThat(resultat.getResponse().getContentAsString()).doesNotContain("Boubacar", "Sidi Import", "300 000.00", "128 000.00");
    }

    @Test
    void leCaissierNeRecoitRienDeCettePartieEtRienNEstCalcule() throws Exception {
        clearInvocations(soldes);
        MvcResult resultat = mvc.perform(get("/").with(user(CAISSIER_A).roles("CAISSIER")))
                .andExpect(status().isOk())
                .andExpect(model().attributeDoesNotExist("creancesDettes", "creances", "dettes", "positionNette",
                        "clientsEnRetard", "fournisseursEnRetard", "avancesClients", "avancesFournisseurs"))
                .andReturn();
        // Aucun calcul de solde n'est même lancé pour lui.
        verifyNoInteractions(soldes);
        for (Object valeur : resultat.getModelAndView().getModel().values()) {
            assertThat(valeur).isNotInstanceOf(CreancesDettes.class);
        }
        assertThat(resultat.getResponse().getContentAsString())
                .doesNotContain("Créances", "Dettes fournisseurs", "Position nette", "Clients en retard", "Fournisseurs à payer en retard",
                        "300 000.00", "250 000.00", "142 000.00", "117 000.00", "65 000.00", "35 000.00", "128 000.00",
                        "Boubacar", "Aminata", "Sidi Import", "Kaba", "/cumule", "Aucun client en retard");
    }

    @Test
    void unVisiteurNeRecoitAucuneCreance() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(model().attributeDoesNotExist("creancesDettes"));
    }

    // --- Outils

    private void fixer(LocalDate jour) {
        when(horloge.instant()).thenReturn(jour.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant());
    }

    private static CreancesDettes attribut(MvcResult resultat) {
        Object valeur = resultat.getModelAndView().getModel().get("creancesDettes");
        assertThat(valeur).as("creancesDettes").isInstanceOf(CreancesDettes.class);
        return (CreancesDettes) valeur;
    }

    private Boutique boutique(String nom) {
        Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b);
    }

    private void utilisateur(String email, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setNom("Compte " + email); u.setEmail(email); u.setRole(role); u.setBoutique(boutique);
        u.setMotDePasse(passwordEncoder.encode("Mot de passe de test 2026 !"));
        utilisateurs.saveAndFlush(u);
    }

    private Client client(Boutique boutique, String nom, String soldeInitial, String echeance) {
        Client c = new Client(); c.setBoutique(boutique); c.setNom(nom); c.setSoldeInitial(new BigDecimal(soldeInitial));
        c.setEcheanceSoldeInitial(echeance == null ? null : LocalDate.parse(echeance));
        return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique boutique, String nom, String soldeInitial, String echeance) {
        Fournisseur f = new Fournisseur(); f.setBoutique(boutique); f.setNom(nom); f.setSoldeInitial(new BigDecimal(soldeInitial));
        f.setEcheanceSoldeInitial(echeance == null ? null : LocalDate.parse(echeance));
        return fournisseurs.saveAndFlush(f);
    }

    private void vente(Boutique boutique, Client client, String total, String verse, String avanceUtilisee, String echeance) {
        Vente v = new Vente(); v.setBoutique(boutique); v.setClient(client); v.setDateVente(LocalDateTime.of(2026, 3, 1, 9, 0));
        v.setMontantTotal(new BigDecimal(total)); v.setMontantVerse(new BigDecimal(verse)); v.setAvanceUtilisee(new BigDecimal(avanceUtilisee));
        v.setDateEcheance(echeance == null ? null : LocalDate.parse(echeance));
        ventes.saveAndFlush(v);
    }

    private void achat(Boutique boutique, Fournisseur fournisseur, String total, String verse, String echeance) {
        Achat a = new Achat(); a.setBoutique(boutique); a.setFournisseur(fournisseur); a.setDateAchat(LocalDateTime.of(2026, 3, 1, 9, 0));
        a.setMontantTotal(new BigDecimal(total)); a.setMontantVerse(new BigDecimal(verse));
        a.setDateEcheance(echeance == null ? null : LocalDate.parse(echeance));
        achats.saveAndFlush(a);
    }

    /** Règlement d'un solde de départ : mouvement de trésorerie rattaché au partenaire, sans facture. */
    private void reglement(Boutique boutique, Client client, Fournisseur fournisseur, String montant, String source) {
        MouvementCaisse m = new MouvementCaisse(); m.setCompte(org.example.stock.support.ComptesDeTest.de(boutique, org.example.stock.enums.TypeCompteTresorerie.ESPECES)); m.setBoutique(boutique); m.setClient(client); m.setFournisseur(fournisseur);
        m.setDateMouvement(LocalDateTime.of(2026, 5, 20, 10, 0)); m.setMontant(new BigDecimal(montant));
        m.setType(montant.startsWith("-") ? "SORTIE" : "ENTREE"); m.setSource(source); m.setMotif("Règlement du solde de départ");
        mouvements.saveAndFlush(m);
    }
}
