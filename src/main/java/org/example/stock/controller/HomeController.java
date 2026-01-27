package org.example.stock.controller;

import org.example.stock.repository.UtilisateurRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @Autowired UtilisateurRepository utilisateurRepository;

    @GetMapping("/")
    public String dashboard(Model model, Authentication authentication) {
        model.addAttribute("pageTitle", "Tableau de Bord - Stock Pro");
        return "dashboard";
    }

}
