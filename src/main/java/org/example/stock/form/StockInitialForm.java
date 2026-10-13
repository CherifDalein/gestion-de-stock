package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter @Setter
public class StockInitialForm {
    @NotNull @PositiveOrZero private Long version;
    @NotNull(message = "La quantité initiale est obligatoire") @Positive(message = "La quantité doit être supérieure à zéro") private Long quantite;
    @NotNull(message = "Le coût unitaire est obligatoire") @PositiveOrZero(message = "Le coût ne peut pas être négatif")
    @Digits(integer = 15, fraction = 2, message = "Le coût accepte au maximum deux décimales") private BigDecimal coutUnitaire;
    @NotNull(message = "Le prix de vente est obligatoire") @PositiveOrZero(message = "Le prix de vente ne peut pas être négatif")
    @Digits(integer = 15, fraction = 2, message = "Le prix de vente accepte au maximum deux décimales") private BigDecimal prixVente;
    @NotBlank(message = "Précisez l’origine du stock initial") @Size(max = 500) private String motif;
    public void setMotif(String motif) { this.motif = motif == null ? null : motif.strip(); }
}
