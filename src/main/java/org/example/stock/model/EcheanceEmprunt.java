package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Une échéance de l'échéancier : capital et intérêt dus à une date. Modifiable seulement tant qu'aucun remboursement n'est saisi. */
@Entity
@Getter @Setter
@Table(name = "echeance_emprunt", uniqueConstraints = @UniqueConstraint(
        name = "uk_echeance_emprunt_rang", columnNames = {"emprunt_id", "rang"}))
public class EcheanceEmprunt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "emprunt_id", nullable = false, foreignKey = @ForeignKey(name = "fk_echeance_emprunt_emprunt"))
    private Emprunt emprunt;

    @Column(nullable = false)
    private int rang;

    @Column(nullable = false)
    private LocalDate dateEcheance;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal capital;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal interet;
}
