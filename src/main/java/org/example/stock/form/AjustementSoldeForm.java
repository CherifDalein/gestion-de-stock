package org.example.stock.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.SensAjustement;

import java.math.BigDecimal;

/** Ajustement du solde de départ : nouveau montant, sens et motif. Le partenaire vient de l'adresse, l'auteur de la session. */
@Getter
@Setter
public class AjustementSoldeForm {
    @NotNull(message = "Le nouveau montant est obligatoire")
    @DecimalMin(value = "0", message = "Le nouveau montant ne peut pas être négatif")
    @Digits(integer = 15, fraction = 2, message = "Le montant accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montant;

    @NotNull(message = "Choisissez le sens du nouveau solde")
    private SensAjustement sens;

    @NotBlank(message = "Le motif de l'ajustement est obligatoire")
    @Size(min = 3, max = 255, message = "Le motif doit comporter de 3 à 255 caractères")
    private String motif;

    /** Jeton de formulaire : un double envoi n'enregistre qu'un ajustement. */
    @NotBlank(message = "Rechargez le formulaire")
    @Size(max = 36, message = "Rechargez le formulaire")
    private String jeton;
}
