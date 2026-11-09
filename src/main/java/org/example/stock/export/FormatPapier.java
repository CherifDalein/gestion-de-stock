package org.example.stock.export;

/**
 * Format de page d'un document PDF, en millimètres. Trois formats usuels : {@link #A4_PORTRAIT} (par défaut),
 * {@link #A4_PAYSAGE} (tableaux larges) et {@link #TICKET_80MM} (reçu de caisse). Un ticket n'a pas de hauteur
 * naturelle : {@link #ticket(int)} fixe celle des pages (le contenu qui dépasse passe à la page suivante).
 *
 * @param largeurMm largeur de la page
 * @param hauteurMm hauteur de la page
 * @param margesCss marges de la page, au format CSS (haut droite bas gauche)
 * @param pied      vrai pour imprimer le pied de page « Page X / Y » ; faux pour un ticket
 */
public record FormatPapier(String nom, int largeurMm, int hauteurMm, String margesCss, boolean pied) {
    public static final FormatPapier A4_PORTRAIT = new FormatPapier("A4 portrait", 210, 297, "14mm 12mm 18mm 12mm", true);
    public static final FormatPapier A4_PAYSAGE = new FormatPapier("A4 paysage", 297, 210, "12mm 12mm 16mm 12mm", true);
    public static final FormatPapier TICKET_80MM = ticket(297);

    /** Ticket de 80 mm de large et de la hauteur de page demandée (entre 100 et 2000 mm). */
    public static FormatPapier ticket(int hauteurMm) {
        if (hauteurMm < 100 || hauteurMm > 2000) throw new IllegalArgumentException("Hauteur de ticket invalide : " + hauteurMm + " mm");
        return new FormatPapier("Ticket 80 mm", 80, hauteurMm, "4mm 3mm 4mm 3mm", false);
    }

    /** Règle CSS {@code size} de la page. */
    public String tailleCss() {
        return largeurMm + "mm " + hauteurMm + "mm";
    }

    public boolean estTicket() {
        return largeurMm < 120;
    }

    public boolean estPaysage() {
        return largeurMm > hauteurMm;
    }
}
