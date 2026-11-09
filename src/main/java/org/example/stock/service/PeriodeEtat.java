package org.example.stock.service;

import org.example.stock.export.Textes;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Période d'un état, du {@code du} au {@code au} inclus, convertie en intervalle semi-ouvert [début, fin) comme celle du
 * tableau de bord : de {@code du} à 0 h inclus au lendemain de {@code au} à 0 h exclu, en heure locale du serveur.
 */
public record PeriodeEtat(LocalDate du, LocalDate au) {
    public static final int ANNEE_MINIMALE = TableauDeBordService.ANNEE_MINIMALE;
    public static final int ANNEE_MAXIMALE = 9998;

    public PeriodeEtat {
        if (du == null || au == null) throw new IllegalArgumentException("La période doit avoir une date de début et une date de fin.");
        if (au.isBefore(du)) throw new IllegalArgumentException("La date de fin précède la date de début.");
        if (du.getYear() < ANNEE_MINIMALE || au.getYear() > ANNEE_MAXIMALE) {
            throw new IllegalArgumentException("Les dates doivent être comprises entre " + ANNEE_MINIMALE + " et " + ANNEE_MAXIMALE + ".");
        }
    }

    /** Le mois du jour donné, du 1er au dernier jour : la période par défaut des états. */
    public static PeriodeEtat moisDe(LocalDate jour) {
        LocalDate premier = jour.withDayOfMonth(1);
        return new PeriodeEtat(premier, premier.plusMonths(1).minusDays(1));
    }

    public LocalDateTime debut() { return du.atStartOfDay(); }

    public LocalDateTime fin() { return au.plusDays(1).atStartOfDay(); }

    /** Phrase de période des états : « Période du 01/11/2026 au 30/11/2026 inclus ». */
    public String texte() { return "Période du " + Textes.date(du) + " au " + Textes.date(au) + " inclus"; }

    public String suffixeFichier() { return du + "_" + au; }
}
