package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Entity
@Getter
@Setter
public class Categorie {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Le nom de la catégorie est obligatoire")
    @Column(unique = true)
    private String nom;

    // Une catégorie peut avoir plusieurs produits
    @OneToMany(mappedBy = "categorie")
    private List<Produit> produits;
}