package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class DetailVente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    private Vente vente;

    @ManyToOne
    private Produit produit;

    private Integer quantite;
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal prixUnitaire;

    /** Coût de revient unitaire figé lors de la vente ; approximé par le prix d'achat pour les ventes migrées. */
    @Column(precision = 17, scale = 2)
    private BigDecimal coutUnitaire;
}
