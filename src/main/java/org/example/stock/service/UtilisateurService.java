package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class UtilisateurService {

    @Autowired
    private UtilisateurRepository utilisateurRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;


    public Utilisateur registerUtilisateur(String nom, String email, String motDePasse) {
        if (utilisateurRepository.findByEmail(email).isPresent()) {
            throw new RuntimeException("Email déjà utilisé");
        }

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setNom(nom);
        utilisateur.setEmail(email);
        utilisateur.setMotDePasse(passwordEncoder.encode(motDePasse));
        utilisateur.setDateInscription(LocalDate.now());
        utilisateur.setRole(Role.ADMIN);

        return utilisateurRepository.save(utilisateur);
    }



    public Utilisateur login(String email, String motDePasse) {
        Utilisateur utilisateur = utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Email ou mot de passe incorrect"));

        if (!passwordEncoder.matches(motDePasse, utilisateur.getMotDePasse())) {
            throw new RuntimeException("Email ou mot de passe incorrect");
        }

        return utilisateur;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            Utilisateur utilisateur = utilisateurRepository.findByEmail(email)
                    .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouvé : " + email));

            return org.springframework.security.core.userdetails.User.builder()
                    .username(utilisateur.getEmail())
                    .password(utilisateur.getMotDePasse())
                    .roles(utilisateur.getRole().name())
                    .build();
        };
    }

    public Utilisateur getUtilisateurConnecte() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String email = authentication.getName();
        return utilisateurRepository.findByEmail(email)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé : " + email));
    }
}
