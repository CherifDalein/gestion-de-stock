package org.example.stock.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Règles communes aux montants stockés en DECIMAL(17,2). */
public final class Montants {
    public static final BigDecimal ZERO = new BigDecimal("0.00");
    public static final BigDecimal MAX = new BigDecimal("999999999999999.99");

    private Montants() {}

    public static BigDecimal valider(BigDecimal montant, String libelle) {
        if (montant == null || montant.scale() > 2 || montant.abs().compareTo(MAX) > 0) {
            throw new IllegalArgumentException(libelle + " doit comporter au maximum 15 chiffres entiers et 2 décimales.");
        }
        return montant.setScale(2, RoundingMode.UNNECESSARY);
    }

    public static BigDecimal positifOuNul(BigDecimal montant, String libelle) {
        BigDecimal valeur = valider(montant, libelle);
        if (valeur.signum() < 0) throw new IllegalArgumentException(libelle + " ne peut pas être négatif.");
        return valeur;
    }

    public static BigDecimal ouZero(BigDecimal montant) {
        return montant == null ? ZERO : montant;
    }
}
