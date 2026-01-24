package org.example.stock.controller;

import jakarta.validation.Valid;
import org.example.stock.model.Fournisseur;
import org.example.stock.service.FournisseurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/fournisseurs")
public class FournisseurController {
    @Autowired
    private FournisseurService fournisseurService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        return "fournisseurs/liste";
    }

    @GetMapping("/nouveau")
    public String formulaire(Model model) {
        model.addAttribute("fournisseur", new Fournisseur());
        return "fournisseurs/nouveau";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@Valid @ModelAttribute("fournisseur") Fournisseur fournisseur, BindingResult result) {
        if (result.hasErrors()) return "fournisseurs/nouveau";
        fournisseurService.enregistrer(fournisseur);
        return "redirect:/fournisseurs";
    }

    @GetMapping("/supprimer/{id}")
    public String supprimerFournisseur(@PathVariable("id") Long id) {
        fournisseurService.supprimerFournisseur(id);
        return "redirect:/fournisseurs";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModif(@PathVariable Long id, Model model) {
        Fournisseur fournisseur = fournisseurService.trouverParId(id);
        model.addAttribute("fournisseur", fournisseur);
        return "fournisseurs/modifier";
    }

    @PostMapping("/modifier/{id}")
    public String modifierFournisseur(@PathVariable Long id, @ModelAttribute("fournisseur") Fournisseur fournisseur) {
        fournisseur.setId(id); // Sécurité
        fournisseurService.enregistrer(fournisseur);
        return "redirect:/fournisseurs";
    }
}