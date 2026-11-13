package org.example.stock.form;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Motif d'une dévalidation (obligatoire, 3 à 255 caractères ; contrôlé par le service). */
@Getter
@Setter
public class DecisionReleveForm {
    @Size(max = 255, message = "Le motif comporte au plus 255 caractères")
    private String motif;
}
