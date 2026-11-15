package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Solde d'ouverture de l'exercice qui suit un exercice clôturé : un compte de bilan (classes 1 à 5), éventuellement un tiers, et son solde
 * signé (débit moins crédit) à la date de clôture. {@code exercice} est l'exercice clôturé dont le solde est issu ; l'annulation de la clôture
 * les supprime.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "solde_ouverture", uniqueConstraints = @UniqueConstraint(name = "uk_solde_ouverture", columnNames = {"exercice_id", "compte", "tiers_type", "tiers_id"}))
public class SoldeOuverture {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_solde_ouverture_exercice"))
    private ExerciceComptable exercice;

    @Column(nullable = false, length = 8)
    private String compte;

    /** Vide si le solde n'a pas de tiers ; sinon CLIENT, FOURNISSEUR ou PARTENAIRE. */
    @Column(name = "tiers_type", nullable = false, length = 12)
    private String tiersType = "";

    /** Zéro si le solde n'a pas de tiers. */
    @Column(name = "tiers_id", nullable = false)
    private Long tiersId = 0L;

    @Column(name = "tiers_nom", length = 160)
    private String tiersNom;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal solde;

    public SoldeOuverture(ExerciceComptable exercice, String compte, String tiersType, Long tiersId, String tiersNom, BigDecimal solde) {
        this.exercice = exercice; this.compte = compte; this.tiersType = tiersType == null ? "" : tiersType; this.tiersId = tiersId == null ? 0L : tiersId;
        this.tiersNom = tiersNom != null && tiersNom.length() > 160 ? tiersNom.substring(0, 160) : tiersNom; this.solde = solde;
    }
}
