package org.example.stock.export;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Mises en forme françaises des écrans (bean {@code formatsVue}) : nombres sans décimales avec espaces insécables entre les milliers, comme
 * dans les exports ; dates {@code jj/mm/aaaa}. Les montants avec monnaie passent par le bean {@code montants} ({@link org.example.stock.pays.FormatMontants}).
 */
@Component("formatsVue")
public class FormatsVue {
    /** « 1 250 000 » : sans décimales, sans monnaie ({@code @montants.format} ajoute le symbole de la boutique). */
    public String montant(Number valeur) { return valeur == null ? "" : Textes.montant(valeur); }

    public String date(LocalDate d) { return Textes.date(d); }

    public String dateHeure(LocalDateTime d) { return Textes.dateHeure(d); }
}
