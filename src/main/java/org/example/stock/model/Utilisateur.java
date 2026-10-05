package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import org.example.stock.enums.Role;

import java.time.LocalDate;

@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "uk_utilisateur_email", columnNames = "email"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Utilisateur {

    @jakarta.persistence.Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nom;

    @Email(message = "Format d'email invalide")
    @Column(nullable = false)
    private String email;

    private String motDePasse;

    private LocalDate dateInscription;

    @Enumerated(EnumType.STRING)
    private Role role;

    @PrePersist
    @PreUpdate
    private void normaliserEmail() {
        email = Emails.normaliser(email);
    }
}
