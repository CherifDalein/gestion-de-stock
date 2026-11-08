package org.example.stock.enums;

/** Un devis en cours peut devenir une vente (une seule) ou être annulé ; les deux issues sont définitives. */
public enum StatutDevis {
    EN_COURS("En cours"),
    TRANSFORME("Transformé en vente"),
    ANNULE("Annulé");

    private final String libelle;

    StatutDevis(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
