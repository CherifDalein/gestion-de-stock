package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Email;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;

import java.time.LocalDateTime;

/** Espace propriétaire des données d'une boutique. */
@Entity
@DynamicUpdate
@Getter
@Setter
public class Boutique {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String nom;

    @Size(max = 500, message = "L'adresse accepte au maximum 500 caractères")
    @Column(length = 500)
    private String adresse;

    @Size(max = 50, message = "Le téléphone accepte au maximum 50 caractères")
    @Column(length = 50)
    private String telephone;

    @Email(message = "Le format de l'email est invalide")
    @Size(max = 254, message = "L'email accepte au maximum 254 caractères")
    @Column(length = 254)
    private String email;

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean actif = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean accesOffert;

    /** Dates UTC de l'essai public ; null pour les boutiques créées avant son introduction. */
    private LocalDateTime essaiDebut;

    private LocalDateTime essaiFin;

    @PrePersist
    private void daterCreation() {
        if (dateCreation == null) dateCreation = LocalDateTime.now();
    }
}
