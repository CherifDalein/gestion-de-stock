package org.example.stock.export;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Écrit un {@link TableauExport} en classeur .xlsx.
 *
 * <p>Mise en page : nom de la boutique, titre, filtres, notes et date d'édition en tête ; puis une ligne d'en-têtes en
 * gras, figée, avec filtre automatique ; les données ; enfin la ligne de totaux. Les montants, entiers, dates et
 * pourcentages sont de VRAIS nombres et de vraies dates Excel (calculs et tris possibles), avec le format d'affichage de
 * l'écran : séparateur de milliers, décimales seulement si elles ne sont pas nulles.</p>
 *
 * <p>Sécurité : tout texte est écrit comme cellule de type texte et, s'il commence par {@code = + - @}, une tabulation
 * ou un retour chariot, la cellule reçoit en plus l'attribut « préfixe apostrophe » (le texte reste LITTÉRAL, jamais
 * interprété comme une formule, et s'affiche tel quel). Voir {@link #estDangereux(String)}.</p>
 */
@Service
public class ExportExcelService {
    private static final int LARGEUR_MIN = 8, LARGEUR_MAX = 60;

    public byte[] ecrire(TableauExport tableau, ContexteDocument contexte) {
        try (Workbook classeur = new XSSFWorkbook(); ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Sheet feuille = classeur.createSheet(WorkbookUtil.createSafeSheetName(tableau.getNomFeuille()));
            Styles styles = new Styles(classeur);
            List<Colonne> colonnes = tableau.getColonnes();
            int[] largeurs = new int[colonnes.size()];
            for (int i = 0; i < largeurs.length; i++) largeurs[i] = colonnes.get(i).titre().length() + 3;

            int ligne = 0;
            ligne = texteEnTete(feuille, ligne, contexte.nomBoutique(), styles.boutique, styles);
            ligne = texteEnTete(feuille, ligne, tableau.getTitre(), styles.titre, styles);
            if (!tableau.getFiltres().isEmpty()) ligne = texteEnTete(feuille, ligne, tableau.getFiltres(), styles.normal, styles);
            for (String note : tableau.getNotes()) ligne = texteEnTete(feuille, ligne, note, styles.normal, styles);
            String edition = "Édité le " + contexte.editeLeTexte()
                    + (contexte.editeur() == null || contexte.editeur().isBlank() ? "" : " par " + contexte.editeur())
                    + (tableau.aDesMontants() ? " · Montants en GNF" : "");
            ligne = texteEnTete(feuille, ligne, edition, styles.normal, styles);
            ligne++; // ligne vide

            int ligneEntetes = ligne++;
            Row entetes = feuille.createRow(ligneEntetes);
            for (int c = 0; c < colonnes.size(); c++) {
                Cell cellule = entetes.createCell(c);
                ecrireTexte(cellule, colonnes.get(c).titre(), styles.entete(colonnes.get(c)), styles);
            }

            for (List<Object> valeurs : tableau.getLignes()) {
                ecrireLigne(feuille.createRow(ligne++), colonnes, valeurs, styles, largeurs, false);
            }
            int derniereDonnee = ligne - 1;
            if (tableau.getTotaux() != null) {
                ecrireLigne(feuille.createRow(ligne++), colonnes, tableau.getTotaux(), styles, largeurs, true);
            }

            for (int c = 0; c < largeurs.length; c++) {
                feuille.setColumnWidth(c, Math.min(LARGEUR_MAX, Math.max(LARGEUR_MIN, largeurs[c])) * 256);
            }
            feuille.createFreezePane(0, ligneEntetes + 1);
            if (derniereDonnee >= ligneEntetes) {
                feuille.setAutoFilter(new CellRangeAddress(ligneEntetes, Math.max(derniereDonnee, ligneEntetes), 0, colonnes.size() - 1));
            }
            PrintSetup impression = feuille.getPrintSetup();
            impression.setLandscape(colonnes.size() > 6);
            impression.setFitWidth((short) 1);
            impression.setFitHeight((short) 0);
            feuille.setFitToPage(true);
            feuille.setRepeatingRows(new CellRangeAddress(ligneEntetes, ligneEntetes, -1, -1));

            classeur.write(sortie);
            return sortie.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Écriture du fichier Excel impossible", e);
        }
    }

    private int texteEnTete(Sheet feuille, int ligne, String texte, CellStyle style, Styles styles) {
        ecrireTexte(feuille.createRow(ligne).createCell(0), texte, style, styles);
        return ligne + 1;
    }

    private void ecrireLigne(Row ligne, List<Colonne> colonnes, List<Object> valeurs, Styles styles, int[] largeurs, boolean totaux) {
        for (int c = 0; c < colonnes.size(); c++) {
            Object valeur = valeurs.get(c);
            if (valeur == null) {
                if (totaux) ligne.createCell(c).setCellStyle(styles.totalVide);
                continue;
            }
            Colonne colonne = colonnes.get(c);
            Cell cellule = ligne.createCell(c);
            if (totaux && valeur instanceof CharSequence libelle && colonne.type() != TypeColonne.TEXTE) {
                ecrireTexte(cellule, libelle.toString(), styles.totalTexte, styles); // libellé « Total » dans une colonne numérique
                continue;
            }
            int longueur = ecrireValeur(cellule, colonne, valeur, styles, totaux);
            largeurs[c] = Math.max(largeurs[c], longueur + 3);
        }
    }

    /** Écrit une valeur typée et retourne la longueur d'affichage estimée (pour la largeur de colonne). */
    private int ecrireValeur(Cell cellule, Colonne colonne, Object valeur, Styles styles, boolean totaux) {
        try {
            switch (colonne.type()) {
                case TEXTE -> {
                    String texte = valeur.toString();
                    ecrireTexte(cellule, texte, totaux ? styles.totalTexte : styles.texte, styles);
                    return plusLongueLigne(texte);
                }
                case ENTIER -> {
                    Number n = (Number) valeur;
                    cellule.setCellValue(n.doubleValue());
                    cellule.setCellStyle(styles.nombre(Styles.ENTIER, totaux));
                    return Textes.entier(n).length();
                }
                case MONTANT -> {
                    BigDecimal b = Textes.enDecimal((Number) valeur);
                    boolean entier = b.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().scale() <= 0;
                    cellule.setCellValue(b.doubleValue());
                    cellule.setCellStyle(styles.nombre(entier ? Styles.MONTANT_ENTIER : Styles.MONTANT_DECIMAL, totaux));
                    return Textes.montant(b).length();
                }
                case POURCENTAGE -> {
                    BigDecimal b = Textes.enDecimal((Number) valeur);
                    int decimales = Math.min(2, Math.max(0, b.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().scale()));
                    cellule.setCellValue(b.movePointLeft(2).doubleValue());
                    cellule.setCellStyle(styles.nombre(decimales == 0 ? Styles.POURCENT_0 : (decimales == 1 ? Styles.POURCENT_1 : Styles.POURCENT_2), totaux));
                    return Textes.pourcentage(b).length();
                }
                case DATE -> {
                    LocalDate d = valeur instanceof LocalDateTime t ? t.toLocalDate() : (LocalDate) valeur;
                    cellule.setCellValue(d);
                    cellule.setCellStyle(styles.nombre(Styles.DATE, totaux));
                    return 10;
                }
                case DATE_HEURE -> {
                    cellule.setCellValue((LocalDateTime) valeur);
                    cellule.setCellStyle(styles.nombre(Styles.DATE_HEURE, totaux));
                    return 16;
                }
            }
        } catch (ClassCastException e) {
            throw new IllegalArgumentException("La colonne « " + colonne.titre() + " » (" + colonne.type() + ") ne peut pas recevoir une valeur de type "
                    + valeur.getClass().getSimpleName(), e);
        }
        throw new IllegalStateException("Type de colonne inconnu : " + colonne.type());
    }

    /**
     * Écrit un texte en cellule de type texte. Un texte à risque (formule potentielle) reçoit le style « préfixe
     * apostrophe » : Excel et LibreOffice l'affichent tel quel et ne l'évaluent jamais.
     */
    private void ecrireTexte(Cell cellule, String texte, CellStyle style, Styles styles) {
        String propre = Textes.pourXml(texte);
        if (propre.length() > 32_000) propre = propre.substring(0, 32_000); // limite d'une cellule Excel : 32 767
        cellule.setCellValue(propre);
        cellule.setCellStyle(estDangereux(propre) ? styles.litteral(style) : style);
    }

    /**
     * Vrai si le texte pourrait être pris pour une formule par un tableur : il commence par {@code =}, {@code +},
     * {@code -}, {@code @}, une tabulation ou un retour chariot.
     */
    public static boolean estDangereux(String texte) {
        if (texte == null || texte.isEmpty()) return false;
        char c = texte.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }

    private static int plusLongueLigne(String texte) {
        int max = 0;
        for (String l : texte.split("\n", -1)) max = Math.max(max, l.length());
        return max;
    }

    /** Styles du classeur, créés une seule fois (Excel limite leur nombre à environ 64 000). */
    private static final class Styles {
        static final int ENTIER = 0, MONTANT_ENTIER = 1, MONTANT_DECIMAL = 2, POURCENT_0 = 3, POURCENT_1 = 4, POURCENT_2 = 5, DATE = 6, DATE_HEURE = 7;
        static final String[] FORMATS = {"#,##0", "#,##0", "#,##0.00", "0%", "0.0%", "0.00%", "dd/mm/yyyy", "dd/mm/yyyy hh:mm"};

        private final Workbook classeur;
        final CellStyle boutique, titre, normal, texte, totalTexte, totalVide;
        private final CellStyle[] donnees = new CellStyle[FORMATS.length], totaux = new CellStyle[FORMATS.length];
        private final Map<CellStyle, CellStyle> litteraux = new HashMap<>();
        private final Map<Boolean, CellStyle> entetes = new HashMap<>();

        Styles(Workbook classeur) {
            this.classeur = classeur;
            Font gras = police(true, 11), grosGras = police(true, 14), titreGras = police(true, 12), normalPolice = police(false, 10);
            boutique = style(grosGras, null, false);
            titre = style(titreGras, null, false);
            normal = style(normalPolice, null, false);
            texte = style(normalPolice, null, false);
            totalTexte = style(gras, null, true);
            totalVide = style(gras, null, true);
            for (int i = 0; i < FORMATS.length; i++) {
                donnees[i] = style(normalPolice, FORMATS[i], false);
                totaux[i] = style(gras, FORMATS[i], true);
            }
        }

        CellStyle nombre(int format, boolean total) { return total ? totaux[format] : donnees[format]; }

        CellStyle entete(Colonne colonne) {
            return entetes.computeIfAbsent(colonne.estNumerique(), numerique -> {
                CellStyle s = style(police(true, 10), null, false);
                s.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                s.setBorderBottom(BorderStyle.THIN);
                s.setAlignment(numerique ? HorizontalAlignment.RIGHT : HorizontalAlignment.LEFT);
                s.setVerticalAlignment(VerticalAlignment.CENTER);
                return s;
            });
        }

        /** Variante « texte littéral » d'un style : même présentation, avec le préfixe apostrophe. */
        CellStyle litteral(CellStyle base) {
            return litteraux.computeIfAbsent(base, b -> {
                CellStyle s = classeur.createCellStyle();
                s.cloneStyleFrom(b);
                s.setQuotePrefixed(true);
                return s;
            });
        }

        private Font police(boolean gras, int taille) {
            Font f = classeur.createFont(); f.setBold(gras); f.setFontHeightInPoints((short) taille); return f;
        }

        private CellStyle style(Font police, String format, boolean total) {
            CellStyle s = classeur.createCellStyle();
            s.setFont(police);
            if (format != null) s.setDataFormat(classeur.createDataFormat().getFormat(format));
            if (total) s.setBorderTop(BorderStyle.THIN);
            return s;
        }
    }
}
