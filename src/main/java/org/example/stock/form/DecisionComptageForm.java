package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Décision du gérant sur un comptage (valider, refuser, annuler) : seul le motif vient du navigateur. */
@Getter
@Setter
public class DecisionComptageForm {
    @NotBlank(message = "Le motif est obligatoire")
    @Size(min = 3, max = 255, message = "Le motif doit comporter de 3 à 255 caractères")
    private String motif;
}
