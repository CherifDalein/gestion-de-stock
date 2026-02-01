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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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
    public String enregistrer(@ModelAttribute("achat") Achat achat) {
        service.enregistrerAchat(achat);
        return "redirect:/achats";
    }



}
