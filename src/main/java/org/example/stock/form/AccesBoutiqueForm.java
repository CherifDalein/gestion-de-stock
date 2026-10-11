package org.example.stock.form;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccesBoutiqueForm {
    @NotNull(message = "Le statut d'accès est obligatoire")
    private Boolean actif;

    @NotNull(message = "Le choix de l'accès offert est obligatoire")
    private Boolean accesOffert;
}
