package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;
import java.time.LocalDateTime;
import java.util.List;

@Entity
// Unicité du nom parmi les catégories actives seulement : la clé « nom_cle » vaut le nom tant que la catégorie est active et
// reste vide dans la corbeille (une clé unique ignore les valeurs vides). Voir docs/lot-6.md.
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_categorie_boutique_nom_cle", columnNames = {"boutique_id", "nom_cle"}))
@SQLRestriction("supprime_le is null")
@Getter
@Setter
public class Categorie implements ElementSupprimable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @NotBlank(message = "Le nom de la catégorie est obligatoire")
    private String nom;

    /** Clé d'unicité : le nom d'une catégorie active, vide dans la corbeille. Maintenue par {@link #aligner()}, jamais liée. */
    @Column(name = "nom_cle")
    private String nomCle;

    /** Corbeille (lot 6) : date de mise à la corbeille ; vide tant que la fiche est active. Jamais liée depuis un formulaire. */
    @Column(name = "supprime_le")
    private LocalDateTime supprimeLe;

    /** Corbeille (lot 6) : identifiant du compte qui a supprimé la fiche (sans clé étrangère : le compte peut disparaître). */
    @Column(name = "supprime_par")
    private Long supprimePar;

    @Override
    public void mettreALaCorbeille(LocalDateTime quand, Long par) {
        this.supprimeLe = quand;
        this.supprimePar = par;
        this.nomCle = null;
    }

    /** Garde la clé d'unicité cohérente avec le nom et l'état, quel que soit le chemin d'écriture. */
    @PrePersist
    @PreUpdate
    void aligner() {
        nomCle = supprimeLe == null ? nom : null;
    }

    @OneToMany(mappedBy = "categorie")
    private List<Produit> produits;
}