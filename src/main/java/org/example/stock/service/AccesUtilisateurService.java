package org.example.stock.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.example.stock.enums.Role;
import org.example.stock.form.AccesUtilisateurForm;
import org.example.stock.model.Emails;
import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.TreeSet;

/** Le statut change sous verrou ; les rôles, secrets et rattachements du compte restent ceux de la base. */
@Service
@Transactional(readOnly = true)
public class AccesUtilisateurService {
    private final UtilisateurRepository utilisateurs;
    private final BoutiqueService boutiques;
    private final PlateformeService plateforme;
    private final EntityManager entityManager;

    public AccesUtilisateurService(UtilisateurRepository utilisateurs, BoutiqueService boutiques,
                                   PlateformeService plateforme, EntityManager entityManager) {
        this.utilisateurs = utilisateurs;
        this.boutiques = boutiques;
        this.plateforme = plateforme;
        this.entityManager = entityManager;
    }

    public void verifierGestionBoutiqueCourante() {
        exigerGerant();
    }

    public List<Utilisateur> listerPourBoutiqueCourante() {
        Utilisateur gerant = exigerGerant();
        return utilisateurs.findAllByBoutiqueIdOrderByNomAscIdAsc(gerant.getBoutique().getId()).stream()
                .map(AccesUtilisateurService::sansMotDePasse).toList();
    }

    @Transactional
    public void modifierPourBoutiqueCourante(Long utilisateurId, AccesUtilisateurForm formulaire) {
        Utilisateur gerant = exigerGerant();
        boolean actif = statutValide(formulaire);
        Long boutiqueId = gerant.getBoutique().getId();
        Map<Long, Utilisateur> comptes = verrouillerActeurEtCible(gerant, utilisateurId, boutiqueId);
        // Les droits sont relus après l'attente des deux verrous, avec l'acteur lui-même rafraîchi.
        gerant = exigerGerant();
        Utilisateur cible = comptes.get(utilisateurId);
        boutiques.exigerAppartenance(cible.getBoutique());
        verifierAutoDesactivation(gerant, cible, actif);
        if (cible.isAdministrateurPlateforme()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Ce compte relève de l'administration de plateforme");
        }
        cible.setActif(actif);
    }

    @Transactional
    public void modifierPourPlateforme(Long boutiqueId, Long utilisateurId, AccesUtilisateurForm formulaire) {
        plateforme.verifierAdministrationPlateforme();
        Utilisateur administrateur = compteConnecte();
        boolean actif = statutValide(formulaire);
        Map<Long, Utilisateur> comptes = verrouillerActeurEtCible(administrateur, utilisateurId, boutiqueId);
        plateforme.verifierAdministrationPlateforme();
        administrateur = compteConnecte();
        Utilisateur cible = comptes.get(utilisateurId);
        if (cible.getBoutique() == null || !boutiqueId.equals(cible.getBoutique().getId())) throw introuvable();
        verifierAutoDesactivation(administrateur, cible, actif);
        cible.setActif(actif);
    }

    private Utilisateur exigerGerant() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getAuthorities().stream()
                .noneMatch(autorite -> autorite.getAuthority().equals("ROLE_ADMIN"))) throw interdit();
        Utilisateur compte = compteConnecte();
        if (compte.getRole() != Role.ADMIN || !compte.isActif()) throw interdit();
        boutiques.exigerAppartenance(compte.getBoutique());
        return compte;
    }

    private Utilisateur compteConnecte() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) throw interdit();
        return utilisateurs.findByEmail(Emails.normaliser(authentication.getName())).orElseThrow(AccesUtilisateurService::interdit);
    }

    private Map<Long, Utilisateur> verrouillerActeurEtCible(Utilisateur acteur, Long utilisateurId, Long boutiqueId) {
        if (utilisateurId == null || boutiqueId == null) throw introuvable();
        Long acteurId = acteur.getId();
        Long boutiqueActeurId = acteur.getBoutique().getId();
        Map<Long, Utilisateur> comptes = new LinkedHashMap<>();
        // Le même ordre global empêche les interblocages lorsque deux administrateurs se ciblent mutuellement.
        for (Long id : new TreeSet<>(List.of(acteurId, utilisateurId))) {
            boolean estActeur = id.equals(acteurId);
            Long boutiqueRecherchee = estActeur ? boutiqueActeurId : boutiqueId;
            Utilisateur compte = entityManager.createQuery("select u from Utilisateur u where u.id = :id and u.boutique.id = :boutiqueId", Utilisateur.class)
                    .setParameter("id", id).setParameter("boutiqueId", boutiqueRecherchee)
                    .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList().stream().findFirst()
                    .orElseThrow(() -> estActeur ? interdit() : introuvable());
            // Écarter aussi une version déjà chargée par le rendu ou par une opération précédente dans le contexte JPA.
            entityManager.refresh(compte, LockModeType.PESSIMISTIC_WRITE);
            if (compte.getBoutique() == null || !boutiqueRecherchee.equals(compte.getBoutique().getId())) {
                throw estActeur ? interdit() : introuvable();
            }
            comptes.put(id, compte);
        }
        Utilisateur cible = comptes.get(utilisateurId);
        if (cible.getBoutique() == null || !boutiqueId.equals(cible.getBoutique().getId())) throw introuvable();
        return comptes;
    }

    private static boolean statutValide(AccesUtilisateurForm formulaire) {
        if (formulaire == null || formulaire.getActif() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le statut du compte est obligatoire");
        }
        return formulaire.getActif();
    }

    private static void verifierAutoDesactivation(Utilisateur actuel, Utilisateur cible, boolean actif) {
        if (!actif && actuel.getId().equals(cible.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vous ne pouvez pas désactiver votre propre compte");
        }
    }

    private static Utilisateur sansMotDePasse(Utilisateur original) {
        Utilisateur copie = new Utilisateur();
        copie.setId(original.getId()); copie.setNom(original.getNom()); copie.setEmail(original.getEmail());
        copie.setDateInscription(original.getDateInscription()); copie.setRole(original.getRole());
        copie.setAdministrateurPlateforme(original.isAdministrateurPlateforme()); copie.setActif(original.isActif());
        copie.setBoutique(original.getBoutique());
        return copie;
    }

    private static ResponseStatusException interdit() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "Gestion des utilisateurs inaccessible");
    }

    private static ResponseStatusException introuvable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Utilisateur introuvable");
    }
}
