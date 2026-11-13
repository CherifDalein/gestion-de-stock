package org.example.stock.enums;

/** Statut d'un relevé bancaire (étape 3c) : en cours de pointage, ou validé par le gérant (figé). */
public enum StatutReleve {
    BROUILLON("En cours"),
    VALIDE("Validé");

    private final String libelle;

    StatutReleve(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
