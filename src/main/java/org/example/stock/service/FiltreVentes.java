package org.example.stock.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.List;

/**
 * Filtres de la liste des ventes, communs à l'écran (/ventes) et aux exports Excel et PDF : une seule définition pour
 * que le fichier contienne EXACTEMENT les lignes de l'écran.
 *
 * @param echues    seulement les ventes échues non soldées (reste à payer et échéance dépassée)
 * @param vendeurId seulement les ventes de ce compte (déjà validé comme compte de la boutique) ; null pour tous
 * @param du        première date incluse (00:00) ; null sans borne
 * @param au        dernière date incluse (jusqu'à 23:59:59) ; null sans borne
 */
public record FiltreVentes(boolean echues, Long vendeurId, LocalDate du, LocalDate au) {
    public static final int ANNEE_MINIMALE = 2000, ANNEE_MAXIMALE = 9998;

    public static FiltreVentes aucun() { return new FiltreVentes(false, null, null, null); }

    /**
     * Une seule date {@code AAAA-MM-JJ} réelle, de 2000 à 9998. Retourne null si le paramètre est absent ou vide ;
     * lève {@link IllegalArgumentException} s'il est invalide (valeurs multiples, texte, date inexistante).
     */
    public static LocalDate analyserDate(List<String> valeurs) {
        if (valeurs == null || (valeurs.size() == 1 && valeurs.getFirst().isBlank())) return null;
        if (valeurs.size() != 1 || !valeurs.getFirst().strip().matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Date invalide");
        try {
            LocalDate date = LocalDate.parse(valeurs.getFirst().strip());
            if (date.getYear() < ANNEE_MINIMALE || date.getYear() > ANNEE_MAXIMALE) throw new IllegalArgumentException("Année invalide");
            return date;
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Date invalide", e);
        }
    }

    /** Vrai si les deux bornes, quand elles sont présentes toutes les deux, sont dans l'ordre. */
    public static boolean periodeCoherente(LocalDate du, LocalDate au) {
        return du == null || au == null || !au.isBefore(du);
    }
}
