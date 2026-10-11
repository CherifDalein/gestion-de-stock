package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
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
        if ("/login".equals(request.getServletPath()) || "/error".equals(request.getServletPath())) return;
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
                Utilisateur utilisateur = utilisateurRepository.findByEmail(authentication.getName())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible"));
                if (utilisateur.getBoutique() == null || utilisateur.getBoutique().getId() == null) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Boutique inaccessible");
                }
                model.addAttribute("utilisateur", utilisateur);
                model.addAttribute("boutique", utilisateur.getBoutique());
        }
    }
}
