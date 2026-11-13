package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Proposition automatique refusée par le gérant : la même paire ligne-mouvement n'est plus reproposée. */
@Entity
@Getter @Setter
@Table(name = "refus_proposition", uniqueConstraints = @UniqueConstraint(name = "uk_refus_proposition", columnNames = {"ligne_id", "mouvement_id"}))
public class RefusProposition {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ligne_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refus_proposition_ligne"))
    private LigneReleve ligne;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mouvement_id", nullable = false, foreignKey = @ForeignKey(name = "fk_refus_proposition_mouvement"))
    private MouvementCaisse mouvement;
}
