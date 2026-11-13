package org.example.stock.releve;

import org.example.stock.enums.Role;
import org.example.stock.enums.StatutReleve;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.*;
import org.example.stock.support.ComptesDeTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Rapprochement bancaire (étape 3c) : propositions, pointage manuel, enregistrement, état, validation, gel, chaînage, droits et liste blanche. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:rapprochement-bancaire;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class RapprochementBancaireTests {
    static final String GERANT = "gerant-rb@example.test", CAISSIER = "caissier-rb@example.test", AUTRE_GERANT = "gerant2-rb@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired OperationTresorerieRepository operations;
    @Autowired ReleveBancaireRepository relevesRepo;
    @Autowired LigneReleveRepository lignesRepo;
    @Autowired RapprochementMouvementRepository liens;
    @Autowired CorrespondanceImportRepository memorisees;
    @Autowired RapprochementRepository rapprochements;
    @Autowired RefusPropositionRepository refus;
    @Autowired EvenementReleveRepository evenements;
    @Autowired ReleveBancaireService releves;
    @Autowired RapprochementService pointage;
    @Autowired ValidationReleveService validation;
    @Autowired EtatRapprochementService etats;
    @Autowired CaisseService caisse;
    @Autowired MockMvc mvc;

    Boutique boutique, autre;
    CompteTresorerieBoutique banque, orange, especes, banqueAutre;

    @BeforeEach
    void preparer() {
        evenements.deleteAll(); refus.deleteAll(); lignesRepo.findAll().forEach(l -> { l.setRapprochement(null); lignesRepo.save(l); });
        liens.deleteAll(); rapprochements.deleteAll(); lignesRepo.deleteAll(); memorisees.deleteAll(); relevesRepo.deleteAll();
        mouvements.deleteAll(); operations.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique rapprochement"); autre = boutique("Autre boutique rapprochement");
        utilisateur(GERANT, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(AUTRE_GERANT, Role.ADMIN, autre);
        banque = ComptesDeTest.de(boutique, TypeCompteTresorerie.BANQUE);
        orange = ComptesDeTest.de(boutique, TypeCompteTresorerie.ORANGE_MONEY);
        especes = ComptesDeTest.de(boutique, TypeCompteTresorerie.ESPECES);
        banqueAutre = ComptesDeTest.de(autre, TypeCompteTresorerie.BANQUE);
        mouvement(banque, "1000000", "2025-02-20T09:00", "SOLDE_INITIAL");
        mouvement(banqueAutre, "777", "2025-02-20T09:00", "SOLDE_INITIAL");
        authentifier(GERANT, "ADMIN");
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    // ---- Scénario de référence ------------------------------------------------------------------------------------

    /** Relevé de mars : 3 lignes (+500 000, -200 000, -15 000), clôture 1 285 000 ; l'appli a 3 mouvements dont un « double » du virement. */
    private record Jeu(ReleveBancaire releve, LigneReleve virement, LigneReleve cheque, LigneReleve frais, MouvementCaisse proche, MouvementCaisse eloigne, MouvementCaisse sortie) { }

    private Jeu jeuDeMars(String cloture) {
        ReleveBancaire r = releves.creer(banque.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), new BigDecimal("1000000"), new BigDecimal(cloture), "jeton-" + System.nanoTime());
        LigneReleve l1 = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 5), "Virement client A", "V1", new BigDecimal("500000"));
        LigneReleve l2 = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 10), "Cheque 123", null, new BigDecimal("-200000"));
        LigneReleve l3 = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 20), "Frais de tenue de compte", null, new BigDecimal("-15000"));
        MouvementCaisse proche = mouvement(banque, "500000", "2025-03-04T10:00", "ENCAISSEMENT");
        MouvementCaisse eloigne = mouvement(banque, "500000", "2025-03-07T10:00", "ENCAISSEMENT");
        MouvementCaisse sortie = mouvement(banque, "-200000", "2025-03-12T10:00", "PAIEMENT");
        return new Jeu(r, l1, l2, l3, proche, eloigne, sortie);
    }

    @Test
    void lesPropositionsRetiennentLaDatePlusProcheEtNeReutilisentJamaisUnMouvement() {
        Jeu j = jeuDeMars("1285000");
        var ecran = pointage.ecran(j.releve().getId(), false);
        assertThat(ecran.propositions()).hasSize(2);
        var virement = ecran.propositions().stream().filter(p -> p.ligne().getId().equals(j.virement().getId())).findFirst().orElseThrow();
        assertThat(virement.mouvement().getId()).as("le plus proche (1 jour) l'emporte sur celui à 2 jours").isEqualTo(j.proche().getId());
        assertThat(virement.ecartJours()).isEqualTo(1);
        assertThat(ecran.propositions()).extracting(p -> p.mouvement().getId()).doesNotHaveDuplicates().doesNotContain(j.eloigne().getId());
        // Hors tolérance de 3 jours : pas de proposition.
        assertThat(ecran.propositions()).noneMatch(p -> p.ligne().getId().equals(j.frais().getId()));
        // Un mouvement à 4 jours n'est pas proposé.
        mouvement(banque, "-15000", "2025-03-24T10:00", "PAIEMENT");
        assertThat(pointage.ecran(j.releve().getId(), false).propositions()).hasSize(2);
    }

    @Test
    void accepterRefuserEtToutAccepter() {
        Jeu j = jeuDeMars("1285000");
        pointage.refuser(j.releve().getId(), j.cheque().getId(), j.sortie().getId());
        var ecran = pointage.ecran(j.releve().getId(), false);
        assertThat(ecran.propositions()).extracting(p -> p.ligne().getId()).containsExactly(j.virement().getId());
        assertThat(ecran.refusees()).isEqualTo(1);
        assertThat(pointage.toutAccepter(j.releve().getId())).isEqualTo(1);
        assertThat(lignesRepo.findById(j.virement().getId()).orElseThrow().estRapprochee()).isTrue();
        pointage.reinitialiserRefus(j.releve().getId());
        pointage.accepter(j.releve().getId(), j.cheque().getId(), j.sortie().getId());
        assertThat(lignesRepo.findById(j.cheque().getId()).orElseThrow().estRapprochee()).isTrue();
        assertThat(pointage.ecran(j.releve().getId(), false).propositions()).isEmpty();
        // Un mouvement déjà rapproché ne se rapproche pas une seconde fois.
        assertThatThrownBy(() -> pointage.rapprocher(j.releve().getId(), List.of(j.frais().getId()), List.of(j.sortie().getId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rapprochementUnPourPlusieursEtPlusieursPourUnAvecSommesEgalesSansTolerance() {
        ReleveBancaire r = releves.creer(banque.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), new BigDecimal("1000000"), new BigDecimal("1300000"), "jeton-1n");
        LigneReleve gros = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 8), "Remise de chèques", null, new BigDecimal("300000"));
        LigneReleve a = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 15), "Virement 1", null, new BigDecimal("100000"));
        LigneReleve b = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 15), "Virement 2", null, new BigDecimal("50000"));
        LigneReleve c = releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 15), "Virement 3", null, new BigDecimal("-150000"));
        // Réglons le total du relevé : 300 000 + 100 000 + 50 000 - 150 000 = 300 000.
        MouvementCaisse m1 = mouvement(banque, "100000", "2025-03-07T10:00", "ENCAISSEMENT");
        MouvementCaisse m2 = mouvement(banque, "200000", "2025-03-08T10:00", "ENCAISSEMENT");
        MouvementCaisse m3 = mouvement(banque, "150000", "2025-03-15T10:00", "ENCAISSEMENT");
        MouvementCaisse m4 = mouvement(banque, "-150000", "2025-03-15T10:00", "PAIEMENT");
        // 1-n : une ligne de 300 000 pour deux mouvements 100 000 + 200 000.
        pointage.rapprocher(r.getId(), List.of(gros.getId()), List.of(m1.getId(), m2.getId()));
        // n-1 : deux lignes 100 000 + 50 000 pour un mouvement de 150 000.
        pointage.rapprocher(r.getId(), List.of(a.getId(), b.getId()), List.of(m3.getId()));
        assertThat(pointage.groupes(relevesRepo.findById(r.getId()).orElseThrow())).hasSize(2);
        // Pas de tolérance : un franc d'écart refuse le rapprochement.
        MouvementCaisse faux = mouvement(banque, "-150001", "2025-03-15T11:00", "PAIEMENT");
        assertThatThrownBy(() -> pointage.rapprocher(r.getId(), List.of(c.getId()), List.of(faux.getId())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sommes ne sont pas égales");
        pointage.rapprocher(r.getId(), List.of(c.getId()), List.of(m4.getId()));
        // Défaire libère tout le groupe.
        Long groupe = lignesRepo.findById(gros.getId()).orElseThrow().getRapprochement().getId();
        pointage.defaire(r.getId(), groupe);
        assertThat(lignesRepo.findById(gros.getId()).orElseThrow().estRapprochee()).isFalse();
        assertThat(liens.deMouvements(List.of(m1.getId(), m2.getId()))).isEmpty();
    }

    @Test
    void enregistrerDansLAppliCreeLOperationEtLaRapprocheAussitot() {
        Jeu j = jeuDeMars("1285000");
        BigDecimal avant = mouvements.calculerSoldeCompte(boutique.getId(), banque.getId());
        pointage.enregistrerDansAppli(j.releve().getId(), j.frais().getId(), TypeOperationTresorerie.CHARGE_FINANCIERE);
        LigneReleve l = lignesRepo.findById(j.frais().getId()).orElseThrow();
        assertThat(l.estRapprochee()).isTrue();
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), banque.getId())).isEqualByComparingTo(avant.subtract(new BigDecimal("15000")));
        MouvementCaisse cree = mouvements.findAll().stream().filter(m -> m.getOperationTresorerie() != null).findFirst().orElseThrow();
        assertThat(cree.getMontant()).isEqualByComparingTo("-15000");
        assertThat(cree.getDateMouvement().toLocalDate()).isEqualTo(LocalDate.of(2025, 3, 20));
        assertThat(liens.deMouvements(List.of(cree.getId()))).hasSize(1);
        // Un débit ne s'enregistre pas comme un produit.
        assertThatThrownBy(() -> pointage.enregistrerDansAppli(j.releve().getId(), j.cheque().getId(), TypeOperationTresorerie.PRODUIT_FINANCIER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- État, validation, gel ------------------------------------------------------------------------------------

    @Test
    void lEtatChiffreLaFormuleEtLEcartEstNulQuandTousLesPointagesSontFaits() {
        Jeu j = jeuDeMars("1285000");
        pointage.toutAccepter(j.releve().getId());
        var e = etats.calculer(j.releve().getId());
        assertThat(e.soldeAppli()).isEqualByComparingTo("1800000");
        assertThat(e.totalMouvementsNonPointes()).isEqualByComparingTo("500000");   // le virement « double » non retrouvé sur le relevé
        assertThat(e.totalLignesNonPointees()).isEqualByComparingTo("-15000");      // les frais bancaires
        assertThat(e.soldeCalcule()).isEqualByComparingTo("1285000");
        assertThat(e.ecart()).isEqualByComparingTo("0");
        assertThat(e.controle().coherent()).isTrue();
        assertThat(e.releve().getNumero()).matches("RB-202503-.+");
    }

    @Test
    void laValidationEstRefuseeSiLesSoldesSontIncoherentsPuisAcceptee() throws Exception {
        Jeu j = jeuDeMars("1290000");   // 1 000 000 + 285 000 != 1 290 000
        pointage.toutAccepter(j.releve().getId());
        assertThatThrownBy(() -> validation.valider(j.releve().getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pas cohérents");
        // La saisie reste possible malgré l'avertissement.
        releves.ajouterLigne(j.releve().getId(), LocalDate.of(2025, 3, 21), "Agios", null, new BigDecimal("5000"));
        assertThat(relevesRepo.findById(j.releve().getId()).orElseThrow().getStatut()).isEqualTo(StatutReleve.BROUILLON);
        poster(GERANT, "/rapprochements/" + j.releve().getId() + "/entete", "compte", String.valueOf(banque.getId()), "dateDebut", "2025-03-01", "dateFin", "2025-03-31",
                "soldeOuverture", "1000000", "soldeCloture", "1290000").andExpect(status().is3xxRedirection());
        poster(GERANT, "/rapprochements/" + j.releve().getId() + "/valider").andExpect(status().is3xxRedirection());
        assertThat(relevesRepo.findById(j.releve().getId()).orElseThrow().getStatut()).isEqualTo(StatutReleve.VALIDE);
    }

    @Test
    void laValidationEstRefuseeSiLEcartNEstPasNul() {
        mouvement(banque, "30000", "2025-02-25T10:00", "ENCAISSEMENT");   // un mouvement d'avant le relevé, jamais relevé : l'appli et la banque divergent
        Jeu j = jeuDeMars("1285000");
        pointage.toutAccepter(j.releve().getId());
        assertThat(etats.calculer(j.releve().getId()).ecart()).isEqualByComparingTo("30000");
        assertThatThrownBy(() -> validation.valider(j.releve().getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("écart de rapprochement n'est pas nul");
    }

    @Test
    void unReleveValideEstFigeEtSesMouvementsNePeuventPlusEtreAnnulesAvantLaDevalidation() {
        Jeu j = jeuDeMars("1285000");
        pointage.toutAccepter(j.releve().getId());
        validation.valider(j.releve().getId());
        ReleveBancaire valide = relevesRepo.findById(j.releve().getId()).orElseThrow();
        assertThat(valide.getSoldeAppliValidation()).isEqualByComparingTo("1800000");
        Utilisateur gerant = utilisateurs.findByEmail(GERANT).orElseThrow();
        assertThatThrownBy(() -> caisse.enregistrerAnnulation(j.proche(), "Erreur de saisie", gerant))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mouvement rapproché sur le relevé RB-202503-").hasMessageContaining("validé");
        // Relevé figé : plus de ligne, plus de défaire, plus de pointage.
        assertThatThrownBy(() -> releves.ajouterLigne(j.releve().getId(), LocalDate.of(2025, 3, 22), "X", null, BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releves.supprimerLigne(j.releve().getId(), j.frais().getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pointage.toutAccepter(j.releve().getId())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> releves.supprimer(j.releve().getId())).isInstanceOf(IllegalArgumentException.class);
        // Une seconde validation signale que le relevé est déjà traité.
        assertThatThrownBy(() -> validation.valider(j.releve().getId())).isInstanceOf(ReleveDejaTraiteException.class);
        // La dévalidation exige un motif, puis l'annulation redevient possible ; l'historique garde les deux événements.
        assertThatThrownBy(() -> validation.devalider(j.releve().getId(), " ")).isInstanceOf(IllegalArgumentException.class);
        validation.devalider(j.releve().getId(), "Erreur sur un montant");
        assertThat(relevesRepo.findById(j.releve().getId()).orElseThrow().getStatut()).isEqualTo(StatutReleve.BROUILLON);
        // L'annulation d'un mouvement rapproché brise le pointage : défaire d'abord le rapprochement.
        caisse.enregistrerAnnulation(j.eloigne(), "Doublon", gerant);
        assertThat(validation.historique(j.releve().getId())).hasSize(2);
    }

    @Test
    void lesRelevesSEnchainentSansChevauchementEtSeDevalidentDansLOrdreInverse() {
        Jeu j = jeuDeMars("1285000");
        pointage.toutAccepter(j.releve().getId());
        assertThat(releves.debutPropose(banque.getId())).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(releves.ouvertureProposee(banque.getId(), LocalDate.of(2025, 4, 1))).isEqualByComparingTo("1285000");
        assertThatThrownBy(() -> releves.creer(banque.getId(), LocalDate.of(2025, 3, 15), LocalDate.of(2025, 4, 15), new BigDecimal("1285000"), BigDecimal.ONE, "chev"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chevauche");
        ReleveBancaire avril = releves.creer(banque.getId(), LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), new BigDecimal("1285000"), new BigDecimal("1285010"), "avril");
        releves.ajouterLigne(avril.getId(), LocalDate.of(2025, 4, 2), "Mouvement", null, new BigDecimal("10"));
        // Le précédent n'est pas validé : refus ; une ouverture différente de la clôture précédente : refus aussi.
        assertThatThrownBy(() -> validation.valider(avril.getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Validez d'abord");
        validation.valider(j.releve().getId());
        releves.modifierEntete(avril.getId(), LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), new BigDecimal("1284000"), new BigDecimal("1284010"));
        assertThatThrownBy(() -> validation.valider(avril.getId())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("doit être la clôture du relevé précédent");
        releves.modifierEntete(avril.getId(), LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), new BigDecimal("1285000"), new BigDecimal("1285010"));
        mouvement(banque, "10", "2025-04-02T10:00", "ENCAISSEMENT");
        pointage.toutAccepter(avril.getId());
        // Le virement de mars non relevé (500 000) et les frais non enregistrés restent des éléments de rapprochement, pas un écart.
        validation.valider(avril.getId());
        assertThatThrownBy(() -> validation.devalider(j.releve().getId(), "Trop tôt")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dernier relevé validé");
        validation.devalider(avril.getId(), "Reprise");
        validation.devalider(j.releve().getId(), "Reprise");
    }

    // ---- Web : droits, isolation, liste blanche, CSRF, import ---------------------------------------------------

    @Test
    void leCaissierEstRefuseSurToutesLesPagesEtLesActions() throws Exception {
        Jeu j = jeuDeMars("1285000");
        String id = String.valueOf(j.releve().getId());
        for (String url : List.of("/rapprochements", "/rapprochements/nouveau", "/rapprochements/" + id, "/rapprochements/" + id + "/pointage", "/rapprochements/" + id + "/etat",
                "/rapprochements/" + id + "/etat/pdf", "/rapprochements/" + id + "/etat/excel", "/rapprochements/" + id + "/import")) {
            mvc.perform(get(url).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        for (String url : List.of("/rapprochements", "/rapprochements/" + id + "/valider", "/rapprochements/" + id + "/lignes", "/rapprochements/" + id + "/pointage/tout-accepter")) {
            mvc.perform(post(url).with(csrf()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        }
        assertThat(relevesRepo.findById(j.releve().getId()).orElseThrow().getStatut()).isEqualTo(StatutReleve.BROUILLON);
    }

    @Test
    void lesPagesDuGerantSAffichentEtLesExportsSontDesFichiers() throws Exception {
        Jeu j = jeuDeMars("1285000");
        pointage.toutAccepter(j.releve().getId());
        String id = String.valueOf(j.releve().getId());
        assertThat(page("/rapprochements")).contains("RB-202503-").doesNotContain("Boutique rapprochement\">");
        assertThat(page("/rapprochements/" + id)).contains("Cheque 123", "Frais de tenue de compte");
        assertThat(page("/rapprochements/" + id + "/pointage")).contains("Frais de tenue de compte");
        assertThat(page("/rapprochements/" + id + "/etat")).contains("1 800 000", "1 285 000");
        MvcResult pdf = mvc.perform(get("/rapprochements/" + id + "/etat/pdf").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk())
                .andExpect(content().contentType("application/pdf")).andReturn();
        assertThat(pdf.getResponse().getContentAsByteArray()).startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));
        MvcResult xlsx = mvc.perform(get("/rapprochements/" + id + "/etat/excel").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn();
        assertThat(xlsx.getResponse().getContentAsByteArray()).startsWith(new byte[]{'P', 'K'});
        // Le tableau de bord du gérant signale l'absence de relevé validé.
        assertThat(mvc.perform(get("/").with(user(GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString()).contains("Rapprochement bancaire");
    }

    @Test
    void uneAutreBoutiqueNeVoitNiNeModifieRien() throws Exception {
        Jeu j = jeuDeMars("1285000");
        String id = String.valueOf(j.releve().getId());
        for (String url : List.of("/rapprochements/" + id, "/rapprochements/" + id + "/pointage", "/rapprochements/" + id + "/etat", "/rapprochements/" + id + "/etat/pdf")) {
            mvc.perform(get(url).with(user(AUTRE_GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        }
        mvc.perform(post("/rapprochements/" + id + "/valider").with(csrf()).with(user(AUTRE_GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        assertThat(page("/rapprochements", AUTRE_GERANT)).doesNotContain("RB-202503-");
        // Un compte d'une autre boutique ne sert pas à créer un relevé.
        poster(GERANT, "/rapprochements", "compte", String.valueOf(banqueAutre.getId()), "dateDebut", "2025-03-01", "dateFin", "2025-03-31", "soldeOuverture", "0", "soldeCloture", "0", "jeton", "x1");
        assertThat(relevesRepo.findAll()).noneMatch(r -> r.getCompte().getId().equals(banqueAutre.getId()));
    }

    @Test
    void seulsLesComptesBanqueEtOrangeMoneyOntDesReleves() throws Exception {
        assertThatThrownBy(() -> releves.creer(especes.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), BigDecimal.ZERO, BigDecimal.ZERO, "esp"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Banque et Orange Money");
        assertThat(releves.creer(orange.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), BigDecimal.ZERO, BigDecimal.ZERO, "om").getNumero()).startsWith("RB-202503-");
    }

    @Test
    void laListeBlancheDesChampsEtLeCsrfProtegentLesFormulaires() throws Exception {
        Jeu j = jeuDeMars("1285000");
        String id = String.valueOf(j.releve().getId());
        poster(GERANT, "/rapprochements/" + id + "/lignes", "dateOperation", "2025-03-22", "libelle", "X", "montant", "5", "statut", "VALIDE").andExpect(status().isBadRequest());
        poster(GERANT, "/rapprochements/" + id + "/lignes", "dateOperation", "2025-03-22", "libelle", "X", "montant", "5", "montant", "6").andExpect(status().isBadRequest());
        poster(GERANT, "/rapprochements/" + id + "/valider", "statut", "VALIDE").andExpect(status().isBadRequest());
        poster(GERANT, "/rapprochements/" + id + "/pointage/accepter", "ligne", "1", "mouvement", "1", "rapprochement", "2", "boutique", "3").andExpect(status().isBadRequest());
        poster(GERANT, "/rapprochements", "compte", String.valueOf(banque.getId()), "dateDebut", "2025-05-01", "dateFin", "2025-05-31", "soldeOuverture", "0", "soldeCloture", "0",
                "jeton", "z", "statut", "VALIDE").andExpect(status().isBadRequest());
        MockHttpServletRequestBuilder trop = post("/rapprochements/" + id + "/pointage/rapprocher").with(csrf()).with(user(GERANT).roles("ADMIN"));
        for (int i = 0; i < 101; i++) trop.param("mouvements", String.valueOf(i + 1));
        mvc.perform(trop).andExpect(status().isBadRequest());
        // Sans jeton CSRF : refus.
        mvc.perform(post("/rapprochements/" + id + "/valider").with(user(GERANT).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/rapprochements/" + id + "/lignes").with(user(GERANT).roles("ADMIN")).param("libelle", "X")).andExpect(status().isForbidden());
        assertThat(lignesRepo.countByReleveId(j.releve().getId())).isEqualTo(3);
    }

    @Test
    void importCsvApercuDoublonsEtCorrespondanceMemorisee() throws Exception {
        ReleveBancaire r = releves.creer(banque.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), new BigDecimal("1000000"), new BigDecimal("1100000"), "imp");
        releves.ajouterLigne(r.getId(), LocalDate.of(2025, 3, 5), "Virement client A", "V1", new BigDecimal("100000"));
        String csv = "Date;Libellé;Réf;Montant\n05/03/2025;Virement client A;V1;100 000\n06/03/2025;=cmd|' /C calc'!A0;;-5 000\n07/03/2025;Agios;;-1 000,50\n31/02/2025;Date fausse;;1\n";
        MockMultipartFile fichier = new MockMultipartFile("fichier", "releve.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
        MvcResult depot = mvc.perform(multipart("/rapprochements/" + r.getId() + "/import").file(fichier).with(csrf()).with(user(GERANT).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andReturn();
        String ecran = depot.getResponse().getRedirectedUrl();
        assertThat(ecran).startsWith("/rapprochements/" + r.getId() + "/import/");
        assertThat(page(ecran)).contains("Date", "Libellé");
        String apercu = poster(GERANT, ecran + "/apercu", "ligneEntete", "1", "colonneDate", "1", "colonneLibelle", "2", "colonneReference", "3", "modeMontant", "SIGNE",
                "colonneMontant", "4", "formatDate", "JJ_MM_AAAA", "separateur", "VIRGULE").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(apercu).contains("Virement client A", "date illisible").contains("&#39;=cmd");
        poster(GERANT, ecran, "ligneEntete", "1", "colonneDate", "1", "colonneLibelle", "2", "colonneReference", "3", "modeMontant", "SIGNE",
                "colonneMontant", "4", "formatDate", "JJ_MM_AAAA", "separateur", "VIRGULE").andExpect(status().is3xxRedirection());
        // Le doublon (même date, montant, libellé, référence) est ignoré, la ligne en erreur aussi : 2 lignes importées + la ligne manuelle.
        assertThat(lignesRepo.duReleve(r.getId())).hasSize(3);
        assertThat(lignesRepo.duReleve(r.getId())).anyMatch(l -> l.getLibelle().startsWith("'="));
        var m = memorisees.findByCompteId(banque.getId()).orElseThrow();
        assertThat(m.getColonneMontant()).isEqualTo(4);
        assertThat(m.getColonneDebit()).isNull();
        // Le fichier suivant propose la correspondance mémorisée.
        authentifier(GERANT, "ADMIN");
        ReleveBancaire suivant = releves.creer(banque.getId(), LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), new BigDecimal("1000000"), new BigDecimal("1000000"), "imp2");
        MvcResult depot2 = mvc.perform(multipart("/rapprochements/" + suivant.getId() + "/import")
                .file(new MockMultipartFile("fichier", "avril.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8))).with(csrf()).with(user(GERANT).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(page(depot2.getResponse().getRedirectedUrl())).contains("mémoris");
    }

    @Test
    void importRefuseUnMauvaisTypeUnFichierVideOuUnParametreInconnu() throws Exception {
        ReleveBancaire r = releves.creer(banque.getId(), LocalDate.of(2025, 3, 1), LocalDate.of(2025, 3, 31), BigDecimal.ZERO, BigDecimal.ZERO, "imp3");
        String url = "/rapprochements/" + r.getId() + "/import";
        MvcResult mauvais = mvc.perform(multipart(url).file(new MockMultipartFile("fichier", "x.exe", "application/octet-stream", "MZ".getBytes())).with(csrf()).with(user(GERANT).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(String.valueOf(mauvais.getFlashMap().get("error"))).contains("Type de fichier");
        MvcResult vide = mvc.perform(multipart(url).file(new MockMultipartFile("fichier", "x.csv", "text/csv", new byte[0])).with(csrf()).with(user(GERANT).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andReturn();
        assertThat(String.valueOf(vide.getFlashMap().get("error"))).isNotBlank();
        mvc.perform(multipart(url).file(new MockMultipartFile("fichier", "x.csv", "text/csv", "a;b".getBytes())).param("statut", "VALIDE").with(csrf()).with(user(GERANT).roles("ADMIN")))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart(url).file(new MockMultipartFile("fichier", "x.csv", "text/csv", "a;b".getBytes())).with(user(GERANT).roles("ADMIN"))).andExpect(status().isForbidden());
        // Une autre boutique ne peut pas lire l'écran de correspondance d'un dépôt qui n'est pas le sien.
        mvc.perform(multipart(url).file(new MockMultipartFile("fichier", "x.csv", "text/csv", "a;b".getBytes())).with(csrf()).with(user(AUTRE_GERANT).roles("ADMIN")))
                .andExpect(status().isNotFound());
        assertThat(lignesRepo.duReleve(r.getId())).isEmpty();
    }

    // ---- Outils -----------------------------------------------------------------------------------------------

    private ResultActions poster(String email, String url, String... parametres) throws Exception {
        MockHttpServletRequestBuilder b = post(url).with(csrf()).with(user(email).roles(email.startsWith("caissier") ? "CAISSIER" : "ADMIN"));
        for (int i = 0; i + 1 < parametres.length; i += 2) b.param(parametres[i], parametres[i + 1]);
        return mvc.perform(b);
    }

    private String page(String url) throws Exception { return page(url, GERANT); }

    private String page(String url, String email) throws Exception {
        return mvc.perform(get(url).with(user(email).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString().replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    private MouvementCaisse mouvement(CompteTresorerieBoutique compte, String montant, String date, String source) {
        MouvementCaisse m = new MouvementCaisse();
        BigDecimal v = new BigDecimal(montant);
        m.setBoutique(compte.getBoutique()); m.setCompte(compte); m.setMontant(v); m.setType(v.signum() >= 0 ? "ENTREE" : "SORTIE"); m.setSource(source);
        m.setMotif("Test " + source); m.setDateMouvement(LocalDateTime.parse(date));
        return mouvements.saveAndFlush(m);
    }

    private void authentifier(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(new UsernamePasswordAuthenticationToken(email, "x", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.save(u);
    }
}
