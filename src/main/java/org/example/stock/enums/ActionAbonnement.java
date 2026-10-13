package org.example.stock.enums;

public enum ActionAbonnement {
    PROLONGATION_ESSAI("Prolongation de l’essai"),
    ACTIVATION_ABONNEMENT("Activation d’un mois d’abonnement"),
    ACCES_OFFERT("Accès offert accordé"),
    RETRAIT_ACCES_OFFERT("Accès offert retiré"),
    SUSPENSION_BOUTIQUE("Boutique suspendue"),
    REACTIVATION_BOUTIQUE("Boutique réactivée");

    private final String libelle;

    ActionAbonnement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
