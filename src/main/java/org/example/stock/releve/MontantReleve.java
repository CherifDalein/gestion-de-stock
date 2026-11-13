package org.example.stock.releve;

import org.example.stock.enums.SeparateurDecimal;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * Lecture d'un montant de relevé saisi comme un texte : « 1 200 000,50 GNF », « 1.200.000,50 », « (500) », « 500- », « -1 000 FG ».
 * Les espaces (insécables compris), « GNF » et « FG » sont ignorés ; l'autre signe que le séparateur décimal sépare les milliers.
 */
public final class MontantReleve {
    private static final Pattern DEVISE = Pattern.compile("(?i)(GNF|FG)");
    private static final Pattern FORME = Pattern.compile("[0-9]+(\\.[0-9]+)?");
    private static final int CHIFFRES_ENTIERS_MAX = 15;

    private MontantReleve() { }

    /** @return le montant à deux décimales au plus ; {@code null} si le texte est vide ; {@link ImportReleveException} s'il n'est pas un montant */
    public static BigDecimal lire(String brut, SeparateurDecimal separateur) {
        if (brut == null || brut.isBlank()) return null;
        String t = DEVISE.matcher(brut).replaceAll("");
        t = t.replaceAll("[\\s\\u00A0\\u202F\\u2009']", "").replace('−', '-').replace('–', '-');
        if (t.isEmpty()) return null;
        boolean negatif = false;
        if (t.startsWith("(") && t.endsWith(")")) { negatif = true; t = t.substring(1, t.length() - 1); }
        if (t.startsWith("-")) { negatif = true; t = t.substring(1); }
        else if (t.startsWith("+")) t = t.substring(1);
        if (t.endsWith("-")) { negatif = true; t = t.substring(0, t.length() - 1); }
        char decimal = separateur.getSigne(), milliers = decimal == ',' ? '.' : ',';
        t = t.replace(String.valueOf(milliers), "");
        if (t.indexOf(decimal) != t.lastIndexOf(decimal)) throw new ImportReleveException("montant illisible « " + extrait(brut) + " »");
        t = t.replace(decimal, '.');
        if (!FORME.matcher(t).matches()) throw new ImportReleveException("montant illisible « " + extrait(brut) + " »");
        BigDecimal valeur = new BigDecimal(t).stripTrailingZeros();
        if (valeur.scale() > 2) throw new ImportReleveException("montant avec plus de deux décimales « " + extrait(brut) + " »");
        if (valeur.precision() - valeur.scale() > CHIFFRES_ENTIERS_MAX) throw new ImportReleveException("montant trop grand « " + extrait(brut) + " »");
        valeur = valeur.setScale(2, java.math.RoundingMode.UNNECESSARY);
        return negatif ? valeur.negate() : valeur;
    }

    private static String extrait(String brut) { return brut.strip().length() > 30 ? brut.strip().substring(0, 29) + "…" : brut.strip(); }
}
