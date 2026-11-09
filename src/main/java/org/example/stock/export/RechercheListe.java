package org.example.stock.export;

import java.math.BigDecimal;
import java.text.Collator;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recherche et tri d'une liste exportée, communs à tous les exports : la barre de recherche de l'écran travaille dans le
 * navigateur (sans accents ni casse) ; le serveur applique la même règle au paramètre {@code q} pour que le fichier
 * contienne exactement les lignes visibles.
 *
 * <p>Une ligne est gardée si <b>chaque mot</b> de la recherche figure dans son texte, accents, casse, « œ » et espaces
 * insécables ignorés.</p>
 */
public final class RechercheListe {
    private static final Pattern MARQUES = Pattern.compile("\\p{M}+");
    private static final Pattern BLOCS = Pattern.compile("\\d+|\\D+");

    private RechercheListe() { }

    /** Vrai si chaque mot de {@code recherche} figure dans {@code texte} (recherche vide : toujours vrai). */
    public static boolean correspond(String recherche, String texte) {
        String cible = simplifier(texte);
        for (String mot : simplifier(recherche).split("\\s+")) {
            if (!mot.isEmpty() && !cible.contains(mot)) return false;
        }
        return true;
    }

    /** Minuscules, sans accents, « œ » devenu « oe », espaces insécables remplacés par des espaces. */
    public static String simplifier(String texte) {
        String sansMarques = MARQUES.matcher(Normalizer.normalize(texte == null ? "" : texte, Normalizer.Form.NFD)).replaceAll("");
        return sansMarques.replace(' ', ' ').replace(' ', ' ').toLowerCase(Locale.ROOT).replace("œ", "oe").replace("æ", "ae");
    }

    /** Montant en chiffres seuls, avec et sans séparateur de milliers : « 1500000 » et « 1 500 000 » retrouvent la ligne. */
    public static String montantRecherchable(BigDecimal montant) {
        BigDecimal valeur = montant == null ? BigDecimal.ZERO : montant;
        return valeur.toPlainString() + " " + Textes.montant(valeur);
    }

    /**
     * Ordre alphabétique français sans tenir compte de la casse ni des accents, les nombres comparés comme des nombres
     * (« A2 » avant « A10 »), comme le tri des listes de l'écran. Les valeurs vides passent en dernier.
     */
    public static Comparator<String> ordreNaturel() {
        Collator collateur = Collator.getInstance(Locale.FRANCE);
        collateur.setStrength(Collator.PRIMARY);
        return (a, b) -> {
            boolean videA = a == null || a.isBlank(), videB = b == null || b.isBlank();
            if (videA || videB) return videA == videB ? 0 : (videA ? 1 : -1);
            Matcher ma = BLOCS.matcher(a.strip()), mb = BLOCS.matcher(b.strip());
            while (ma.find() && mb.find()) {
                String x = ma.group(), y = mb.group();
                int c;
                if (Character.isDigit(x.charAt(0)) && Character.isDigit(y.charAt(0))) {
                    c = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
                } else {
                    c = collateur.compare(x, y);
                }
                if (c != 0) return c;
            }
            return collateur.compare(a.strip(), b.strip());
        };
    }
}
