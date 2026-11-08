package org.example.stock.enums;

/** Un retour de vente est rendu par un client ; un retour d'achat est rendu à un fournisseur. */
public enum TypeRetour {
    VENTE("Retour de vente"),
    ACHAT("Retour d'achat");

    private final String libelle;

    TypeRetour(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
