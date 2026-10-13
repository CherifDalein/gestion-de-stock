package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.repository.UtilisateurRepository;
import org.example.stock.service.EssaiBoutiqueService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class AccesEssaiController {
    private final UtilisateurRepository utilisateurs;
    private final EssaiBoutiqueService essais;

    public AccesEssaiController(UtilisateurRepository utilisateurs, EssaiBoutiqueService essais) {
        this.utilisateurs = utilisateurs; this.essais = essais;
    }

    @GetMapping("/acces-boutique")
    public String afficher(Authentication auth, Model model, HttpServletResponse response) {
        var compte = utilisateurs.findByEmail(auth.getName()).orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
        var boutique = compte.getBoutique();
        if (!essais.estExpire(boutique)) return "redirect:/";
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("nomBoutique", boutique.getNom());
        model.addAttribute("finEssai", boutique.getEssaiFin());
        return "compte/essai-termine";
    }
}
