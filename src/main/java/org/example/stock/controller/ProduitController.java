package org.example.stock.controller;

import jakarta.validation.Valid;
import org.example.stock.model.Categorie;
import org.example.stock.model.Produit;
import org.example.stock.service.FournisseurService;
import org.springframework.ui.Model;
import org.example.stock.service.CategorieService;
import org.example.stock.service.ProduitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/produits")
public class ProduitController {
    @Autowired private ProduitService produitService;
    @Autowired private CategorieService categorieService;
    @Autowired private FournisseurService fournisseurService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("produits", produitService.listerTous());
        return "produits/liste";
    }

    @GetMapping("/nouveau")
    public String afficherFormulaire(Model model) {
        model.addAttribute("produit", new Produit());
        model.addAttribute("categories", categorieService.listerToutes());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        return "produits/nouveau";
    }

    @PostMapping("/ajouter")
    public String nouveauProduit(@Valid @ModelAttribute("produit") Produit produit,
                                 BindingResult result,
                                 Model model) {
        if (result.hasErrors()) {
            model.addAttribute("categories", categorieService.listerToutes());
            return "produits/nouveau";
        }
        produitService.ajouterProduit(produit);
        return "redirect:/produits";
    }

    @GetMapping("/supprimer/{id}")
    public String supprimerProduit(@PathVariable("id") Long id) {
        produitService.supprimerProduit(id);
        return "redirect:/produits";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModif(@PathVariable Long id, Model model) {
        Produit produit = produitService.trouverParId(id);
        model.addAttribute("produit", produit);
        model.addAttribute("categories", categorieService.listerToutes());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        return "produits/modifier";
    }

    @PostMapping("/modifier/{id}")
    public String modifierProduit(@PathVariable Long id, @Valid @ModelAttribute("produit") Produit produit, BindingResult result, Model model) {
        if (result.hasErrors()) {
            model.addAttribute("categories", categorieService.listerToutes());
            return "produits/modifier";
        }
        produit.setId(id);
        produitService.ajouterProduit(produit);
        return "redirect:/produits";
    }
}
