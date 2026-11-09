package org.example.stock.export;

/** Type d'une colonne d'export : il fixe la valeur attendue dans les cellules et le format affiché (Excel et PDF). */
public enum TypeColonne {
    /** Texte libre (toute valeur est convertie par {@code toString()}). */
    TEXTE,
    /** Nombre entier (Integer, Long, BigInteger… ). */
    ENTIER,
    /** Montant en GNF : {@link java.math.BigDecimal} ou tout nombre ; affiché sans décimales si elles sont nulles. */
    MONTANT,
    /** {@link java.time.LocalDate} (un LocalDateTime est accepté : seule la date est gardée). */
    DATE,
    /** {@link java.time.LocalDateTime}. */
    DATE_HEURE,
    /** Pourcentage exprimé EN POURCENTS : 12,5 pour 12,5 %. */
    POURCENTAGE
}
