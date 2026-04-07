package org.example.stock.controller;

import org.example.stock.service.UtilisateurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class AuthController {

    @Autowired
    private UtilisateurService utilisateurService;

    @GetMapping("/register")
    public String showRegisterForm() {
        return "register";
    }

    @PostMapping("/register")
    public String registerUser(
            @RequestParam String nom,
            @RequestParam String email,
            @RequestParam String password,
            Model model
    ) {
        try {
            utilisateurService.registerUtilisateur(nom, email, password);
            model.addAttribute("success", "Compte crÃ©Ã© avec succÃ¨s !");
        } catch (Exception e) {
            model.addAttribute("error", "Erreur : " + e.getMessage());
        }
        return "register";
    }

    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }
}
