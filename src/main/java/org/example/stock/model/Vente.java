package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.CompteTresorerie;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_vente_boutique_numero", columnNames = {"boutique_id", "numero"}))
@Getter
@Setter
public class Vente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    private LocalDateTime dateVente;

    /** Numéro de facture unique par boutique, attribué à la création puis jamais modifié. */
    @Column(length = 30, updatable = false)
    private String numero;

    /** Échéance du reste à payer ; vide pour un document soldé dès sa création, conservée en historique ensuite. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateEcheance;

    /** Part du montant réglée par une avance existante du partenaire. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal avanceUtilisee = Montants.ZERO;

    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantTotal;
    @Column(precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantVerse;

    /**
     * Valeur totale des marchandises retournées sur cette facture (Σ montant des retours de vente). Elle ne s'écrit que
     * sous le verrou de la vente, par le retour de vente ; le total effectif est {@code montantTotal − montantRetours}.
     */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal montantRetours = Montants.ZERO;

    /** Compte de l'encaissement saisi, indépendant de ceux des règlements suivants. */
    @Transient
    private CompteTresorerie comptePaiement = CompteTresorerie.CAISSE;

    @ManyToOne(fetch = FetchType.LAZY)
    private Client client;

    /** Compte qui a saisi la vente ; vide pour les ventes sans mouvement de caisse nominatif. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendeur_id", foreignKey = @ForeignKey(name = "fk_vente_vendeur"))
    private Utilisateur vendeur;

    @OneToMany(mappedBy = "vente", cascade = CascadeType.ALL)
    private List<DetailVente> lignes = new ArrayList<>();

    /** Retours de vente enregistrés sur cette facture, du premier au dernier ; lecture seule. */
    @OneToMany(mappedBy = "vente")
    @OrderBy("id ASC")
    private List<Retour> retours = new ArrayList<>();

    /** Total effectif = total d'origine − marchandises retournées : la base du reste à payer et du chiffre d'affaires net. */
    public BigDecimal getTotalEffectif() {
        return Montants.ouZero(montantTotal).subtract(Montants.ouZero(montantRetours));
    }

    /**
     * Reste à payer = max(0, total effectif − versé − avance utilisée), avec total effectif = total − retours. Un
     * trop-payé ou une avance qui couvre tout le total donne zéro : le reste n'est jamais négatif.
     */
    public BigDecimal getResteAPayer() {
        BigDecimal reste = getTotalEffectif().subtract(Montants.ouZero(montantVerse)).subtract(Montants.ouZero(avanceUtilisee));
        return reste.signum() < 0 ? Montants.ZERO : reste;
    }

    /**
     * Surplus d'origine = max(0, versé − total d'origine) : l'avance née à la création. Il se calcule avec le total
     * d'origine et non avec le total effectif : l'excédent créé par un retour est traité par le retour lui-même
     * (remboursement ou avoir), sinon il serait compté deux fois.
     */
    public BigDecimal getSurplus() {
        BigDecimal surplus = Montants.ouZero(montantVerse).subtract(Montants.ouZero(montantTotal));
        return surplus.signum() < 0 ? Montants.ZERO : surplus;
    }

    /** En retard : un reste à payer et une échéance strictement antérieure au jour donné. */
    public boolean estEnRetard(LocalDate aujourdHui) {
        return dateEcheance != null && aujourdHui != null && getResteAPayer().signum() > 0 && dateEcheance.isBefore(aujourdHui);
    }
}
