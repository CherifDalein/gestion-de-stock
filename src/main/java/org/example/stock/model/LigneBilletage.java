package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Nombre de billets ou de pièces d'une coupure (en francs guinéens) comptés pour un compte d'espèces. */
@Entity
@Getter @Setter
@Table(name = "comptage_billetage", uniqueConstraints = @UniqueConstraint(name = "uk_comptage_billetage", columnNames = {"comptage_id", "coupure"}))
public class LigneBilletage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "comptage_id", nullable = false, foreignKey = @ForeignKey(name = "fk_comptage_billetage_comptage"))
    private ComptageCaisse comptage;

    @Column(nullable = false)
    private int coupure;

    @Column(nullable = false)
    private int quantite;

    @Transient
    public java.math.BigDecimal getTotal() { return java.math.BigDecimal.valueOf((long) coupure * quantite).setScale(2); }
}
