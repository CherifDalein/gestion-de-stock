package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.example.stock.enums.TypeRetour;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Marchandise rendue par un client (retour de vente) ou à un fournisseur (retour d'achat). Le montant total se partage
 * entre un remboursement en trésorerie et un avoir sur le compte du partenaire.
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_retour_boutique_numero", columnNames = {"boutique_id", "numero"}))
@Getter
@Setter
public class Retour {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_retour_boutique"))
    private Boutique boutique;

    /** Numéro unique par boutique, attribué à la création. */
    @Column(nullable = false, length = 30, updatable = false)
    private String numero;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private TypeRetour type;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id", foreignKey = @ForeignKey(name = "fk_retour_vente"))
    private Vente vente;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "achat_id", foreignKey = @ForeignKey(name = "fk_retour_achat"))
    private Achat achat;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", foreignKey = @ForeignKey(name = "fk_retour_client"))
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id", foreignKey = @ForeignKey(name = "fk_retour_fournisseur"))
    private Fournisseur fournisseur;

    /** Compte qui a saisi le retour. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", foreignKey = @ForeignKey(name = "fk_retour_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateRetour;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantTotal = Montants.ZERO;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantRembourse = Montants.ZERO;

    /** Compte du remboursement ; vide quand rien n'est remboursé en trésorerie. */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 16)
    private CompteTresorerie compteTresorerie;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantAvoir = Montants.ZERO;

    @Column(length = 255)
    private String motif;

    @OneToMany(mappedBy = "retour", cascade = CascadeType.ALL)
    private List<DetailRetour> lignes = new ArrayList<>();

    /** Partie du retour qui a réduit la dette ou la créance du partenaire, sans argent : montant − remboursé − avoir. */
    public BigDecimal getMontantImpute() {
        return Montants.ouZero(montantTotal).subtract(Montants.ouZero(montantRembourse)).subtract(Montants.ouZero(montantAvoir));
    }

    /** Un retour dont l'excédent est gardé en avoir s'imprime comme un « Avoir » ; sinon c'est un « Bon de retour » (retour de vente) ou un « Bon de retour fournisseur » (retour d'achat). */
    public boolean estAvoir() {
        return Montants.ouZero(montantAvoir).signum() > 0;
    }
}
