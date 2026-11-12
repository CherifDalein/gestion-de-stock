package org.example.stock.enums;

/** Sens d'un financement : la boutique emprunte (elle reçoit puis rembourse) ou elle prête (elle verse puis est remboursée). */
public enum SensEmprunt {
    EMPRUNT("Emprunt", "EM-"),
    PRET("Prêt", "PR-");

    private final String libelle;
    private final String prefixe;

    SensEmprunt(String libelle, String prefixe) { this.libelle = libelle; this.prefixe = prefixe; }

    public String getLibelle() { return libelle; }
    public String getPrefixe() { return prefixe; }
}
