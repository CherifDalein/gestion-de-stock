package org.example.stock.compta;

/** Journaux de la comptabilité : ventes, achats, trésorerie et opérations diverses. */
public enum Journal {
    VE("Ventes"), AC("Achats"), TR("Trésorerie"), OD("Opérations diverses");

    private final String libelle;

    Journal(String libelle) { this.libelle = libelle; }

    public String getLibelle() { return libelle; }

    /** Code reçu d'un formulaire ou d'une adresse ; {@code null} si ce n'est pas un journal. */
    public static Journal depuisCode(String code) {
        if (code == null) return null;
        for (Journal j : values()) if (j.name().equals(code.strip())) return j;
        return null;
    }
}
