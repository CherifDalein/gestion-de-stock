package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.TypeEvenementReleve;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Historique d'un relevé : chaque validation et chaque dévalidation (avec son motif) est conservée. */
@Entity
@Getter @Setter
@Table(name = "evenement_releve", indexes = @Index(name = "idx_evenement_releve_releve", columnList = "releve_id,id"))
public class EvenementReleve {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "releve_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evenement_releve_releve"))
    private ReleveBancaire releve;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private TypeEvenementReleve type;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_evenement_releve_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateEvenement;

    /** Motif de la dévalidation (obligatoire) ; vide pour une validation. */
    @Column(length = 255)
    private String motif;

    /** Solde du compte selon l'appli à la clôture, au moment de l'événement. */
    @Column(precision = 17, scale = 2)
    private BigDecimal soldeAppli;
}
