package org.example.stock.export;

/**
 * Colonne d'un {@link TableauExport}. Les fabriques nomment le type : {@code Colonne.montant("Total")}.
 * Les cellules vides ({@code null}) sont autorisées dans tous les types.
 */
public record Colonne(String titre, TypeColonne type, boolean sansRetour) {
    public Colonne(String titre, TypeColonne type) { this(titre, type, false); }

    public Colonne {
        if (titre == null || titre.isBlank()) throw new IllegalArgumentException("Une colonne doit avoir un titre");
        if (type == null) throw new IllegalArgumentException("Une colonne doit avoir un type");
    }

    public static Colonne texte(String titre) { return new Colonne(titre, TypeColonne.TEXTE); }
    /** Texte court qui ne passe jamais à la ligne dans le PDF : numéro de pièce, statut, code. */
    public static Colonne texteCourt(String titre) { return new Colonne(titre, TypeColonne.TEXTE, true); }
    public static Colonne entier(String titre) { return new Colonne(titre, TypeColonne.ENTIER); }
    public static Colonne montant(String titre) { return new Colonne(titre, TypeColonne.MONTANT); }
    public static Colonne date(String titre) { return new Colonne(titre, TypeColonne.DATE); }
    public static Colonne dateHeure(String titre) { return new Colonne(titre, TypeColonne.DATE_HEURE); }
    public static Colonne pourcentage(String titre) { return new Colonne(titre, TypeColonne.POURCENTAGE); }

    /** Les nombres s'alignent à droite dans le PDF, le reste à gauche. */
    public boolean estNumerique() {
        return type == TypeColonne.ENTIER || type == TypeColonne.MONTANT || type == TypeColonne.POURCENTAGE;
    }
}
