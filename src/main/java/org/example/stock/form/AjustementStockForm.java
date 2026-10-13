package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter
public class AjustementStockForm {
    @NotNull @PositiveOrZero private Long version;
    @NotNull(message = "La quantité comptée est obligatoire") @PositiveOrZero(message = "La quantité ne peut pas être négative") private Long quantite;
    @NotBlank(message = "Le motif de la correction est obligatoire") @Size(max = 500) private String motif;
    public void setMotif(String motif) { this.motif = motif == null ? null : motif.strip(); }
}
