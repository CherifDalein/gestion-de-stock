package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.SensEmprunt;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter
public class EmpruntForm {
    @NotNull(message = "Choisissez emprunt ou prêt")
    private SensEmprunt sens = SensEmprunt.EMPRUNT;

    @NotNull(message = "Choisissez le partenaire")
    private Long partenaireId;

    @NotBlank(message = "Choisissez le compte")
    @Size(max = 20, message = "Choisissez le compte")
    private String compte = "BANQUE";

    @NotNull(message = "La date est obligatoire")
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) private LocalDate dateEmprunt;

    @NotNull(message = "Le capital est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le capital doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le capital accepte au maximum 15 chiffres et deux décimales")
    private BigDecimal capital;

    @NotNull(message = "Le taux annuel est obligatoire (0 si sans intérêt)")
    @DecimalMin(value = "0", message = "Le taux annuel doit être compris entre 0 et 100")
    @DecimalMax(value = "100", message = "Le taux annuel doit être compris entre 0 et 100")
    @Digits(integer = 3, fraction = 2, message = "Le taux accepte au maximum deux décimales")
    private BigDecimal tauxAnnuel = BigDecimal.ZERO;

    @NotNull(message = "Le nombre d'échéances est obligatoire")
    @Min(value = 1, message = "Le nombre d'échéances doit être compris entre 1 et 120")
    @Max(value = 120, message = "Le nombre d'échéances doit être compris entre 1 et 120")
    private Integer nombreEcheances = 12;

    @NotNull(message = "Choisissez la périodicité")
    private Periodicite periodicite = Periodicite.MENSUELLE;

    @NotNull(message = "La date de la première échéance est obligatoire")
    @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) private LocalDate datePremiereEcheance;

    @DecimalMin(value = "0", message = "Les frais de dossier ne peuvent pas être négatifs")
    @Digits(integer = 15, fraction = 2, message = "Les frais acceptent au maximum 15 chiffres et deux décimales")
    private BigDecimal fraisDossier;

    @Size(max = 255, message = "La note accepte au maximum 255 caractères")
    private String note;

    @NotBlank(message = "Rechargez le formulaire")
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", message = "Rechargez le formulaire")
    private String jetonOperation;

    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.strip(); }
}
