package org.example.stock.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ParametresPlateformeForm {
    @NotNull(message = "Indiquez une durée d'essai.")
    @Min(value = 1, message = "La durée d'essai doit être d'au moins 1 jour.")
    @Max(value = 3650, message = "La durée d'essai accepte au maximum 3650 jours.")
    private Integer dureeEssaiJours;
}
