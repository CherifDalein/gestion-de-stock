package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.ActionAbonnement;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

/** Événements ajoutés après leur action effective, sans réécriture du passé. */
@Entity
@Immutable
@Getter
@Setter
@Table(name = "historique_abonnement", uniqueConstraints = @UniqueConstraint(
        name = "uk_historique_abonnement_jeton", columnNames = "jeton_action"),
        indexes = @Index(name = "idx_historique_abonnement_boutique_date", columnList = "boutique_id,cree_le,id"))
public class HistoriqueAbonnement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "acteur_id", nullable = false)
    private Utilisateur acteur;

    @Column(nullable = false, length = 255)
    private String acteurNom;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private ActionAbonnement action;

    @Column(nullable = false)
    private LocalDateTime creeLe;

    private LocalDateTime periodeDebut;
    private LocalDateTime periodeFin;
    private LocalDateTime ancienneFin;

    @Column(length = 500)
    private String motif;

    @Column(nullable = false, length = 36)
    private String jetonAction;

    private Integer quantite;
}
