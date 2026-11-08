package org.example.stock.enums;

/**
 * Sort de l'excédent d'un retour. Retour de vente : l'argent est rendu au client, ou gardé comme avance (avoir du client).
 * Retour d'achat : l'argent est rendu par le fournisseur, ou gardé comme avance chez lui (avoir du fournisseur).
 */
public enum ModeRemboursement {
    ARGENT("Rendre l'argent"),
    AVOIR("Garder en avoir");

    private final String libelle;

    ModeRemboursement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
