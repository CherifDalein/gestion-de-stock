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
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_achat_boutique_numero", columnNames = {"boutique_id", "numero"}))
@Getter
@Setter
public class Achat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    private LocalDateTime dateAchat;

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

    /** Compte du versement saisi ; chaque paiement est archivé dans le journal de trésorerie. */
    @Transient
    private CompteTresorerie comptePaiement = CompteTresorerie.CAISSE;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "fournisseur_id")
    private Fournisseur fournisseur;

    @OneToMany(mappedBy = "achat", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<DetailAchat> lignes = new ArrayList<>();

    public BigDecimal getResteAPayer() {
        return Montants.ouZero(montantTotal).subtract(Montants.ouZero(montantVerse));
    }

    /** En retard : un reste à payer et une échéance strictement antérieure au jour donné. */
    public boolean estEnRetard(LocalDate aujourdHui) {
        return dateEcheance != null && aujourdHui != null && getResteAPayer().signum() > 0 && dateEcheance.isBefore(aujourdHui);
    }
}
