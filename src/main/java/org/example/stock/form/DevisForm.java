package org.example.stock.form;

import lombok.Getter;
import lombok.Setter;
import org.example.stock.model.Client;
import org.example.stock.model.Produit;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Saisie d'un devis. Le total, le numéro et la date du devis ne viennent jamais du navigateur. */
@Getter
@Setter
public class DevisForm {
    /** Client facultatif ; seul son identifiant est lu, la fiche est relue dans la boutique courante. */
    private Client client;

    /** Dernier jour de validité ; à défaut, la date du jour + 30 jours. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateValidite;

    private String note;

    private List<Ligne> lignes = new ArrayList<>();

    @Getter
    @Setter
    public static class Ligne {
        private Produit produit;
        private Integer quantite;
        private BigDecimal prixUnitaire;
    }
}
