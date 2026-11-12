package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.SensEmprunt;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Emprunt (la boutique reçoit puis rembourse) ou prêt (la boutique verse puis est remboursée). Le statut n'est pas stocké : il se
 * calcule depuis l'échéancier et les imputations des remboursements non annulés.
 */
@Entity
@Getter @Setter
@Table(name = "emprunt", uniqueConstraints = {
        @UniqueConstraint(name = "uk_emprunt_boutique_numero", columnNames = {"boutique_id", "numero"}),
        @UniqueConstraint(name = "uk_emprunt_jeton", columnNames = "jeton")},
        indexes = @Index(name = "idx_emprunt_boutique_partenaire", columnList = "boutique_id,partenaire_id"))
public class Emprunt {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_emprunt_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "partenaire_id", nullable = false, foreignKey = @ForeignKey(name = "fk_emprunt_partenaire"))
    private PartenaireFinancier partenaire;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_emprunt_utilisateur"))
    private Utilisateur utilisateur;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_emprunt_compte"))
    private CompteTresorerieBoutique compte;

    @Column(nullable = false, length = 30)
    private String numero;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private SensEmprunt sens;

    @Column(nullable = false)
    private LocalDate dateEmprunt;

    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal capital;

    /** Taux annuel simple en pourcentage (12,00 pour 12 %). */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal tauxAnnuel;

    @Column(nullable = false)
    private int nombreEcheances;

    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 16)
    private Periodicite periodicite;

    @Column(nullable = false)
    private LocalDate datePremiereEcheance;

    /** Frais de dossier d'un emprunt, payés au déblocage et comptés en charge financière ; toujours 0 pour un prêt. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal fraisDossier = BigDecimal.ZERO;

    @Column(length = 255)
    private String note;

    @Column(nullable = false, length = 36)
    private String jeton;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @OneToMany(mappedBy = "emprunt", fetch = FetchType.LAZY)
    @OrderBy("rang ASC")
    private List<EcheanceEmprunt> echeances = new ArrayList<>();

    public boolean estEmprunt() { return sens == SensEmprunt.EMPRUNT; }
}
