package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeRapprochement;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Groupe de lignes de relevé et de mouvements de l'appli qui se correspondent : la somme des lignes est égale à la somme des mouvements
 * (aucun écart toléré). Un groupe d'exclusion ne contient que des mouvements (un mouvement annulé et sa contre-passation, somme nulle).
 */
@Entity
@Getter @Setter
@Table(name = "rapprochement", indexes = @Index(name = "idx_rapprochement_releve", columnList = "releve_id"))
public class Rapprochement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "releve_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rapprochement_releve"))
    private ReleveBancaire releve;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private TypeRapprochement type;

    /** Somme commune des lignes et des mouvements du groupe. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal montant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rapprochement_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateCreation;
}
