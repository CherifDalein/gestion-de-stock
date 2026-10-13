package org.example.stock.form;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProlongationEssaiForm extends ActionAbonnementForm {
    @NotNull(message = "Le nombre de jours est obligatoire")
    @Min(value = 1, message = "Ajoutez au moins un jour d’essai")
    @Max(value = 3650, message = "La prolongation ne peut pas dépasser 3650 jours")
    private Integer jours;
}
