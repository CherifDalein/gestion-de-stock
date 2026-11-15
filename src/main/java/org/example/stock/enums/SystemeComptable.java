package org.example.stock.enums;

/** Système comptable retenu par une boutique pour ses états financiers : le système normal (par défaut) ou le système minimal de trésorerie. */
public enum SystemeComptable {
    NORMAL("Système normal", "Compte de résultat, bilan et tableau des flux de trésorerie."),
    MINIMAL("Système minimal de trésorerie", "États de recettes et de dépenses et bilan simplifié, pour une très petite entité.");

    private final String libelle, description;
    SystemeComptable(String libelle, String description) { this.libelle = libelle; this.description = description; }
    public String getLibelle() { return libelle; }
    public String getDescription() { return description; }
}
