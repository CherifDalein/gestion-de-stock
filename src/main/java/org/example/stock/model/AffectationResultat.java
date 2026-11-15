package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Affectation du résultat d'un exercice clôturé : le compte 13 est soldé vers le report à nouveau (12), les réserves (11) ou les associés,
 * dividendes à payer (465). Une seule par exercice. Elle figure en OD dans l'exercice suivant, à {@code dateAffectation}.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "affectation_resultat", uniqueConstraints = @UniqueConstraint(name = "uk_affectation_exercice", columnNames = "exercice_id"))
public class AffectationResultat {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_affectation_boutique"))
    private Boutique boutique;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_affectation_exercice"))
    private ExerciceComptable exercice;

    @Column(name = "date_affectation", nullable = false)
    private LocalDate dateAffectation;

    @Column(name = "compte_destination", nullable = false, length = 8)
    private String compteDestination;

    /** Résultat affecté : positif pour un bénéfice (131), négatif pour une perte (139). */
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 160)
    private String libelle;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;

    @Column(name = "cree_par", nullable = false, length = 120)
    private String creePar;

    public AffectationResultat(Boutique boutique, ExerciceComptable exercice, LocalDate dateAffectation, String compteDestination, BigDecimal montant, String libelle,
                               LocalDateTime creeLe, String creePar) {
        this.boutique = boutique; this.exercice = exercice; this.dateAffectation = dateAffectation; this.compteDestination = compteDestination; this.montant = montant;
        this.libelle = libelle; this.creeLe = creeLe; this.creePar = creePar;
    }
}
