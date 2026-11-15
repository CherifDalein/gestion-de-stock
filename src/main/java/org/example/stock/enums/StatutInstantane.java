package org.example.stock.enums;

/** Instantané d'un exercice : actif tant que la clôture tient, annulé (mais conservé dans l'historique) quand la clôture est annulée. */
public enum StatutInstantane {
    ACTIF("Actif"), ANNULE("Annulé");

    private final String libelle;

    StatutInstantane(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
