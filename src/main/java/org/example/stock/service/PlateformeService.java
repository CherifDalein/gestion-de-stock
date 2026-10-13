package org.example.stock.service;

import jakarta.validation.Validator;
import org.example.stock.enums.Role;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.CreationBoutiqueForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.BoutiqueRepository;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Administration transversale réservée à un administrateur de plateforme encore autorisé en base. */
@Service
@Transactional(readOnly = true)
public class PlateformeService {
    private final BoutiqueRepository boutiques;
    private final UtilisateurRepository utilisateurs;
    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final GestionAbonnementService abonnements;

    public PlateformeService(BoutiqueRepository boutiques, UtilisateurRepository utilisateurs,
                            PasswordEncoder passwordEncoder, Validator validator, GestionAbonnementService abonnements) {
        this.boutiques = boutiques;
        this.utilisateurs = utilisateurs;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.abonnements = abonnements;
    }

    public void verifierAdministrationPlateforme() {
        exigerAdministrationPlateforme();
    }

    public List<Boutique> listerBoutiques() {
        exigerAdministrationPlateforme();
        return boutiques.findAllByOrderByDateCreationDescIdDesc();
    }

    public Map<Long, Long> compterUtilisateursParBoutique() {
        exigerAdministrationPlateforme();
        Map<Long, Long> nombres = new LinkedHashMap<>();
        for (Boutique boutique : boutiques.findAllByOrderByDateCreationDescIdDesc()) nombres.put(boutique.getId(), 0L);
        for (var compte : utilisateurs.compterParBoutique()) nombres.put(compte.getBoutiqueId(), compte.getNombre());
        return nombres;
    }

    public Boutique trouverBoutique(Long id) {
        exigerAdministrationPlateforme();
        return chargerBoutique(id);
    }

    public List<Utilisateur> listerUtilisateurs(Long boutiqueId) {
        exigerAdministrationPlateforme();
        chargerBoutique(boutiqueId);
        return utilisateurs.findAllByBoutiqueIdOrderByNomAscIdAsc(boutiqueId).stream()
                .map(PlateformeService::compteSansMotDePasse).toList();
    }

    @Transactional
    public Boutique creerBoutique(CreationBoutiqueForm formulaire) {
        exigerAdministrationPlateforme();
        if (formulaire == null) throw new IllegalArgumentException("Le formulaire de création est obligatoire.");
        var erreurs = validator.validate(formulaire);
        if (!erreurs.isEmpty()) throw new IllegalArgumentException(erreurs.stream()
                .map(erreur -> erreur.getMessage()).sorted().findFirst().orElseThrow());
        if (utilisateurs.existsByEmail(formulaire.getEmail())) throw new EmailDejaUtiliseException();

        String motDePasseEncode = passwordEncoder.encode(formulaire.getPassword());
        Boutique boutique = new Boutique();
        boutique.setNom(formulaire.getNomBoutique());
        boutique.setActif(true);
        boutique.setAccesOffert(formulaire.isAccesOffert());
        boutique = boutiques.save(boutique);

        Utilisateur gerant = new Utilisateur();
        gerant.setNom(formulaire.getNom());
        gerant.setEmail(formulaire.getEmail());
        gerant.setMotDePasse(motDePasseEncode);
        gerant.setRole(Role.ADMIN);
        gerant.setAdministrateurPlateforme(false);
        gerant.setDateInscription(LocalDate.now());
        gerant.setBoutique(boutique);
        try {
            utilisateurs.saveAndFlush(gerant);
        } catch (DataIntegrityViolationException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                        && violation.getConstraintName() != null
                        && violation.getConstraintName().toLowerCase(Locale.ROOT).contains("uk_utilisateur_email")) {
                    throw new EmailDejaUtiliseException();
                }
            }
            throw e;
        }
        return boutique;
    }

    @Transactional
    public Boutique modifierAcces(Long id, AccesBoutiqueForm formulaire) {
        return abonnements.modifierAcces(id, formulaire);
    }

    private void exigerAdministrationPlateforme() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getAuthorities().stream()
                .noneMatch(autorite -> autorite.getAuthority().equals("ROLE_PLATEFORME_ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration de plateforme inaccessible");
        }
        Utilisateur compte = utilisateurs.findByEmail(Emails.normaliser(authentication.getName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration de plateforme inaccessible"));
        if (!compte.isActif() || !compte.isAdministrateurPlateforme() || compte.getRole() != Role.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration de plateforme inaccessible");
        }
    }

    private Boutique chargerBoutique(Long id) {
        if (id == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Boutique introuvable");
        return boutiques.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Boutique introuvable"));
    }

    private static Utilisateur compteSansMotDePasse(Utilisateur original) {
        Utilisateur copie = new Utilisateur();
        copie.setId(original.getId());
        copie.setNom(original.getNom());
        copie.setEmail(original.getEmail());
        copie.setDateInscription(original.getDateInscription());
        copie.setRole(original.getRole());
        copie.setAdministrateurPlateforme(original.isAdministrateurPlateforme());
        copie.setActif(original.isActif());
        copie.setBoutique(original.getBoutique());
        return copie;
    }
}
