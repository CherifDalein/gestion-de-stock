package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeOperationTresorerie;

import java.math.BigDecimal;

/** Apport, retrait, produit ou charge hors activité courante. Le compte se désigne par son identifiant (ou le nom d'un type). */
@Getter @Setter
public class OperationFinanciereForm {
    @NotNull(message = "Choisissez la nature de l'opération")
    private TypeOperationTresorerie type;

    /** Obligatoire pour un apport ou un retrait (un associé) ; facultatif pour un produit ou une charge. */
    private Long partenaireId;

    @NotBlank(message = "Choisissez le compte")
    @Size(max = 20, message = "Choisissez le compte")
    private String compte = "ESPECES";

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
