package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.OrigineLigneReleve;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ligne d'un relevé : date, libellé, référence facultative et montant signé (crédit positif, débit négatif). */
@Entity
@Getter @Setter
@Table(name = "ligne_releve", indexes = {
        @Index(name = "idx_ligne_releve_releve", columnList = "releve_id,date_operation,id"),
        @Index(name = "idx_ligne_releve_empreinte", columnList = "empreinte")})
public class LigneReleve {
    public static final int TAILLE_LIBELLE = 255, TAILLE_REFERENCE = 100;

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "releve_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ligne_releve_releve"))
    private ReleveBancaire releve;

    @Column(nullable = false)
    private LocalDate dateOperation;

    @Column(nullable = false, length = TAILLE_LIBELLE)
    private String libelle;

    @Column(length = TAILLE_REFERENCE)
    private String reference;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal montant;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private OrigineLigneReleve origine = OrigineLigneReleve.SAISIE;

    /** Empreinte SHA-256 (hexadécimal) de la date, du montant, du libellé et de la référence : détecte une ligne déjà importée sur le compte. */
    @Column(nullable = false, length = 64)
    private String empreinte;

    /** Rapprochement qui contient cette ligne ; vide tant qu'elle n'est pas pointée. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rapprochement_id", foreignKey = @ForeignKey(name = "fk_ligne_releve_rapprochement"))
    private Rapprochement rapprochement;

    public boolean estRapprochee() { return rapprochement != null; }
}
