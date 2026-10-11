package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_categorie_boutique_nom", columnNames = {"boutique_id", "nom"}))
@Getter
@Setter
public class Categorie {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @NotBlank(message = "Le nom de la catégorie est obligatoire")
    private String nom;

    @OneToMany(mappedBy = "categorie")
    private List<Produit> produits;
}