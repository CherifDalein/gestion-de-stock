package org.example.stock.controller;

import org.example.stock.model.Client;
import org.example.stock.service.ClientService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/clients")
public class ClientController {
    @Autowired
    private ClientService clientService;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("view", "clients/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String formulaire(Model model) {
        model.addAttribute("client", new Client());
        model.addAttribute("view", "clients/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("client") Client client) {
        clientService.enregistrerClient(client);
        return "redirect:/clients";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModif(@PathVariable Long id, Model model) {
        model.addAttribute("client", clientService.trouverParId(id));
        model.addAttribute("view", "clients/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifier(@PathVariable Long id, @ModelAttribute("client") Client client) {
        client.setId(id);
        clientService.enregistrerClient(client);
        return "redirect:/clients";
    }

    @GetMapping("/supprimer/{id}")
    public String supprimer(@PathVariable Long id) {
        clientService.supprimerClient(id);
        return "redirect:/clients";
    }
}