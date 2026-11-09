package org.example.stock.service;

import org.example.stock.export.FormatPapier;
import org.example.stock.export.TableauExport;

import java.time.LocalDate;
import java.util.List;

/**
 * Un état du gérant prêt à s'afficher à l'écran et à s'exporter : le MÊME objet alimente la page, le PDF et l'Excel, donc
 * les trois montrent exactement les mêmes chiffres.
 *
 * @param code       identifiant de l'état dans l'adresse (« bilan », « creances »…)
 * @param titre      titre du document
 * @param parametres période ou date de l'état, en une phrase
 * @param notes      remarques générales (limites, définitions)
 * @param sections   tableaux, dans l'ordre ; chacun a son titre et devient une feuille Excel
 * @param format     format de page du PDF
 * @param base       début du nom des fichiers exportés
 * @param du         début de la période (vide pour un état à une date)
 * @param au         fin de la période, ou date de l'état
 */
public record Etat(String code, String titre, String parametres, List<String> notes, List<TableauExport> sections,
                   FormatPapier format, String base, LocalDate du, LocalDate au) {
    public Etat {
        notes = List.copyOf(notes);
        sections = List.copyOf(sections);
    }
}
