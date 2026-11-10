package org.example.stock.enums;

/** Format de papier des reçus d'une boutique : feuille A4 comme la facture, ou ticket de 80 mm pour une imprimante de caisse. */
public enum FormatRecu {
    A4("A4 (comme la facture)"),
    TICKET_80("Ticket 80 mm (imprimante de caisse)");

    private final String libelle;

    FormatRecu(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
