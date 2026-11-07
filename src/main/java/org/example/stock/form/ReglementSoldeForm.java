package org.example.stock.form;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Règlement du solde de départ d'un client ou d'un fournisseur ; seuls ces champs sont liés depuis le navigateur. */
@Getter
@Setter
public class ReglementSoldeForm {
    @NotNull(message = "Choisissez le compte de trésorerie du règlement")
    private CompteTresorerie comptePaiement = CompteTresorerie.CAISSE;

    @NotNull(message = "Le montant du règlement est obligatoire")
    @DecimalMin(value = "0", inclusive = false, message = "Le règlement doit être supérieur à zéro")
    @Digits(integer = 15, fraction = 2, message = "Le règlement accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal montant;

    /** Somme des règlements du solde de départ au moment de l'ouverture du formulaire, contrôlée sous verrou. */
    @NotNull(message = "Rechargez le formulaire de règlement")
    @DecimalMin(value = "0", message = "Le montant déjà réglé est invalide")
    @Digits(integer = 15, fraction = 2, message = "Rechargez le formulaire de règlement")
    private BigDecimal reglementAttendu;

    /** Échéance du reste du solde de départ ; appliquée seulement s'il reste un montant dû. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateEcheance;
}
