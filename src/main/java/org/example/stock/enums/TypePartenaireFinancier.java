package org.example.stock.enums;

/** Famille d'un partenaire financier de la boutique (autre qu'un client ou un fournisseur). */
public enum TypePartenaireFinancier {
    BANQUE("Banque"),
    ASSOCIE("Associé"),
    PERSONNEL("Personnel"),
    DIVERS("Divers");

    private final String libelle;

    TypePartenaireFinancier(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
