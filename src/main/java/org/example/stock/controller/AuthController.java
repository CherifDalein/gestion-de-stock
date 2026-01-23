package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
import org.example.stock.service.UtilisateurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {

    @Autowired
    private UtilisateurService utilisateurService;

    // Page d'inscription
    @GetMapping("/register")
    public String showRegisterForm() {
        return "register";
    }

    // Soumission du formulaire d'inscription
    @PostMapping("/register")
    public String registerUser(
            @RequestParam String nom,
            @RequestParam String email,
            @RequestParam String password,
            Model model
    ) {
        try {
            utilisateurService.registerUtilisateur(nom, email, password);
            model.addAttribute("success", "Compte créé avec succès !");
        } catch (Exception e) {
            model.addAttribute("error", "Erreur : " + e.getMessage());
        }
        return "register";
    }

    // Page de connexion
    @GetMapping("/login")
    public String showLoginForm() {
        return "login";
    }

    // Pour un login manuel si tu veux gérer via un formulaire
    @PostMapping("/login")
    public String loginUser(
            @RequestParam String email,
            @RequestParam String password,
            Model model
    ) {
        try {
            Utilisateur utilisateur = utilisateurService.login(email, password);
            model.addAttribute("success", "Bienvenue " + utilisateur.getNom() + " !");
            return "dashboard"; // ou page principale après login
        } catch (Exception e) {
            model.addAttribute("error", e.getMessage());
            return "login";
        }
    }
}
