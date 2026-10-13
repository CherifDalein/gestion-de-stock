package org.example.stock.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;

/** Réglage global réservé à l'administrateur de la plateforme. */
@Entity
@Getter
@Setter
public class ParametresPlateforme {
    @Id
    private Integer id;

    @Column(nullable = false)
    private int dureeEssaiJours;
}
