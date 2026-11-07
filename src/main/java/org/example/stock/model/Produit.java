package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.NatureProduit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Getter
@Setter
public class Produit {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version;

    @NotBlank(message = "Le nom du produit est obligatoire")
    @Size(max = 255, message = "Le nom ne peut pas dépasser 255 caractères")
    private String nom;

    @NotBlank(message = "La référence est obligatoire")
    @Size(max = 255, message = "La référence ne peut pas dépasser 255 caractères")
    private String reference;

    @NotNull(message = "Le prix d'achat est obligatoire")
    @PositiveOrZero(message = "Le prix d'achat ne peut pas être négatif")
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal prixAchat = Montants.ZERO;

    @NotNull(message = "Le prix de vente est obligatoire")
    @PositiveOrZero(message = "Le prix de vente ne peut pas être négatif")
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal prixVente = Montants.ZERO;

    // Les produits existants conservent leurs tarifs ; les nouvelles fiches attendent une entrée de stock.
    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean prixDefinis = true;

    @NotNull(message = "La quantité est obligatoire")
    @PositiveOrZero(message = "La quantité ne peut pas être négative")
    private Long quantite = 0L;

    /** Lot 1 : les produits existants et nouveaux restent des articles en stock. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private NatureProduit nature = NatureProduit.STOCK;

    /** Unité de vente facultative (pièce, kg, sac…) ; aucune unité n'est inventée pour l'existant. */
    @Size(max = 30, message = "L'unité accepte au maximum 30 caractères")
    @Column(length = 30)
    private String unite;

    @Column(nullable = false)
    private Long seuilAlerte = 5L;

    /** Coût moyen pondéré ; la migration l'initialise avec le prix d'achat des produits existants. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal coutMoyen = Montants.ZERO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "categorie_id")
    @NotNull(message = "La catégorie est obligatoire")
    private Categorie categorie;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id")
    private Fournisseur fournisseur;
}
