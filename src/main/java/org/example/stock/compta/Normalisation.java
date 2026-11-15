package org.example.stock.compta;

import java.text.Normalizer;
import java.util.Locale;

/** Texte sans accents, en minuscules et en mots séparés par une espace : base des recherches et des mots-clés de dépense. */
public final class Normalisation {
    private Normalisation() {}

    public static String mots(String texte) {
        if (texte == null) return "";
        String sans = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return sans.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }
}
