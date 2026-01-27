package org.example.stock.controller;

import jakarta.validation.Valid;
import org.example.stock.model.Categorie;
import org.example.stock.service.CategorieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.BindingResult;

@Controller
@RequestMapping("/categories")
public class CategorieController {

    @Autowired
    private CategorieService categorieService;

    @GetMapping
    public String afficherListe(Model model) {
        model.addAttribute("categories", categorieService.listerToutes());
        model.addAttribute("view", "categories/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String afficherFormulaire(Model model) {
        model.addAttribute("nouvelleCategorie", new Categorie());
        model.addAttribute("view", "categories/nouveau");
        return "dashboard";
    }

    @PostMapping("/ajouter")
    public String ajouterCategorie(@Valid @ModelAttribute("nouvelleCategorie") Categorie categorie,
                                   BindingResult result,
                                   Model model) {

        if (categorieService.existeDeja(categorie.getNom())) {
            result.rejectValue("nom", "error.categorie", "Cette catégorie existe déjà.");
        }

        if (result.hasErrors()) {
            model.addAttribute("view", "categories/nouveau");
            return "dashboard";
        }

        categorieService.ajouterCategorie(categorie);
        return "redirect:/categories";
    }

    @GetMapping("/supprimer/{id}")
    public String supprimerCategorie(@PathVariable("id") Long id) {
        categorieService.supprimer(id);
        return "redirect:/categories";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModification(@PathVariable Long id, Model model) {
        Categorie categorie = categorieService.trouverParId(id);
        if (categorie == null) {
            return "redirect:/categories";
        }
        model.addAttribute("categorie", categorie);
        model.addAttribute("view", "categories/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifierCategorie(@PathVariable Long id,
                                    @Valid @ModelAttribute("categorie") Categorie categorie,
                                    BindingResult result,
                                    Model model) {
        if (result.hasErrors()) {
            model.addAttribute("view", "categories/modifier");
            return "dashboard";
        }

        categorie.setId(id);
        categorieService.ajouterCategorie(categorie);
        return "redirect:/categories";
    }
}