package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Inventaire des créances et des dettes à une date : le gérant y consigne le solde confirmé par chaque partenaire contacté, l'écart avec
 * le solde de l'application et une raison. C'est une trace : elle ne modifie aucun solde.
 */
@Entity
@Getter @Setter
@Table(name = "inventaire_creances", uniqueConstraints = @UniqueConstraint(name = "uk_inventaire_creances_jeton", columnNames = "jeton"))
public class InventaireCreances {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "boutique_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventaire_creances_boutique"))
    private Boutique boutique;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_inventaire_creances_utilisateur"))
    private Utilisateur utilisateur;

    /** Date à laquelle les partenaires ont confirmé leur solde. */
    @Column(nullable = false)
    private LocalDate dateInventaire;

    /** Instant de la saisie : c'est à cet instant que les soldes de l'application ont été lus. */
    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @Column(length = 255)
    private String note;

    @Column(nullable = false, length = 36)
    private String jeton;

    @OneToMany(mappedBy = "inventaire", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<LigneInventaire> lignes = new ArrayList<>();
}
