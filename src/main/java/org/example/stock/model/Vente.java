package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Setter
public class Vente {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime dateVente;
    private Double montantTotal;

    @ManyToOne
    private Client client;

    @OneToMany(mappedBy = "vente", cascade = CascadeType.ALL)
    private List<DetailVente> lignes = new ArrayList<>();
}
