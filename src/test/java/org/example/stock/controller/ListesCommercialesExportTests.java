package org.example.stock.controller;

import org.apache.poi.ss.usermodel.*;
import org.example.stock.enums.EtatDevis;
import org.example.stock.enums.Role;
import org.example.stock.enums.StatutDevis;
import org.example.stock.enums.TypeRetour;
import org.example.stock.model.*;
import org.example.stock.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.example.stock.export.ExportTestOutils.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 4, tâche B : exports Excel et PDF des listes commerciales : achats (gérant), devis (gérant et caissier) et retours (gérant).
 * Deux boutiques, un gérant et un caissier dans la boutique A, un gérant dans la boutique B. Chaque chiffre est comparé au calcul fait à la main.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:export-listes-commerciales;DB_CLOSE_DELAY=-1", "stock.export.limite-lignes=5"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class ListesCommercialesExportTests {
    private static final String GERANT_A = "liste-gerant-a@example.test";
    private static final String CAISSIER_A = "liste-caissier-a@example.test";
    private static final String GERANT_B = "liste-gerant-b@example.test";
    private static final String FORMULE = "=HYPERLINK(\"http://evil.example\",\"clic\")";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired ClientRepository clients;
    @Autowired FournisseurRepository fournisseurs;
    @Autowired VenteRepository ventes;
    @Autowired AchatRepository achats;
    @Autowired DevisRepository devis;
    @Autowired RetourRepository retours;

    Boutique boutiqueA, boutiqueB;
    Utilisateur awa, bakary, gerantB;
    Fournisseur sory, agro, piege, fournisseurB;
    Client eleve, secret;

    @BeforeEach
    void preparer() {
        retours.deleteAll(); devis.deleteAll(); achats.deleteAll(); ventes.deleteAll(); clients.deleteAll(); fournisseurs.deleteAll();
        utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique Étoile A", "12 rue de l'Œuvre, Conakry"); boutiqueB = boutique("Boutique Autre B", null);
        awa = compte(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA); bakary = compte(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA);
        gerantB = compte(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB);
        sory = fournisseur(boutiqueA, "Sory Import"); agro = fournisseur(boutiqueA, "Agro Plus"); piege = fournisseur(boutiqueA, FORMULE);
        fournisseurB = fournisseur(boutiqueB, "Fournisseur Secret B");
        eleve = client(boutiqueA, "Élève Ça’ñ"); secret = client(boutiqueB, "Client Secret B");

        // Achats de la boutique A : total, retours, versé, avance, échéance.
        achat(boutiqueA, "FA-000001", LocalDateTime.of(2026, 10, 5, 10, 0), sory, awa, "1000000", "0", "1000000", "0", null);
        achat(boutiqueA, "FA-000002", LocalDateTime.of(2026, 10, 20, 15, 30), agro, awa, "800000", "50000", "300000", "100000", LocalDate.now().minusDays(1));
        achat(boutiqueA, "FA-000003", LocalDateTime.of(2026, 11, 3, 9, 0), piege, null, "250000.50", "0", "250000.50", "0", null);
        achat(boutiqueB, "FA-000777", LocalDateTime.of(2026, 10, 10, 10, 0), fournisseurB, gerantB, "9999999", "0", "9999999", "0", null);

        // Devis : en cours, expiré (validité hier), transformé, annulé.
        devis(boutiqueA, "DV-000001", LocalDateTime.of(2026, 10, 5, 10, 0), eleve, awa, LocalDate.now().plusDays(10), "300000", StatutDevis.EN_COURS);
        devis(boutiqueA, "DV-000002", LocalDateTime.of(2026, 10, 20, 15, 30), eleve, bakary, LocalDate.now().minusDays(1), "150000.50", StatutDevis.EN_COURS);
        devis(boutiqueA, "DV-000003", LocalDateTime.of(2026, 11, 3, 9, 0), null, bakary, LocalDate.now().plusDays(5), "90000", StatutDevis.TRANSFORME);
        devis(boutiqueA, "DV-000004", LocalDateTime.of(2026, 11, 4, 9, 0), eleve, awa, LocalDate.now().plusDays(5), "10000", StatutDevis.ANNULE);
        devis(boutiqueB, "DV-000777", LocalDateTime.of(2026, 10, 10, 10, 0), secret, gerantB, LocalDate.now().plusDays(5), "9999999", StatutDevis.EN_COURS);

        // Retours : un retour de vente (remboursé 200 000, avoir 100 000) et un retour d'achat entièrement remboursé.
        Vente v1 = vente(boutiqueA, awa, eleve, "FV-000001", LocalDateTime.of(2026, 10, 5, 10, 0), "1500000");
        retour(boutiqueA, "FR-000001", TypeRetour.VENTE, v1, null, eleve, null, LocalDateTime.of(2026, 10, 6, 9, 0), "500000", "200000", "100000");
        retour(boutiqueA, "FRA-000001", TypeRetour.ACHAT, null, achats.findAll().stream().filter(a -> "FA-000001".equals(a.getNumero())).findFirst().orElseThrow(), null, sory,
                LocalDateTime.of(2026, 10, 25, 11, 0), "300000", "300000", "0");
        Vente vB = vente(boutiqueB, gerantB, secret, "FV-000777", LocalDateTime.of(2026, 10, 10, 10, 0), "9999999");
        retour(boutiqueB, "FR-000777", TypeRetour.VENTE, vB, null, secret, null, LocalDateTime.of(2026, 10, 11, 9, 0), "9999999", "9999999", "0");
    }

    // ===== Achats =====

    @Test
    void lExcelDesAchatsADeVraisNombresEtDesTotauxJustes() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/achats/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"achats_" + LocalDate.now() + ".xlsx\"");
        assertThat(reponse.getHeader("Cache-Control")).isEqualTo("no-store");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Achats");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Étoile A");
            assertThat(f.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Journal des achats");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Tous les achats");
            assertThat(entetes(f, "Numéro")).containsExactly("Numéro", "Date", "Fournisseur", "Saisi par", "Total", "Retours", "Versé", "Avance utilisée",
                    "Reste", "Statut", "Échéance");
            List<Row> lignes = lignes(f, "Numéro");
            // Plus récents d'abord, comme à l'écran.
            assertThat(premieresCellules(lignes)).containsExactly("FA-000003", "FA-000002", "FA-000001");
            Row a1 = lignes.get(2), a2 = lignes.get(1), a3 = lignes.get(0);
            assertThat(a1.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 5, 10, 0));
            assertThat(chaine(a1, 2)).isEqualTo("Sory Import");
            assertThat(chaine(a1, 3)).isEqualTo("Awa Gérante");
            assertThat(List.of(nombre(a1, 4), nombre(a1, 5), nombre(a1, 6), nombre(a1, 7), nombre(a1, 8))).containsExactly(1_000_000d, 0d, 1_000_000d, 0d, 0d);
            assertThat(chaine(a1, 9)).isEqualTo("Réglé");
            assertThat(a1.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            // A2 : 800 000 − 50 000 − 300 000 − 100 000 = 350 000 restent, en retard depuis hier.
            assertThat(List.of(nombre(a2, 4), nombre(a2, 5), nombre(a2, 6), nombre(a2, 7), nombre(a2, 8))).containsExactly(800_000d, 50_000d, 300_000d, 100_000d, 350_000d);
            assertThat(chaine(a2, 9)).isEqualTo("À régler (en retard)");
            assertThat(a2.getCell(10).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.now().minusDays(1));
            assertThat(DateUtil.isCellDateFormatted(a2.getCell(10))).isTrue();
            assertThat(chaine(a3, 3)).isEqualTo("Non renseigné");
            assertThat(nombre(a3, 4)).isEqualTo(250_000.5);
            assertThat(a3.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            // Un nom de fournisseur qui ressemble à une formule reste du texte inoffensif.
            assertThat(a3.getCell(2).getCellType()).isEqualTo(CellType.STRING);
            assertThat(a3.getCell(2).getCellStyle().getQuotePrefixed()).isTrue();
            aucuneFormule(f);
            // Totaux : 2 050 000,50 ; 50 000 ; 1 550 000,50 ; 100 000 ; 350 000.
            Row totaux = totaux(f, "Numéro");
            assertThat(chaine(totaux, 0)).isEqualTo("3 achats");
            assertThat(List.of(nombre(totaux, 4), nombre(totaux, 5), nombre(totaux, 6), nombre(totaux, 7), nombre(totaux, 8)))
                    .containsExactly(2_050_000.5, 50_000d, 1_550_000.5, 100_000d, 350_000d);
            assertThat(texteDeLaFeuille(f)).doesNotContain("FA-000777", "Fournisseur Secret B", "9999999", "Boutique Autre B");
        }
    }

    @Test
    void lePdfDesAchatsCommenceParPdfContientLaBoutiqueEtSesLignesEnPaysage() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/achats/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"achats_" + LocalDate.now() + ".pdf\"");
        String texte = pdf(reponse);
        assertThat(texte).contains("Boutique Étoile A", "12 rue de l'Œuvre, Conakry", "Journal des achats", "FA-000001", "Sory Import", "Agro Plus",
                "À régler (en retard)", "Réglé", "2 050 000,50", "350 000", "3 achats", "par Awa Gérante", "Page 1 / 1");
        assertThat(texte).doesNotContain("RFC-COMPTA", "FA-000777", "Fournisseur Secret B", "9 999 999");
        assertThat(estPaysage(reponse.getContentAsByteArray())).isTrue();
    }

    @Test
    void lesFiltresDesAchatsDonnentLesMemesLignesQueLEcran() throws Exception {
        // Écran et export : tous, puis échus non soldés.
        assertThat(achatsDeLEcran(gerant(get("/achats")))).containsExactly("FA-000003", "FA-000002", "FA-000001");
        assertThat(achatsDeLEcran(gerant(get("/achats").param("filtre", "echues")))).containsExactly("FA-000002");
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/achats/export/excel").param("filtre", "echues"))).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(premieresCellules(lignes(f, "Numéro"))).containsExactly("FA-000002");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Achats échus non soldés");
            assertThat(nombre(totaux(f, "Numéro"), 8)).isEqualTo(350_000d);
        }
        String pdf = pdf(mvc.perform(gerant(get("/achats/export/pdf").param("filtre", "echues"))).andReturn().getResponse());
        assertThat(pdf).contains("FA-000002", "Achats échus non soldés").doesNotContain("FA-000001", "FA-000003");
    }

    @Test
    void laRechercheDesAchatsIgnoreAccentsEtCasseEtSeSignaleDansLEnTete() throws Exception {
        for (String q : new String[]{"agro", "AGRO plus", "fa-000002", "350000", "350 000", "awa gerante en retard"}) {
            try (Workbook classeur = classeur(mvc.perform(gerant(get("/achats/export/excel").param("q", q))).andReturn().getResponse())) {
                Sheet f = classeur.getSheetAt(0);
                assertThat(premieresCellules(lignes(f, "Numéro"))).as(q).contains("FA-000002");
                assertThat(f.getRow(2).getCell(0).getStringCellValue()).contains("Recherche : « " + q + " »");
            }
        }
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/achats/export/excel").param("q", "introuvable"))).andReturn().getResponse())) {
            assertThat(lignes(classeur.getSheetAt(0), "Numéro")).isEmpty();
            assertThat(chaine(totaux(classeur.getSheetAt(0), "Numéro"), 0)).isEqualTo("0 achat");
        }
    }

    @Test
    void lesAchatsSontRefusesAuCaissierEtAUnVisiteurSansConnexion() throws Exception {
        for (String url : List.of("/achats/export/excel", "/achats/export/pdf")) {
            mvc.perform(caissier(get(url))).andExpect(status().isForbidden());
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
            mvc.perform(post(url).with(user(GERANT_A).roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        }
        mvc.perform(gerant(get("/achats/export/excel").param("filtre", "tout"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/achats/export/excel").param("q", "a", "b"))).andExpect(status().isBadRequest());
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesAchats() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/achats/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(premieresCellules(lignes(f, "Numéro"))).containsExactly("FA-000777");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Autre B");
            assertThat(texteDeLaFeuille(f)).doesNotContain("Étoile", "FA-000001", "Sory", "Awa");
        }
    }

    @Test
    void auDelaDeLaLimiteLesAchatsRevientALaListeAvecUnMessage() throws Exception {
        for (int i = 4; i <= 6; i++) achat(boutiqueA, "FA-00000" + i, LocalDateTime.of(2026, 11, i, 9, 0), sory, awa, "1000", "0", "1000", "0", null);
        for (String format : new String[]{"excel", "pdf"}) {
            MvcResult resultat = mvc.perform(gerant(get("/achats/export/" + format))).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/achats");
            assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 5 lignes");
        }
        // Le filtre « échus » ramène sous la limite : il se revoit sur la liste avec le message.
        mvc.perform(gerant(get("/achats/export/excel").param("filtre", "echues"))).andExpect(status().isOk());
        assertThat(mvc.perform(gerant(get("/achats")).flashAttr("error", "Cet export dépasse 5 lignes.")).andReturn().getResponse().getContentAsString()).contains("Cet export dépasse 5 lignes");
    }

    @Test
    void laListeDesAchatsPorteLesBoutonsAvecSonFiltre() throws Exception {
        String page = mvc.perform(gerant(get("/achats").param("filtre", "echues"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-export-liste=\"excel\"", "data-export-liste=\"pdf\"", "href=\"/achats/export/excel?filtre=echues\"",
                "href=\"/achats/export/pdf?filtre=echues\"", "js/export-liste.js");
        String toutes = mvc.perform(gerant(get("/achats"))).andReturn().getResponse().getContentAsString();
        assertThat(toutes).contains("href=\"/achats/export/excel\"").doesNotContain("export/excel?filtre");
    }

    // ===== Devis =====

    @Test
    void lExcelDesDevisADeVraisNombresEtLEtatCalcule() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/devis/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"devis_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Devis");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Tous les devis");
            assertThat(entetes(f, "Numéro")).containsExactly("Numéro", "Date", "Client", "Auteur", "Total", "Valable jusqu’au", "État");
            List<Row> lignes = lignes(f, "Numéro");
            assertThat(premieresCellules(lignes)).containsExactly("DV-000004", "DV-000003", "DV-000002", "DV-000001");
            Row d1 = lignes.get(3), d2 = lignes.get(2), d3 = lignes.get(1), d4 = lignes.get(0);
            assertThat(chaine(d1, 2)).isEqualTo("Élève Ça’ñ");
            assertThat(chaine(d1, 3)).isEqualTo("Awa Gérante");
            assertThat(nombre(d1, 4)).isEqualTo(300_000d);
            assertThat(d1.getCell(5).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.now().plusDays(10));
            assertThat(DateUtil.isCellDateFormatted(d1.getCell(5))).isTrue();
            assertThat(chaine(d1, 6)).isEqualTo("En cours");
            assertThat(chaine(d2, 6)).isEqualTo("Expiré");
            assertThat(chaine(d2, 3)).isEqualTo("Bakary Caissier");
            assertThat(nombre(d2, 4)).isEqualTo(150_000.5);
            assertThat(chaine(d3, 2)).isEqualTo("Sans client");
            assertThat(chaine(d3, 6)).isEqualTo("Transformé");
            assertThat(chaine(d4, 6)).isEqualTo("Annulé");
            Row totaux = totaux(f, "Numéro");
            assertThat(chaine(totaux, 0)).isEqualTo("4 devis");
            assertThat(nombre(totaux, 4)).isEqualTo(550_000.5);
            assertThat(texteDeLaFeuille(f)).doesNotContain("DV-000777", "Client Secret B", "9999999");
        }
    }

    @Test
    void lePdfDesDevisCommenceParPdfEtPorteLaBoutique() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/devis/export/pdf"))).andReturn().getResponse();
        String texte = pdf(reponse);
        assertThat(texte).contains("Boutique Étoile A", "Liste des devis", "DV-000001", "Élève Ça’ñ", "Sans client", "550 000,50", "Expiré", "Transformé", "4 devis");
        assertThat(texte).doesNotContain("DV-000777", "Client Secret B");
        // Sept colonnes : portrait.
        assertThat(estPaysage(reponse.getContentAsByteArray())).isFalse();
    }

    @Test
    void chaqueEtatDeDevisDonneLesMemesDevisQueLEcran() throws Exception {
        for (EtatDevis etat : EtatDevis.values()) {
            List<String> ecran = devisDeLEcran(gerant(get("/devis").param("etat", etat.getCode())));
            assertThat(ecran).as("écran " + etat).hasSize(1);
            try (Workbook classeur = classeur(mvc.perform(gerant(get("/devis/export/excel").param("etat", etat.getCode()))).andReturn().getResponse())) {
                assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).as("Excel " + etat).containsExactlyElementsOf(ecran);
                assertThat(classeur.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("Devis : " + etat.getLibelle());
            }
            String pdf = pdf(mvc.perform(gerant(get("/devis/export/pdf").param("etat", etat.getCode()))).andReturn().getResponse());
            assertThat(pdf).as("PDF " + etat).contains(ecran.getFirst());
            for (String autre : List.of("DV-000001", "DV-000002", "DV-000003", "DV-000004")) if (!autre.equals(ecran.getFirst())) assertThat(pdf).doesNotContain(autre);
        }
        assertThat(devisDeLEcran(gerant(get("/devis")))).hasSize(4);
        mvc.perform(gerant(get("/devis/export/excel").param("etat", "inconnu"))).andExpect(status().isBadRequest());
    }

    @Test
    void leCaissierExporteLesDevisEtAUneRechercheSansAccents() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/devis/export/excel"))).andExpect(status().isOk()).andReturn().getResponse())) {
            assertThat(lignes(classeur.getSheetAt(0), "Numéro")).hasSize(4);
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("DV-000777");
        }
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/devis/export/excel").param("q", "ELEVE ca").param("etat", "annule"))).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).containsExactly("DV-000004");
        }
        mvc.perform(caissier(get("/devis/export/pdf"))).andExpect(status().isOk());
    }

    @Test
    void uneAutreBoutiqueNeVoitQueSesDevisEtLAccesExigeUneConnexion() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/devis/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).containsExactly("DV-000777");
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("Étoile", "DV-000001", "Élève");
        }
        for (String url : List.of("/devis/export/excel", "/devis/export/pdf")) {
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
    }

    @Test
    void auDelaDeLaLimiteLesDevisRevientALaListeAvecLEtatChoisi() throws Exception {
        for (int i = 5; i <= 7; i++) devis(boutiqueA, "DV-00000" + i, LocalDateTime.of(2026, 11, i, 9, 0), eleve, awa, LocalDate.now().plusDays(5), "1000", StatutDevis.EN_COURS);
        MvcResult resultat = mvc.perform(gerant(get("/devis/export/pdf"))).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/devis");
        assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 5 lignes");
        mvc.perform(gerant(get("/devis/export/pdf").param("etat", "annule"))).andExpect(status().isOk());
    }

    @Test
    void laListeDesDevisPorteLesBoutonsAvecLEtatChoisi() throws Exception {
        for (MockHttpServletRequestBuilder requete : List.of(gerant(get("/devis").param("etat", "expire")), caissier(get("/devis").param("etat", "expire")))) {
            String page = mvc.perform(requete).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("href=\"/devis/export/excel?etat=expire\"", "href=\"/devis/export/pdf?etat=expire\"", "data-export-liste=\"excel\"");
        }
    }

    // ===== Retours =====

    @Test
    void lExcelDesRetoursMelangeVenteEtAchatAvecLesBonsMontants() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/retours/export/excel"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"retours_" + LocalDate.now() + ".xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Retours");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Tous les retours");
            assertThat(entetes(f, "Numéro")).containsExactly("Numéro", "Type", "Date", "Document d’origine", "Client / fournisseur", "Montant", "Remboursé", "Avoir");
            List<Row> lignes = lignes(f, "Numéro");
            // Le plus récent d'abord : le retour d'achat du 25/10 puis le retour de vente du 06/10.
            assertThat(premieresCellules(lignes)).containsExactly("FRA-000001", "FR-000001");
            Row achat = lignes.get(0), vente = lignes.get(1);
            assertThat(chaine(achat, 1)).isEqualTo("Achat");
            assertThat(chaine(achat, 3)).isEqualTo("FA-000001");
            assertThat(chaine(achat, 4)).isEqualTo("Sory Import");
            assertThat(List.of(nombre(achat, 5), nombre(achat, 6), nombre(achat, 7))).containsExactly(300_000d, 300_000d, 0d);
            assertThat(achat.getCell(2).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 25, 11, 0));
            assertThat(chaine(vente, 1)).isEqualTo("Vente");
            assertThat(chaine(vente, 3)).isEqualTo("FV-000001");
            assertThat(chaine(vente, 4)).isEqualTo("Élève Ça’ñ");
            assertThat(List.of(nombre(vente, 5), nombre(vente, 6), nombre(vente, 7))).containsExactly(500_000d, 200_000d, 100_000d);
            Row totaux = totaux(f, "Numéro");
            assertThat(chaine(totaux, 0)).isEqualTo("2 retours");
            assertThat(List.of(nombre(totaux, 5), nombre(totaux, 6), nombre(totaux, 7))).containsExactly(800_000d, 500_000d, 100_000d);
            assertThat(texteDeLaFeuille(f)).doesNotContain("FR-000777", "Client Secret B", "9999999");
        }
    }

    @Test
    void lePdfDesRetoursEstEnPaysageAvecLaBoutiqueEtLesLignes() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/retours/export/pdf"))).andReturn().getResponse();
        String texte = pdf(reponse);
        assertThat(texte).contains("Boutique Étoile A", "Liste des retours", "FR-000001", "FRA-000001", "Sory Import", "Élève Ça’ñ", "800 000", "2 retours", "par Awa Gérante");
        assertThat(texte).doesNotContain("FR-000777", "Client Secret B", "9 999 999");
        assertThat(estPaysage(reponse.getContentAsByteArray())).isTrue();
    }

    @Test
    void leFiltreVenteAchatDesRetoursEstRespecte() throws Exception {
        assertThat(retoursDeLEcran(gerant(get("/retours")))).containsExactly("FRA-000001", "FR-000001");
        assertThat(retoursDeLEcran(gerant(get("/retours").param("type", "vente")))).containsExactly("FR-000001");
        assertThat(retoursDeLEcran(gerant(get("/retours").param("type", "achat")))).containsExactly("FRA-000001");
        for (String type : new String[]{"vente", "achat"}) {
            List<String> attendu = retoursDeLEcran(gerant(get("/retours").param("type", type)));
            try (Workbook classeur = classeur(mvc.perform(gerant(get("/retours/export/excel").param("type", type))).andReturn().getResponse())) {
                assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).containsExactlyElementsOf(attendu);
                assertThat(classeur.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo(type.equals("vente") ? "Retours de vente" : "Retours d’achat");
            }
            String pdf = pdf(mvc.perform(gerant(get("/retours/export/pdf").param("type", type))).andReturn().getResponse());
            assertThat(pdf).contains(attendu.getFirst()).doesNotContain(type.equals("vente") ? "FRA-000001" : "FR-000001");
        }
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/retours/export/excel").param("q", "sory"))).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).containsExactly("FRA-000001");
        }
        mvc.perform(gerant(get("/retours/export/excel").param("type", "tout"))).andExpect(status().isBadRequest());
    }

    @Test
    void lesRetoursSontReservesAuGerantEtLAutreBoutiqueEstExclue() throws Exception {
        for (String url : List.of("/retours/export/excel", "/retours/export/pdf")) {
            mvc.perform(caissier(get(url))).andExpect(status().isForbidden());
            assertThat(mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl()).endsWith("/login");
        }
        try (Workbook classeur = classeur(mvc.perform(avec(get("/retours/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            assertThat(premieresCellules(lignes(classeur.getSheetAt(0), "Numéro"))).containsExactly("FR-000777");
            assertThat(texteDeLaFeuille(classeur.getSheetAt(0))).doesNotContain("FR-000001", "FRA-000001", "Sory", "Élève");
        }
    }

    @Test
    void auDelaDeLaLimiteLesRetoursRevientAlaListeAvecLeType() throws Exception {
        Vente v = ventes.findAll().stream().filter(x -> "FV-000001".equals(x.getNumero())).findFirst().orElseThrow();
        for (int i = 2; i <= 7; i++) retour(boutiqueA, "FR-00000" + i, TypeRetour.VENTE, v, null, eleve, null, LocalDateTime.of(2026, 11, i, 9, 0), "1000", "1000", "0");
        MvcResult resultat = mvc.perform(gerant(get("/retours/export/excel").param("type", "vente"))).andExpect(status().is3xxRedirection()).andReturn();
        assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/retours?type=vente");
        assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 5 lignes");
        mvc.perform(gerant(get("/retours/export/excel").param("type", "achat"))).andExpect(status().isOk());
    }

    @Test
    void laListeDesRetoursPorteLesBoutonsAvecLeType() throws Exception {
        String page = mvc.perform(gerant(get("/retours").param("type", "achat"))).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("href=\"/retours/export/excel?type=achat\"", "href=\"/retours/export/pdf?type=achat\"", "js/export-liste.js");
        assertThat(mvc.perform(gerant(get("/retours"))).andReturn().getResponse().getContentAsString()).contains("href=\"/retours/export/excel\"");
    }

    /** Génère les PDF de démonstration si EXPORT_DEMO_DIR est défini (inspection visuelle, voir docs/lot-4.md). */
    @Test
    void genererLaDemonstration() throws Exception {
        String dossier = System.getenv("EXPORT_DEMO_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        java.nio.file.Path cible = java.nio.file.Path.of(dossier);
        java.nio.file.Files.createDirectories(cible);
        for (String liste : List.of("achats", "devis", "retours")) {
            for (String format : new String[]{"pdf", "excel"}) {
                byte[] octets = mvc.perform(gerant(get("/" + liste + "/export/" + format))).andReturn().getResponse().getContentAsByteArray();
                java.nio.file.Files.write(cible.resolve("demo-" + liste + (format.equals("pdf") ? ".pdf" : ".xlsx")), octets);
            }
        }
    }

    // ----- Outils -----

    @SuppressWarnings("unchecked")
    private List<String> achatsDeLEcran(MockHttpServletRequestBuilder requete) throws Exception {
        return ((List<Achat>) mvc.perform(requete).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("achats")).stream().map(Achat::getNumero).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> devisDeLEcran(MockHttpServletRequestBuilder requete) throws Exception {
        return ((List<Devis>) mvc.perform(requete).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("devisListe")).stream().map(Devis::getNumero).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> retoursDeLEcran(MockHttpServletRequestBuilder requete) throws Exception {
        return ((List<Retour>) mvc.perform(requete).andExpect(status().isOk()).andReturn().getModelAndView().getModel().get("retours")).stream().map(Retour::getNumero).toList();
    }

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder requete) { return avec(requete, GERANT_A, "ADMIN"); }
    private MockHttpServletRequestBuilder caissier(MockHttpServletRequestBuilder requete) { return avec(requete, CAISSIER_A, "CAISSIER"); }

    private Boutique boutique(String nom, String adresse) {
        Boutique b = new Boutique(); b.setNom(nom); b.setAdresse(adresse); return boutiques.saveAndFlush(b);
    }

    private Utilisateur compte(String email, String nom, Role role, Boutique boutique) {
        Utilisateur u = new Utilisateur(); u.setNom(nom); u.setEmail(email); u.setRole(role); u.setBoutique(boutique); u.setActif(true);
        return utilisateurs.saveAndFlush(u);
    }

    private Client client(Boutique boutique, String nom) {
        Client c = new Client(); c.setBoutique(boutique); c.setNom(nom); return clients.saveAndFlush(c);
    }

    private Fournisseur fournisseur(Boutique boutique, String nom) {
        Fournisseur f = new Fournisseur(); f.setBoutique(boutique); f.setNom(nom); return fournisseurs.saveAndFlush(f);
    }

    private Achat achat(Boutique boutique, String numero, LocalDateTime date, Fournisseur fournisseur, Utilisateur auteur, String total, String retoursMontant,
                        String verse, String avance, LocalDate echeance) {
        Achat a = new Achat(); a.setBoutique(boutique); a.setNumero(numero); a.setDateAchat(date); a.setFournisseur(fournisseur); a.setUtilisateur(auteur);
        a.setMontantTotal(new BigDecimal(total)); a.setMontantRetours(new BigDecimal(retoursMontant)); a.setMontantVerse(new BigDecimal(verse));
        a.setAvanceUtilisee(new BigDecimal(avance)); a.setDateEcheance(echeance);
        return achats.saveAndFlush(a);
    }

    private Devis devis(Boutique boutique, String numero, LocalDateTime date, Client client, Utilisateur auteur, LocalDate validite, String total, StatutDevis statut) {
        Devis d = new Devis(); d.setBoutique(boutique); d.setNumero(numero); d.setDateDevis(date); d.setClient(client); d.setUtilisateur(auteur);
        d.setDateValidite(validite); d.setMontantTotal(new BigDecimal(total)); d.setStatut(statut);
        return devis.saveAndFlush(d);
    }

    private Vente vente(Boutique boutique, Utilisateur vendeur, Client client, String numero, LocalDateTime date, String total) {
        Vente v = new Vente(); v.setBoutique(boutique); v.setVendeur(vendeur); v.setClient(client); v.setNumero(numero); v.setDateVente(date);
        v.setMontantTotal(new BigDecimal(total)); v.setMontantVerse(new BigDecimal(total));
        return ventes.saveAndFlush(v);
    }

    private Retour retour(Boutique boutique, String numero, TypeRetour type, Vente vente, Achat achat, Client client, Fournisseur fournisseur, LocalDateTime date,
                          String total, String rembourse, String avoir) {
        Retour r = new Retour(); r.setBoutique(boutique); r.setNumero(numero); r.setType(type); r.setVente(vente); r.setAchat(achat); r.setClient(client);
        r.setFournisseur(fournisseur); r.setDateRetour(date); r.setMontantTotal(new BigDecimal(total)); r.setMontantRembourse(new BigDecimal(rembourse));
        r.setMontantAvoir(new BigDecimal(avoir));
        return retours.saveAndFlush(r);
    }
}
