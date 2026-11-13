package org.example.stock.releve;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cellule lue dans un fichier. {@code texte} est le contenu affiché (jamais une formule évaluée). Une cellule numérique d'un classeur garde sa
 * valeur exacte dans {@code nombre}, une cellule au format date sa date dans {@code date} : le séparateur décimal et le format choisis par le
 * gérant ne concernent que les textes.
 */
public record Cellule(String texte, BigDecimal nombre, LocalDate date) {
    public static final Cellule VIDE = new Cellule("", null, null);

    public static Cellule texte(String texte) { return new Cellule(texte == null ? "" : texte, null, null); }

    public boolean estVide() { return texte.isBlank() && nombre == null && date == null; }
}
