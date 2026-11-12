package org.example.stock.enums;

/**
 * Statut d'un comptage de caisse (étape 3b2). Un écart nul est « Conforme » dès l'enregistrement ; un écart non nul reste « En attente de
 * validation » tant que le gérant ne l'a ni validé (un mouvement d'ajustement est alors passé) ni refusé. Un comptage validé peut être
 * annulé par contre-passation de son mouvement.
 */
public enum StatutComptage {
    CONFORME("Conforme"),
    EN_ATTENTE("En attente de validation"),
    VALIDE("Validé"),
    REFUSE("Refusé"),
    ANNULE("Annulé");

    private final String libelle;

    StatutComptage(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }

    /** Vrai tant que le comptage attend la décision du gérant. */
    public boolean enAttente() { return this == EN_ATTENTE; }
}
