package org.example.stock.service;

import org.example.stock.enums.Periodicite;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Calcul de l'échéancier d'un emprunt ou d'un prêt : capital constant, intérêts simples.
 *
 * <p>Le capital de chaque échéance est {@code capital / n} arrondi vers le bas au centime ; la dernière échéance absorbe le reste.
 * L'intérêt d'une échéance est {@code capital restant dû avant l'échéance x taux annuel / 100 / périodes par an}, arrondi une seule
 * fois au centime (HALF_UP). Les dates se calculent depuis la première échéance.</p>
 */
public final class EcheancierCalcul {
    public static final int ECHEANCES_MAXIMUM = 120;
    public static final BigDecimal TAUX_MAXIMUM = new BigDecimal("100");
    private static final BigDecimal CENTIME = new BigDecimal("0.01");

    private EcheancierCalcul() { }

    /** Une échéance calculée. */
    public record Ligne(int rang, LocalDate date, BigDecimal capital, BigDecimal interet) {
        public BigDecimal total() { return capital.add(interet); }
    }

    /** Échéancier complet à capital constant. */
    public static List<Ligne> generer(BigDecimal capital, BigDecimal tauxAnnuel, int nombre, Periodicite periodicite, LocalDate premiere) {
        verifier(capital, tauxAnnuel, nombre);
        if (periodicite == null || premiere == null) throw new IllegalArgumentException("La périodicité et la date de la première échéance sont obligatoires.");
        if (capital.compareTo(CENTIME.multiply(BigDecimal.valueOf(nombre))) < 0) {
            throw new IllegalArgumentException("Le capital est trop petit pour ce nombre d'échéances.");
        }
        BigDecimal part = capital.divide(BigDecimal.valueOf(nombre), 2, RoundingMode.DOWN);
        List<LocalDate> dates = new ArrayList<>();
        List<BigDecimal> capitaux = new ArrayList<>();
        BigDecimal cumule = BigDecimal.ZERO;
        for (int k = 0; k < nombre; k++) {
            dates.add(periodicite.date(premiere, k));
            BigDecimal montant = k == nombre - 1 ? capital.subtract(cumule) : part;
            capitaux.add(montant);
            cumule = cumule.add(montant);
        }
        return recalculer(capital, tauxAnnuel, periodicite, dates, capitaux);
    }

    /**
     * Échéancier à partir de dates et de capitaux choisis (modification avant tout remboursement) : les intérêts sont recalculés.
     * Les capitaux sont strictement positifs, leur total est le capital emprunté et les dates sont strictement croissantes.
     */
    public static List<Ligne> recalculer(BigDecimal capital, BigDecimal tauxAnnuel, Periodicite periodicite, List<LocalDate> dates, List<BigDecimal> capitaux) {
        if (dates == null || capitaux == null || dates.size() != capitaux.size() || dates.isEmpty()) {
            throw new IllegalArgumentException("Chaque échéance doit avoir une date et un capital.");
        }
        BigDecimal total = BigDecimal.ZERO;
        LocalDate precedente = null;
        for (int k = 0; k < dates.size(); k++) {
            LocalDate date = dates.get(k);
            BigDecimal montant = capitaux.get(k);
            if (date == null || montant == null) throw new IllegalArgumentException("Chaque échéance doit avoir une date et un capital.");
            if (montant.scale() > 2 || montant.signum() <= 0) {
                throw new IllegalArgumentException("Le capital de l'échéance " + (k + 1) + " doit être supérieur à zéro, avec au maximum deux décimales.");
            }
            if (precedente != null && !date.isAfter(precedente)) {
                throw new IllegalArgumentException("Les dates d'échéance doivent se suivre dans l'ordre (échéance " + (k + 1) + ").");
            }
            precedente = date;
            total = total.add(montant);
        }
        if (total.compareTo(capital) != 0) {
            throw new IllegalArgumentException("Le total des capitaux des échéances doit être égal au capital emprunté.");
        }
        List<Ligne> lignes = new ArrayList<>();
        BigDecimal restant = capital;
        for (int k = 0; k < dates.size(); k++) {
            BigDecimal montant = capitaux.get(k).setScale(2, RoundingMode.UNNECESSARY);
            lignes.add(new Ligne(k + 1, dates.get(k), montant, interet(restant, tauxAnnuel, periodicite)));
            restant = restant.subtract(montant);
        }
        return lignes;
    }

    /** Intérêt simple d'une période sur le capital restant dû, arrondi une seule fois au centime. */
    public static BigDecimal interet(BigDecimal restantDu, BigDecimal tauxAnnuel, Periodicite periodicite) {
        return restantDu.multiply(tauxAnnuel).divide(BigDecimal.valueOf(100L * periodicite.getPeriodesParAn()), 2, RoundingMode.HALF_UP);
    }

    private static void verifier(BigDecimal capital, BigDecimal taux, int nombre) {
        if (capital == null || capital.signum() <= 0) throw new IllegalArgumentException("Le capital doit être supérieur à zéro.");
        if (capital.scale() > 2) throw new IllegalArgumentException("Le capital accepte au maximum deux décimales.");
        if (taux == null || taux.signum() < 0 || taux.compareTo(TAUX_MAXIMUM) > 0 || taux.scale() > 2) {
            throw new IllegalArgumentException("Le taux annuel doit être compris entre 0 et 100 %, avec au maximum deux décimales.");
        }
        if (nombre < 1 || nombre > ECHEANCES_MAXIMUM) {
            throw new IllegalArgumentException("Le nombre d'échéances doit être compris entre 1 et " + ECHEANCES_MAXIMUM + ".");
        }
    }
}
