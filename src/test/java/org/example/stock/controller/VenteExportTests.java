package org.example.stock.controller;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.enums.Role;
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

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lot 4, tâche A : exports Excel et PDF de la liste des ventes. Deux boutiques, un gérant et un caissier dans la
 * boutique A, un gérant dans la boutique B. Chaque chiffre est comparé au calcul fait à la main.
 */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:export-ventes;DB_CLOSE_DELAY=-1", "stock.export.limite-lignes=3"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class VenteExportTests {
    private static final String GERANT_A = "export-gerant-a@example.test";
    private static final String CAISSIER_A = "export-caissier-a@example.test";
    private static final String GERANT_B = "export-gerant-b@example.test";
    private static final String FORMULE = "=HYPERLINK(\"http://evil.example\",\"clic\")";

    @Autowired MockMvc mvc;
    @Autowired BoutiqueRepository boutiques;
    @Autowired UtilisateurRepository utilisateurs;
    @Autowired CategorieRepository categories;
    @Autowired ProduitRepository produits;
    @Autowired ClientRepository clients;
    @Autowired VenteRepository ventes;
    @Autowired RetourRepository retours;
    @Autowired MouvementCaisseRepository caisse;
    @Autowired OperationCreationRepository operations;

    Boutique boutiqueA, boutiqueB;
    Utilisateur awa, bakary, gerantB;
    Produit riz, rizB;

    @BeforeEach
    void preparer() {
        operations.deleteAll(); caisse.deleteAll(); retours.deleteAll(); ventes.deleteAll(); clients.deleteAll(); produits.deleteAll();
        categories.deleteAll(); utilisateurs.deleteAll(); boutiques.deleteAll();
        boutiqueA = boutique("Boutique Étoile A", "12 rue de l'Œuvre, Conakry"); boutiqueB = boutique("Boutique Autre B", null);
        awa = compte(GERANT_A, "Awa Gérante", Role.ADMIN, boutiqueA); bakary = compte(CAISSIER_A, "Bakary Caissier", Role.CAISSIER, boutiqueA);
        gerantB = compte(GERANT_B, "Zoé Autre Boutique", Role.ADMIN, boutiqueB);
        Categorie rayonA = categorie(boutiqueA), rayonB = categorie(boutiqueB);
        riz = produit(boutiqueA, rayonA, "Riz"); rizB = produit(boutiqueB, rayonB, "Riz B");
        Client eleve = client(boutiqueA, "Élève Ça’ñ"), piege = client(boutiqueA, FORMULE), secret = client(boutiqueB, "Client Secret B");

        // V1 : 3 × (500 000 − 300 000) = 600 000, moins un retour d'une unité (marge 200 000) = 400 000 ; retours 500 000 ; réglée.
        Vente v1 = vente(boutiqueA, awa, eleve, "FV-000001", LocalDateTime.of(2026, 10, 5, 10, 0), "1500000", "1500000", null, ligne(riz, 3, "500000", "300000"));
        v1.setMontantRetours(new BigDecimal("500000")); ventes.saveAndFlush(v1);
        retour(v1, riz, 1, "500000", "300000");
        // V2 : 2 × (1 000 000 − 400 000) = 1 200 000 ; reste 1 500 000 ; échue depuis hier ; client au nom de formule.
        vente(boutiqueA, bakary, piege, "FV-000002", LocalDateTime.of(2026, 10, 20, 15, 30), "2000000", "500000", LocalDate.now().minusDays(1),
                ligne(riz, 2, "1000000", "400000"));
        // V3 : sans client, coût inconnu (compté 0), centimes ; réglée.
        vente(boutiqueA, bakary, null, "FV-000003", LocalDateTime.of(2026, 11, 3, 9, 0), "250000.50", "250000.50", null, ligne(riz, 1, "250000.50", null));
        // Autre boutique.
        vente(boutiqueB, gerantB, secret, "FV-000777", LocalDateTime.of(2026, 10, 10, 10, 0), "9999999", "9999999", null, ligne(rizB, 1, "9999999", "1"));
    }

    // ----- Excel : gérant -----

    @Test
    void leGerantRecoitUnExcelAvecDeVraisNombresLesTotauxEtLeBeneficeBrut() throws Exception {
        MvcResult resultat = mvc.perform(gerant(get("/ventes/export/excel"))).andExpect(status().isOk()).andReturn();
        MockHttpServletResponse reponse = resultat.getResponse();
        assertThat(reponse.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"ventes_" + LocalDate.now() + ".xlsx\"");
        assertThat(reponse.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(reponse.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");

        try (Workbook classeur = classeur(reponse)) {
            Sheet f = classeur.getSheet("Ventes");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Étoile A");
            assertThat(f.getRow(1).getCell(0).getStringCellValue()).isEqualTo("Journal des ventes");
            assertThat(f.getRow(2).getCell(0).getStringCellValue()).isEqualTo("Toutes les ventes");
            assertThat(texteDesEntetes(f)).containsExactly("Numéro", "Date", "Client", "Vendeur", "Total", "Retours", "Versé", "Avance utilisée",
                    "Reste", "Statut", "Échéance", "Bénéfice brut");
            List<Row> lignes = lignesDeDonnees(f);
            // Plus récentes d'abord, comme à l'écran.
            assertThat(lignes).extracting(r -> r.getCell(0).getStringCellValue()).containsExactly("FV-000003", "FV-000002", "FV-000001");
            Row v1 = lignes.get(2), v2 = lignes.get(1), v3 = lignes.get(0);
            assertThat(v1.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 10, 5, 10, 0));
            assertThat(v1.getCell(2).getStringCellValue()).isEqualTo("Élève Ça’ñ");
            assertThat(v1.getCell(3).getStringCellValue()).isEqualTo("Awa Gérante");
            assertThat(nombres(v1)).containsExactly(1_500_000d, 500_000d, 1_500_000d, 0d, 0d, 400_000d);
            assertThat(v1.getCell(9).getStringCellValue()).isEqualTo("Réglée");
            assertThat(v1.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(v1.getCell(10)).isNull();
            assertThat(nombres(v2)).containsExactly(2_000_000d, 0d, 500_000d, 0d, 1_500_000d, 1_200_000d);
            assertThat(v2.getCell(9).getStringCellValue()).isEqualTo("À régler (en retard)");
            assertThat(v2.getCell(10).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.now().minusDays(1));
            assertThat(DateUtil.isCellDateFormatted(v2.getCell(10))).isTrue();
            assertThat(v3.getCell(2).getStringCellValue()).isEqualTo("Client de passage");
            assertThat(nombres(v3)).containsExactly(250_000.5, 0d, 250_000.5, 0d, 0d, 250_000.5);
            assertThat(v3.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            // Totaux : 3 750 000,50 ; retours 500 000 ; versé 2 250 000,50 ; reste 1 500 000 ; bénéfice 1 850 000,50.
            Row totaux = f.getRow(lignes.get(2).getRowNum() + 1);
            assertThat(totaux.getCell(0).getStringCellValue()).isEqualTo("3 ventes");
            assertThat(nombres(totaux)).containsExactly(3_750_000.5, 500_000d, 2_250_000.5, 0d, 1_500_000d, 1_850_000.5);
            assertThat(texteDeLaFeuille(f)).contains("1 ligne de vente sans coût connu, comptée à 0");
            // Exclusion de l'autre boutique.
            assertThat(texteDeLaFeuille(f)).doesNotContain("FV-000777", "Client Secret B", "Boutique Autre B", "9999999");
        }
    }

    @Test
    void lesFormulesDesDonneesSaisiesSontNeutraliseesDansLExcel() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/ventes/export/excel"))).andReturn().getResponse())) {
            Row v2 = lignesDeDonnees(classeur.getSheet("Ventes")).get(1);
            Cell client = v2.getCell(2);
            assertThat(client.getCellType()).isEqualTo(CellType.STRING);
            assertThat(client.getStringCellValue()).isEqualTo(FORMULE);
            assertThat(client.getCellStyle().getQuotePrefixed()).isTrue();
            for (Row ligne : classeur.getSheet("Ventes")) for (Cell c : ligne) assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA);
        }
    }

    // ----- PDF -----

    @Test
    void leGerantRecoitUnVraiPdfAvecLeNomDeLaBoutiqueLesNumerosLesAccentsEtLeBenefice() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/ventes/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(reponse.getContentType()).isEqualTo("application/pdf");
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"ventes_" + LocalDate.now() + ".pdf\"");
        assertThat(reponse.getHeader("Cache-Control")).isEqualTo("no-store");
        byte[] octets = reponse.getContentAsByteArray();
        assertThat(new String(octets, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        String texte = texte(octets);
        assertThat(texte).contains("Boutique Étoile A", "12 rue de l'Œuvre, Conakry", "Journal des ventes", "FV-000001", "FV-000002", "FV-000003",
                "Élève Ça’ñ", "Awa Gérante", "Bénéfice brut", "1 500 000", "250 000,50", "3 750 000,50", "1 850 000,50", "Réglée",
                "À régler (en retard)", "3 ventes", "Édité le", "par Awa Gérante", "Page 1 / 1");
        assertThat(texte).doesNotContain("RFC-COMPTA", "FV-000777", "Client Secret B", "Boutique Autre B", "9 999 999");
    }

    // ----- Filtres : l'export porte exactement sur la liste de l'écran -----

    @Test
    void chaqueFiltreDonneLesMemesVentesQueLEcran() throws Exception {
        List<String[]> cas = List.of(
                new String[]{},
                new String[]{"filtre", "echues"},
                new String[]{"du", "2026-10-15", "au", "2026-10-31"},
                new String[]{"du", "2026-11-03"},
                new String[]{"au", "2026-10-05"},
                new String[]{"vendeur", "" + bakary.getId()},
                new String[]{"vendeur", "" + awa.getId(), "du", "2026-10-01", "au", "2026-12-31"},
                new String[]{"filtre", "echues", "vendeur", "" + bakary.getId(), "du", "2026-10-20", "au", "2026-10-20"});
        List<List<String>> attendus = List.of(List.of("FV-000003", "FV-000002", "FV-000001"), List.of("FV-000002"), List.of("FV-000002"),
                List.of("FV-000003"), List.of("FV-000001"), List.of("FV-000003", "FV-000002"), List.of("FV-000001"), List.of("FV-000002"));
        for (int i = 0; i < cas.size(); i++) {
            List<String> ecran = numerosDeLEcran(gerant(avecParametres(get("/ventes"), cas.get(i))));
            assertThat(ecran).as("écran " + i).containsExactlyElementsOf(attendus.get(i));
            try (Workbook classeur = classeur(mvc.perform(gerant(avecParametres(get("/ventes/export/excel"), cas.get(i)))).andExpect(status().isOk()).andReturn().getResponse())) {
                assertThat(lignesDeDonnees(classeur.getSheetAt(0))).as("Excel " + i).extracting(r -> r.getCell(0).getStringCellValue())
                        .containsExactlyElementsOf(attendus.get(i));
            }
            String pdf = texte(mvc.perform(gerant(avecParametres(get("/ventes/export/pdf"), cas.get(i)))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
            for (String numero : List.of("FV-000001", "FV-000002", "FV-000003")) {
                if (attendus.get(i).contains(numero)) assertThat(pdf).as("PDF " + i).contains(numero); else assertThat(pdf).as("PDF " + i).doesNotContain(numero);
            }
        }
    }

    @Test
    void lePeriodeEtLesFiltresFigurentDansLEnTeteEtLeNomDuFichier() throws Exception {
        MockHttpServletResponse reponse = mvc.perform(gerant(get("/ventes/export/excel").param("du", "2026-10-01").param("au", "2026-10-31")
                .param("vendeur", "" + bakary.getId()).param("filtre", "echues"))).andReturn().getResponse();
        assertThat(reponse.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"ventes_2026-10-01_2026-10-31.xlsx\"");
        try (Workbook classeur = classeur(reponse)) {
            assertThat(classeur.getSheetAt(0).getRow(2).getCell(0).getStringCellValue())
                    .isEqualTo("Période du 01/10/2026 au 31/10/2026 · Vendeur : Bakary Caissier · Ventes échues non soldées");
        }
    }

    @Test
    void laRechercheDeLEcranFiltreSansTenirCompteDesAccentsNiDeLaCasse() throws Exception {
        for (String q : new String[]{"ELEVE ca", "fv-000001", "Awa", "1500000", "1 500 000"}) {
            try (Workbook classeur = classeur(mvc.perform(gerant(get("/ventes/export/excel").param("q", q))).andReturn().getResponse())) {
                Sheet f = classeur.getSheetAt(0);
                assertThat(lignesDeDonnees(f)).as(q).extracting(r -> r.getCell(0).getStringCellValue()).contains("FV-000001");
                assertThat(f.getRow(2).getCell(0).getStringCellValue()).contains("Recherche : « " + q + " »");
            }
        }
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/ventes/export/excel").param("q", "élève"))).andReturn().getResponse())) {
            assertThat(lignesDeDonnees(classeur.getSheetAt(0))).extracting(r -> r.getCell(0).getStringCellValue()).containsExactly("FV-000001");
        }
        try (Workbook classeur = classeur(mvc.perform(gerant(get("/ventes/export/excel").param("q", "introuvable"))).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(lignesDeDonnees(f)).isEmpty();
            assertThat(texteDeLaFeuille(f)).contains("0 vente");
        }
    }

    // ----- Caissier -----

    @Test
    void leCaissierNeRecoitNiCoutNiBeneficeNiDansLExcelNiDansLePdf() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/ventes/export/excel"))).andExpect(status().isOk()).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(texteDesEntetes(f)).containsExactly("Numéro", "Date", "Client", "Vendeur", "Total", "Retours", "Versé", "Avance utilisée",
                    "Reste", "Statut", "Échéance");
            List<Row> lignes = lignesDeDonnees(f);
            assertThat(lignes).hasSize(3);
            assertThat(lignes).allSatisfy(r -> assertThat(r.getLastCellNum()).isLessThanOrEqualTo((short) 11));
            String tout = texteDeLaFeuille(f);
            assertThat(tout).doesNotContain("Bénéfice", "bénéfice", "coût", "400000", "1850000", "sans coût");
            for (Row ligne : f) for (Cell c : ligne) {
                if (c.getCellType() == CellType.NUMERIC && !DateUtil.isCellDateFormatted(c)) {
                    assertThat(c.getNumericCellValue()).isNotIn(400_000d, 1_200_000d, 1_850_000.5, 600_000d);
                }
            }
            assertThat(nombres(f.getRow(lignes.get(2).getRowNum() + 1))).containsExactly(3_750_000.5, 500_000d, 2_250_000.5, 0d, 1_500_000d);
        }
        String pdf = texte(mvc.perform(caissier(get("/ventes/export/pdf"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(pdf).contains("Boutique Étoile A", "FV-000001", "3 750 000,50", "par Bakary Caissier")
                .doesNotContain("Bénéfice", "1 850 000", "400 000", "1 200 000", "sans coût", "FV-000777");
    }

    @Test
    void leCaissierNePeutPasFiltrerParVendeurExactementCommeALEcran() throws Exception {
        List<String> ecran = numerosDeLEcran(caissier(get("/ventes").param("vendeur", "" + awa.getId())));
        assertThat(ecran).containsExactly("FV-000003", "FV-000002", "FV-000001");
        try (Workbook classeur = classeur(mvc.perform(caissier(get("/ventes/export/excel").param("vendeur", "" + awa.getId()))).andReturn().getResponse())) {
            assertThat(lignesDeDonnees(classeur.getSheetAt(0))).hasSize(3);
        }
    }

    // ----- Isolation -----

    @Test
    void uneAutreBoutiqueNeVoitQueSesVentesEtJamaisCellesDeLAutre() throws Exception {
        try (Workbook classeur = classeur(mvc.perform(avec(get("/ventes/export/excel"), GERANT_B, "ADMIN")).andReturn().getResponse())) {
            Sheet f = classeur.getSheetAt(0);
            assertThat(lignesDeDonnees(f)).extracting(r -> r.getCell(0).getStringCellValue()).containsExactly("FV-000777");
            assertThat(f.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Autre B");
            assertThat(texteDeLaFeuille(f)).doesNotContain("Étoile", "FV-000001", "Élève", "Awa");
        }
        // Un vendeur d'une autre boutique est refusé (400), sans révéler s'il existe.
        mvc.perform(avec(get("/ventes/export/excel").param("vendeur", "" + awa.getId()), GERANT_B, "ADMIN")).andExpect(status().isBadRequest());
    }

    // ----- Volume -----

    @Test
    void auDelaDeLaLimiteLUtilisateurRevientALaListeAvecUnMessageClairEtSesFiltres() throws Exception {
        // Limite de ce test : 3 lignes. Une quatrième vente de la boutique A dépasse.
        vente(boutiqueA, awa, null, "FV-000004", LocalDateTime.of(2026, 11, 4, 9, 0), "1000", "1000", null, ligne(riz, 1, "1000", "500"));
        for (String format : new String[]{"excel", "pdf"}) {
            MvcResult resultat = mvc.perform(gerant(get("/ventes/export/" + format).param("du", "2026-01-01"))).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(resultat.getResponse().getRedirectedUrl()).isEqualTo("/ventes?du=2026-01-01");
            assertThat(resultat.getFlashMap().get("error").toString()).contains("dépasse 3 lignes", "Réduisez la période");
            assertThat(resultat.getResponse().getContentType()).isNotEqualTo("application/pdf");
        }
        // La page de la liste affiche le message.
        String page = mvc.perform(gerant(get("/ventes")).flashAttr("error", "Cet export dépasse 3 lignes. Réduisez la période ou ajoutez un filtre, puis recommencez."))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Cet export dépasse 3 lignes");
        // Un filtre qui ramène la liste sous la limite exporte normalement.
        mvc.perform(gerant(get("/ventes/export/excel").param("du", "2026-11-01"))).andExpect(status().isOk());
    }

    // ----- Écran, sécurité et paramètres -----

    @Test
    void lesBoutonsExcelEtPdfSontSurLaListePourLesDeuxRolesAvecLesFiltres() throws Exception {
        for (MockHttpServletRequestBuilder requete : List.of(gerant(get("/ventes").param("filtre", "echues").param("du", "2026-10-01")),
                caissier(get("/ventes").param("filtre", "echues").param("du", "2026-10-01")))) {
            String page = mvc.perform(requete).andReturn().getResponse().getContentAsString();
            assertThat(page).contains("data-export-liste=\"excel\"", "data-export-liste=\"pdf\"", "href=\"/ventes/export/excel?filtre=echues&amp;du=2026-10-01\"",
                    "href=\"/ventes/export/pdf?filtre=echues&amp;du=2026-10-01\"", "js/export-liste.js", "id=\"filtreDu\"", "id=\"filtreAu\"");
        }
    }

    @Test
    void unePeriodeInvalideSurLEcranEstIgnoreeAvecUnAvertissementEtRefuseeParLExport() throws Exception {
        for (String[] invalide : new String[][]{{"abc", ""}, {"2026-11-30", "2026-11-01"}, {"2026-02-30", ""}, {"1999-01-01", ""}}) {
            MvcResult ecran = mvc.perform(gerant(get("/ventes").param("du", invalide[0]).param("au", invalide[1]))).andExpect(status().isOk()).andReturn();
            assertThat(ecran.getResponse().getContentAsString()).contains("La période demandée est invalide");
            mvc.perform(gerant(get("/ventes/export/excel").param("du", invalide[0]).param("au", invalide[1]))).andExpect(status().isBadRequest());
            mvc.perform(gerant(get("/ventes/export/pdf").param("du", invalide[0]).param("au", invalide[1]))).andExpect(status().isBadRequest());
        }
        mvc.perform(gerant(get("/ventes/export/excel").param("du", "2026-10-01", "2026-10-02"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/ventes/export/excel").param("vendeur", "abc"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/ventes/export/excel").param("vendeur", "1", "2"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/ventes/export/excel").param("q", "a", "b"))).andExpect(status().isBadRequest());
        mvc.perform(gerant(get("/ventes/export/excel").param("q", "x".repeat(101)))).andExpect(status().isBadRequest());
    }

    @Test
    void lAccesSansConnexionEstRefuseEtLExportEstEnLectureSeule() throws Exception {
        for (String url : List.of("/ventes/export/excel", "/ventes/export/pdf")) {
            MvcResult resultat = mvc.perform(get(url)).andExpect(status().is3xxRedirection()).andReturn();
            assertThat(resultat.getResponse().getRedirectedUrl()).endsWith("/login");
            mvc.perform(post(url).with(user(GERANT_A).roles("ADMIN")).with(csrf())).andExpect(status().isMethodNotAllowed());
        }
        long avant = ventes.count();
        mvc.perform(gerant(get("/ventes/export/excel"))).andExpect(status().isOk());
        mvc.perform(gerant(get("/ventes/export/pdf"))).andExpect(status().isOk());
        assertThat(ventes.count()).isEqualTo(avant);
        assertThat(caisse.count()).isZero();
    }

    /** Génère les PDF et Excel du pilote si EXPORT_DEMO_DIR est défini (inspection visuelle, voir docs/lot-4.md). */
    @Test
    void genererLaDemonstrationDuPilote() throws Exception {
        String dossier = System.getenv("EXPORT_DEMO_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        java.nio.file.Path cible = java.nio.file.Path.of(dossier);
        java.nio.file.Files.createDirectories(cible);
        for (String[] cas : new String[][]{{"gerant", GERANT_A, "ADMIN"}, {"caissier", CAISSIER_A, "CAISSIER"}}) {
            for (String format : new String[]{"pdf", "excel"}) {
                byte[] octets = mvc.perform(avec(get("/ventes/export/" + format), cas[1], cas[2])).andReturn().getResponse().getContentAsByteArray();
                java.nio.file.Files.write(cible.resolve("demo-ventes-" + cas[0] + (format.equals("pdf") ? ".pdf" : ".xlsx")), octets);
            }
        }
    }

    // ----- Outils -----

    @SuppressWarnings("unchecked")
    private List<String> numerosDeLEcran(MockHttpServletRequestBuilder requete) throws Exception {
        MvcResult resultat = mvc.perform(requete).andExpect(status().isOk()).andReturn();
        List<Vente> liste = (List<Vente>) resultat.getModelAndView().getModel().get("ventes");
        return liste.stream().map(Vente::getNumero).toList();
    }

    private static MockHttpServletRequestBuilder avecParametres(MockHttpServletRequestBuilder requete, String[] paires) {
        for (int i = 0; i < paires.length; i += 2) requete.param(paires[i], paires[i + 1]);
        return requete;
    }

    private static Workbook classeur(MockHttpServletResponse reponse) throws Exception {
        assertThat(reponse.getStatus()).isEqualTo(200);
        return new XSSFWorkbook(new ByteArrayInputStream(reponse.getContentAsByteArray()));
    }

    private static String texte(byte[] octets) throws Exception {
        try (PDDocument document = Loader.loadPDF(octets)) {
            return new PDFTextStripper().getText(document).replace(' ', ' ');
        }
    }

    private static List<String> texteDesEntetes(Sheet feuille) {
        List<String> titres = new ArrayList<>();
        for (Cell c : feuille.getRow(enteteIndex(feuille))) titres.add(c.getStringCellValue());
        return titres;
    }

    private static int enteteIndex(Sheet feuille) {
        for (Row ligne : feuille) if (ligne.getCell(0) != null && "Numéro".equals(ligne.getCell(0).getStringCellValue())) return ligne.getRowNum();
        throw new AssertionError("Ligne d'en-têtes introuvable");
    }

    /** Lignes entre les en-têtes et la ligne de totaux (la ligne qui commence par « N vente(s) »). */
    private static List<Row> lignesDeDonnees(Sheet feuille) {
        List<Row> lignes = new ArrayList<>();
        for (int i = enteteIndex(feuille) + 1; i <= feuille.getLastRowNum(); i++) {
            Row ligne = feuille.getRow(i);
            if (ligne == null || ligne.getCell(0) == null) continue;
            String premier = ligne.getCell(0).getStringCellValue();
            if (premier.matches("\\d+ ventes?")) break;
            lignes.add(ligne);
        }
        return lignes;
    }

    /** Total, retours, versé, avance, reste puis, pour le gérant, bénéfice : les colonnes montants de la ligne. */
    private static List<Double> nombres(Row ligne) {
        List<Double> valeurs = new ArrayList<>();
        for (int c = 4; c <= 8; c++) valeurs.add(ligne.getCell(c) == null ? 0d : ligne.getCell(c).getNumericCellValue());
        if (ligne.getLastCellNum() > 11) valeurs.add(ligne.getCell(11).getNumericCellValue());
        return valeurs;
    }

    private static String texteDeLaFeuille(Sheet feuille) {
        StringBuilder sb = new StringBuilder();
        for (Row ligne : feuille) for (Cell c : ligne) sb.append(c.getCellType() == CellType.NUMERIC ? String.valueOf(c.getNumericCellValue()) : c.getStringCellValue()).append('\n');
        return sb.toString();
    }

    private MockHttpServletRequestBuilder gerant(MockHttpServletRequestBuilder requete) { return avec(requete, GERANT_A, "ADMIN"); }
    private MockHttpServletRequestBuilder caissier(MockHttpServletRequestBuilder requete) { return avec(requete, CAISSIER_A, "CAISSIER"); }
    private MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) { return requete.with(user(email).roles(role)); }

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

    private Categorie categorie(Boutique boutique) {
        Categorie c = new Categorie(); c.setNom("Rayon"); c.setBoutique(boutique); return categories.saveAndFlush(c);
    }

    private Produit produit(Boutique boutique, Categorie categorie, String nom) {
        Produit p = new Produit(); p.setBoutique(boutique); p.setCategorie(categorie); p.setNom(nom);
        p.setReference("EXP-" + nom.toUpperCase().replace(' ', '-')); p.setQuantite(100L); p.setPrixDefinis(true);
        p.setPrixAchat(new BigDecimal("100")); p.setPrixVente(new BigDecimal("1000")); p.setCoutMoyen(new BigDecimal("100"));
        return produits.saveAndFlush(p);
    }

    private static DetailVente ligne(Produit produit, int quantite, String prix, String cout) {
        DetailVente l = new DetailVente(); l.setProduit(produit); l.setQuantite(quantite); l.setPrixUnitaire(new BigDecimal(prix));
        l.setCoutUnitaire(cout == null ? null : new BigDecimal(cout)); return l;
    }

    private Vente vente(Boutique boutique, Utilisateur vendeur, Client client, String numero, LocalDateTime date, String total, String verse,
                        LocalDate echeance, DetailVente... lignes) {
        Vente v = new Vente(); v.setBoutique(boutique); v.setVendeur(vendeur); v.setClient(client); v.setNumero(numero); v.setDateVente(date);
        v.setMontantTotal(new BigDecimal(total)); v.setMontantVerse(new BigDecimal(verse)); v.setDateEcheance(echeance);
        for (DetailVente l : lignes) { l.setVente(v); v.getLignes().add(l); }
        return ventes.saveAndFlush(v);
    }

    private void retour(Vente vente, Produit produit, int quantite, String prix, String cout) {
        Retour r = new Retour(); r.setBoutique(vente.getBoutique()); r.setNumero("FR-000001"); r.setType(TypeRetour.VENTE); r.setVente(vente);
        r.setClient(vente.getClient()); r.setDateRetour(LocalDateTime.of(2026, 10, 6, 9, 0)); r.setMontantTotal(new BigDecimal(prix).multiply(BigDecimal.valueOf(quantite)));
        DetailRetour d = new DetailRetour(); d.setRetour(r); d.setProduit(produit); d.setQuantite(quantite); d.setPrixUnitaire(new BigDecimal(prix));
        d.setCoutUnitaire(new BigDecimal(cout)); r.getLignes().add(d);
        retours.saveAndFlush(r);
    }
}
