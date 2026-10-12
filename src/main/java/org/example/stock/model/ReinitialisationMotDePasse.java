package org.example.stock.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Un seul lien courant par compte. Aucun jeton utilisable n'est conservé en base. */
@Entity
@Table(name = "reinitialisation_mot_de_passe", uniqueConstraints =
        @UniqueConstraint(name = "uk_reinitialisation_mdp_jeton", columnNames = "empreinte_jeton"))
@Getter
@Setter
public class ReinitialisationMotDePasse {
    @Id
    @Column(name = "utilisateur_id")
    private Long utilisateurId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_id", nullable = false)
    private Utilisateur utilisateur;

    @Column(name = "empreinte_jeton", nullable = false, length = 64)
    private String empreinteJeton;

    @Column(name = "empreinte_mot_de_passe", nullable = false, length = 64)
    private String empreinteMotDePasse;

    // Valeurs UTC explicites, identiques dans H2 et dans DATETIME(6) MariaDB.
    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;

    @Column(name = "expire_le", nullable = false)
    private LocalDateTime expireLe;
}
