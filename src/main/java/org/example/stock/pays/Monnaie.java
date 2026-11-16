package org.example.stock.pays;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monnaie d'une boutique, fixée par son pays à la création et qui ne change jamais ({@link Pays}).
 *
 * <p>Chaque monnaie porte son code ISO 4217, le symbole affiché à l'écran et dans les documents (« FCFA » pour les deux francs CFA, qui
 * ne sont pas interchangeables mais s'écrivent pareil), son nom au singulier et au pluriel pour les montants en lettres, et ses
 * coupures de billets et de pièces pour le billetage du comptage de caisse. Sources des coupures : {@code docs/pays-monnaie.md}.</p>
 *
 * <p>Affichage : toujours sans décimales (arrondi {@link RoundingMode#HALF_UP}), espace insécable entre les milliers et avant le symbole.
 * Les calculs et la base gardent {@code DECIMAL(17,2)} : seul l'affichage arrondit.</p>
 */
public enum Monnaie {
    GNF("GNF", "GNF", "Guinée", "franc guinéen", "francs guinéens",
            List.of(20_000, 10_000, 5_000, 2_000, 1_000, 500, 100), List.of()),
    XOF("XOF", "FCFA", "UEMOA", "franc CFA", "francs CFA",
            List.of(10_000, 5_000, 2_000, 1_000, 500), List.of(500, 250, 200, 100, 50, 25, 10, 5)),
    // La pièce de 200 est celle de la série 2024 de la BEAC, en circulation depuis avril 2025 (voir docs/pays-monnaie.md).
    XAF("XAF", "FCFA", "CEMAC", "franc CFA", "francs CFA",
            List.of(10_000, 5_000, 2_000, 1_000, 500), List.of(500, 200, 100, 50, 25, 10, 5));

    /** Espace insécable : sépare les milliers et le symbole sans jamais couper une ligne au milieu d'un montant. */
    public static final char ESPACE = ' ';

    private final String code, symbole, zone, nomSingulier, nomPluriel;
    private final List<Integer> billets, pieces, coupures;

    Monnaie(String code, String symbole, String zone, String nomSingulier, String nomPluriel, List<Integer> billets, List<Integer> pieces) {
        this.code = code; this.symbole = symbole; this.zone = zone; this.nomSingulier = nomSingulier; this.nomPluriel = nomPluriel;
        this.billets = List.copyOf(billets); this.pieces = List.copyOf(pieces);
        // Billetage : une ligne par valeur (le 500 existe en billet et en pièce : une seule ligne), de la plus grande à la plus petite.
        List<Integer> toutes = new ArrayList<>(billets);
        for (Integer piece : pieces) if (!toutes.contains(piece)) toutes.add(piece);
        toutes.sort(java.util.Comparator.reverseOrder());
        this.coupures = List.copyOf(toutes);
    }

    /** Code ISO 4217 : GNF, XOF ou XAF. */
    public String getCode() { return code; }
    /** Symbole affiché après les montants : « GNF » ou « FCFA ». */
    public String getSymbole() { return symbole; }
    /** Zone monétaire : Guinée, UEMOA ou CEMAC. */
    public String getZone() { return zone; }
    public String getNomSingulier() { return nomSingulier; }
    public String getNomPluriel() { return nomPluriel; }
    public List<Integer> getBillets() { return billets; }
    public List<Integer> getPieces() { return pieces; }
    /** Valeurs proposées au billetage, de la plus grande à la plus petite, sans doublon. */
    public List<Integer> getCoupures() { return coupures; }

    /** Nom de la monnaie accordé à un nombre entier de francs : singulier pour 0 et 1, pluriel au-delà. */
    public String nom(long unites) { return Math.abs(unites) > 1 ? nomPluriel : nomSingulier; }

    /** Pays qui utilisent cette monnaie, par ordre alphabétique. */
    public List<Pays> pays() { return Pays.de(this); }

    /** Valeur lue dans une base ou un formulaire : le code ISO, sans tenir compte de la casse ; {@code null} si inconnue. */
    public static Monnaie depuisCode(String code) {
        if (code == null) return null;
        String valeur = code.strip();
        for (Monnaie m : values()) if (m.code.equalsIgnoreCase(valeur)) return m;
        return null;
    }

    /** Montant arrondi au franc ({@code HALF_UP}). Un négatif qui arrondit à zéro vaut zéro. */
    public static BigDecimal arrondir(Number valeur) {
        return enDecimal(valeur).setScale(0, RoundingMode.HALF_UP);
    }

    /** « 150 000 » ou « -5 000 » : sans décimales, espace insécable entre les milliers, sans symbole. */
    public static String nombre(Number valeur) {
        if (valeur == null) return "";
        DecimalFormatSymbols symboles = DecimalFormatSymbols.getInstance(Locale.FRANCE);
        symboles.setGroupingSeparator(ESPACE);
        symboles.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0", symboles);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(arrondir(valeur));
    }

    /** « 150 000 GNF » ou « 150 000 FCFA ». */
    public String formater(Number valeur) {
        return valeur == null ? "" : nombre(valeur) + ESPACE + symbole;
    }

    /** « + 5 000 FCFA », « − 5 000 FCFA » ou « 0 FCFA » : le signe est toujours écrit (le zéro n'en a pas). */
    public String formaterAvecSigne(Number valeur) {
        if (valeur == null) return "";
        BigDecimal arrondi = arrondir(valeur);
        if (arrondi.signum() == 0) return formater(BigDecimal.ZERO);
        return (arrondi.signum() > 0 ? "+ " : "− ") + formater(arrondi.abs());
    }

    private static BigDecimal enDecimal(Number valeur) {
        if (valeur instanceof BigDecimal b) return b;
        if (valeur instanceof java.math.BigInteger i) return new BigDecimal(i);
        if (valeur instanceof Double || valeur instanceof Float) return BigDecimal.valueOf(valeur.doubleValue());
        return BigDecimal.valueOf(valeur.longValue());
    }
}
