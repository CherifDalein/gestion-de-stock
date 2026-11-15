package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.StatutInstantane;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Instantané des états d'un exercice au moment de sa clôture : les montants calculés ({@code contenu}, en JSON) et les documents PDF et Excel
 * ({@link InstantaneDocument}) ne changent plus jamais, même si une règle de calcul évolue. {@code empreinte} est le SHA-256 du contenu et des
 * documents. L'annulation de la clôture marque l'instantané « annulé » sans le supprimer.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "instantane_exercice", uniqueConstraints = @UniqueConstraint(name = "uk_instantane_exercice_version", columnNames = {"exercice_id", "version"}))
public class InstantaneExercice {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_instantane_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "exercice_id", nullable = false, foreignKey = @ForeignKey(name = "fk_instantane_exercice"))
    private ExerciceComptable exercice;

    /** 1 pour la première clôture de l'exercice, 2 s'il a été clôturé de nouveau après une annulation, etc. */
    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatutInstantane statut = StatutInstantane.ACTIF;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    /** Système comptable de la boutique à la clôture : NORMAL ou MINIMAL. */
    @Column(nullable = false, length = 10)
    private String systeme;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal resultat;

    @Column(nullable = false, length = 64)
    private String empreinte;

    /** Montants calculés de l'exercice (JSON UTF-8). */
    @Lob
    @Column(nullable = false, length = 8388608, columnDefinition = "LONGBLOB")
    private byte[] contenu;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;

    @Column(name = "cree_par", nullable = false, length = 120)
    private String creePar;

    @Column(length = 255)
    private String motif;

    @Column(name = "annule_le")
    private LocalDateTime annuleLe;

    @Column(name = "annule_par", length = 120)
    private String annulePar;

    @Column(name = "motif_annulation", length = 255)
    private String motifAnnulation;

    public InstantaneExercice(Boutique boutique, ExerciceComptable exercice, int version, String systeme, BigDecimal resultat, String empreinte, byte[] contenu,
                              LocalDateTime creeLe, String creePar, String motif) {
        this.boutique = boutique; this.exercice = exercice; this.version = version; this.dateDebut = exercice.getDateDebut(); this.dateFin = exercice.getDateFin();
        this.systeme = systeme; this.resultat = resultat; this.empreinte = empreinte; this.contenu = contenu; this.creeLe = creeLe; this.creePar = creePar; this.motif = motif;
    }

    /** Pose l'empreinte une fois les documents enregistrés (elle les couvre). */
    public void fixerEmpreinte(String empreinte) { this.empreinte = empreinte; }

    public void annuler(LocalDateTime quand, String par, String motif) {
        this.statut = StatutInstantane.ANNULE; this.annuleLe = quand; this.annulePar = par; this.motifAnnulation = motif;
    }
}
