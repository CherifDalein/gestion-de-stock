package org.example.stock.service;

import org.example.stock.pays.Monnaie;

import java.math.BigDecimal;
import java.util.List;

/**
 * Coupures proposées au billetage d'un comptage d'espèces, de la plus grande à la plus petite : celles de la monnaie de la boutique
 * ({@link Monnaie#getCoupures()}). Le 500 existe en billet et en pièce dans les deux zones CFA : une seule ligne, car le total est le même.
 * La liste est volontairement complète pour qu'aucun comptage ne soit bloqué ; les coupures non utilisées restent vides. Sources et règles :
 * {@code docs/pays-monnaie.md} (GNF : {@code docs/lot-3.md}).
 */
public final class Billetage {
    /** Quantité maximale par coupure : borne de saisie (évite un total hors limites). */
    public static final int QUANTITE_MAXIMALE = 1_000_000;
    /** Nombre de cases prévues dans le formulaire : au moins autant que les coupures de la monnaie qui en a le plus. */
    public static final int CASES_MAXIMALES = 16;

    private Billetage() { }

    /** Les coupures de la monnaie, de la plus grande à la plus petite. */
    public static List<Integer> coupures(Monnaie monnaie) { return monnaie.getCoupures(); }

    /** Total des quantités saisies par coupure (dans l'ordre de {@link #coupures(Monnaie)}) ; les cases vides comptent zéro. */
    public static BigDecimal total(Monnaie monnaie, List<Integer> quantites) {
        List<Integer> coupures = coupures(monnaie);
        BigDecimal total = BigDecimal.ZERO;
        for (int i = 0; i < coupures.size() && quantites != null && i < quantites.size(); i++) {
            Integer quantite = quantites.get(i);
            if (quantite != null) total = total.add(BigDecimal.valueOf(coupures.get(i)).multiply(BigDecimal.valueOf(quantite)));
        }
        return total;
    }

    /** Vrai si au moins une coupure a une quantité positive. */
    public static boolean saisi(List<Integer> quantites) {
        return quantites != null && quantites.stream().anyMatch(q -> q != null && q > 0);
    }

    /** Contrôle les quantités : aucune case au-delà des coupures de la monnaie, ni quantité négative ou excessive. */
    public static void controler(Monnaie monnaie, List<Integer> quantites) {
        if (quantites == null) return;
        int nombre = coupures(monnaie).size();
        for (int i = 0; i < quantites.size(); i++) {
            Integer quantite = quantites.get(i);
            if (quantite == null) continue;
            if (i >= nombre) throw new IllegalArgumentException("Le billetage comporte trop de coupures.");
            if (quantite < 0 || quantite > QUANTITE_MAXIMALE) {
                throw new IllegalArgumentException("Une quantité du billetage est invalide : de 0 à " + QUANTITE_MAXIMALE + ".");
            }
        }
    }
}
