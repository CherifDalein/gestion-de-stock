package org.example.stock.enums;

/**
 * Famille d'un compte de trésorerie. Une boutique peut avoir plusieurs comptes de chaque type (par exemple « Orange Money
 * Labé » et « Orange Money Conakry ») ; le type sert aux regroupements (bilan, états, tableau de bord) et à choisir le compte
 * principal proposé par défaut. Les libellés des types sont ceux que le bilan et les exports affichent en en-tête de colonne.
 */
public enum TypeCompteTresorerie {
    ESPECES("Caisse (espèces)", "Caisse"),
    ORANGE_MONEY("Orange Money", "Orange Money"),
    BANQUE("Banque", "Banque");

    private final String libelle;
    private final String nomParDefaut;

    TypeCompteTresorerie(String libelle, String nomParDefaut) {
        this.libelle = libelle;
        this.nomParDefaut = nomParDefaut;
    }

    public String getLibelle() { return libelle; }

    /** Nom du compte de base créé avec toute boutique (et par la migration du lot 3 pour les boutiques existantes). */
    public String getNomParDefaut() { return nomParDefaut; }

    /**
     * Valeur reçue d'un formulaire : le nom du type, ou l'ancien nom {@code CAISSE} des formulaires ouverts avant le lot 3.
     * Retourne {@code null} si ce n'est pas un type.
     */
    public static TypeCompteTresorerie depuisSaisie(String saisie) {
        if (saisie == null) return null;
        String valeur = saisie.strip();
        if (valeur.equals("CAISSE")) return ESPECES;
        for (TypeCompteTresorerie type : values()) if (type.name().equals(valeur)) return type;
        return null;
    }
}
