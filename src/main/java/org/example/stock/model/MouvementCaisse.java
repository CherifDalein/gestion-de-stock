package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class MouvementCaisse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    private CompteTresorerie compte = CompteTresorerie.CAISSE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operation_tresorerie_id", foreignKey = @ForeignKey(name = "fk_mouvement_operation_tresorerie"))
    private OperationTresorerie operationTresorerie;

    private LocalDateTime dateMouvement;
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montant; // Positif pour entrée, Négatif pour sortie
    private String type; // ENTRÉE, SORTIE
    private String motif; // Ex: "Vente #45"
    private String source; // "VENTE", "ACHAT", "DEPENSE", "AJUSTEMENT"

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

}
