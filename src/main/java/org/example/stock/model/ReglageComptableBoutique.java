package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.SystemeComptable;

/**
 * Réglage comptable d'une boutique (étape 5b) : le système dans lequel elle présente ses états financiers. Une boutique sans ligne est au
 * système normal ; la ligne n'existe qu'après un premier choix du gérant.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "reglage_comptable_boutique", uniqueConstraints = @UniqueConstraint(name = "uk_reglage_comptable_boutique", columnNames = "boutique_id"))
public class ReglageComptableBoutique {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reglage_comptable_boutique"))
    private Boutique boutique;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SystemeComptable systeme = SystemeComptable.NORMAL;

    public ReglageComptableBoutique(Boutique boutique, SystemeComptable systeme) { this.boutique = boutique; this.systeme = systeme; }

    public void choisir(SystemeComptable systeme) { this.systeme = systeme; }
}
