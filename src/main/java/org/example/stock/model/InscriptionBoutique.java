package org.example.stock.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** Email à confirmer : aucun compte, aucune boutique et aucun mot de passe avant la validation finale. */
@Entity
@Table(name = "inscription_boutique", uniqueConstraints =
        @UniqueConstraint(name = "uk_inscription_boutique_jeton", columnNames = "empreinte_jeton"))
@Getter
@Setter
public class InscriptionBoutique {
    @Id
    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "empreinte_jeton", nullable = false, length = 64)
    private String empreinteJeton;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;

    @Column(name = "expire_le", nullable = false)
    private LocalDateTime expireLe;

    @PrePersist
    @PreUpdate
    private void normaliserEmail() { email = Emails.normaliser(email); }
}
