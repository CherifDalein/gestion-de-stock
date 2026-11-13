package org.example.stock.enums;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Formats de date acceptés à l'import d'un relevé. L'analyse est stricte : le 31/02 est refusé. */
public enum FormatDateReleve {
    JJ_MM_AAAA("jj/mm/aaaa", "dd/MM/uuuu"),
    AAAA_MM_JJ("aaaa-mm-jj", "uuuu-MM-dd"),
    JJ_TIRET_MM_AAAA("jj-mm-aaaa", "dd-MM-uuuu");

    private final String libelle;
    private final DateTimeFormatter formateur;

    FormatDateReleve(String libelle, String motif) {
        this.libelle = libelle;
        this.formateur = DateTimeFormatter.ofPattern(motif).withResolverStyle(ResolverStyle.STRICT);
    }

    public String getLibelle() { return libelle; }

    /** La date, ou {@code null} si le texte n'a pas ce format. */
    public LocalDate lire(String texte) {
        try { return LocalDate.parse(texte, formateur); } catch (DateTimeParseException e) { return null; }
    }
}
