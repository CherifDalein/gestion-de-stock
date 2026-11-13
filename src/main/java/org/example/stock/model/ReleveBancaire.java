package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.StatutReleve;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Relevé bancaire ou Orange Money d'un compte (étape 3c) : une période, les soldes d'ouverture et de clôture donnés par la banque et des
 * lignes ({@link LigneReleve}). Les relevés d'un compte se suivent sans se chevaucher. Une fois validé il est figé, avec ses rapprochements.
 */
@Entity
@Getter @Setter
@Table(name = "releve_bancaire", uniqueConstraints = {
        @UniqueConstraint(name = "uk_releve_bancaire_numero", columnNames = {"boutique_id", "numero"}),
        @UniqueConstraint(name = "uk_releve_bancaire_jeton", columnNames = "jeton")},
        indexes = @Index(name = "idx_releve_bancaire_compte_debut", columnList = "compte_id,date_debut"))
public class ReleveBancaire {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_releve_bancaire_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_releve_bancaire_compte"))
    private CompteTresorerieBoutique compte;

    /** « RB-AAAAMM-identifiant du compte » (mois de la clôture), suivi de « -2 », « -3 »… si ce numéro existe déjà. */
    @Column(nullable = false, length = 40)
    private String numero;

    @Column(nullable = false)
    private LocalDate dateDebut;

    @Column(nullable = false)
    private LocalDate dateFin;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeOuverture;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeCloture;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private StatutReleve statut = StatutReleve.BROUILLON;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_releve_bancaire_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @Column(nullable = false, length = 36)
    private String jeton;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "valide_par_id", foreignKey = @ForeignKey(name = "fk_releve_bancaire_valide_par"))
    private Utilisateur validePar;

    private LocalDateTime dateValidation;

    /** Solde du compte selon l'appli à la clôture, figé à la validation (le relevé validé ne se recalcule plus). */
    @Column(precision = 17, scale = 2)
    private BigDecimal soldeAppliValidation;

    public boolean estValide() { return statut == StatutReleve.VALIDE; }
}
