package org.example.stock.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.stock.enums.Role;

import java.time.LocalDate;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Utilisateur {

    @jakarta.persistence.Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String nom;

    @Email(message = "Format d'email invalide")
    private String email;

    private String motDePasse;

    private LocalDate dateInscription;

    @Enumerated(EnumType.STRING)
    private Role role;
}

