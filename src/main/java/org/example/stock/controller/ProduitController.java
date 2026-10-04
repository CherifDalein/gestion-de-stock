package org.example.stock.controller;

import jakarta.validation.Valid;
import org.example.stock.model.Produit;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.UtilisateurService;
import org.example.stock.service.ProduitModifieException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.web.server.ResponseStatusException;
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
    @Autowired private UtilisateurService utilisateurService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("view", "produits/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String afficherFormulaire(Model model) {
        model.addAttribute("produit", new Produit());
        model.addAttribute("categories", categorieService.listerToutes());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());

        model.addAttribute("view", "produits/nouveau");
        return "dashboard";
    }

    @PostMapping("/ajouter")
    public String nouveauProduit(@Valid @ModelAttribute("produit") Produit produit,
                                 BindingResult result,
                                 Model model) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) {
            model.addAttribute("categories", categorieService.listerToutes());
            model.addAttribute("fournisseurs", fournisseurService.listerTous());
            model.addAttribute("view", "produits/nouveau");
            return "dashboard";
        }
        produitService.ajouterProduit(produit);
        return "redirect:/produits";
    }

    @PostMapping("/supprimer/{id}")
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
        model.addAttribute("view", "produits/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifierProduit(@PathVariable Long id, @Valid @ModelAttribute("produit") Produit produit, BindingResult result, Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        produit.setId(id);
        if (produit.getVersion() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Version du produit obligatoire");
        }
        if (!result.hasErrors()) {
            try {
                produitService.modifierProduit(id, produit);
                return "redirect:/produits";
            } catch (ProduitModifieException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("produit.modifie", e instanceof ProduitModifieException ? e.getMessage()
                        : "Une autre opération modifie ce produit. Rechargez la fiche et réessayez.");
            }
        }
        model.addAttribute("categories", categorieService.listerToutes());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("view", "produits/modifier");
        return "dashboard";
    }
}
