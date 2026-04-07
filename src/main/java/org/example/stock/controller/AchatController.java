package org.example.stock.controller;

import org.example.stock.model.Achat;
import org.example.stock.service.AchatService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.ProduitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;

@Controller
@RequestMapping("/achats")
public class AchatController {
    @Autowired private AchatService service;
    @Autowired private ProduitService produitService;
    @Autowired private FournisseurService fournisseurService;

    @GetMapping
    public String listeVentes(Model model) {
        model.addAttribute("achats", service.listerTous());
        model.addAttribute("view", "achats/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        Achat achat = new Achat();
        achat.setLignes(new ArrayList<>());

        model.addAttribute("achat", achat);
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("view", "achats/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("achat") Achat achat, RedirectAttributes redirectAttributes, Model model) {
        try {
            service.enregistrerAchat(achat);
            redirectAttributes.addFlashAttribute("success", "Achat enregistre avec succes !");
            return "redirect:/achats";
        } catch (RuntimeException e) {
            model.addAttribute("achat", achat);
            model.addAttribute("produits", produitService.listerTous());
            model.addAttribute("fournisseurs", fournisseurService.listerTous());
            model.addAttribute("error", e.getMessage());
            model.addAttribute("view", "achats/nouveau");
            return "dashboard";
        }
    }

    @GetMapping("/modifier/{id}")
    public String afficherModifier(@PathVariable Long id, Model model) {
        Achat achat = service.trouverParId(id);
        if (achat == null) return "redirect:/achats";

        model.addAttribute("achat", achat);
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("view", "achats/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String enregistrerModification(@PathVariable Long id, @ModelAttribute("achat") Achat achat) {
        try {
            service.modifierAchat(id, achat);
            return "redirect:/achats?success=modifie";
        } catch (Exception e) {
            return "redirect:/achats/modifier/" + id + "?error=" + e.getMessage();
        }
    }
}
