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
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
public class UtilisateurService {

    @Autowired
    private UtilisateurRepository utilisateurRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Transactional
    public Utilisateur registerUtilisateur(String nom, String email, String motDePasse) {
        String emailNormalise = email == null ? null : email.trim().toLowerCase();

        if (emailNormalise == null || emailNormalise.isBlank()) {
            throw new RuntimeException("Email obligatoire");
        }

        if (utilisateurRepository.findByEmail(emailNormalise).isPresent()) {
            throw new RuntimeException("Email deja utilise");
        }

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setNom(nom != null ? nom.trim() : null);
        utilisateur.setEmail(emailNormalise);
        utilisateur.setMotDePasse(passwordEncoder.encode(motDePasse));
        utilisateur.setDateInscription(LocalDate.now());
        utilisateur.setRole(Role.ADMIN);

        Utilisateur enregistre = utilisateurRepository.saveAndFlush(utilisateur);

        if (utilisateurRepository.findByEmail(emailNormalise).isEmpty()) {
            throw new RuntimeException("L'utilisateur n'a pas pu etre enregistre.");
        }

        return enregistre;
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
                    .orElseThrow(() -> new UsernameNotFoundException("Utilisateur non trouve : " + email));

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
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouve : " + email));
    }
}
