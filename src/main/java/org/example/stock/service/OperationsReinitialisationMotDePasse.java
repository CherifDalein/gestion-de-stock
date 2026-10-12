package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.config.RecoveryProperties;
import org.example.stock.enums.Role;
import org.example.stock.form.ReinitialisationMotDePasseForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.ReinitialisationMotDePasse;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.ReinitialisationMotDePasseRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;

/** Les opérations SQL restent séparées de l'envoi des emails et de la file d'attente. */
@Service
@Transactional(readOnly = true)
public class OperationsReinitialisationMotDePasse {
    private final EntityManager entityManager;
    private final ReinitialisationMotDePasseRepository liens;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final RecoveryProperties properties;
    private final Clock clock;
    private final SecureRandom aleatoire = new SecureRandom();
    private static final String LIEN_INVALIDE = "Le lien est invalide ou expiré. Demandez un nouveau lien.";

    public OperationsReinitialisationMotDePasse(EntityManager entityManager, ReinitialisationMotDePasseRepository liens,
                                               PasswordEncoder encoder, Validator validator, RecoveryProperties properties,
                                               @Qualifier("recoveryClock") Clock clock) {
        this.entityManager = entityManager;
        this.liens = liens;
        this.encoder = encoder;
        this.validator = validator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Optional<LienEmis> emettre(String email) {
        if (!properties.isEnabled()) return Optional.empty();
        Long id = entityManager.createQuery("select u.id from Utilisateur u where u.email = :email", Long.class)
                .setParameter("email", Emails.normaliser(email)).getResultList().stream().findFirst().orElse(null);
        if (id == null) return Optional.empty();
        Utilisateur compte = verrouillerCompte(id);
        if (!eligible(compte) || compte.getMotDePasse() == null) return Optional.empty();
        byte[] octets = new byte[32]; aleatoire.nextBytes(octets);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        ReinitialisationMotDePasse lien = entityManager.find(ReinitialisationMotDePasse.class, id, LockModeType.PESSIMISTIC_WRITE);
        boolean nouveau = lien == null;
        if (lien == null) {
            lien = new ReinitialisationMotDePasse();
            lien.setUtilisateur(compte);
            lien.setUtilisateurId(id);
        } else entityManager.refresh(lien, LockModeType.PESSIMISTIC_WRITE);
        lien.setEmpreinteJeton(EmpreintesRecuperation.calculer(jeton));
        lien.setEmpreinteMotDePasse(EmpreintesRecuperation.calculer(compte.getMotDePasse()));
        lien.setCreeLe(maintenant());
        lien.setExpireLe(maintenant().plus(properties.getTtl()));
        if (nouveau) entityManager.persist(lien);
        entityManager.flush();
        return Optional.of(new LienEmis(compte.getEmail(), jeton));
    }

    public boolean lienValide(String jeton) {
        if (!properties.isEnabled() || !formatJetonValide(jeton)) return false;
        ReinitialisationMotDePasse lien = liens.findByEmpreinteJeton(EmpreintesRecuperation.calculer(jeton)).orElse(null);
        return utilisable(lien, lien == null ? null : lien.getUtilisateur(), jeton);
    }

    @Transactional
    public String reinitialiser(ReinitialisationMotDePasseForm formulaire) {
        if (!properties.isEnabled() || formulaire == null || !formatJetonValide(formulaire.getJeton())) throw lienInvalide();
        verifierFormulaire(formulaire);
        String empreinte = EmpreintesRecuperation.calculer(formulaire.getJeton());
        // Cette projection ne charge pas de copie JPA avant les verrous et ne verrouille pas le jeton avant l'utilisateur.
        Long id = entityManager.createQuery("select r.utilisateurId from ReinitialisationMotDePasse r where r.empreinteJeton = :empreinte", Long.class)
                .setParameter("empreinte", empreinte).getResultList().stream().findFirst().orElse(null);
        if (id == null) throw lienInvalide();
        Utilisateur compte = verrouillerCompte(id);
        ReinitialisationMotDePasse lien = entityManager.find(ReinitialisationMotDePasse.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (lien != null) entityManager.refresh(lien, LockModeType.PESSIMISTIC_WRITE);
        if (!utilisable(lien, compte, formulaire.getJeton())) throw lienInvalide();
        if (encoder.matches(formulaire.getNouveauMotDePasse(), compte.getMotDePasse())) {
            throw new RecuperationMotDePasseException("nouveauMotDePasse", "Choisissez un mot de passe différent du mot de passe actuel.");
        }
        compte.setMotDePasse(encoder.encode(formulaire.getNouveauMotDePasse()));
        entityManager.remove(lien);
        entityManager.flush();
        return compte.getEmail();
    }

    private Utilisateur verrouillerCompte(Long id) {
        Utilisateur compte = entityManager.find(Utilisateur.class, id, LockModeType.PESSIMISTIC_WRITE);
        if (compte == null) return null;
        entityManager.refresh(compte, LockModeType.PESSIMISTIC_WRITE);
        if (compte.getBoutique() != null) {
            Boutique boutique = entityManager.find(Boutique.class, compte.getBoutique().getId(), LockModeType.PESSIMISTIC_READ);
            if (boutique != null) entityManager.refresh(boutique, LockModeType.PESSIMISTIC_READ);
        }
        return compte;
    }

    private boolean utilisable(ReinitialisationMotDePasse lien, Utilisateur compte, String jeton) {
        return lien != null && eligible(compte) && compte.getMotDePasse() != null
                && lien.getExpireLe().isAfter(maintenant())
                && EmpreintesRecuperation.identiques(lien.getEmpreinteJeton(), EmpreintesRecuperation.calculer(jeton))
                && EmpreintesRecuperation.identiques(lien.getEmpreinteMotDePasse(), EmpreintesRecuperation.calculer(compte.getMotDePasse()));
    }

    private static boolean eligible(Utilisateur compte) {
        return compte != null && compte.isActif() && compte.getRole() != null && compte.getBoutique() != null
                && (compte.getBoutique().isActif() || compte.isAdministrateurPlateforme() && compte.getRole() == Role.ADMIN);
    }

    private void verifierFormulaire(ReinitialisationMotDePasseForm formulaire) {
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) {
            var erreur = erreurs.stream().min(Comparator.comparing(e -> e.getPropertyPath().toString())).orElseThrow();
            throw new RecuperationMotDePasseException(erreur.getPropertyPath().toString(), erreur.getMessage());
        }
        if (!formulaire.getNouveauMotDePasse().equals(formulaire.getConfirmationMotDePasse())) {
            throw new RecuperationMotDePasseException("confirmationMotDePasse", "Les deux nouveaux mots de passe doivent être identiques.");
        }
    }

    static boolean formatJetonValide(String jeton) { return jeton != null && jeton.matches("[A-Za-z0-9_-]{43}"); }
    private LocalDateTime maintenant() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    private static RecuperationMotDePasseException lienInvalide() { return new RecuperationMotDePasseException(null, LIEN_INVALIDE); }

    public record LienEmis(String email, String jeton) {
        @Override public String toString() { return "LienEmis[confidentiel]"; }
    }
}
