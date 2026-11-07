package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Getter
@Setter
public class Client {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "boutique_id", nullable = false)
    private Boutique boutique;

    @NotBlank(message = "Le nom du client est obligatoire")
    private String nom;

    private String telephone;
    @Email(message = "Format d'email invalide")
    private String email;
    private String adresse;

    /** Solde d'ouverture signé : positif si le partenaire me doit, négatif si je lui dois ou s'il a versé une avance. */
    @Column(nullable = false, precision = 17, scale = 2)
    private BigDecimal soldeInitial = Montants.ZERO;

    private LocalDate echeanceSoldeInitial;
}