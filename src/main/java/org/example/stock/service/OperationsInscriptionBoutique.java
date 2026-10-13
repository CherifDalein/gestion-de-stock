package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.config.RecoveryProperties;
import org.example.stock.config.SignupProperties;
import org.example.stock.enums.Role;
import org.example.stock.form.ConfirmationInscriptionForm;
import org.example.stock.form.DemandeInscriptionForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.InscriptionBoutique;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.InscriptionBoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.Optional;

/** L'émission et la consommation restent transactionnelles ; aucun SMTP sous un verrou SQL. */
@Service
@Transactional(readOnly = true)
public class OperationsInscriptionBoutique {
    private final EntityManager entityManager;
    private final InscriptionBoutiqueRepository inscriptions;
    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder encoder;
    private final Validator validator;
    private final RecoveryProperties messagerie;
    private final SignupProperties properties;
    private final EssaiBoutiqueService essais;
    private final Clock clock;
    private final SecureRandom aleatoire = new SecureRandom();
    static final String LIEN_INVALIDE = "Le lien est invalide ou expiré. Demandez un nouveau lien d'inscription.";

    public OperationsInscriptionBoutique(EntityManager entityManager, InscriptionBoutiqueRepository inscriptions,
                                        UtilisateurRepository utilisateurs, PasswordEncoder encoder, Validator validator,
                                        RecoveryProperties messagerie, SignupProperties properties, EssaiBoutiqueService essais,
                                        @Qualifier("recoveryClock") Clock clock) {
        this.entityManager = entityManager;
        this.inscriptions = inscriptions;
        this.utilisateurs = utilisateurs;
        this.encoder = encoder;
        this.validator = validator;
        this.messagerie = messagerie;
        this.properties = properties;
        this.essais = essais;
        this.clock = clock;
    }

    @Transactional
    public Optional<LienEmis> emettre(String email) {
        if (!messagerie.isEnabled()) return Optional.empty();
        DemandeInscriptionForm demande = new DemandeInscriptionForm();
        demande.setEmail(email);
        if (!validator.validate(demande).isEmpty() || utilisateurs.existsByEmail(demande.getEmail())) return Optional.empty();
        LocalDateTime maintenant = maintenant();
        // Les demandes expirées ne créent aucune donnée métier et ne sont pas conservées indéfiniment.
        entityManager.createQuery("delete from InscriptionBoutique i where i.expireLe <= :maintenant")
                .setParameter("maintenant", maintenant).executeUpdate();
        InscriptionBoutique inscription = entityManager.find(InscriptionBoutique.class, demande.getEmail(), LockModeType.PESSIMISTIC_WRITE);
        boolean nouvelle = inscription == null;
        if (nouvelle && inscriptions.count() >= 10_000) return Optional.empty();
        if (nouvelle) {
            inscription = new InscriptionBoutique();
            inscription.setEmail(demande.getEmail());
        } else entityManager.refresh(inscription, LockModeType.PESSIMISTIC_WRITE);
        // Une confirmation concurrente peut avoir créé le compte pendant l'attente du verrou.
        if (utilisateurs.existsByEmail(demande.getEmail())) return Optional.empty();
        byte[] octets = new byte[32]; aleatoire.nextBytes(octets);
        String jeton = Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
        inscription.setEmpreinteJeton(EmpreintesRecuperation.calculer(jeton));
        inscription.setCreeLe(maintenant);
        inscription.setExpireLe(maintenant.plus(properties.getTtl()));
        if (nouvelle) entityManager.persist(inscription);
        entityManager.flush();
        return Optional.of(new LienEmis(demande.getEmail(), jeton));
    }

    /** La lecture n'active rien et ne consomme aucun jeton, y compris par les scanners d'emails. */
    public Optional<String> emailPourLien(String jeton) {
        if (!messagerie.isEnabled() || !formatJetonValide(jeton)) return Optional.empty();
        InscriptionBoutique inscription = inscriptions.findByEmpreinteJeton(EmpreintesRecuperation.calculer(jeton)).orElse(null);
        return utilisable(inscription, jeton) ? Optional.of(inscription.getEmail()) : Optional.empty();
    }

    @Transactional
    public void confirmer(ConfirmationInscriptionForm formulaire) {
        if (!messagerie.isEnabled() || formulaire == null || !formatJetonValide(formulaire.getJeton())) throw lienInvalide();
        verifierFormulaire(formulaire);
        // Projection seule : aucune copie du jeton avant le verrou et son rafraîchissement.
        String email = entityManager.createQuery("select i.email from InscriptionBoutique i where i.empreinteJeton = :empreinte", String.class)
                .setParameter("empreinte", EmpreintesRecuperation.calculer(formulaire.getJeton()))
                .getResultList().stream().findFirst().orElse(null);
        if (email == null) throw lienInvalide();
        InscriptionBoutique inscription = entityManager.find(InscriptionBoutique.class, email, LockModeType.PESSIMISTIC_WRITE);
        if (inscription != null) entityManager.refresh(inscription, LockModeType.PESSIMISTIC_WRITE);
        if (!utilisable(inscription, formulaire.getJeton())) throw lienInvalide();
        String hash = encoder.encode(formulaire.getNouveauMotDePasse());
        Boutique boutique = new Boutique();
        boutique.setNom(formulaire.getNomBoutique());
        boutique.setActif(true);
        boutique.setAccesOffert(false);
        boutique.setDateCreation(maintenant());
        essais.demarrerEssai(boutique);
        entityManager.persist(boutique);
        Utilisateur gerant = new Utilisateur();
        gerant.setNom(formulaire.getNom());
        gerant.setEmail(inscription.getEmail());
        gerant.setMotDePasse(hash);
        gerant.setBoutique(boutique);
        gerant.setRole(Role.ADMIN);
        gerant.setAdministrateurPlateforme(false);
        gerant.setActif(true);
        gerant.setDateInscription(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        entityManager.persist(gerant);
        entityManager.remove(inscription);
        // L'unicité globale de l'email tranche aussi une création concurrente par un gérant/la plateforme.
        entityManager.flush();
    }

    private boolean utilisable(InscriptionBoutique inscription, String jeton) {
        return inscription != null && inscription.getExpireLe().isAfter(maintenant())
                && EmpreintesRecuperation.identiques(inscription.getEmpreinteJeton(), EmpreintesRecuperation.calculer(jeton))
                && !utilisateurs.existsByEmail(inscription.getEmail());
    }

    private void verifierFormulaire(ConfirmationInscriptionForm formulaire) {
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) {
            var erreur = erreurs.stream().min(Comparator.comparing(e -> e.getPropertyPath().toString())).orElseThrow();
            throw new InscriptionBoutiqueException(erreur.getPropertyPath().toString(), erreur.getMessage());
        }
        if (!formulaire.getNouveauMotDePasse().equals(formulaire.getConfirmationMotDePasse())) {
            throw new InscriptionBoutiqueException("confirmationMotDePasse", "Les deux mots de passe doivent être identiques.");
        }
    }

    static boolean formatJetonValide(String jeton) { return jeton != null && jeton.matches("[A-Za-z0-9_-]{43}"); }
    private LocalDateTime maintenant() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC); }
    static InscriptionBoutiqueException lienInvalide() { return new InscriptionBoutiqueException(null, LIEN_INVALIDE); }

    public record LienEmis(String email, String jeton) {
        @Override public String toString() { return "LienEmis[confidentiel]"; }
    }
}
