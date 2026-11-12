package org.example.stock.form;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.SensSolde;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventaire des créances et dettes : la date, une note, le jeton et, par partenaire affiché, la clé « TYPE:identifiant », le solde confirmé,
 * son sens et la raison de l'écart. Les listes sont parallèles (même indice) ; un partenaire dont le solde confirmé est vide est ignoré.
 */
@Getter
@Setter
public class InventaireCreancesForm {
    @NotNull(message = "Choisissez la date de l'inventaire")
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateInventaire;

    @Size(max = 255, message = "La note comporte au plus 255 caractères")
    private String note;

    @NotBlank(message = "Rechargez le formulaire")
    @Size(max = 36, message = "Rechargez le formulaire")
    private String jeton;

    private List<String> cles = new ArrayList<>();
    private List<BigDecimal> montants = new ArrayList<>();
    private List<SensSolde> sens = new ArrayList<>();
    private List<String> raisons = new ArrayList<>();
}
