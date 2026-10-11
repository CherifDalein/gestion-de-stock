package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
import org.example.stock.enums.Role;
import org.example.stock.repository.UtilisateurRepository;
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

    @ModelAttribute
    public void addAttributes(Model model, Authentication authentication, HttpServletRequest request) {
        String chemin = request.getServletPath();
        if (chemin == null || chemin.isEmpty()) {
            chemin = request.getRequestURI().substring(request.getContextPath().length());
        }
        if ("/login".equals(chemin) || "/error".equals(chemin)) return;
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Utilisateur utilisateur = utilisateurRepository.findByEmail(authentication.getName())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible"));
            if (utilisateur.getBoutique() == null || utilisateur.getBoutique().getId() == null) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
            }
            boolean plateforme = "/plateforme".equals(chemin) || chemin.startsWith("/plateforme/");
            if (plateforme && (!utilisateur.isAdministrateurPlateforme() || utilisateur.getRole() != Role.ADMIN
                    || authentication.getAuthorities().stream()
                    .noneMatch(authority -> "ROLE_PLATEFORME_ADMIN".equals(authority.getAuthority())))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administration inaccessible");
            }
            if (!plateforme && !utilisateur.getBoutique().isActif()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "L'accès à cette boutique est suspendu");
            }
            model.addAttribute("utilisateur", utilisateur);
            model.addAttribute("boutique", utilisateur.getBoutique());
        }
    }
}
