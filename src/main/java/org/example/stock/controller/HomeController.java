package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @Autowired UtilisateurRepository utilisateurRepository;

    @GetMapping("/")
    public String dashboard(Model model, Authentication authentication) {
        if (authentication != null && authentication.isAuthenticated()) {
            String email = ((UserDetails) authentication.getPrincipal()).getUsername();
            Utilisateur utilisateur = utilisateurRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
            model.addAttribute("utilisateur", utilisateur);
        }
        return "dashboard";
    }

}
