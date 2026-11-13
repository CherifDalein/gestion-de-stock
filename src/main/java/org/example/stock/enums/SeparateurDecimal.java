package org.example.stock.enums;

/** Séparateur décimal des montants d'un fichier de relevé : l'autre signe, s'il y en a un, sépare les milliers. */
public enum SeparateurDecimal {
    VIRGULE("virgule (1 200,50)", ','),
    POINT("point (1 200.50)", '.');

    private final String libelle;
    private final char signe;

    SeparateurDecimal(String libelle, char signe) { this.libelle = libelle; this.signe = signe; }

    public String getLibelle() { return libelle; }

    public char getSigne() { return signe; }
}
