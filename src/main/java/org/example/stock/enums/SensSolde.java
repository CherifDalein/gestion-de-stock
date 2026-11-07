package org.example.stock.enums;

/**
 * Sens d'un solde de départ saisi sur la fiche d'un client ou d'un fournisseur, du point de vue de la boutique :
 * « il me doit » donne un solde positif, « je lui dois » un solde négatif.
 */
public enum SensSolde {
    IL_ME_DOIT("Il me doit"),
    JE_LUI_DOIS("Je lui dois");

    private final String libelle;

    SensSolde(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
