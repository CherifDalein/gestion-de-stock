package org.example.stock.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coupures du franc guinéen proposées au billetage d'un comptage d'espèces, de la plus grande à la plus petite.
 *
 * <p>Billets en circulation : 20 000, 10 000, 5 000, 2 000, 1 000, 500 et 100 GNF (le 100 est rare). Pièces : 50, 25, 10, 5 et 1 GNF,
 * légales mais très rarement rencontrées. La liste est volontairement complète pour qu'aucun comptage ne soit bloqué ; les coupures
 * non utilisées restent vides. Elle est documentée dans {@code docs/lot-3.md}.</p>
 */
public final class Billetage {
    public static final List<Integer> COUPURES = List.of(20_000, 10_000, 5_000, 2_000, 1_000, 500, 100, 50, 25, 10, 5, 1);
    /** Quantité maximale par coupure : borne de saisie (évite un total hors limites). */
    public static final int QUANTITE_MAXIMALE = 1_000_000;

    private Billetage() { }

    /** Total des quantités saisies par coupure (dans l'ordre de {@link #COUPURES}) ; les cases vides comptent zéro. */
    public static BigDecimal total(List<Integer> quantites) {
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < COUPURES.size() && quantites != null && i < quantites.size(); i++) {
            Integer quantite = quantites.get(i);
            if (quantite != null) total = total.add(BigDecimal.valueOf(COUPURES.get(i)).multiply(BigDecimal.valueOf(quantite)));
        }
        return total;
    }

    /** Vrai si au moins une coupure a une quantité positive. */
    public static boolean saisi(List<Integer> quantites) {
        return quantites != null && quantites.stream().anyMatch(q -> q != null && q > 0);
    }

    /** Contrôle les quantités : pas plus de cases que de coupures, ni négatives ni excessives. */
    public static void controler(List<Integer> quantites) {
        if (quantites == null) return;
        if (quantites.size() > COUPURES.size()) throw new IllegalArgumentException("Le billetage comporte trop de coupures.");
        for (Integer quantite : quantites) {
            if (quantite != null && (quantite < 0 || quantite > QUANTITE_MAXIMALE)) {
                throw new IllegalArgumentException("Une quantité du billetage est invalide : de 0 à " + QUANTITE_MAXIMALE + ".");
            }
        }
    }
}
