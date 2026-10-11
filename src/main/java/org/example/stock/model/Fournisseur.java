package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Entity
@Getter
@Setter
public class Fournisseur {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @NotBlank(message = "Le nom du fournisseur est obligatoire")
    private String nom;

    private String telephone;

    @Email(message = "Format d'email invalide")
    private String email;

    private String adresse;

    @OneToMany(mappedBy = "fournisseur")
    private List<Produit> produits;
}