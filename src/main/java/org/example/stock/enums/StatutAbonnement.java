package org.example.stock.enums;

public enum StatutAbonnement {
    SUSPENDU("Boutique suspendue"),
    OFFERT("Accès offert"),
    ESSAI("Essai gratuit"),
    ABONNEMENT("Abonnement actif"),
    EXPIRE("Accès expiré"),
    ACCES_EXISTANT("Accès existant");

    private final String libelle;

    StatutAbonnement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
