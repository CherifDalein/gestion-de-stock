package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
public class OuvertureCaisseForm {
    @NotNull(message="Le montant initial est obligatoire") @PositiveOrZero(message="Le montant ne peut pas être négatif")
    @Digits(integer=15,fraction=2,message="Le montant accepte au maximum deux décimales") private BigDecimal montant;
    @NotBlank(message="Le motif est obligatoire") @Size(max=255) private String motif="Argent déjà présent au démarrage de la boutique";
    public void setMotif(String motif){this.motif=motif==null?null:motif.strip();}
}
