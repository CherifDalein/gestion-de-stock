package org.example.stock.export;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Écrit un montant en toutes lettres, en français de France (« soixante-dix », « quatre-vingts »), pour les documents
 * officiels : justificatifs de solde, reçus, chèques.
 *
 * <p>Orthographe suivie (graphie traditionnelle, celle de l'exemple « cent vingt-cinq mille francs guinéens ») :</p>
 * <ul>
 *   <li>trait d'union entre dizaines et unités (« vingt-cinq », « quatre-vingt-un », « soixante-dix-sept »), sauf avec « et »
 *       (« vingt et un », « soixante et onze ») ; pas de « et » après quatre-vingt (« quatre-vingt-un », « quatre-vingt-onze ») ;</li>
 *   <li>« cent » et « vingt » (dans quatre-vingts) prennent un « s » quand ils sont multipliés ET terminent le nombre ou précèdent un
 *       nom de million ou de milliard : « deux cents », « quatre-vingts », « deux cents millions » ; ils restent invariables devant
 *       « mille » et devant un autre nombre : « deux cent mille », « quatre-vingt mille », « deux cent un » ;</li>
 *   <li>« mille » est invariable et n'est jamais précédé de « un » : « mille », « mille un », « deux mille » ;</li>
 *   <li>« million » et « milliard » sont des noms : « un million », « deux millions » ; une somme qui se termine par eux
 *       s'écrit « deux millions de francs guinéens ».</li>
 * </ul>
 */
public final class MontantEnLettres {
    private static final String[] UNITES = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] DIZAINES = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante"};
    /** Plus grand nombre écrit : moins de mille milliards (assez pour tout montant en francs guinéens). */
    public static final long MAXIMUM = 999_999_999_999L;

    private MontantEnLettres() { }

    /** Nombre entier en lettres : 0 → « zéro », 80 → « quatre-vingts », 1 250 300 → « un million deux cent cinquante mille trois cents ». */
    public static String enLettres(long n) {
        if (n < 0) return "moins " + enLettres(-n);
        if (n > MAXIMUM) throw new IllegalArgumentException("Nombre trop grand pour être écrit en lettres : " + n);
        if (n == 0) return UNITES[0];
        StringBuilder sb = new StringBuilder();
        long milliards = n / 1_000_000_000L, millions = n / 1_000_000L % 1000, milliers = n / 1000 % 1000, reste = n % 1000;
        if (milliards > 0) ajouter(sb, trois(milliards, true) + " milliard" + (milliards > 1 ? "s" : ""));
        if (millions > 0) ajouter(sb, trois(millions, true) + " million" + (millions > 1 ? "s" : ""));
        if (milliers > 0) ajouter(sb, milliers == 1 ? "mille" : trois(milliers, false) + " mille");
        if (reste > 0) ajouter(sb, trois(reste, true));
        return sb.toString();
    }

    /**
     * Somme d'argent en francs guinéens : « cent vingt-cinq mille francs guinéens », « un franc guinéen », « zéro franc guinéen »,
     * « deux millions de francs guinéens ». Les centimes éventuels (deux décimales) sont ajoutés : « … et cinquante centimes ».
     * Un montant négatif est précédé de « moins ».
     */
    public static String francsGuineens(BigDecimal montant) {
        if (montant == null) throw new IllegalArgumentException("Montant absent");
        BigDecimal arrondi = montant.setScale(2, RoundingMode.HALF_UP);
        boolean negatif = arrondi.signum() < 0;
        BigDecimal absolu = arrondi.abs();
        long francs = absolu.toBigInteger().longValueExact();
        int centimes = absolu.remainder(BigDecimal.ONE).movePointRight(2).intValueExact();
        StringBuilder sb = new StringBuilder();
        if (negatif) sb.append("moins ");
        sb.append(enLettres(francs));
        boolean de = francs >= 1_000_000L && francs % 1_000_000L == 0;
        sb.append(de ? " de franc" : " franc");
        if (francs > 1) sb.append('s');
        sb.append(" guinéen").append(francs > 1 ? "s" : "");
        if (centimes > 0) sb.append(" et ").append(enLettres(centimes)).append(" centime").append(centimes > 1 ? "s" : "");
        return sb.toString();
    }

    /** Même chose avec une majuscule initiale (début de phrase). */
    public static String francsGuineensEnPhrase(BigDecimal montant) {
        String t = francsGuineens(montant);
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static void ajouter(StringBuilder sb, String morceau) {
        if (sb.length() > 0) sb.append(' ');
        sb.append(morceau);
    }

    /** De 1 à 999. {@code accord} : « cent » et « quatre-vingt » prennent leur s s'ils terminent le groupe. */
    private static String trois(long n, boolean accord) {
        int centaines = (int) (n / 100), reste = (int) (n % 100);
        StringBuilder sb = new StringBuilder();
        if (centaines > 0) {
            if (centaines > 1) sb.append(UNITES[centaines]).append(' ');
            sb.append("cent");
            if (centaines > 1 && reste == 0 && accord) sb.append('s');
        }
        if (reste > 0) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(deux(reste, accord));
        }
        return sb.toString();
    }

    /** De 1 à 99. */
    private static String deux(int n, boolean accord) {
        if (n < 20) return UNITES[n];
        int dizaine = n / 10, unite = n % 10;
        if (dizaine <= 6) {
            if (unite == 0) return DIZAINES[dizaine];
            return DIZAINES[dizaine] + (unite == 1 ? " et un" : "-" + UNITES[unite]);
        }
        if (dizaine == 7) return unite == 1 ? "soixante et onze" : "soixante-" + UNITES[10 + unite];
        // 80 à 99 : quatre-vingt(s), jamais de « et »
        if (n == 80) return accord ? "quatre-vingts" : "quatre-vingt";
        return "quatre-vingt-" + UNITES[n - 80 < 10 ? n - 80 : 10 + unite];
    }
}
