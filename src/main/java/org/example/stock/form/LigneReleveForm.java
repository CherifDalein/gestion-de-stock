package org.example.stock.form;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ligne saisie à la main : date, libellé, référence facultative et montant signé (crédit positif, débit négatif). */
@Getter
@Setter
public class LigneReleveForm {
    @NotNull(message = "La date de la ligne est obligatoire")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateOperation;

    @NotBlank(message = "Le libellé de la ligne est obligatoire")
    @Size(max = 255, message = "Le libellé comporte au plus 255 caractères")
    private String libelle;

    @Size(max = 100, message = "La référence comporte au plus 100 caractères")
    private String reference;

    @NotNull(message = "Le montant de la ligne est obligatoire (crédit positif, débit négatif)")
    @Digits(integer = 15, fraction = 2, message = "Le montant accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montant;
}
