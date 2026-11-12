package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeOperationTresorerie;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** En-tête immuable des écritures d'un transfert ou d'une dépense. */
@Entity
@Getter @Setter
@Table(name = "operation_tresorerie", uniqueConstraints = @UniqueConstraint(
        name = "uk_operation_tresorerie_jeton", columnNames = "jeton"), indexes = @Index(
        name = "idx_operation_tresorerie_boutique_date", columnList = "boutique_id,date_operation,id"))
public class OperationTresorerie {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_operation_tresorerie_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_operation_tresorerie_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false, length = 36)
    private String jeton;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 32)
    private TypeOperationTresorerie type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "compte_source_id", nullable = false, foreignKey = @ForeignKey(name = "fk_operation_tresorerie_source"))
    private CompteTresorerieBoutique compteSource;

    /** Vide pour une dépense. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "compte_destination_id", foreignKey = @ForeignKey(name = "fk_operation_tresorerie_destination"))
    private CompteTresorerieBoutique compteDestination;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal montant;

    @Column(nullable = false, length = 255)
    private String motif;

    @Column(nullable = false)
    private LocalDateTime dateOperation;
}
