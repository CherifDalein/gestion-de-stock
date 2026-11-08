package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.EtatDevis;
import org.example.stock.enums.StatutDevis;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Offre de prix valable jusqu'à une date ; elle ne touche ni le stock ni la caisse tant qu'elle n'est pas transformée en vente. */
@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_devis_boutique_numero", columnNames = {"boutique_id", "numero"}),
        @UniqueConstraint(name = "uk_devis_vente", columnNames = "vente_id")})
@Getter
@Setter
public class Devis {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_devis_boutique"))
    private Boutique boutique;

    /** Numéro unique par boutique, attribué à la création. */
    @Column(nullable = false, length = 30, updatable = false)
    private String numero;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", foreignKey = @ForeignKey(name = "fk_devis_client"))
    private Client client;

    /** Compte qui a établi le devis. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", foreignKey = @ForeignKey(name = "fk_devis_utilisateur"))
    private Utilisateur utilisateur;

    @Column(nullable = false)
    private LocalDateTime dateDevis;

    @Column(nullable = false)
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dateValidite;

    @Column(nullable = false, precision = 17, scale = 2)
    @Digits(integer = 15, fraction = 2, message = "Le montant doit comporter au maximum 15 chiffres entiers et 2 décimales")
    private BigDecimal montantTotal = Montants.ZERO;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 16)
    private StatutDevis statut = StatutDevis.EN_COURS;

    /** Vente issue du devis ; la contrainte d'unicité interdit de transformer deux fois le même devis. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_id", foreignKey = @ForeignKey(name = "fk_devis_vente"))
    private Vente vente;

    @Column(length = 255)
    private String note;

    @Version
    @Column(nullable = false, columnDefinition = "bigint default 0")
    private Long version;

    @OneToMany(mappedBy = "devis", cascade = CascadeType.ALL)
    @OrderBy("id")
    private List<DetailDevis> lignes = new ArrayList<>();

    /**
     * Expiré : encore en cours mais valable jusqu'à une date <strong>strictement antérieure</strong> au jour donné
     * (le dernier jour de validité compte encore). Jamais stocké ; le jour vient de {@code EcheanceService.aujourdHui()}.
     */
    public boolean estExpire(LocalDate aujourdHui) {
        return statut == StatutDevis.EN_COURS && aujourdHui != null && dateValidite != null && dateValidite.isBefore(aujourdHui);
    }

    /** Seul un devis en cours et non expiré peut devenir une vente. */
    public boolean estTransformable(LocalDate aujourdHui) {
        return statut == StatutDevis.EN_COURS && !estExpire(aujourdHui);
    }

    public EtatDevis etat(LocalDate aujourdHui) {
        return switch (statut) {
            case TRANSFORME -> EtatDevis.TRANSFORME;
            case ANNULE -> EtatDevis.ANNULE;
            case EN_COURS -> estExpire(aujourdHui) ? EtatDevis.EXPIRE : EtatDevis.EN_COURS;
        };
    }
}
