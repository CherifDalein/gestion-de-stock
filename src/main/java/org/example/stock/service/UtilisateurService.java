package org.example.stock.service;

import org.example.stock.enums.Role;
import org.example.stock.model.Utilisateur;
import org.example.stock.model.Emails;
import org.example.stock.form.InscriptionForm;
import jakarta.validation.Validator;
import org.springframework.dao.DataIntegrityViolationException;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;

@Service
public class UtilisateurService {

    @Autowired
    private UtilisateurRepository utilisateurRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private Validator validator;

    @Transactional
    public Utilisateur registerUtilisateur(String nom, String email, String motDePasse) {
        InscriptionForm formulaire = new InscriptionForm();
        formulaire.setNom(nom); formulaire.setEmail(email); formulaire.setPassword(motDePasse);
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) throw new IllegalArgumentException(erreurs.stream()
                .map(erreur -> erreur.getMessage()).sorted().findFirst().orElseThrow());
        String emailNormalise = formulaire.getEmail();
        if (utilisateurRepository.existsByEmail(emailNormalise)) throw new EmailDejaUtiliseException();

        Utilisateur utilisateur = new Utilisateur();
        utilisateur.setNom(formulaire.getNom());
        utilisateur.setEmail(emailNormalise);
        utilisateur.setMotDePasse(passwordEncoder.encode(motDePasse));
        utilisateur.setDateInscription(LocalDate.now());
        utilisateur.setRole(Role.CAISSIER);

        try {
            return utilisateurRepository.saveAndFlush(utilisateur);
        } catch (DataIntegrityViolationException e) {
            // La contrainte SQL décide aussi lorsque deux pré-vérifications réussissent simultanément.
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().toLowerCase(Locale.ROOT).contains("uk_utilisateur_email")) {
                    throw new EmailDejaUtiliseException();
                }
            }
            throw e;
        }
    }

    public Utilisateur login(String email, String motDePasse) {
        Utilisateur utilisateur = utilisateurRepository.findByEmail(Emails.normaliser(email))
                .orElseThrow(() -> new RuntimeException("Email ou mot de passe incorrect"));

        if (!passwordEncoder.matches(motDePasse, utilisateur.getMotDePasse())) {
            throw new RuntimeException("Email ou mot de passe incorrect");
        }

        return utilisateur;
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            Utilisateur utilisateur = utilisateurRepository.findByEmail(Emails.normaliser(email))
                    .orElseThrow(() -> new UsernameNotFoundException("Email ou mot de passe incorrect"));

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
