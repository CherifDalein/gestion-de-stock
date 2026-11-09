package org.example.stock.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Socle d'export (lot 4) sans base de données : rendu PDF (police embarquée, accents, pagination, logo, formats), classeur
 * Excel (vrais nombres et dates, en-têtes figés, totaux) et neutralisation des formules.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:socle-export;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class SocleExportTests {
    @Autowired DocumentPdfService pdf;
    @Autowired ExportExcelService excel;

    static ContexteDocument contexte(String logo) {
        return new ContexteDocument("Boutique Étoile d'Œuvres « Kaloum »", "12 avenue de la République, Conakry",
                "Téléphones : 622 00 00 01 · 655 00 00 02", "Email : contact@etoile.example", logo, "Awa Diallo",
                LocalDateTime.of(2026, 11, 9, 14, 5));
    }

    static String logoPng() throws Exception {
        BufferedImage image = new BufferedImage(200, 120, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(21, 128, 61)); g.fillOval(10, 10, 100, 100);
        g.setColor(new Color(250, 204, 21)); g.fillRect(100, 40, 90, 40);
        g.dispose();
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        ImageIO.write(image, "png", sortie);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(sortie.toByteArray());
    }

    static TableauExport tableau(int lignes) {
        TableauExport t = TableauExport.titre("Journal des ventes — œuvres à l’honneur").nomFeuille("Ventes")
                .filtres("Période du 01/11/2026 au 30/11/2026 · Vendeur : Zoé Ça’ñ")
                .note("Prix en € de référence — « fin »")
                .colonnes(Colonne.texte("Numéro"), Colonne.dateHeure("Date"), Colonne.texte("Client"), Colonne.entier("Quantité"),
                        Colonne.montant("Total"), Colonne.pourcentage("Marge"), Colonne.date("Échéance"));
        BigDecimal somme = BigDecimal.ZERO;
        for (int i = 1; i <= lignes; i++) {
            BigDecimal total = new BigDecimal(1_000_000 + i * 12_345L);
            somme = somme.add(total);
            t.ligne("FV-%06d".formatted(i), LocalDateTime.of(2026, 11, 1 + i % 28, 8 + i % 10, i % 60), i % 3 == 0 ? "Élève Ça’ñ œuvre" : "Client de passage",
                    i, total, new BigDecimal("12.5"), i % 2 == 0 ? LocalDate.of(2026, 12, 1) : null);
        }
        return t.totaux("Total", null, null, lignes, somme, null, null);
    }

    static String texte(byte[] contenu) throws Exception {
        try (PDDocument document = Loader.loadPDF(contenu)) {
            return new PDFTextStripper().getText(document).replace(' ', ' ');
        }
    }

    // ----- PDF -----

    @Test
    void lePdfEstUnVraiPdfAvecNomDeBoutiqueAccentsEtPolicesEmbarquees() throws Exception {
        byte[] octets = pdf.rendreTableau(tableau(5), contexte(null), FormatPapier.A4_PAYSAGE);
        assertThat(new String(octets, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String texte = texte(octets);
        assertThat(texte).contains("Boutique Étoile d'Œuvres « Kaloum »", "12 avenue de la République", "622 00 00 01", "contact@etoile.example",
                "Journal des ventes — œuvres à l’honneur", "Période du 01/11/2026 au 30/11/2026", "Zoé Ça’ñ", "€", "Élève Ça’ñ œuvre",
                "FV-000001", "Édité le 09/11/2026 14:05", "par Awa Diallo", "Page 1 / 1", "Montants en GNF");
        assertThat(texte).doesNotContain("RFC-COMPTA");
        // Montants à 7 chiffres avec séparateur de milliers, décimales absentes quand elles sont nulles, pourcentage.
        assertThat(texte).contains("1 012 345", "12,5 %");
        try (PDDocument document = Loader.loadPDF(octets)) {
            Set<String> polices = new HashSet<>();
            for (PDPage page : document.getPages()) {
                for (COSName nom : page.getResources().getFontNames()) {
                    PDFont police = page.getResources().getFont(nom);
                    assertThat(police.isEmbedded()).as("police " + police.getName()).isTrue();
                    polices.add(police.getName());
                }
            }
            assertThat(polices).isNotEmpty().allMatch(n -> n.contains("DejaVu"));
            assertThat(document.getDocumentInformation().getTitle()).contains("Journal des ventes");
        }
    }

    @Test
    void soixanteLignesTiennentSurPlusieursPagesAvecEnTeteRepeteEtNumerotation() throws Exception {
        byte[] octets = pdf.rendreTableau(tableau(60), contexte(null), FormatPapier.A4_PORTRAIT);
        try (PDDocument document = Loader.loadPDF(octets)) {
            int pages = document.getNumberOfPages();
            assertThat(pages).isGreaterThanOrEqualTo(2);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p); stripper.setEndPage(p);
                String page = stripper.getText(document);
                assertThat(page).contains("Page " + p + " / " + pages);
                assertThat(page).contains("Numéro"); // en-tête du tableau répété
            }
            stripper.setStartPage(pages); stripper.setEndPage(pages);
            assertThat(stripper.getText(document)).contains("FV-000060", "Total");
        }
    }

    @Test
    void sansLogoRienNEstAfficheEtAvecLogoUneImageEstEmbarquee() throws Exception {
        try (PDDocument sans = Loader.loadPDF(pdf.rendreTableau(tableau(2), contexte(null), FormatPapier.A4_PORTRAIT));
             PDDocument avec = Loader.loadPDF(pdf.rendreTableau(tableau(2), contexte(logoPng()), FormatPapier.A4_PORTRAIT))) {
            assertThat(sans.getPage(0).getResources().getXObjectNames()).isEmpty();
            assertThat(avec.getPage(0).getResources().getXObjectNames()).hasSize(1);
        }
    }

    @Test
    void leFormatTicketFixeLaLargeurEtSupprimeLePied() throws Exception {
        byte[] octets = pdf.rendreTableau(tableau(2), contexte(null), FormatPapier.TICKET_80MM);
        try (PDDocument document = Loader.loadPDF(octets)) {
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(80f / 25.4f * 72f, org.assertj.core.data.Offset.offset(1f));
            assertThat(texte(octets)).doesNotContain("Page 1 /");
        }
        try (PDDocument paysage = Loader.loadPDF(pdf.rendreTableau(tableau(2), contexte(null), FormatPapier.A4_PAYSAGE))) {
            assertThat(paysage.getPage(0).getMediaBox().getWidth()).isGreaterThan(paysage.getPage(0).getMediaBox().getHeight());
        }
        assertThatThrownBy(() -> FormatPapier.ticket(5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void lesTextesSaisisSontEchappesEtLesCaracteresDeControleNeCassentPas() throws Exception {
        TableauExport t = TableauExport.titre("Clients").filtres("Recherche : « </td><script>alert(1)</script> & \" ' »")
                .colonnes(Colonne.texte("Nom"), Colonne.montant("Solde"))
                .ligne("A\u0001B <b>gras</b> &amp; \"guillemets\" \\ 'x'", new BigDecimal("1500.50"))
                .totaux("Total", new BigDecimal("1500.50"));
        ContexteDocument piege = new ContexteDocument("Chez \"Awa\" \\ \n<i>Fils</i>", null, null, null, null, null, LocalDateTime.of(2026, 1, 2, 3, 4));
        String texte = texte(pdf.rendreTableau(t, piege, FormatPapier.A4_PORTRAIT));
        assertThat(texte).contains("<script>alert(1)</script>", "AB <b>gras</b> &amp; \"guillemets\"", "1 500,50", "Chez \"Awa\"");
    }

    @Test
    void uneCelluleDeMauvaisTypeEstRefuseeAvecUnMessageClair() {
        TableauExport t = TableauExport.titre("X").colonnes(Colonne.montant("Total")).ligne("pas un nombre");
        assertThatThrownBy(() -> pdf.rendreTableau(t, contexte(null), FormatPapier.A4_PORTRAIT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Total");
        assertThatThrownBy(() -> excel.ecrire(t, contexte(null))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Total");
        assertThatThrownBy(() -> TableauExport.titre("X").colonnes(Colonne.texte("A")).ligne("1", "2")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pdf.rendre("../application", java.util.Map.of(), contexte(null), FormatPapier.A4_PORTRAIT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----- Excel -----

    @Test
    void leClasseurContientDeVraisNombresDeVraiesDatesEtLaMiseEnPage() throws Exception {
        byte[] octets = excel.ecrire(tableau(3), contexte(null));
        try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(octets))) {
            Sheet feuille = classeur.getSheet("Ventes");
            assertThat(feuille).isNotNull();
            assertThat(feuille.getRow(0).getCell(0).getStringCellValue()).isEqualTo("Boutique Étoile d'Œuvres « Kaloum »");
            assertThat(feuille.getRow(1).getCell(0).getStringCellValue()).startsWith("Journal des ventes");
            assertThat(feuille.getRow(2).getCell(0).getStringCellValue()).contains("Période du 01/11/2026");
            String lignes = feuille.getRow(4).getCell(0).getStringCellValue() + feuille.getRow(3).getCell(0).getStringCellValue();
            assertThat(lignes).contains("Édité le 09/11/2026 14:05", "par Awa Diallo", "Montants en GNF");
            int entetes = 6;
            Row ligneEntetes = feuille.getRow(entetes);
            assertThat(ligneEntetes.getCell(0).getStringCellValue()).isEqualTo("Numéro");
            assertThat(classeur.getFontAt(ligneEntetes.getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(feuille.getPaneInformation().getHorizontalSplitPosition()).isEqualTo((short) (entetes + 1));
            assertThat(((XSSFSheet) feuille).getCTWorksheet().isSetAutoFilter()).isTrue();
            assertThat(((XSSFSheet) feuille).getCTWorksheet().getAutoFilter().getRef()).isEqualTo("A7:G10");

            Row ligne = feuille.getRow(entetes + 1);
            assertThat(ligne.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(DateUtil.isCellDateFormatted(ligne.getCell(1))).isTrue();
            assertThat(ligne.getCell(1).getLocalDateTimeCellValue()).isEqualTo(LocalDateTime.of(2026, 11, 2, 9, 1));
            assertThat(ligne.getCell(3).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ligne.getCell(4).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(ligne.getCell(4).getNumericCellValue()).isEqualTo(1_012_345d);
            assertThat(ligne.getCell(4).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(ligne.getCell(5).getNumericCellValue()).isEqualTo(0.125);
            assertThat(ligne.getCell(5).getCellStyle().getDataFormatString()).isEqualTo("0.0%");
            assertThat(ligne.getCell(6)).isNull(); // cellule vide : rien n'est écrit
            assertThat(feuille.getRow(entetes + 2).getCell(6).getLocalDateTimeCellValue().toLocalDate()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(DateUtil.isCellDateFormatted(feuille.getRow(entetes + 2).getCell(6))).isTrue();

            Row totaux = feuille.getRow(entetes + 4);
            assertThat(totaux.getCell(0).getStringCellValue()).isEqualTo("Total");
            assertThat(totaux.getCell(4).getNumericCellValue()).isEqualTo(1_012_345d + 1_024_690d + 1_037_035d);
            assertThat(classeur.getFontAt(totaux.getCell(4).getCellStyle().getFontIndex()).getBold()).isTrue();
            assertThat(feuille.getColumnWidth(2)).isGreaterThan(feuille.getColumnWidth(3));
        }
    }

    @Test
    void lesDecimalesNeSontAfficheesQueSiElleNeSontPasNulles() throws Exception {
        TableauExport t = TableauExport.titre("Montants").colonnes(Colonne.montant("M"))
                .ligne(new BigDecimal("1500.00")).ligne(new BigDecimal("1500.50")).ligne((Object) null).ligne(2000L);
        try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.ecrire(t, contexte(null))))) {
            Sheet f = classeur.getSheetAt(0);
            int base = 5; // boutique, titre, édition, ligne vide, en-têtes (sans filtres ni notes) : première donnée en ligne 5
            assertThat(f.getRow(base).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0");
            assertThat(f.getRow(base + 1).getCell(0).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
            assertThat(f.getRow(base + 1).getCell(0).getNumericCellValue()).isEqualTo(1500.5);
            assertThat(f.getRow(base + 2).getCell(0)).isNull();
            assertThat(f.getRow(base + 3).getCell(0).getNumericCellValue()).isEqualTo(2000d);
        }
    }

    @Test
    void lesFormulesSontNeutraliseesDansToutesLesCellulesTexte() throws Exception {
        String[] dangereux = {"=1+1", "+33 1 23", "-2+3", "@SUM(A1)", "\t=cmd", "\r=cmd", "=HYPERLINK(\"http://x\",\"clic\")"};
        TableauExport t = TableauExport.titre("=TITRE()").filtres("@filtre").colonnes(Colonne.texte("=entête"), Colonne.texte("Autre"));
        for (String d : dangereux) t.ligne(d, "sûr " + d);
        t.ligne("Awa", "a=b").totaux("-Total", "+1");
        try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.ecrire(t, new ContexteDocument("=Boutique", null, null, null, null, "+Eve", LocalDateTime.now()))))) {
            Sheet f = classeur.getSheetAt(0);
            for (Row ligne : f) {
                for (Cell cellule : ligne) {
                    assertThat(cellule.getCellType()).as("cellule %s%d".formatted((char) ('A' + cellule.getColumnIndex()), ligne.getRowNum() + 1))
                            .isNotEqualTo(CellType.FORMULA);
                    if (cellule.getCellType() == CellType.STRING && ExportExcelService.estDangereux(cellule.getStringCellValue())) {
                        assertThat(cellule.getCellStyle().getQuotePrefixed()).as("préfixe apostrophe : " + cellule.getStringCellValue()).isTrue();
                    }
                }
            }
            // Le texte est conservé tel quel (littéral) ; un texte sûr ne reçoit pas le préfixe.
            Row premiere = f.getRow(6);
            assertThat(premiere.getCell(0).getStringCellValue()).isEqualTo("=1+1");
            assertThat(premiere.getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(premiere.getCell(1).getCellStyle().getQuotePrefixed()).isFalse();
            assertThat(f.getRow(0).getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(f.getRow(5).getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
            assertThat(f.getRow(6 + dangereux.length).getCell(1).getCellStyle().getQuotePrefixed()).isFalse();
            assertThat(f.getRow(6 + dangereux.length + 1).getCell(0).getCellStyle().getQuotePrefixed()).isTrue();
        }
        assertThat(ExportExcelService.estDangereux("=a")).isTrue();
        assertThat(ExportExcelService.estDangereux("Awa")).isFalse();
        assertThat(ExportExcelService.estDangereux("")).isFalse();
        assertThat(ExportExcelService.estDangereux(null)).isFalse();
    }

    @Test
    void leNomDeFeuilleInvalideEstCorrige() throws Exception {
        TableauExport t = TableauExport.titre("Ventes/2026 : [liste] ? * très long titre qui dépasse trente et un caractères").colonnes(Colonne.texte("A"));
        try (Workbook classeur = new XSSFWorkbook(new ByteArrayInputStream(excel.ecrire(t, contexte(null))))) {
            String nom = classeur.getSheetName(0);
            assertThat(nom).hasSizeLessThanOrEqualTo(31).doesNotContain("/", ":", "[", "]", "?", "*");
        }
    }

    // ----- Téléchargement -----

    @Test
    void lesNomsDeFichierSontSurs() {
        assertThat(TelechargementExport.nomFichier("ventes", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), "xlsx"))
                .isEqualTo("ventes_2026-11-01_2026-11-30.xlsx");
        assertThat(TelechargementExport.nomFichier("Journal des ventes — Œuvres", LocalDate.of(2026, 11, 9), null, "pdf"))
                .isEqualTo("Journal_des_ventes_OEuvres_2026-11-09.pdf");
        assertThat(TelechargementExport.nomSur("../../etc/passwd\r\nX-Evil: 1")).doesNotContain("/", "\r", "\n", " ", ":");
        assertThat(TelechargementExport.nomSur("   ")).isEqualTo("export");
        assertThat(TelechargementExport.nomSur("é\"';")).matches("[A-Za-z0-9._-]+");
        var reponse = TelechargementExport.pdf("a.pdf", new byte[]{1, 2});
        assertThat(reponse.getHeaders().getFirst("Content-Disposition")).isEqualTo("attachment; filename=\"a.pdf\"");
        assertThat(reponse.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    // ----- Démonstration (facultative) -----

    /** Génère les fichiers de démonstration si EXPORT_DEMO_DIR est défini (inspection visuelle, voir docs/lot-4.md). */
    @Test
    void genererLaDemonstration() throws Exception {
        String dossier = System.getenv("EXPORT_DEMO_DIR");
        Assumptions.assumeTrue(dossier != null && !dossier.isBlank());
        Path cible = Path.of(dossier);
        Files.createDirectories(cible);
        ContexteDocument contexte = contexte(logoPng());
        Files.write(cible.resolve("demo-paysage-60-lignes.pdf"), pdf.rendreTableau(tableau(60), contexte, FormatPapier.A4_PAYSAGE));
        Files.write(cible.resolve("demo-portrait-sans-logo.pdf"), pdf.rendreTableau(tableau(30), contexte(null), FormatPapier.A4_PORTRAIT));
        Files.write(cible.resolve("demo-ticket.pdf"), pdf.rendreTableau(tableau(6), contexte, FormatPapier.TICKET_80MM));
        Files.write(cible.resolve("demo.xlsx"), excel.ecrire(tableau(60), contexte));
    }
}
