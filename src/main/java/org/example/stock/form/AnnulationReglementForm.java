package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/** Annulation d'un règlement : seul le motif vient du navigateur. Le mouvement et son document viennent de l'adresse. */
@Getter
@Setter
public class AnnulationReglementForm {
    @NotBlank(message = "Le motif de l'annulation est obligatoire")
    @Size(min = 3, max = 255, message = "Le motif doit comporter de 3 à 255 caractères")
    private String motif;
}
