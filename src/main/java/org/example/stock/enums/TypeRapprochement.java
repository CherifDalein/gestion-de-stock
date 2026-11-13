package org.example.stock.enums;

/** Origine d'un rapprochement : proposition acceptée, pointage manuel, mouvement créé depuis une ligne du relevé, ou annulation exclue avec sa contre-passation. */
public enum TypeRapprochement {
    AUTO("Proposition acceptée"),
    MANUEL("Pointage manuel"),
    ENREGISTREMENT("Enregistré dans l'appli"),
    EXCLUSION("Annulation exclue");

    private final String libelle;

    TypeRapprochement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
