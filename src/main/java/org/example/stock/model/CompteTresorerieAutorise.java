package org.example.stock.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Compte que le gérant a autorisé à un caissier. Un caissier sans aucune ligne utilise les comptes principaux. */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "compte_tresorerie_autorise", uniqueConstraints = @UniqueConstraint(
        name = "uk_compte_tresorerie_autorise", columnNames = {"utilisateur_id", "compte_id"}))
public class CompteTresorerieAutorise {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "utilisateur_id", nullable = false, foreignKey = @ForeignKey(name = "fk_compte_autorise_utilisateur"))
    private Utilisateur utilisateur;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "compte_id", nullable = false, foreignKey = @ForeignKey(name = "fk_compte_autorise_compte"))
    private CompteTresorerieBoutique compte;

    public CompteTresorerieAutorise(Utilisateur utilisateur, CompteTresorerieBoutique compte) {
        this.utilisateur = utilisateur;
        this.compte = compte;
    }
}
