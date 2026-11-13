package org.example.stock.export;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Mises en forme françaises des écrans (bean {@code formatsVue}) : montants avec espaces entre les milliers et virgule décimale, sans décimales quand
 * elles sont nulles, comme dans les exports ; dates {@code jj/mm/aaaa}.
 */
@Component("formatsVue")
public class FormatsVue {
    /** « 1 250 000 » ou « 1 250 000,50 » (valeur absolue ou signée selon la valeur reçue). */
    public String montant(Number valeur) { return valeur == null ? "" : Textes.montant(valeur); }

    /** « 1 250 000 GNF ». */
    public String gnf(Number valeur) { return valeur == null ? "" : Textes.montant(valeur) + " GNF"; }

    /** « + 5 000 GNF », « − 5 000 GNF » ou « 0 GNF » : le signe est toujours écrit. */
    public String signe(BigDecimal valeur) {
        if (valeur == null || valeur.signum() == 0) return "0 GNF";
        return (valeur.signum() > 0 ? "+ " : "− ") + Textes.montant(valeur.abs()) + " GNF";
    }

    public String date(LocalDate d) { return Textes.date(d); }

    public String dateHeure(LocalDateTime d) { return Textes.dateHeure(d); }
}
