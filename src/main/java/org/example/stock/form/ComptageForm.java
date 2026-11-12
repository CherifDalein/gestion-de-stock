package org.example.stock.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Comptage de caisse : le compte, le montant compté (ou le billetage des espèces), un commentaire facultatif et le jeton contre le
 * double envoi. L'auteur, la date et le solde théorique viennent du serveur.
 */
@Getter
@Setter
public class ComptageForm {
    @NotBlank(message = "Choisissez le compte à compter")
    @Size(max = 20, message = "Choisissez le compte à compter")
    private String compte;

    /** Vide quand un billetage est saisi : le total du billetage tient lieu de montant compté. */
    @DecimalMin(value = "0", message = "Le montant compté ne peut pas être négatif")
    @Digits(integer = 15, fraction = 2, message = "Le montant accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montantCompte;

    /** Quantités par coupure, dans l'ordre de {@code Billetage.COUPURES} (20 000 en premier). */
    private List<Integer> quantites = new ArrayList<>(Collections.nCopies(7, (Integer) null));

    @Size(max = 255, message = "Le commentaire comporte au plus 255 caractères")
    private String commentaire;

    @NotBlank(message = "Rechargez le formulaire")
    @Size(max = 36, message = "Rechargez le formulaire")
    private String jeton;
}
