package org.example.stock.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.springframework.format.annotation.DateTimeFormat;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class ReglementAchatForm {
    @NotNull(message = "Choisissez le compte utilisé pour le versement")
    private CompteTresorerie comptePaiement = CompteTresorerie.CAISSE;

    @NotNull(message = "Le montant du versement est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le versement doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le versement accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montant;

    @NotNull(message = "Rechargez le formulaire de règlement")
    @DecimalMin(value = "0", message = "Le montant déjà versé est invalide")
    private BigDecimal montantVerseAttendu;

    /** Échéance du reste à payer ; appliquée seulement s'il reste un montant dû. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateEcheance;
}
