package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class DetailRetour {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "retour_id", nullable = false, foreignKey = @ForeignKey(name = "fk_detail_retour_retour"))
    private Retour retour;

    @ManyToOne
    @JoinColumn(name = "produit_id", nullable = false, foreignKey = @ForeignKey(name = "fk_detail_retour_produit"))
    private Produit produit;

    /** Ligne de vente retournée (retour de vente) ; vide pour un retour d'achat. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detail_vente_id", foreignKey = @ForeignKey(name = "fk_detail_retour_detail_vente"))
    private DetailVente detailVente;

    /** Ligne d'achat retournée (retour d'achat) ; vide pour un retour de vente. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "detail_achat_id", foreignKey = @ForeignKey(name = "fk_detail_retour_detail_achat"))
    private DetailAchat detailAchat;

    @Column(nullable = false)
    private Integer quantite;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal prixUnitaire;

    /** Coût de revient unitaire de la marchandise rendue, pour corriger la marge ; vide quand il n'est pas connu. */
    @Column(precision = 17, scale = 2)
    private BigDecimal coutUnitaire;
}
