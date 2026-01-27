package org.example.stock.controller;

import jakarta.validation.Valid;
import org.example.stock.model.Fournisseur;
import org.example.stock.service.FournisseurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

@Controller
@RequestMapping("/fournisseurs")
public class FournisseurController {
    @Autowired
    private FournisseurService fournisseurService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("view", "fournisseurs/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String formulaire(Model model) {
        model.addAttribute("fournisseur", new Fournisseur());
        model.addAttribute("view", "fournisseurs/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@Valid @ModelAttribute("fournisseur") Fournisseur fournisseur, BindingResult result, Model model) {
        if (result.hasErrors()){
            model.addAttribute("view", "fournisseurs/nouveau");
            return "dashboard";
        }
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
        model.addAttribute("view", "fournisseurs/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifierFournisseur(@PathVariable Long id, @ModelAttribute("fournisseur") Fournisseur fournisseur) {
        fournisseur.setId(id); // Sécurité
        fournisseurService.enregistrer(fournisseur);
        return "redirect:/fournisseurs";
    }
}