package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.validation.Validator;
import org.example.stock.enums.Role;
import org.example.stock.form.ParametresBoutiqueForm;
import org.example.stock.model.Boutique;
import org.example.stock.model.Emails;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Le gérant modifie uniquement les coordonnées de la boutique de son compte. */
@Service
@Transactional(readOnly = true)
public class ParametresBoutiqueService {
    private final UtilisateurRepository utilisateurs;
    private final BoutiqueService boutiques;
    private final EntityManager entityManager;
    private final Validator validator;

    public ParametresBoutiqueService(UtilisateurRepository utilisateurs, BoutiqueService boutiques,
                                    EntityManager entityManager, Validator validator) {
        this.utilisateurs = utilisateurs;
        this.boutiques = boutiques;
        this.entityManager = entityManager;
        this.validator = validator;
    }

    public void verifierGestionBoutiqueCourante() {
        exigerGerant();
    }

    public ParametresBoutiqueForm chargerPourBoutiqueCourante() {
        Boutique boutique = exigerGerant().getBoutique();
        ParametresBoutiqueForm formulaire = new ParametresBoutiqueForm();
        formulaire.setNom(boutique.getNom());
        formulaire.setAdresse(boutique.getAdresse());
        formulaire.setTelephone(boutique.getTelephone());
        formulaire.setEmail(boutique.getEmail());
        return formulaire;
    }

    @Transactional
    public void modifierPourBoutiqueCourante(ParametresBoutiqueForm formulaire) {
        Utilisateur gerant = exigerGerant();
        if (formulaire == null || !validator.validate(formulaire).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Les coordonnées de la boutique sont invalides");
        }
        Long boutiqueId = gerant.getBoutique().getId();
        // Le compte est relu sous verrou : une session ancienne ne conserve pas des droits révoqués en base.
        gerant = entityManager.createQuery("select u from Utilisateur u where u.id = :id and u.boutique.id = :boutiqueId", Utilisateur.class)
                .setParameter("id", gerant.getId()).setParameter("boutiqueId", boutiqueId)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst()
                .orElseThrow(ParametresBoutiqueService::interdit);
        entityManager.refresh(gerant, LockModeType.PESSIMISTIC_WRITE);
        if (gerant.getBoutique() == null || !boutiqueId.equals(gerant.getBoutique().getId())) throw interdit();
        exigerGerant();

        Boutique boutique = entityManager.find(Boutique.class, boutiqueId, LockModeType.PESSIMISTIC_WRITE);
        if (boutique == null) throw interdit();
        // Conserver aussi les modifications de statut ou de coordonnées déjà validées dans une autre transaction.
        entityManager.refresh(boutique, LockModeType.PESSIMISTIC_WRITE);
        boutiques.exigerAppartenance(boutique);
        boutique.setNom(formulaire.getNom());
        boutique.setAdresse(formulaire.getAdresse());
        boutique.setTelephone(formulaire.getTelephone());
        boutique.setEmail(formulaire.getEmail());
    }

    private Utilisateur exigerGerant() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getAuthorities().stream()
                .noneMatch(autorite -> autorite.getAuthority().equals("ROLE_ADMIN"))) throw interdit();
        Utilisateur compte = utilisateurs.findByEmail(Emails.normaliser(authentication.getName()))
                .orElseThrow(ParametresBoutiqueService::interdit);
        if (compte.getRole() != Role.ADMIN || !compte.isActif()) throw interdit();
        boutiques.exigerAppartenance(compte.getBoutique());
        return compte;
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Paramètres de boutique inaccessibles");
    }
}
