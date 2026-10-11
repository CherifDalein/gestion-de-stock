package org.example.stock.form;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccesUtilisateurForm {
    @NotNull(message = "Le statut du compte est obligatoire")
    private Boolean actif;
}
