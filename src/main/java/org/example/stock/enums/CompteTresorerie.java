package org.example.stock.enums;

/** Les soldes sont propres à chaque boutique ; ce choix décrit où l'argent est détenu. */
public enum CompteTresorerie {
    CAISSE("Caisse (espèces)"),
    ORANGE_MONEY("Orange Money"),
    BANQUE("Banque");

    private final String libelle;

    CompteTresorerie(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
