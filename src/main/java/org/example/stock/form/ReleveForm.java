package org.example.stock.form;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;

/** En-tête d'un relevé : compte, période et soldes donnés par la banque ou l'opérateur. L'auteur, le statut et le numéro viennent du serveur. */
@Getter
@Setter
public class ReleveForm {
    private Long compte;

    @NotNull(message = "La date de début du relevé est obligatoire")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateDebut;

    @NotNull(message = "La date de fin du relevé est obligatoire")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateFin;

    @NotNull(message = "Le solde d'ouverture est obligatoire (il peut être négatif ou nul)")
    @Digits(integer = 15, fraction = 2, message = "Le solde accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal soldeOuverture;

    @NotNull(message = "Le solde de clôture est obligatoire (il peut être négatif ou nul)")
    @Digits(integer = 15, fraction = 2, message = "Le solde accepte au maximum 15 chiffres et 2 décimales")
    private BigDecimal soldeCloture;

    @Size(max = 36, message = "Rechargez le formulaire")
    private String jeton;
}
