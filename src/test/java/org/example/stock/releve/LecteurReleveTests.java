package org.example.stock.releve;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.stock.enums.FormatDateReleve;
import org.example.stock.enums.SeparateurDecimal;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Lecture des fichiers de relevé (CSV et .xlsx) : séparateurs, encodages, formats de date et de montant, formules, limites. */
class LecteurReleveTests {
    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private static AnalyseReleve analyser(String nom, byte[] contenu, Correspondance c) {
        return AnalyseurReleve.analyser(LecteurFichierReleve.lire(nom, contenu), c);
    }

    private static Correspondance signe(int entete, FormatDateReleve f, SeparateurDecimal s) {
        return new Correspondance(entete, 1, 2, 3, 4, null, null, f, s);
    }

    @Test
    void csvPointVirguleUtf8AvecMontantsFrancais() {
        String csv = "Date;Libellé;Réf;Montant\n05/03/2025;Virement reçu éé;R1;1 200 000,50\n06/03/2025;Frais de tenue;R2;-15 000\n";
        AnalyseReleve a = analyser("rel.csv", csv.getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.lignes()).hasSize(2);
        assertThat(a.lignes().get(0).date()).isEqualTo(LocalDate.of(2025, 3, 5));
        assertThat(a.lignes().get(0).libelle()).isEqualTo("Virement reçu éé");
        assertThat(a.lignes().get(0).montant()).isEqualByComparingTo("1200000.50");
        assertThat(a.lignes().get(1).montant()).isEqualByComparingTo("-15000");
        assertThat(a.erreurs()).isEmpty();
    }

    @Test
    void csvVirguleEtTabulationSontDetectes() {
        String virgule = "Date,Libelle,Ref,Montant\n2025-03-05,Depot,A,\"1,000.50\"\n2025-03-06,Retrait,B,-200.00\n";
        AnalyseReleve a = analyser("v.csv", virgule.getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.AAAA_MM_JJ, SeparateurDecimal.POINT));
        assertThat(a.lignes()).extracting(LigneAnalysee::montant).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1000.50"), new BigDecimal("-200"));
        String tab = "Date\tLibelle\tRef\tMontant\n05-03-2025\tDepot\tA\t1000\n";
        AnalyseReleve b = analyser("t.csv", tab.getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.JJ_TIRET_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(b.lignes()).hasSize(1);
        assertThat(b.lignes().get(0).date()).isEqualTo(LocalDate.of(2025, 3, 5));
    }

    @Test
    void encodageWindows1252EstDetecte() {
        String csv = "Date;Libellé;Réf;Montant\n05/03/2025;Règlement fournisseur é à ç;;-5000\n";
        AnalyseReleve a = analyser("w.csv", csv.getBytes(WINDOWS_1252), signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.lignes().get(0).libelle()).isEqualTo("Règlement fournisseur é à ç");
    }

    @Test
    void montantsAvecEspacesInsecablesEtUnites() {
        String csv = "Date;Libellé;Réf;Montant\n05/03/2025;A;;1 250 000 GNF\n05/03/2025;B;;350 000 FG\n05/03/2025;C;;(80 000,00)\n";
        AnalyseReleve a = analyser("m.csv", csv.getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.lignes().get(0).montant()).isEqualByComparingTo("1250000");
        assertThat(a.lignes().get(1).montant()).isEqualByComparingTo("350000");
        assertThat(a.lignes().get(2).valide() ? a.lignes().get(2).montant() : null).satisfiesAnyOf(
                m -> assertThat(m).isNull(), m -> assertThat(m).isEqualByComparingTo("-80000"));
    }

    @Test
    void colonnesDebitEtCreditDonnentUnMontantSigne() {
        String csv = "Date;Libellé;Débit;Crédit\n05/03/2025;Retrait;25 000;\n06/03/2025;Dépôt;;100 000\n";
        Correspondance c = new Correspondance(1, 1, 2, null, null, 3, 4, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE);
        AnalyseReleve a = analyser("d.csv", csv.getBytes(StandardCharsets.UTF_8), c);
        assertThat(a.lignes().get(0).montant()).isEqualByComparingTo("-25000");
        assertThat(a.lignes().get(1).montant()).isEqualByComparingTo("100000");
    }

    @Test
    void enTeteDecaleLignesVidesEtLignesEnErreur() {
        String csv = "Banque X;;;\nCompte 123;;;\n\nDate;Libellé;Réf;Montant\n05/03/2025;Bon;;100\n\n31/02/2025;Date impossible;;100\n07/03/2025;;;100\n08/03/2025;Sans montant;;\n09/03/2025;Montant nul;;0\n";
        AnalyseReleve a = analyser("e.csv", csv.getBytes(StandardCharsets.UTF_8), signe(4, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.valides()).hasSize(1);
        assertThat(a.erreurs()).hasSize(4);
        assertThat(a.erreurs()).allSatisfy(l -> assertThat(l.erreur()).isNotBlank());
        assertThat(a.lignes()).extracting(LigneAnalysee::numeroLigne).first().isEqualTo(5);
    }

    @Test
    void lesFormulesSontNeutraliseesDansLeLibelleEtLaReference() {
        String csv = "Date;Libellé;Réf;Montant\n05/03/2025;=HYPERLINK(1);+1;100\n05/03/2025;-ABC;=1+1;100\n";
        AnalyseReleve a = analyser("f.csv", csv.getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.lignes().get(0).libelle()).startsWith("'=");
        assertThat(a.lignes().get(0).reference()).startsWith("'+");
        assertThat(a.lignes().get(1).libelle()).startsWith("'-");
        assertThat(a.lignes().get(1).reference()).startsWith("'=");
    }

    @Test
    void fichierTropGrosVideOuDuMauvaisTypeEstRefuse() {
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.csv", new byte[LecteurFichierReleve.TAILLE_MAX + 1])).isInstanceOf(ImportReleveException.class).hasMessageContaining("2 Mo");
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.csv", new byte[0])).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.exe", "x;y".getBytes())).isInstanceOf(ImportReleveException.class).hasMessageContaining("Type de fichier");
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.csv", "%PDF-1.4 ...".getBytes())).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.xlsx", "Date;Montant".getBytes())).isInstanceOf(ImportReleveException.class).hasMessageContaining("classeur");
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.xls", "xx".getBytes())).isInstanceOf(ImportReleveException.class).hasMessageContaining(".xls");
        assertThatThrownBy(() -> LecteurFichierReleve.lire("a.csv", new byte[]{'P', 'K', 3, 4, 0})).isInstanceOf(ImportReleveException.class);
    }

    @Test
    void plusDeCinqMilleLignesSontRefusees() {
        StringBuilder sb = new StringBuilder("Date;Libellé;Réf;Montant\n");
        for (int i = 0; i < 5_001; i++) sb.append("05/03/2025;L").append(i).append(";;1\n");
        byte[] contenu = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> analyser("g.csv", contenu, signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE)))
                .isInstanceOf(ImportReleveException.class).hasMessageContaining("5000");
        StringBuilder ok = new StringBuilder("Date;Libellé;Réf;Montant\n");
        for (int i = 0; i < 5_000; i++) ok.append("05/03/2025;L").append(i).append(";;1\n");
        assertThat(analyser("g.csv", ok.toString().getBytes(StandardCharsets.UTF_8), signe(1, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE)).lignes()).hasSize(5_000);
    }

    @Test
    void correspondanceIncoherenteEstRefusee() {
        TableauFichier t = LecteurFichierReleve.lire("a.csv", "Date;Libellé;Montant\n05/03/2025;A;1\n".getBytes(StandardCharsets.UTF_8));
        FormatDateReleve f = FormatDateReleve.JJ_MM_AAAA; SeparateurDecimal s = SeparateurDecimal.VIRGULE;
        assertThatThrownBy(() -> AnalyseurReleve.analyser(t, new Correspondance(1, 1, 2, null, null, null, null, f, s))).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> AnalyseurReleve.analyser(t, new Correspondance(1, 1, 2, null, 3, 3, null, f, s))).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> AnalyseurReleve.analyser(t, new Correspondance(1, 1, 1, null, 3, null, null, f, s))).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> AnalyseurReleve.analyser(t, new Correspondance(1, 1, 2, null, 9, null, null, f, s))).isInstanceOf(ImportReleveException.class);
        assertThatThrownBy(() -> AnalyseurReleve.analyser(t, new Correspondance(1, 1, 2, null, 3, null, null, null, s))).isInstanceOf(ImportReleveException.class);
    }

    @Test
    void laCorrespondanceEstProposeD_apresLesTitres() {
        TableauFichier t = LecteurFichierReleve.lire("a.csv", "Relevé\nDate;Libellé;Débit;Crédit\n05/03/2025;A;1;\n".getBytes(StandardCharsets.UTF_8));
        Correspondance c = AnalyseurReleve.proposer(t);
        assertThat(c).isNotNull();
        assertThat(c.ligneEntete()).isEqualTo(2);
        assertThat(c.colonneDebit()).isEqualTo(3);
        assertThat(c.colonneCredit()).isEqualTo(4);
        assertThat(c.montantSigne()).isFalse();
        assertThat(AnalyseurReleve.proposer(LecteurFichierReleve.lire("b.csv", "a;b\n1;2\n".getBytes()))).isNull();
    }

    // ---- Excel --------------------------------------------------------------------------------------------------

    private static byte[] classeur() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            var feuille = wb.createSheet("Relevé");
            Row titre = feuille.createRow(0);
            titre.createCell(0).setCellValue("Date"); titre.createCell(1).setCellValue("Libellé"); titre.createCell(2).setCellValue("Montant");
            Row r1 = feuille.createRow(1);
            Cell d = r1.createCell(0);
            d.setCellValue(Date.from(LocalDate.of(2025, 3, 5).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant()));
            var style = wb.createCellStyle(); style.setDataFormat(wb.createDataFormat().getFormat("dd/mm/yyyy")); d.setCellStyle(style);
            r1.createCell(1).setCellValue("Virement"); r1.createCell(2).setCellValue(1250000.5);
            Row r2 = feuille.createRow(2);
            r2.createCell(0).setCellValue("06/03/2025"); r2.createCell(1).setCellValue("=SUM(A1:A2)"); r2.createCell(2).setCellValue("-2 500 GNF");
            Row r3 = feuille.createRow(3);
            r3.createCell(0).setCellValue("07/03/2025"); r3.createCell(1).setCellFormula("A1&\"x\""); r3.createCell(2).setCellValue(10);
            wb.write(sortie);
            return sortie.toByteArray();
        }
    }

    @Test
    void classeurExcelAvecDatesNombresTextesEtFormules() throws Exception {
        AnalyseReleve a = analyser("rel.xlsx", classeur(), new Correspondance(1, 1, 2, null, 3, null, null, FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE));
        assertThat(a.lignes()).hasSize(3);
        assertThat(a.lignes().get(0).date()).isEqualTo(LocalDate.of(2025, 3, 5));
        assertThat(a.lignes().get(0).montant()).isEqualByComparingTo("1250000.50");
        assertThat(a.lignes().get(1).date()).isEqualTo(LocalDate.of(2025, 3, 6));
        assertThat(a.lignes().get(1).libelle()).startsWith("'=");
        assertThat(a.lignes().get(1).montant()).isEqualByComparingTo("-2500");
        // Une cellule de formule n'est jamais évaluée : elle ne produit ni erreur ni valeur calculée dangereuse.
        assertThat(a.lignes().get(2).libelle()).doesNotContain("Err");
    }

    @Test
    void uneEmpreinteIgnoreLaCasseEtLesEspacesDuLibelle() {
        String a = Empreinte.calculer(LocalDate.of(2025, 3, 5), new BigDecimal("100.00"), "Virement  REÇU", "R1");
        String b = Empreinte.calculer(LocalDate.of(2025, 3, 5), new BigDecimal("100"), "virement reçu", "r1");
        assertThat(a).isEqualTo(b);
        assertThat(Empreinte.calculer(LocalDate.of(2025, 3, 6), new BigDecimal("100"), "virement reçu", "r1")).isNotEqualTo(a);
    }
}
