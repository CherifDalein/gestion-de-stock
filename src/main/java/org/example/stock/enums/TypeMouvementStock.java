package org.example.stock.enums;

public enum TypeMouvementStock {
    INITIAL("Stock initial"), AJUSTEMENT("Ajustement d’inventaire");
    private final String libelle;
    TypeMouvementStock(String libelle) { this.libelle = libelle; }
    public String getLibelle() { return libelle; }
}
