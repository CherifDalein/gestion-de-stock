package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
import org.example.stock.enums.Role;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.EssaiBoutiqueService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalControllerAdvice {

    @Autowired
    private UtilisateurRepository utilisateurRepository;

    @Autowired
    private EssaiBoutiqueService essais;

    @ModelAttribute
    public void addAttributes(Model model, Authentication authentication, HttpServletRequest request) {
        String chemin = request.getServletPath();
        if (chemin == null || chemin.isEmpty()) {
            chemin = request.getRequestURI().substring(request.getContextPath().length());
        }
        if ("/login".equals(chemin) || "/error".equals(chemin)
                || "/mot-de-passe-oublie".equals(chemin) || "/reinitialiser-mot-de-passe".equals(chemin)) return;
        if ("/accueil".equals(chemin) || "/inscription".equals(chemin) || "/inscription/confirmer".equals(chemin)) return;
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Utilisateur utilisateur = utilisateurRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible"));
            if (!utilisateur.isActif()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à ce compte est désactivé");
            }
            if (utilisateur.getBoutique() == null || utilisateur.getBoutique().getId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
            }
            boolean plateforme = "/plateforme".equals(chemin) || chemin.startsWith("/plateforme/");
            boolean droitPlateforme = utilisateur.isAdministrateurPlateforme() && utilisateur.getRole() == Role.ADMIN
                    && authentication.getAuthorities().stream()
                    .anyMatch(authority -> "ROLE_PLATEFORME_ADMIN".equals(authority.getAuthority()));
            if (plateforme && !droitPlateforme) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration inaccessible");
            }
            boolean comptePersonnel = "/mon-compte".equals(chemin) || chemin.startsWith("/mon-compte/");
            boolean consultationAbonnement = "/mon-abonnement".equals(chemin) && utilisateur.getRole() == Role.ADMIN;
            if (!plateforme && !consultationAbonnement && !(comptePersonnel && droitPlateforme) && !utilisateur.getBoutique().isActif()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à cette boutique est suspendu");
            }
            model.addAttribute("utilisateur", utilisateur);
            model.addAttribute("boutique", utilisateur.getBoutique());
            if (utilisateur.getBoutique().getEssaiFin() != null && !utilisateur.getBoutique().isAccesOffert()) {
                model.addAttribute("essaiEnCours", essais.joursRestants(utilisateur.getBoutique()) > 0);
                model.addAttribute("joursEssaiRestants", essais.joursRestants(utilisateur.getBoutique()));
            }
        }
    }
}
