package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class TransfertTresorerieForm extends OperationTresorerieForm {
    /** Identifiant du compte à créditer (ou nom d'un type : son compte principal) ; contrôlé côté serveur. */
    @NotBlank(message = "Choisissez le compte à créditer")
    @Size(max = 20, message = "Choisissez le compte à créditer")
    private String compteDestination = "ORANGE_MONEY";
}
