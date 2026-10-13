package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.service.GestionAbonnementService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class MonAbonnementController {
    private final GestionAbonnementService abonnements;

    public MonAbonnementController(GestionAbonnementService abonnements) { this.abonnements = abonnements; }

    @GetMapping("/mon-abonnement")
    public String consulter(@RequestParam(defaultValue = "0") int page, Model model, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("vueAbonnement", abonnements.consulterPourCompteCourant(page));
        model.addAttribute("view", "abonnement/mon-abonnement");
        return "dashboard";
    }
}
