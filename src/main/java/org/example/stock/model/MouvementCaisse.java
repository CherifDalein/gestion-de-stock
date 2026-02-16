package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Getter
@Setter
public class MouvementCaisse {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDateTime dateMouvement;
    private Double montant; // Positif pour entrée, Négatif pour sortie
    private String type; // ENTRÉE, SORTIE
    private String motif; // Ex: "Vente #45"
    private String source; // "VENTE", "ACHAT", "DEPENSE", "AJUSTEMENT"

    @ManyToOne
    private Utilisateur utilisateur;

}
