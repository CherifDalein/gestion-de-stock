package org.example.stock.enums;

/** Événement conservé dans l'historique d'un relevé : validation ou dévalidation par le gérant. */
public enum TypeEvenementReleve {
    VALIDATION("Validation"),
    DEVALIDATION("Dévalidation");

    private final String libelle;

    TypeEvenementReleve(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
