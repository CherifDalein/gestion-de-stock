package org.example.stock.releve;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture de la première feuille d'un classeur .xlsx. Les formules ne sont jamais évaluées : seule la valeur enregistrée dans le classeur
 * est lue. Les cellules au format date deviennent des dates ; les nombres gardent leur valeur exacte. Le classeur est lu en mémoire, rien n'est
 * écrit sur le disque.
 */
final class LecteurExcel {
    static {
        // Garde-fou contre un classeur « bombe » : une feuille décompressée ne dépasse pas 40 Mo.
        ZipSecureFile.setMaxEntrySize(40L * 1024 * 1024);
    }

    private LecteurExcel() { }

    static TableauFichier lire(byte[] contenu, int lignesMax) {
        try (OPCPackage paquet = OPCPackage.open(new ByteArrayInputStream(contenu)); XSSFWorkbook classeur = new XSSFWorkbook(paquet)) {
            if (classeur.getNumberOfSheets() == 0) throw new ImportReleveException("Ce classeur ne contient aucune feuille.");
            Sheet feuille = classeur.getSheetAt(0);
            if (feuille.getLastRowNum() > lignesMax) throw new ImportReleveException("Ce fichier a plus de " + lignesMax + " lignes.");
            List<List<Cellule>> lignes = new ArrayList<>();
            int largeur = 0;
            for (int i = 0; i <= feuille.getLastRowNum(); i++) {
                Row ligne = feuille.getRow(i);
                List<Cellule> cellules = new ArrayList<>();
                if (ligne != null) {
                    int fin = Math.max(ligne.getLastCellNum(), 0);
                    if (fin > LecteurFichierReleve.COLONNES_MAX) throw new ImportReleveException("Ce fichier a plus de " + LecteurFichierReleve.COLONNES_MAX + " colonnes.");
                    for (int j = 0; j < fin; j++) cellules.add(lire(ligne.getCell(j)));
                }
                largeur = Math.max(largeur, cellules.size());
                lignes.add(cellules);
            }
            for (List<Cellule> l : lignes) while (l.size() < largeur) l.add(Cellule.VIDE);
            return new TableauFichier(lignes, largeur);
        } catch (ImportReleveException e) {
            throw e;
        } catch (IOException | org.apache.poi.openxml4j.exceptions.InvalidFormatException | RuntimeException e) {
            throw new ImportReleveException("Ce fichier n'est pas un classeur Excel (.xlsx) lisible. Enregistrez-le au format .xlsx ou en CSV.");
        }
    }

    private static Cellule lire(Cell cellule) {
        if (cellule == null) return Cellule.VIDE;
        CellType type = cellule.getCellType() == CellType.FORMULA ? cellule.getCachedFormulaResultType() : cellule.getCellType();
        return switch (type) {
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cellule)) {
                    LocalDate date = cellule.getLocalDateTimeCellValue() == null ? null : cellule.getLocalDateTimeCellValue().toLocalDate();
                    yield date == null ? Cellule.VIDE : new Cellule(date.toString(), null, date);
                }
                BigDecimal nombre = BigDecimal.valueOf(cellule.getNumericCellValue());
                yield new Cellule(nombre.stripTrailingZeros().toPlainString(), nombre, null);
            }
            case STRING -> Cellule.texte(cellule.getStringCellValue());
            case BOOLEAN -> Cellule.texte(cellule.getBooleanCellValue() ? "VRAI" : "FAUX");
            default -> Cellule.VIDE;
        };
    }
}
