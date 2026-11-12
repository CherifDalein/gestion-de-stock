package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter @Setter
public class OperationTresorerieForm {
    /** Identifiant du compte à débiter (ou nom d'un type : son compte principal) ; contrôlé côté serveur. */
    @NotBlank(message = "Choisissez le compte à débiter")
    @Size(max = 20, message = "Choisissez le compte à débiter")
    private String compteSource = "ESPECES";

    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le montant doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le montant accepte au maximum 15 chiffres et deux décimales")
    private BigDecimal montant;

    @NotBlank(message = "Le motif est obligatoire")
    @Size(max = 255, message = "Le motif accepte au maximum 255 caractères")
    private String motif;

    @NotBlank(message = "Rechargez le formulaire")
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", message = "Rechargez le formulaire")
    private String jetonOperation;

    public void setMotif(String motif) { this.motif = motif == null ? null : motif.strip(); }
}
