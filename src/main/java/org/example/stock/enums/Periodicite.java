package org.example.stock.enums;

import java.time.LocalDate;

/**
 * Périodicité des échéances. {@code periodesParAn} sert à l'intérêt simple d'une période : capital restant dû x taux annuel / periodesParAn.
 * Les dates se calculent depuis la première échéance (et non d'échéance en échéance), pour qu'un 31 ne dérive pas vers le 28.
 */
public enum Periodicite {
    HEBDOMADAIRE("Hebdomadaire", 52),
    MENSUELLE("Mensuelle", 12),
    TRIMESTRIELLE("Trimestrielle", 4);

    private final String libelle;
    private final int periodesParAn;

    Periodicite(String libelle, int periodesParAn) { this.libelle = libelle; this.periodesParAn = periodesParAn; }

    public String getLibelle() { return libelle; }
    public int getPeriodesParAn() { return periodesParAn; }

    /** Date de l'échéance de rang {@code decalage} (0 pour la première). */
    public LocalDate date(LocalDate premiere, int decalage) {
        return switch (this) {
            case HEBDOMADAIRE -> premiere.plusWeeks(decalage);
            case MENSUELLE -> premiere.plusMonths(decalage);
            case TRIMESTRIELLE -> premiere.plusMonths(3L * decalage);
        };
    }
}
