package org.example.stock.releve;

/**
 * Neutralise les formules d'un fichier importé : un libellé ou une référence qui commence par {@code =}, {@code +}, {@code -}, {@code @},
 * une tabulation ou un retour chariot reçoit une apostrophe en tête. Un classeur ou un CSV rouvert dans un tableur ne l'évalue donc jamais.
 * Les formules d'un classeur ne sont de toute façon jamais évaluées à la lecture (seule la valeur enregistrée est lue).
 */
public final class Neutralisation {
    private Neutralisation() { }

    /** Texte nettoyé : espaces ramenés à un seul, caractères de contrôle retirés, longueur limitée, formule neutralisée ; {@code null} si vide. */
    public static String nettoyer(String texte, int tailleMax) {
        if (texte == null) return null;
        StringBuilder sb = new StringBuilder(texte.length());
        for (int i = 0; i < texte.length(); i++) {
            char c = texte.charAt(i);
            if (c == '\t' || c == '\r' || c == '\n' || c == ' ' || c == ' ') sb.append(' ');
            else if (!Character.isISOControl(c)) sb.append(c);
        }
        String propre = sb.toString().strip().replaceAll(" {2,}", " ");
        if (propre.isEmpty()) return null;
        if (estFormule(texte.stripLeading()) || estFormule(propre)) propre = "'" + propre;
        return propre.length() <= tailleMax ? propre : propre.substring(0, tailleMax - 1) + "…";
    }

    public static boolean estFormule(String texte) {
        if (texte == null || texte.isEmpty()) return false;
        char c = texte.charAt(0);
        return c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r';
    }
}
