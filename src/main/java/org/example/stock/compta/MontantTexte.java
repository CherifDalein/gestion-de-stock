package org.example.stock.compta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Montant lisible dans les messages des contrôles comptables : « 62 122 600,00 ». */
final class MontantTexte {

    private MontantTexte() {
    }

    static String de(BigDecimal montant) {
        if (montant == null) return "0,00";
        DecimalFormatSymbols symboles = new DecimalFormatSymbols(Locale.FRANCE);
        symboles.setGroupingSeparator(' ');
        symboles.setDecimalSeparator(',');
        symboles.setMinusSign('-');
        DecimalFormat format = new DecimalFormat("#,##0.00", symboles);
        return format.format(montant.setScale(2, RoundingMode.HALF_UP));
    }
}
