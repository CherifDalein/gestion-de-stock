package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** Un mouvement de l'appli appartient à au plus un rapprochement : l'unicité de {@code mouvement_id} interdit de le pointer deux fois. */
@Entity
@Getter @Setter
@Table(name = "rapprochement_mouvement", uniqueConstraints = @UniqueConstraint(name = "uk_rapprochement_mouvement_mouvement", columnNames = "mouvement_id"),
        indexes = @Index(name = "idx_rapprochement_mouvement_rapprochement", columnList = "rapprochement_id"))
public class RapprochementMouvement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "rapprochement_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rapprochement_mouvement_rapprochement"))
    private Rapprochement rapprochement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mouvement_id", nullable = false, foreignKey = @ForeignKey(name = "fk_rapprochement_mouvement_mouvement"))
    private MouvementCaisse mouvement;
}
