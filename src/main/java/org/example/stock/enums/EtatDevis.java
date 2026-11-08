package org.example.stock.enums;

/**
 * État affiché d'un devis. « Expiré » est calculé (devis en cours dont la validité est dépassée) et n'est jamais stocké :
 * seul {@link StatutDevis} l'est.
 */
public enum EtatDevis {
    EN_COURS("En cours", "en-cours"),
    EXPIRE("Expiré", "expire"),
    TRANSFORME("Transformé", "transforme"),
    ANNULE("Annulé", "annule");

    private final String libelle;
    private final String code;

    EtatDevis(String libelle, String code) {
        this.libelle = libelle;
        this.code = code;
    }

    public String getLibelle() { return libelle; }

    /** Valeur du paramètre {@code ?etat=} de la liste et de l'attribut {@code data-status} des écrans. */
    public String getCode() { return code; }

    /** État correspondant au paramètre de la liste, ou vide pour « tous les devis » (valeur absente ou inconnue). */
    public static EtatDevis depuisCode(String code) {
        if (code == null) return null;
        for (EtatDevis etat : values()) if (etat.code.equals(code)) return etat;
        return null;
    }
}
