package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.StatutComptage;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Comptage physique d'un compte de trésorerie (étape 3b2). Il fige le solde théorique du compte à l'instant de la saisie et l'écart
 * (compté − théorique : positif pour un excédent, négatif pour un manque). L'écart ne devient un mouvement qu'à la validation du gérant.
 */
@Entity
@Getter @Setter
@Table(name = "comptage_caisse", uniqueConstraints = @UniqueConstraint(name = "uk_comptage_caisse_jeton", columnNames = "jeton"),
        indexes = @Index(name = "idx_comptage_caisse_boutique_statut", columnList = "boutique_id,statut"))
public class ComptageCaisse {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_comptage_caisse_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_comptage_caisse_compte"))
    private CompteTresorerieBoutique compte;

    /** Compte (caissier ou gérant) qui a compté. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_comptage_caisse_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateComptage;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal montantCompte;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeTheorique;

    /** Montant compté moins solde théorique, à l'instant de la saisie. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal ecart;

    @Column(length = 255)
    private String commentaire;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private StatutComptage statut;

    /** Gérant qui a validé ou refusé. */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "decideur_id", foreignKey = @ForeignKey(name = "fk_comptage_caisse_decideur"))
    private Utilisateur decideur;

    private LocalDateTime dateDecision;

    @Column(length = 255)
    private String motifDecision;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "annule_par_id", foreignKey = @ForeignKey(name = "fk_comptage_caisse_annule_par"))
    private Utilisateur annulePar;

    private LocalDateTime dateAnnulation;

    @Column(length = 255)
    private String motifAnnulation;

    @Column(nullable = false, length = 36)
    private String jeton;

    @OneToMany(mappedBy = "comptage", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("coupure DESC")
    private List<LigneBilletage> billetage = new ArrayList<>();

    public boolean estExcedent() { return ecart != null && ecart.signum() > 0; }

    public boolean estManque() { return ecart != null && ecart.signum() < 0; }

    public BigDecimal getEcartAbsolu() { return ecart == null ? BigDecimal.ZERO : ecart.abs(); }

    /** « Excédent », « Manque » ou « Aucun écart ». */
    @Transient
    public String getNatureEcart() { return estExcedent() ? "Excédent" : estManque() ? "Manque" : "Aucun écart"; }

    public void ajouterLigne(int coupure, int quantite) {
        LigneBilletage ligne = new LigneBilletage();
        ligne.setComptage(this); ligne.setCoupure(coupure); ligne.setQuantite(quantite);
        billetage.add(ligne);
    }
}
