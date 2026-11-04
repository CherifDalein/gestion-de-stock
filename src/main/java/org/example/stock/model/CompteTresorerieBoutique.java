package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.CompteTresorerie;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Point de verrouillage stable : le journal reste l'unique source du solde. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "compte_tresorerie_boutique", uniqueConstraints = @UniqueConstraint(
        name = "uk_compte_tresorerie_boutique", columnNames = {"boutique_id", "compte"}))
public class CompteTresorerieBoutique {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_compte_tresorerie_boutique"))
    private Boutique boutique;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private CompteTresorerie compte;

    public CompteTresorerieBoutique(Boutique boutique, CompteTresorerie compte) {
        this.boutique = boutique;
        this.compte = compte;
    }
}
