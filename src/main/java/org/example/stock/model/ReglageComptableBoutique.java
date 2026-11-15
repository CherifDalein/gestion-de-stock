package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.SystemeComptable;

import java.time.LocalDate;

/**
 * Réglage comptable d'une boutique (étape 5b) : le système dans lequel elle présente ses états financiers. Une boutique sans ligne est au
 * système normal ; la ligne n'existe qu'après un premier choix du gérant.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "reglage_comptable_boutique", uniqueConstraints = @UniqueConstraint(name = "uk_reglage_comptable_boutique", columnNames = "boutique_id"))
public class ReglageComptableBoutique {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_reglage_comptable_boutique"))
    private Boutique boutique;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private SystemeComptable systeme = SystemeComptable.NORMAL;

    /** Début du premier exercice (étape 5c) ; vide : le 1er janvier de l'année de la première opération. Réglable tant qu'aucun exercice n'est clôturé. */
    @Column(name = "debut_premier_exercice")
    private LocalDate debutPremierExercice;

    /** Cabinet d'expertise comptable qui a validé les états (étape 5c) ; vide tant que les états ne sont pas validés. */
    @Column(name = "valide_cabinet", length = 120)
    private String valideCabinet;

    @Column(name = "valide_le")
    private LocalDate valideLe;

    public ReglageComptableBoutique(Boutique boutique, SystemeComptable systeme) { this.boutique = boutique; this.systeme = systeme; }

    public void choisir(SystemeComptable systeme) { this.systeme = systeme; }

    public void fixerDebutPremierExercice(LocalDate debut) { this.debutPremierExercice = debut; }

    /** Les états sont validés par {@code cabinet} à la date {@code le} ; deux valeurs vides retirent la validation. */
    public void valider(String cabinet, LocalDate le) { this.valideCabinet = cabinet; this.valideLe = le; }

    public boolean estValide() { return valideCabinet != null && !valideCabinet.isBlank() && valideLe != null; }
}
