package org.example.stock.controller;

import org.example.stock.model.Vente;
import org.example.stock.service.ClientService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.VenteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ventes")
public class VenteController {

    @Autowired private VenteService venteService;
    @Autowired private ProduitService produitService;
    @Autowired private ClientService clientService;

    @GetMapping
    public String listeVentes(Model model) {
        model.addAttribute("ventes", venteService.listerToutes());
        return "ventes/liste";
    }

    @GetMapping("/nouveau")
    public String nouveauFormulaire(Model model) {
        model.addAttribute("vente", new Vente());
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        return "ventes/nouveau";
    }

    @PostMapping("/enregistrer")
    public String enregistrerVente(@ModelAttribute("vente") Vente vente, RedirectAttributes redirectAttributes) {
        try {
            venteService.effectuerVente(vente);
            redirectAttributes.addFlashAttribute("success", "Vente enregistrée avec succès !");
        } catch (RuntimeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/ventes/nouveau";
        }
        return "redirect:/ventes";
    }
}
