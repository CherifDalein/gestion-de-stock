package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Correction du solde de départ d'un client ou d'un fournisseur déjà utilisé (étape 3b2). Les soldes sont signés comme
 * {@code solde_initial} : positif, le partenaire doit de l'argent à la boutique ; négatif, la boutique lui en doit. L'ajustement ne passe
 * aucun mouvement de trésorerie et ne réécrit aucun règlement passé. Il est conservé tel quel : ni modifié ni supprimé.
 */
@Entity
@Getter @Setter
@Table(name = "ajustement_solde_depart", uniqueConstraints = @UniqueConstraint(name = "uk_ajustement_solde_depart_jeton", columnNames = "jeton"))
public class AjustementSoldeDepart {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ajustement_solde_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", foreignKey = @ForeignKey(name = "fk_ajustement_solde_client"))
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id", foreignKey = @ForeignKey(name = "fk_ajustement_solde_fournisseur"))
    private Fournisseur fournisseur;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_ajustement_solde_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateAjustement;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal ancienSolde;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal nouveauSolde;

    @Column(nullable = false, length = 255)
    private String motif;

    @Column(nullable = false, length = 36)
    private String jeton;

    /** Variation du solde signé (nouveau − ancien). */
    @Transient
    public BigDecimal getVariation() { return nouveauSolde.subtract(ancienSolde); }
}
