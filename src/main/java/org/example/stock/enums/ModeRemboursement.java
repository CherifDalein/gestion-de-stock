package org.example.stock.enums;

/** Sort de l'excédent d'un retour de vente : l'argent est rendu au client, ou gardé comme avance (avoir). */
public enum ModeRemboursement {
    ARGENT("Rendre l'argent"),
    AVOIR("Garder en avoir");

    private final String libelle;

    ModeRemboursement(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
