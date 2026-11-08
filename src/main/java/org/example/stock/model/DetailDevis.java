package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class DetailDevis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "devis_id", nullable = false, foreignKey = @ForeignKey(name = "fk_detail_devis_devis"))
    private Devis devis;

    @ManyToOne
    @JoinColumn(name = "produit_id", nullable = false, foreignKey = @ForeignKey(name = "fk_detail_devis_produit"))
    private Produit produit;

    @Column(nullable = false)
    private Integer quantite;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal prixUnitaire;
}
