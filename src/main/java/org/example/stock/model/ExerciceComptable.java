package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.StatutExercice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Exercice comptable d'une boutique (étape 5c). Les exercices à venir ne sont pas stockés : leurs dates se calculent à partir du premier
 * exercice (réglage de la boutique) et de la durée de 12 mois. Une ligne est créée à la première clôture ; une clôture annulée remet la
 * ligne à « ouvert » (l'instantané annulé reste dans l'historique).
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "exercice_comptable", uniqueConstraints = @UniqueConstraint(name = "uk_exercice_boutique_debut", columnNames = {"boutique_id", "date_debut"}))
public class ExerciceComptable {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_exercice_boutique"))
    private Boutique boutique;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatutExercice statut = StatutExercice.OUVERT;

    @Column(name = "cloture_le")
    private LocalDateTime clotureLe;

    @Column(name = "cloture_par", length = 120)
    private String cloturePar;

    @Column(length = 255)
    private String motif;

    /** Résultat net de l'exercice (bénéfice positif, perte négative), figé à la clôture. */
    @Column(precision = 19, scale = 2)
    private BigDecimal resultat;

    /** Valeur du stock à la clôture : elle sert de stock d'ouverture et de valeur de référence à la fin de l'exercice. */
    @Column(name = "valeur_stock", precision = 19, scale = 2)
    private BigDecimal valeurStock;

    /** Plus grand identifiant de client à la clôture : un client plus récent a un solde de départ reporté dans l'exercice suivant. */
    @Column(name = "plafond_client_id")
    private Long plafondClientId;

    @Column(name = "plafond_fournisseur_id")
    private Long plafondFournisseurId;

    public ExerciceComptable(Boutique boutique, LocalDate dateDebut, LocalDate dateFin) {
        this.boutique = boutique; this.dateDebut = dateDebut; this.dateFin = dateFin;
    }

    public boolean estCloture() { return statut == StatutExercice.CLOTURE; }

    public void cloturer(LocalDateTime quand, String par, String motif, BigDecimal resultat, BigDecimal valeurStock, long plafondClientId, long plafondFournisseurId) {
        this.statut = StatutExercice.CLOTURE; this.clotureLe = quand; this.cloturePar = par; this.motif = motif; this.resultat = resultat; this.valeurStock = valeurStock;
        this.plafondClientId = plafondClientId; this.plafondFournisseurId = plafondFournisseurId;
    }

    public void rouvrir() {
        this.statut = StatutExercice.OUVERT; this.clotureLe = null; this.cloturePar = null; this.motif = null; this.resultat = null; this.valeurStock = null;
        this.plafondClientId = null; this.plafondFournisseurId = null;
    }
}
