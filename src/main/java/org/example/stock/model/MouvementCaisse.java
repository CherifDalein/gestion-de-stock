package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_mouvement_caisse_annulation", columnNames = "annulation_de_id"))
@Getter
@Setter
public class MouvementCaisse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    /** Compte débité ou crédité ; son type ({@code compte.type}) sert aux totaux par type. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_mouvement_caisse_compte"))
    private CompteTresorerieBoutique compte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_tresorerie_id", foreignKey = @ForeignKey(name = "fk_mouvement_operation_tresorerie"))
    private OperationTresorerie operationTresorerie;

    private LocalDateTime dateMouvement;
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montant; // Positif pour entrée, Négatif pour sortie
    private String type; // ENTRÉE, SORTIE
    private String motif; // Ex: "Vente #45"
    private String source; // Voir SourceMouvement : "VENTE", "REGLEMENT_VENTE", "ACHAT", "REGLEMENT_ACHAT", "SOLDE_CLIENT", "ANNULATION"…

    @ManyToOne
    private Utilisateur utilisateur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achat_id")
    private Achat achat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id")
    private Vente vente;

    /** Client dont ce mouvement règle le solde de départ (source {@code SOLDE_CLIENT}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", foreignKey = @ForeignKey(name = "fk_mouvement_caisse_client"))
    private Client client;

    /** Fournisseur dont ce mouvement règle le solde de départ (source {@code SOLDE_FOURNISSEUR}). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id", foreignKey = @ForeignKey(name = "fk_mouvement_caisse_fournisseur"))
    private Fournisseur fournisseur;

    /** Retour dont ce mouvement est le remboursement. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "retour_id", foreignKey = @ForeignKey(name = "fk_mouvement_caisse_retour"))
    private Retour retour;

    /**
     * Mouvement que celui-ci annule (source {@code ANNULATION}, montant opposé, même compte, motif saisi par le gérant) ;
     * au plus une annulation par mouvement (contrainte d'unicité). Le mouvement d'origine n'est jamais modifié.
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "annulation_de_id", foreignKey = @ForeignKey(name = "fk_mouvement_caisse_annulation"))
    private MouvementCaisse annulationDe;


    /** Libellé français de la source pour le journal (les sources de financement ont un libellé clair, les autres restent telles quelles). */
    @Transient
    public String getSourceLibelle() { return SourceMouvement.libelle(source == null ? null : source.strip()); }
}
