package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypePartenaireFinancier;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Banque, associé, membre du personnel ou autre tiers financier d'une boutique. Son nom est unique par boutique, sans tenir compte de la casse. */
@Entity
@Getter @Setter
@Table(name = "partenaire_financier", uniqueConstraints = @UniqueConstraint(
        name = "uk_partenaire_financier_nom", columnNames = {"boutique_id", "nom_cle"}))
public class PartenaireFinancier {
    public static final int NOM_MAX = 80;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_partenaire_financier_boutique"))
    private Boutique boutique;

    @Column(nullable = false, length = NOM_MAX)
    private String nom;

    @Column(name = "nom_cle", nullable = false, length = NOM_MAX)
    private String nomCle;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private TypePartenaireFinancier type;

    @Column(length = 30)
    private String telephone;

    @Column(length = 255)
    private String note;

    @Column(nullable = false)
    private boolean actif = true;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    public void renommer(String nom) {
        this.nom = CompteTresorerieBoutique.normaliserNom(nom);
        this.nomCle = CompteTresorerieBoutique.cle(this.nom);
    }
}
