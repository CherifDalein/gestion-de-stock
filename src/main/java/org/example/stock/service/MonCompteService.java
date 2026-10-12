package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.enums.Role;
import org.example.stock.form.ChangementMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;

/** Seul le compte authentifié est ciblé ; les autorisations persistées sont vérifiées à chaque opération. */
@Service
@Transactional(readOnly = true)
public class MonCompteService {
    private final UtilisateurRepository utilisateurs;
    private final EntityManager entityManager;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;

    public MonCompteService(UtilisateurRepository utilisateurs, EntityManager entityManager,
                            PasswordEncoder passwordEncoder, Validator validator) {
        this.utilisateurs = utilisateurs;
        this.entityManager = entityManager;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
    }

    public ComptePersonnel chargerCompteCourant() {
        Utilisateur compte = exigerCompteCourant();
        return new ComptePersonnel(compte.getNom(), compte.getEmail(), compte.getRole(),
                compte.getDateInscription(), compte.getBoutique().getNom());
    }

    public void verifierCompteCourant() {
        exigerCompteCourant();
    }

    @Transactional
    public void changerMotDePasse(ChangementMotDePasseForm formulaire) {
        Utilisateur compte = exigerCompteCourant();
        verifierFormulaire(formulaire);
        // Relire la copie persistée sous verrou empêche deux changements concurrents avec le même ancien secret.
        Long boutiqueId = compte.getBoutique().getId();
        compte = entityManager.createQuery("select u from Utilisateur u where u.id = :id and u.email = :email and u.boutique.id = :boutiqueId", Utilisateur.class)
                .setParameter("id", compte.getId()).setParameter("email", compte.getEmail()).setParameter("boutiqueId", boutiqueId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst().orElseThrow(MonCompteService::interdit);
        entityManager.refresh(compte, LockModeType.PESSIMISTIC_WRITE);
        if (compte.getBoutique() == null || !boutiqueId.equals(compte.getBoutique().getId())) throw interdit();
        Boutique boutique = entityManager.find(Boutique.class, boutiqueId, LockModeType.PESSIMISTIC_READ);
        if (boutique == null) throw interdit();
        entityManager.refresh(boutique, LockModeType.PESSIMISTIC_READ);
        verifierAutorisation(compte);

        if (!passwordEncoder.matches(formulaire.getAncienMotDePasse(), compte.getMotDePasse())) {
            throw new ChangementMotDePasseException("ancienMotDePasse", "Votre mot de passe actuel est incorrect.");
        }
        if (passwordEncoder.matches(formulaire.getNouveauMotDePasse(), compte.getMotDePasse())) {
            throw new ChangementMotDePasseException("nouveauMotDePasse", "Choisissez un mot de passe différent du mot de passe actuel.");
        }
        compte.setMotDePasse(passwordEncoder.encode(formulaire.getNouveauMotDePasse()));
        // Aucune donnée de profil, aucun rôle et aucun statut d'accès ne vient du formulaire.
        entityManager.flush();
    }

    private void verifierFormulaire(ChangementMotDePasseForm formulaire) {
        if (formulaire == null) {
            throw new ChangementMotDePasseException(null, "Le formulaire de changement du mot de passe est obligatoire.");
        }
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) {
            var erreur = erreurs.stream().min(Comparator.comparing(violation -> violation.getPropertyPath().toString())).orElseThrow();
            throw new ChangementMotDePasseException(erreur.getPropertyPath().toString(), erreur.getMessage());
        }
        if (!formulaire.getNouveauMotDePasse().equals(formulaire.getConfirmationMotDePasse())) {
            throw new ChangementMotDePasseException("confirmationMotDePasse", "Les deux nouveaux mots de passe doivent être identiques.");
        }
    }

    private Utilisateur exigerCompteCourant() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) throw interdit();
        Utilisateur compte = utilisateurs.findByEmail(Emails.normaliser(authentication.getName())).orElseThrow(MonCompteService::interdit);
        verifierAutorisation(compte);
        return compte;
    }

    private void verifierAutorisation(Utilisateur compte) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!Emails.normaliser(authentication.getName()).equals(compte.getEmail())
                || !compte.isActif() || compte.getRole() == null || compte.getBoutique() == null || compte.getBoutique().getId() == null
                || authentication.getAuthorities().stream().noneMatch(autorite -> ("ROLE_" + compte.getRole().name()).equals(autorite.getAuthority()))) {
            throw interdit();
        }
        boolean plateforme = compte.isAdministrateurPlateforme() && compte.getRole() == Role.ADMIN
                && authentication.getAuthorities().stream().anyMatch(autorite -> "ROLE_PLATEFORME_ADMIN".equals(autorite.getAuthority()));
        if (!compte.getBoutique().isActif() && !plateforme) throw interdit();
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Votre compte est inaccessible");
    }
}
