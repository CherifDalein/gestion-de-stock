package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Part d'un remboursement imputée sur une échéance : intérêt payé, capital payé et, pour un remboursement anticipé du solde, intérêt
 * futur remis. Elle compte tant que l'opération de remboursement n'est pas annulée.
 */
@Entity
@Getter @Setter
@Table(name = "imputation_echeance", indexes = @Index(name = "idx_imputation_echeance_echeance", columnList = "echeance_id"))
public class ImputationEcheance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operation_id", nullable = false, foreignKey = @ForeignKey(name = "fk_imputation_echeance_operation"))
    private OperationTresorerie operation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "echeance_id", nullable = false, foreignKey = @ForeignKey(name = "fk_imputation_echeance_echeance"))
    private EcheanceEmprunt echeance;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal capital = BigDecimal.ZERO;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal interet = BigDecimal.ZERO;

    @Column(name = "interet_remis", nullable = false, precision = 17, scale = 2)
    private BigDecimal interetRemis = BigDecimal.ZERO;
}
