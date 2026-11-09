package org.example.stock.export;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

/** Lecture des fichiers Excel et PDF exportés, partagée par les tests d'export des listes (lot 4, tâche B). */
public final class ExportTestOutils {
    private static final Pattern LIGNE_DE_TOTAUX = Pattern.compile("\\d+ [\\p{L}]+");

    private ExportTestOutils() { }

    public static Workbook classeur(MockHttpServletResponse reponse) throws Exception {
        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentType()).isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        return new XSSFWorkbook(new ByteArrayInputStream(reponse.getContentAsByteArray()));
    }

    /** Vérifie l'en-tête %PDF et le type de contenu, puis rend le texte extrait (espaces insécables ramenés à des espaces). */
    public static String pdf(MockHttpServletResponse reponse) throws Exception {
        assertThat(reponse.getStatus()).isEqualTo(200);
        assertThat(reponse.getContentType()).isEqualTo("application/pdf");
        byte[] octets = reponse.getContentAsByteArray();
        assertThat(new String(octets, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("%PDF");
        return texte(octets);
    }

    public static String texte(byte[] octets) throws Exception {
        try (PDDocument document = Loader.loadPDF(octets)) {
            return new PDFTextStripper().getText(document).replace(' ', ' ').replace(' ', ' ');
        }
    }

    /** Vrai si la première page est en paysage (plus large que haute). */
    public static boolean estPaysage(byte[] octets) throws Exception {
        try (PDDocument document = Loader.loadPDF(octets)) {
            var page = document.getPage(0).getMediaBox();
            return page.getWidth() > page.getHeight();
        }
    }

    /** Index de la ligne d'en-têtes : la première dont la première cellule vaut {@code premierTitre}. */
    public static int enteteIndex(Sheet feuille, String premierTitre) {
        for (Row ligne : feuille) if (ligne.getCell(0) != null && premierTitre.equals(ligne.getCell(0).getStringCellValue())) return ligne.getRowNum();
        throw new AssertionError("Ligne d'en-têtes introuvable : " + premierTitre);
    }

    public static List<String> entetes(Sheet feuille, String premierTitre) {
        List<String> titres = new ArrayList<>();
        for (Cell c : feuille.getRow(enteteIndex(feuille, premierTitre))) titres.add(c.getStringCellValue());
        return titres;
    }

    /** Lignes de données : entre les en-têtes et la ligne de totaux (« 3 achats »). */
    public static List<Row> lignes(Sheet feuille, String premierTitre) {
        List<Row> lignes = new ArrayList<>();
        for (int i = enteteIndex(feuille, premierTitre) + 1; i <= feuille.getLastRowNum(); i++) {
            Row ligne = feuille.getRow(i);
            if (ligne == null || ligne.getCell(0) == null) continue;
            if (ligne.getCell(0).getCellType() == CellType.STRING && LIGNE_DE_TOTAUX.matcher(ligne.getCell(0).getStringCellValue()).matches()) break;
            lignes.add(ligne);
        }
        return lignes;
    }

    /** La ligne de totaux, juste après la dernière ligne de données (ou après les en-têtes quand la liste est vide). */
    public static Row totaux(Sheet feuille, String premierTitre) {
        List<Row> donnees = lignes(feuille, premierTitre);
        int index = donnees.isEmpty() ? enteteIndex(feuille, premierTitre) + 1 : donnees.getLast().getRowNum() + 1;
        return feuille.getRow(index);
    }

    public static List<String> premieresCellules(List<Row> lignes) {
        return lignes.stream().map(r -> r.getCell(0).getStringCellValue()).toList();
    }

    /** Valeur numérique de la cellule, ou null si elle est vide. */
    public static Double nombre(Row ligne, int colonne) {
        Cell c = ligne.getCell(colonne);
        if (c == null || c.getCellType() == CellType.BLANK) return null;
        assertThat(c.getCellType()).as("cellule numérique " + colonne).isEqualTo(CellType.NUMERIC);
        return c.getNumericCellValue();
    }

    public static String chaine(Row ligne, int colonne) {
        Cell c = ligne.getCell(colonne);
        return c == null || c.getCellType() == CellType.BLANK ? null : c.getStringCellValue();
    }

    public static String texteDeLaFeuille(Sheet feuille) {
        StringBuilder sb = new StringBuilder();
        for (Row ligne : feuille) for (Cell c : ligne) {
            sb.append(switch (c.getCellType()) {
                case NUMERIC -> DateUtil.isCellDateFormatted(c) ? c.getLocalDateTimeCellValue().toString() : String.valueOf(c.getNumericCellValue());
                case STRING -> c.getStringCellValue();
                default -> "";
            }).append('\n');
        }
        return sb.toString().replace('\u00a0', ' ').replace('\u202f', ' ');
    }

    public static void aucuneFormule(Sheet feuille) {
        for (Row ligne : feuille) for (Cell c : ligne) assertThat(c.getCellType()).isNotEqualTo(CellType.FORMULA);
    }

    public static MockHttpServletRequestBuilder avecParametres(MockHttpServletRequestBuilder requete, String... paires) {
        for (int i = 0; i < paires.length; i += 2) requete.param(paires[i], paires[i + 1]);
        return requete;
    }

    public static MockHttpServletRequestBuilder avec(MockHttpServletRequestBuilder requete, String email, String role) {
        return requete.with(user(email).roles(role));
    }
}
