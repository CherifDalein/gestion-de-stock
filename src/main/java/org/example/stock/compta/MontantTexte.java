package org.example.stock.compta;

import org.example.stock.export.Textes;

import java.math.BigDecimal;

/** Montant lisible dans les messages des contrôles comptables : « 62 122 600 » (sans décimales, arrondi {@code HALF_UP} ; la monnaie est celle de la boutique). */
final class MontantTexte {

    private MontantTexte() {
    }

    static String de(BigDecimal montant) {
        return montant == null ? "0" : Textes.montant(montant);
    }
}
