package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Espace propriétaire des données d'une boutique. */
@Entity
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

    @Column(nullable = false)
    private LocalDateTime dateCreation;

    @Column(nullable = false, columnDefinition = "boolean default true")
    private boolean actif = true;

    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean accesOffert;

    @PrePersist
    private void daterCreation() {
        if (dateCreation == null) dateCreation = LocalDateTime.now();
    }
}
