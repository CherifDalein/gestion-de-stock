package org.example.stock.controles;

import org.example.stock.enums.Role;
import org.example.stock.enums.StatutComptage;
import org.example.stock.enums.TypeCompteTresorerie;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.example.stock.service.ComptageCaisseService;
import org.example.stock.service.ComptesTresorerieService;
import org.example.stock.support.ComptesDeTest;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Comptage de caisse (étape 3b2) : conforme, en attente, validé, refusé, annulé, droits, isolation, billetage et liste blanche des champs. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:comptage-caisse;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ComptageCaisseTests {
    static final String GERANT = "gerant-cpt@example.test", CAISSIER = "caissier-cpt@example.test", AUTRE_CAISSIER = "caissier2-cpt@example.test",
            AUTRE_GERANT = "gerant2-cpt@example.test";

    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired MouvementCaisseRepository mouvements;
    @Autowired ComptageCaisseRepository comptages;
    @Autowired CompteTresorerieBoutiqueRepository comptesRepo;
    @Autowired CompteTresorerieAutoriseRepository autorisations;
    @Autowired ComptesTresorerieService comptesService;
    @Autowired ComptageCaisseService service;
    @Autowired MockMvc mvc;

    Boutique boutique, autre;
    CompteTresorerieBoutique caisse, orange, caisse2, caisseAutre;

    @BeforeEach
    void preparer() {
        mouvements.deleteAll(); comptages.deleteAll(); autorisations.deleteAll(); utilisateurs.deleteAll();
        boutique = boutique("Boutique comptages"); autre = boutique("Autre boutique");
        utilisateur(GERANT, Role.ADMIN, boutique); utilisateur(CAISSIER, Role.CAISSIER, boutique); utilisateur(AUTRE_CAISSIER, Role.CAISSIER, boutique);
        utilisateur(AUTRE_GERANT, Role.ADMIN, autre);
        caisse = ComptesDeTest.de(boutique, TypeCompteTresorerie.ESPECES);
        orange = ComptesDeTest.de(boutique, TypeCompteTresorerie.ORANGE_MONEY);
        caisse2 = comptesRepo.saveAndFlush(new CompteTresorerieBoutique(boutique, TypeCompteTresorerie.ESPECES, "Caisse arrière", false, 9));
        caisseAutre = ComptesDeTest.de(autre, TypeCompteTresorerie.ESPECES);
        solde(caisse, "100000"); solde(orange, "50000"); solde(caisse2, "30000"); solde(caisseAutre, "999");
    }

    @AfterEach
    void nettoyer() { SecurityContextHolder.clearContext(); }

    // ---- Conforme et en attente ---------------------------------------------------------------------------

    @Test
    void unComptageSansEcartEstConformeEtNePasseAucunMouvement() throws Exception {
        long avant = mouvements.count();
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "100000", "j-1");
        assertThat(c.getStatut()).isEqualTo(StatutComptage.CONFORME);
        assertThat(c.getEcart()).isZero();
        assertThat(c.getSoldeTheorique()).isEqualByComparingTo("100000");
        assertThat(c.getUtilisateur().getEmail()).isEqualTo(CAISSIER);
        assertThat(mouvements.count()).isEqualTo(avant);
        assertThat(nombreEnAttente()).isZero();
    }

    @Test
    void unEcartMetLeComptageEnAttenteSansMouvementEtLeCaissierVoitSonStatut() throws Exception {
        long avant = mouvements.count();
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-2");
        assertThat(c.getStatut()).isEqualTo(StatutComptage.EN_ATTENTE);
        assertThat(c.getEcart()).isEqualByComparingTo("-2500");
        assertThat(c.getNatureEcart()).isEqualTo("Manque");
        assertThat(mouvements.count()).as("aucun mouvement avant la décision du gérant").isEqualTo(avant);
        assertThat(nombreEnAttente()).isEqualTo(1);
        String liste = mvc.perform(get("/controles/comptages").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("CC-" + c.getId(), "En attente de validation", "97 500,00 GNF").doesNotContain("100 000", "2 500,00", "Théorique");
        // Le badge du gérant sur le tableau de bord.
        String accueil = mvc.perform(get("/").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(accueil).contains("comptagesEnAttenteAlerte", "1 comptage de caisse attend");
    }

    @Test
    void lEcartEstFigeALHeureDuComptageMemeSiDesMouvementsSuivent() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "90000", "j-3");
        assertThat(c.getEcart()).isEqualByComparingTo("-10000");
        solde(caisse, "4000");   // une vente après le comptage : le solde théorique actuel est 104 000
        valider(c.getId(), "Manque constaté").andExpect(status().is3xxRedirection());
        List<MouvementCaisse> ecritures = mouvements.mouvementsDuComptage(boutique.getId(), c.getId());
        assertThat(ecritures).hasSize(1);
        assertThat(ecritures.get(0).getMontant()).as("l'écart de l'heure du comptage, pas celui d'aujourd'hui").isEqualByComparingTo("-10000");
    }

    // ---- Validation, refus, annulation ----------------------------------------------------------------------

    @Test
    void validerUnManquePasseUneSortieEcartDeCaisseClasseeEnAutreCharge() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-4");
        valider(c.getId(), "Erreur de rendu de monnaie").andExpect(status().is3xxRedirection());
        ComptageCaisse apres = comptages.findByIdAndBoutiqueId(c.getId(), boutique.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutComptage.VALIDE);
        assertThat(apres.getDecideur().getEmail()).isEqualTo(GERANT);
        assertThat(apres.getMotifDecision()).isEqualTo("Erreur de rendu de monnaie");
        MouvementCaisse m = mouvements.mouvementsDuComptage(boutique.getId(), c.getId()).get(0);
        assertThat(m.getCompte().getId()).isEqualTo(caisse.getId());
        assertThat(m.getMontant()).isEqualByComparingTo("-2500");
        assertThat(m.getType()).isEqualTo("SORTIE");
        assertThat(m.getSource()).isEqualTo(SourceMouvement.AUTRE_CHARGE);
        assertThat(m.getSourceLibelle()).isEqualTo("Écart de caisse");
        assertThat(m.getUtilisateur().getEmail()).isEqualTo(GERANT);
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), caisse.getId())).as("le solde rejoint le montant compté").isEqualByComparingTo("97500");
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), orange.getId())).isEqualByComparingTo("50000");
    }

    @Test
    void validerUnExcedentPasseUneEntreeAutreProduitSurLeBonCompte() throws Exception {
        ComptageCaisse c = compter(CAISSIER, String.valueOf(orange.getId()), "51250", "j-5");
        assertThat(c.getEcart()).isEqualByComparingTo("1250");
        valider(c.getId(), "Pourboire non enregistré").andExpect(status().is3xxRedirection());
        MouvementCaisse m = mouvements.mouvementsDuComptage(boutique.getId(), c.getId()).get(0);
        assertThat(m.getCompte().getId()).isEqualTo(orange.getId());
        assertThat(m.getMontant()).isEqualByComparingTo("1250");
        assertThat(m.getType()).isEqualTo("ENTREE");
        assertThat(m.getSource()).isEqualTo(SourceMouvement.AUTRE_PRODUIT);
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), orange.getId())).isEqualByComparingTo("51250");
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), caisse.getId())).isEqualByComparingTo("100000");
    }

    @Test
    void refuserNePasseAucunMouvementEtLeMotifEstObligatoire() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "99000", "j-6");
        long avant = mouvements.count();
        valider(c.getId(), "").andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        valider(c.getId(), "ab").andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("error"));
        assertThat(comptages.findByIdAndBoutiqueId(c.getId(), boutique.getId()).orElseThrow().getStatut()).isEqualTo(StatutComptage.EN_ATTENTE);
        decision(c.getId(), "refuser", GERANT, "Recompter demain").andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        ComptageCaisse apres = comptages.findByIdAndBoutiqueId(c.getId(), boutique.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutComptage.REFUSE);
        assertThat(apres.getMotifDecision()).isEqualTo("Recompter demain");
        assertThat(mouvements.count()).isEqualTo(avant);
    }

    @Test
    void annulerUnComptageValideContrePasseLEcartAvecMotif() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-7");
        valider(c.getId(), "Manque constaté").andExpect(status().is3xxRedirection());
        decision(c.getId(), "annuler", GERANT, "").andExpect(flash().attributeExists("error"));
        decision(c.getId(), "annuler", GERANT, "Le billet a été retrouvé").andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("success"));
        ComptageCaisse apres = comptages.findByIdAndBoutiqueId(c.getId(), boutique.getId()).orElseThrow();
        assertThat(apres.getStatut()).isEqualTo(StatutComptage.ANNULE);
        assertThat(apres.getAnnulePar().getEmail()).isEqualTo(GERANT);
        assertThat(apres.getMotifAnnulation()).isEqualTo("Le billet a été retrouvé");
        List<MouvementCaisse> ecritures = mouvements.mouvementsDuComptage(boutique.getId(), c.getId());
        assertThat(ecritures).hasSize(2);
        assertThat(ecritures.get(1).getSource()).isEqualTo(SourceMouvement.ANNULATION);
        assertThat(ecritures.get(1).getMontant()).isEqualByComparingTo("2500");
        assertThat(ecritures.get(1).getCompte().getId()).isEqualTo(caisse.getId());
        assertThat(mouvements.calculerSoldeCompte(boutique.getId(), caisse.getId())).isEqualByComparingTo("100000");
        // Un comptage annulé ne s'annule pas deux fois.
        decision(c.getId(), "annuler", GERANT, "Encore").andExpect(status().isConflict());
    }

    @Test
    void uneSecondeValidationDuMemeComptageRepond409() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-8");
        valider(c.getId(), "Première décision").andExpect(status().is3xxRedirection());
        valider(c.getId(), "Seconde décision").andExpect(status().isConflict());
        decision(c.getId(), "refuser", GERANT, "Trop tard").andExpect(status().isConflict());
        assertThat(mouvements.mouvementsDuComptage(boutique.getId(), c.getId())).hasSize(1);
        // Un comptage conforme n'attend aucune décision.
        ComptageCaisse conforme = compter(CAISSIER, "CAISSE", "97500", "j-8b");
        assertThat(conforme.getStatut()).isEqualTo(StatutComptage.CONFORME);
        valider(conforme.getId(), "Sans objet").andExpect(status().isConflict());
    }

    // ---- Droits et isolation ----------------------------------------------------------------------------------

    @Test
    void leCaissierNiValideNiRefuseNiAnnuleNiExporteNiVoitLaListeDuGerant() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-9");
        for (String action : List.of("valider", "refuser", "annuler")) decision(c.getId(), action, CAISSIER, "Essai").andExpect(status().isForbidden());
        mvc.perform(get("/controles/comptages/en-attente").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/controles/comptages/export/excel").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/controles/comptages/export/pdf").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        mvc.perform(get("/controles/creances").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        assertThat(comptages.findByIdAndBoutiqueId(c.getId(), boutique.getId()).orElseThrow().getStatut()).isEqualTo(StatutComptage.EN_ATTENTE);
        // Même en appelant le service directement.
        authentifier(CAISSIER, "CAISSIER");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.valider(c.getId(), "Direct")).hasMessageContaining("réservée au gérant");
    }

    @Test
    void leCaissierNeCompteQueLesComptesQuIlPeutUtiliser() throws Exception {
        // Sans liste explicite : les comptes principaux ; la caisse arrière n'en fait pas partie.
        poster(CAISSIER, "/controles/comptages", "montantCompte", "30000", "compte", String.valueOf(caisse2.getId()), "jeton", "j-10").andExpect(status().isBadRequest());
        poster(CAISSIER, "/controles/comptages", "montantCompte", "1", "compte", String.valueOf(caisseAutre.getId()), "jeton", "j-10b").andExpect(status().isBadRequest());
        // Avec une liste explicite, seuls les comptes cochés.
        authentifier(GERANT, "ADMIN");
        comptesService.autoriser(utilisateurs.findByEmail(AUTRE_CAISSIER).orElseThrow().getId(), List.of(caisse2.getId()));
        poster(AUTRE_CAISSIER, "/controles/comptages", "montantCompte", "30000", "compte", String.valueOf(caisse2.getId()), "jeton", "j-10c").andExpect(status().is3xxRedirection());
        poster(AUTRE_CAISSIER, "/controles/comptages", "montantCompte", "100000", "compte", "CAISSE", "jeton", "j-10d").andExpect(status().isBadRequest());
        assertThat(comptages.count()).isEqualTo(1);
        String formulaire = mvc.perform(get("/controles/comptages/nouveau").with(user(AUTRE_CAISSIER).roles("CAISSIER"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(formulaire).contains("Caisse arrière").doesNotContain("Orange Money");
    }

    @Test
    void unCaissierNeVoitQueSesComptagesEtUneAutreBoutiqueNeVoitRien() throws Exception {
        ComptageCaisse mien = compter(CAISSIER, "CAISSE", "97500", "j-11");
        ComptageCaisse autreCaissier = compter(AUTRE_CAISSIER, "CAISSE", "98000", "j-11b");
        String liste = mvc.perform(get("/controles/comptages").with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString();
        assertThat(liste).contains("CC-" + mien.getId()).doesNotContain("CC-" + autreCaissier.getId());
        mvc.perform(get("/controles/comptages/" + autreCaissier.getId()).with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isNotFound());
        mvc.perform(get("/controles/comptages/" + autreCaissier.getId() + "/pdf").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        String gerant = mvc.perform(get("/controles/comptages").with(user(GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(gerant).contains("CC-" + mien.getId(), "CC-" + autreCaissier.getId());
        // Boutique voisine : 404 partout, rien n'est modifié.
        mvc.perform(get("/controles/comptages/" + mien.getId()).with(user(AUTRE_GERANT).roles("ADMIN"))).andExpect(status().isNotFound());
        decision(mien.getId(), "valider", AUTRE_GERANT, "Intrus").andExpect(status().isNotFound());
        decision(mien.getId(), "refuser", AUTRE_GERANT, "Intrus").andExpect(status().isNotFound());
        assertThat(comptages.findByIdAndBoutiqueId(mien.getId(), boutique.getId()).orElseThrow().getStatut()).isEqualTo(StatutComptage.EN_ATTENTE);
        String voisin = mvc.perform(get("/controles/comptages").with(user(AUTRE_GERANT).roles("ADMIN"))).andReturn().getResponse().getContentAsString();
        assertThat(voisin).doesNotContain("CC-" + mien.getId());
    }

    // ---- Billetage --------------------------------------------------------------------------------------------

    @Test
    void leBilletageDesEspecesDonneLeTotalCompteEtEstConserve() throws Exception {
        // 3 x 20 000 + 2 x 10 000 + 1 x 5 000 + 4 x 2 000 + 7 x 500 + 1 x 100 = 96 600
        ResultActions r = poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "jeton", "j-12",
                "quantites[0]", "3", "quantites[1]", "2", "quantites[2]", "1", "quantites[3]", "4", "quantites[4]", "", "quantites[5]", "7",
                "quantites[6]", "1");
        r.andExpect(status().is3xxRedirection());
        ComptageCaisse c = comptages.findByBoutiqueIdAndJeton(boutique.getId(), "j-12").orElseThrow();
        assertThat(c.getMontantCompte()).isEqualByComparingTo("96600");
        assertThat(c.getEcart()).isEqualByComparingTo("-3400");
        authentifier(CAISSIER, "CAISSIER");
        ComptageCaisse lu = service.trouver(c.getId());
        assertThat(lu.getBilletage()).extracting(LigneBilletage::getCoupure).containsExactly(20000, 10000, 5000, 2000, 500, 100);
        assertThat(lu.getBilletage()).extracting(LigneBilletage::getQuantite).containsExactly(3, 2, 1, 4, 7, 1);
        String page = mvc.perform(get("/controles/comptages/" + c.getId()).with(user(CAISSIER).roles("CAISSIER"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Billetage", "20 000 GNF", "96 600,00 GNF");
    }

    @Test
    void leBilletageDoitConcorderAvecLeMontantSaisiEtNeConcerneQueLesEspeces() throws Exception {
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "50000", "jeton", "j-13", "quantites[0]", "1")
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("ne correspond pas au total du billetage")));
        poster(CAISSIER, "/controles/comptages", "compte", String.valueOf(orange.getId()), "jeton", "j-13b", "quantites[0]", "1")
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("comptes d&#39;espèces")));
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "20000", "jeton", "j-13c", "quantites[0]", "1")
                .andExpect(status().is3xxRedirection());
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "jeton", "j-13d").andExpect(status().isOk());   // ni montant ni billetage
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "-5", "jeton", "j-13e").andExpect(status().isOk());
        assertThat(comptages.count()).isEqualTo(1);
    }

    @Test
    void unDoubleEnvoiDuMemeJetonNEnregistreQuUnComptage() throws Exception {
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "99000", "jeton", "j-14").andExpect(status().is3xxRedirection());
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "99000", "jeton", "j-14").andExpect(status().is3xxRedirection());
        assertThat(comptages.count()).isEqualTo(1);
    }

    @Test
    void seulsLesChampsDeclaresSontAcceptes() throws Exception {
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "1", "jeton", "j-15", "statut", "VALIDE").andExpect(status().isBadRequest());
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "1", "jeton", "j-15b", "ecart", "0").andExpect(status().isBadRequest());
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "1", "montantCompte", "2", "jeton", "j-15c").andExpect(status().isBadRequest());
        poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "1", "jeton", "j-15d", "quantites[12]", "1").andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("trop de coupures")));
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-15e");
        mvc.perform(post("/controles/comptages/" + c.getId() + "/valider").with(csrf()).with(user(GERANT).roles("ADMIN")).param("motif", "Bon motif").param("statut", "VALIDE"))
                .andExpect(status().isBadRequest());
        assertThat(comptages.count()).isEqualTo(1);
    }

    @Test
    void lExportDuJournalReconnaitLEcartDeCaisseEtLeSepareDesAutresCharges() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-17");
        valider(c.getId(), "Manque constaté").andExpect(status().is3xxRedirection());
        var ecart = org.example.stock.export.ExportTestOutils.classeur(mvc.perform(get("/caisse/export/excel").param("source", "Écart de caisse")
                .with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        String texte = org.example.stock.export.ExportTestOutils.texteDeLaFeuille(ecart.getSheetAt(0));
        assertThat(texte).contains("Écart de caisse (comptage n° " + c.getId() + ")");
        var charges = org.example.stock.export.ExportTestOutils.classeur(mvc.perform(get("/caisse/export/excel").param("source", "Autre charge")
                .with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse());
        assertThat(org.example.stock.export.ExportTestOutils.texteDeLaFeuille(charges.getSheetAt(0))).doesNotContain("Écart de caisse (comptage");
        String journal = mvc.perform(get("/caisse/journal").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(journal).contains("Écart de caisse");
    }

    // ---- Comptage à l'aveugle ----------------------------------------------------------------------------------

    private String page(String url, String email, String role) throws Exception {
        return mvc.perform(get(url).with(user(email).roles(role))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void leCaissierCompteALAveugleAvantEtApresLEnregistrement() throws Exception {
        // Le solde théorique de la caisse est 100 000 ; ni la valeur, ni l'écart, ni un solde de compte (Orange Money 50 000, arrière 30 000)
        // n'apparaissent dans le HTML du caissier, ni dans les data-*, ni dans le JS servi.
        String formulaire = page("/controles/comptages/nouveau", CAISSIER, "CAISSIER");
        assertThat(formulaire).contains("Billetage", "comptageTotalBilletage", "Montant compté")
                .doesNotContain("100000.00", "100 000", "50000.00", "50 000", "data-solde", "comptageTheorique", "comptageEcart", "Solde théorique", "Écart");
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-aveugle");
        String detail = page("/controles/comptages/" + c.getId(), CAISSIER, "CAISSIER");
        assertThat(detail).contains("En attente de validation", "97 500,00 GNF")
                .doesNotContain("100000.00", "100 000", "2 500", "Solde théorique", "Écart", "/pdf", "Procès-verbal");
        String liste = page("/controles/comptages", CAISSIER, "CAISSIER");
        assertThat(liste).doesNotContain("100 000", "2 500", "Théorique", "Écart");
        mvc.perform(get("/controles/comptages/" + c.getId() + "/pdf").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        // Le message enregistré non plus : seul le statut est communiqué.
        String redirige = poster(CAISSIER, "/controles/comptages", "compte", "CAISSE", "montantCompte", "97400", "jeton", "j-aveugle2")
                .andReturn().getFlashMap().toString();
        assertThat(redirige).doesNotContain("100 000", "2 600");
        // Conforme : le caissier voit seulement le statut.
        ComptageCaisse conforme = compter(CAISSIER, "CAISSE", "100000", "j-aveugle3");
        assertThat(page("/controles/comptages/" + conforme.getId(), CAISSIER, "CAISSIER")).contains("Conforme").doesNotContain("Solde théorique", "Écart");
        // Le JS ne contient aucune valeur de compte : il ne calcule l'écart que si le gérant a reçu un data-solde.
        String js = mvc.perform(get("/js/comptage.js")).andReturn().getResponse().getContentAsString();
        assertThat(js).doesNotContain("100000");
    }

    @Test
    void leGerantGardeLeSoldeTheoriqueEtLEcartEnDirect() throws Exception {
        String formulaire = page("/controles/comptages/nouveau", GERANT, "ADMIN");
        assertThat(formulaire).contains("data-solde=\"100000.00\"", "comptageTheorique", "comptageEcart", "Solde théorique");
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-gerant-voit");
        String detail = page("/controles/comptages/" + c.getId(), GERANT, "ADMIN");
        assertThat(detail).contains("100 000,00 GNF", "2 500,00", "Solde théorique", "/pdf");
        assertThat(page("/controles/comptages", GERANT, "ADMIN")).contains("100 000,00 GNF", "Théorique");
    }

    @Test
    void lAvertissementPrevientLeGerantQuUnManqueValideRendraitLeSoldeNegatif() throws Exception {
        solde(caisse2, "0");
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "1000", "j-negatif");   // théorique 100 000, manque de 99 000
        solde(caisse, "-98000");                                            // le solde actuel tombe à 2 000 : valider le laisserait à -97 000
        String attente = page("/controles/comptages/en-attente", GERANT, "ADMIN");
        assertThat(attente).contains("valider ce manque le rendrait négatif", "2 000,00 GNF");
        assertThat(page("/controles/comptages/" + c.getId(), GERANT, "ADMIN")).contains("valider ce manque le rendrait négatif");
        ComptageCaisse petit = compter(CAISSIER, "CAISSE", "1999", "j-negatif2");
        solde(caisse, "200000");
        assertThat(page("/controles/comptages/" + petit.getId(), GERANT, "ADMIN")).doesNotContain("le rendrait négatif");
    }

    // ---- Documents --------------------------------------------------------------------------------------------

    @Test
    void leProcesVerbalPdfEtLesExportsDuGerantSontProduits() throws Exception {
        ComptageCaisse c = compter(CAISSIER, "CAISSE", "97500", "j-16");
        mvc.perform(get("/controles/comptages/" + c.getId() + "/pdf").with(user(CAISSIER).roles("CAISSIER"))).andExpect(status().isForbidden());
        MockHttpServletResponse pdf = mvc.perform(get("/controles/comptages/" + c.getId() + "/pdf").with(user(GERANT).roles("ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(pdf.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(pdf.getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
        MockHttpServletResponse excel = mvc.perform(get("/controles/comptages/export/excel").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(excel.getContentType()).contains("spreadsheetml");
        MockHttpServletResponse liste = mvc.perform(get("/controles/comptages/export/pdf?statut=EN_ATTENTE").with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(new String(liste.getContentAsByteArray(), 0, 5)).isEqualTo("%PDF-");
        mvc.perform(get("/controles/comptages/export/pdf?statut=INCONNU").with(user(GERANT).roles("ADMIN"))).andExpect(status().isBadRequest());
    }

    // ---- Outils ---------------------------------------------------------------------------------------------

    @Test
    void laFicheDUnComptageEnAttenteSAfficheAvecLaDecisionDuGerant() throws Exception {
        ComptageCaisse c = compter(CAISSIER, String.valueOf(caisse.getId()), "90000", "j-fiche");
        String page = mvc.perform(get("/controles/comptages/" + c.getId()).with(user(GERANT).roles("ADMIN"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("En attente de validation", "Décider de l’écart", "Refuser");
    }

    private ComptageCaisse compter(String email, String compte, String montant, String jeton) throws Exception {
        poster(email, "/controles/comptages", "compte", compte, "montantCompte", montant, "jeton", jeton).andExpect(status().is3xxRedirection());
        return comptages.findByBoutiqueIdAndJeton(boutique.getId(), jeton).orElseThrow();
    }

    private ResultActions poster(String email, String url, String... parametres) throws Exception {
        MockHttpServletRequestBuilder requete = post(url).with(csrf()).with(user(email).roles(utilisateurs.findByEmail(email).orElseThrow().getRole().name()));
        for (int i = 0; i < parametres.length; i += 2) requete.param(parametres[i], parametres[i + 1]);
        return mvc.perform(requete);
    }

    private ResultActions valider(Long id, String motif) throws Exception { return decision(id, "valider", GERANT, motif); }

    private ResultActions decision(Long id, String action, String email, String motif) throws Exception {
        return poster(email, "/controles/comptages/" + id + "/" + action, "motif", motif);
    }

    private long nombreEnAttente() {
        return comptages.countByBoutiqueIdAndStatut(boutique.getId(), StatutComptage.EN_ATTENTE);
    }

    private void solde(CompteTresorerieBoutique compte, String montant) {
        MouvementCaisse m = new MouvementCaisse();
        m.setBoutique(compte.getBoutique()); m.setCompte(compte); m.setMontant(new BigDecimal(montant)); m.setType("ENTREE"); m.setSource("SOLDE_INITIAL");
        m.setMotif("Solde de test"); m.setDateMouvement(LocalDateTime.now().minusDays(1));
        mouvements.saveAndFlush(m);
    }

    private void authentifier(String email, String role) {
        var contexte = SecurityContextHolder.createEmptyContext();
        contexte.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(email, "", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(contexte);
    }

    private Boutique boutique(String nom) { Boutique b = new Boutique(); b.setNom(nom); return boutiques.saveAndFlush(b); }

    private void utilisateur(String email, Role role, Boutique b) {
        Utilisateur u = new Utilisateur(); u.setBoutique(b); u.setEmail(email); u.setNom(email); u.setRole(role); utilisateurs.save(u);
    }
}
