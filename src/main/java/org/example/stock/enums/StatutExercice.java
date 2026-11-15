package org.example.stock.enums;

/** État d'un exercice comptable : ouvert (on peut y écrire) ou clôturé (figé, plus aucune opération datée dans ses dates). */
public enum StatutExercice {
    OUVERT("Ouvert"), CLOTURE("Clôturé");

    private final String libelle;

    StatutExercice(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }
}
