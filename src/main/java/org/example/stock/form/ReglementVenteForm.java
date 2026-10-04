package org.example.stock.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Getter
@Setter
public class ReglementVenteForm {
    @NotNull(message = "Le montant du versement est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le versement doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le versement accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montant;

    @NotNull(message = "Rechargez le formulaire de règlement")
    @DecimalMin(value = "0", message = "Le montant déjà versé est invalide")
    @Digits(integer = 15, fraction = 2, message = "Rechargez le formulaire de règlement")
    private BigDecimal montantVerseAttendu;
}
