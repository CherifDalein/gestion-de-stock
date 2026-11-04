package org.example.stock.form;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;

@Getter @Setter
public class TransfertTresorerieForm extends OperationTresorerieForm {
    @NotNull(message = "Choisissez le compte à créditer")
    private CompteTresorerie compteDestination = CompteTresorerie.ORANGE_MONEY;
}
