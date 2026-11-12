package org.example.stock.form;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Remboursement d'un emprunt (ou encaissement d'un remboursement de prêt). {@code rangAttendu} et {@code resteAttendu} sont l'état
 * que la page montrait : si l'échéancier a changé entre-temps (un autre remboursement), le serveur refuse (409) au lieu de payer deux fois.
 */
@Getter @Setter
public class RemboursementForm {
    @NotNull(message = "Le montant est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le montant doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le montant accepte au maximum 15 chiffres et deux décimales")
    private BigDecimal montant;

    @NotBlank(message = "Choisissez le compte")
    @Size(max = 20, message = "Choisissez le compte")
    private String compte = "ESPECES";

    private boolean anticipe;

    @NotNull(message = "Rechargez le formulaire")
    private Integer rangAttendu;

    @NotNull(message = "Rechargez le formulaire")
    private BigDecimal resteAttendu;

    @Size(max = 255, message = "La note accepte au maximum 255 caractères")
    private String note;

    @NotBlank(message = "Rechargez le formulaire")
    @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", message = "Rechargez le formulaire")
    private String jetonOperation;

    public void setNote(String note) { this.note = note == null || note.isBlank() ? null : note.strip(); }
}
