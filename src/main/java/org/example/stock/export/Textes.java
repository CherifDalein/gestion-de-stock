package org.example.stock.export;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Pattern;

/** Mises en forme communes des exports (affichage PDF, filtres, noms) et nettoyage du texte. */
public final class Textes {
    public static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    public static final DateTimeFormatter DATE_HEURE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final Pattern INVALIDE_XML = Pattern.compile("[^\\u0009\\u000A\\u000D\\u0020-\\uD7FF\\uE000-\\uFFFD\\x{10000}-\\x{10FFFF}]");

    private Textes() { }

    /** Retire les caractères de contrôle qu'un document XML (donc XHTML) ne peut pas contenir. */
    public static String pourXml(String texte) {
        return texte == null ? "" : INVALIDE_XML.matcher(texte).replaceAll("");
    }

    /** Entier avec espaces insécables entre les milliers : 1 250 000. */
    public static String entier(Number valeur) {
        return format("#,##0", valeur);
    }

    /** Montant GNF comme à l'écran : sans décimales quand elles sont nulles, sinon deux décimales (virgule). */
    public static String montant(Number valeur) {
        BigDecimal b = enDecimal(valeur).setScale(2, RoundingMode.HALF_UP);
        return format(b.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00", b);
    }

    /** Pourcentage donné en pourcents : « 12,5 % » (espace insécable avant le signe). */
    public static String pourcentage(Number valeur) {
        BigDecimal b = enDecimal(valeur).setScale(2, RoundingMode.HALF_UP);
        int decimales = b.stripTrailingZeros().scale();
        return format(decimales <= 0 ? "#,##0" : (decimales == 1 ? "#,##0.0" : "#,##0.00"), b) + " %";
    }

    public static BigDecimal enDecimal(Number valeur) {
        if (valeur instanceof BigDecimal b) return b;
        if (valeur instanceof java.math.BigInteger i) return new BigDecimal(i);
        if (valeur instanceof Double || valeur instanceof Float) return BigDecimal.valueOf(valeur.doubleValue());
        return BigDecimal.valueOf(valeur.longValue());
    }

    private static String format(String motif, Number valeur) {
        DecimalFormatSymbols symboles = DecimalFormatSymbols.getInstance(Locale.FRANCE);
        symboles.setGroupingSeparator(' ');
        symboles.setDecimalSeparator(',');
        symboles.setMinusSign('-');
        DecimalFormat f = new DecimalFormat(motif, symboles);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(valeur);
    }

    public static String date(LocalDate d) { return d == null ? "" : DATE.format(d); }
    public static String dateHeure(LocalDateTime d) { return d == null ? "" : DATE_HEURE.format(d); }
}
