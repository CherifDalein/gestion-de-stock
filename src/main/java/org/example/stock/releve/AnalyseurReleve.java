package org.example.stock.releve;

import org.example.stock.enums.FormatDateReleve;
import org.example.stock.enums.SeparateurDecimal;
import org.example.stock.model.LigneReleve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Applique une {@link Correspondance} à un {@link TableauFichier} : une {@link LigneAnalysee} par ligne non vide, avec la raison de chaque erreur. */
public final class AnalyseurReleve {
    private static final int ANNEE_MIN = 2000, ANNEE_MAX = 2100;

    private AnalyseurReleve() { }

    public static AnalyseReleve analyser(TableauFichier fichier, Correspondance c) {
        c.controler(fichier.colonnes(), fichier.lignes().size());
        List<LigneAnalysee> resultat = new ArrayList<>();
        for (int i = c.ligneEntete(); i < fichier.lignes().size(); i++) {
            if (ligneSansDonnee(fichier, i, c)) continue;
            if (resultat.size() >= LecteurFichierReleve.LIGNES_MAX) {
                throw new ImportReleveException("Ce fichier a plus de " + LecteurFichierReleve.LIGNES_MAX + " lignes de données. Divisez le relevé en plusieurs fichiers.");
            }
            resultat.add(analyserLigne(fichier, i, c));
        }
        if (resultat.isEmpty()) throw new ImportReleveException("Aucune ligne à importer après la ligne d'en-tête choisie.");
        return new AnalyseReleve(resultat);
    }

    private static boolean ligneSansDonnee(TableauFichier f, int ligne, Correspondance c) {
        for (Integer col : new Integer[]{c.colonneDate(), c.colonneLibelle(), c.colonneReference(), c.colonneMontant(), c.colonneDebit(), c.colonneCredit()}) {
            if (col != null && !f.cellule(ligne, col - 1).estVide()) return false;
        }
        return true;
    }

    private static LigneAnalysee analyserLigne(TableauFichier f, int i, Correspondance c) {
        int numero = i + 1;
        try {
            LocalDate date = lireDate(f.cellule(i, c.colonneDate() - 1), c.formatDate());
            String libelle = Neutralisation.nettoyer(f.cellule(i, c.colonneLibelle() - 1).texte(), LigneReleve.TAILLE_LIBELLE);
            if (libelle == null) throw new ImportReleveException("libellé manquant");
            String reference = c.colonneReference() == null ? null
                    : Neutralisation.nettoyer(f.cellule(i, c.colonneReference() - 1).texte(), LigneReleve.TAILLE_REFERENCE);
            BigDecimal montant = c.montantSigne() ? lireMontant(f.cellule(i, c.colonneMontant() - 1), c.separateur())
                    : debitCredit(f.cellule(i, c.colonneDebit() - 1), f.cellule(i, c.colonneCredit() - 1), c.separateur());
            if (montant == null) throw new ImportReleveException("montant manquant");
            if (montant.signum() == 0) throw new ImportReleveException("montant nul");
            return new LigneAnalysee(numero, date, libelle, reference, montant, null, false);
        } catch (ImportReleveException e) {
            return new LigneAnalysee(numero, null, null, null, null, e.getMessage(), false);
        }
    }

    static LocalDate lireDate(Cellule cellule, FormatDateReleve format) {
        LocalDate date = cellule.date();
        if (date == null) {
            String texte = cellule.texte().strip();
            if (texte.isEmpty()) throw new ImportReleveException("date manquante");
            int coupure = texte.indexOf(' ');
            int t = texte.indexOf('T');
            if (t > 0 && (coupure < 0 || t < coupure)) coupure = t;
            String jour = coupure > 0 ? texte.substring(0, coupure) : texte;
            date = format.lire(jour);
            if (date == null) date = FormatDateReleve.AAAA_MM_JJ.lire(jour);
            if (date == null) throw new ImportReleveException("date illisible « " + (texte.length() > 20 ? texte.substring(0, 19) + "…" : texte) + " » (format attendu : " + format.getLibelle() + ")");
        }
        if (date.getYear() < ANNEE_MIN || date.getYear() > ANNEE_MAX) throw new ImportReleveException("date hors limites (" + date + ")");
        return date;
    }

    private static BigDecimal lireMontant(Cellule cellule, SeparateurDecimal separateur) {
        if (cellule.nombre() != null) return verifier(cellule.nombre());
        return MontantReleve.lire(cellule.texte(), separateur);
    }

    private static BigDecimal verifier(BigDecimal nombre) {
        BigDecimal v = nombre.stripTrailingZeros();
        if (v.scale() > 2) throw new ImportReleveException("montant avec plus de deux décimales « " + nombre.toPlainString() + " »");
        if (v.precision() - v.scale() > 15) throw new ImportReleveException("montant trop grand");
        return v.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** Débit en négatif, crédit en positif ; un montant écrit avec son signe dans la colonne ne change pas son sens. */
    private static BigDecimal debitCredit(Cellule debit, Cellule credit, SeparateurDecimal separateur) {
        BigDecimal d = lireMontant(debit, separateur), k = lireMontant(credit, separateur);
        if (d == null && k == null) return null;
        BigDecimal net = (k == null ? BigDecimal.ZERO : k.abs()).subtract(d == null ? BigDecimal.ZERO : d.abs());
        return net.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }

    /** Correspondance proposée d'après les titres de colonnes de la première ligne qui ressemble à un en-tête ; {@code null} si rien ne se détecte. */
    public static Correspondance proposer(TableauFichier f) {
        int limite = Math.min(f.lignes().size(), 25);
        for (int i = 0; i < limite; i++) {
            Integer date = null, libelle = null, reference = null, montant = null, debit = null, credit = null;
            for (int j = 0; j < f.colonnes(); j++) {
                String t = f.cellule(i, j).texte().toLowerCase(Locale.ROOT).strip();
                if (t.isEmpty()) continue;
                if (date == null && t.contains("date")) date = j + 1;
                else if (libelle == null && (t.contains("libell") || t.contains("descr") || t.contains("détail") || t.contains("detail") || t.contains("opération") || t.contains("operation") || t.contains("motif"))) libelle = j + 1;
                else if (reference == null && (t.startsWith("réf") || t.startsWith("ref") || t.contains("numéro") || t.contains("numero"))) reference = j + 1;
                else if (debit == null && (t.contains("débit") || t.contains("debit") || t.contains("retrait") || t.contains("sortie"))) debit = j + 1;
                else if (credit == null && (t.contains("crédit") || t.contains("credit") || t.contains("dépôt") || t.contains("depot") || t.contains("entrée"))) credit = j + 1;
                else if (montant == null && (t.contains("montant") || t.contains("amount"))) montant = j + 1;
            }
            if (date != null && libelle != null && (montant != null || (debit != null && credit != null))) {
                boolean signe = montant != null && !(debit != null && credit != null);
                return new Correspondance(i + 1, date, libelle, reference, signe ? montant : null, signe ? null : debit, signe ? null : credit,
                        FormatDateReleve.JJ_MM_AAAA, SeparateurDecimal.VIRGULE);
            }
        }
        return null;
    }
}
