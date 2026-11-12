package org.example.stock.enums;

/** Nature du partenaire d'une ligne d'inventaire des créances et dettes. */
public enum TypeLigneInventaire {
    CLIENT("Client"),
    FOURNISSEUR("Fournisseur"),
    FINANCIER("Autre partenaire");

    private final String libelle;

    TypeLigneInventaire(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
