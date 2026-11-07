package org.example.stock.enums;

/** Un article STOCK suit les quantités ; un SERVICE est vendu sans stock. */
public enum NatureProduit {
    STOCK("Article en stock"),
    SERVICE("Service");

    private final String libelle;

    NatureProduit(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
