package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.enums.SensSolde;
import org.example.stock.model.Client;
import org.example.stock.service.ClientService;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.SoldePartenaireService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/clients")
public class ClientController {
    @Autowired
    private ClientService clientService;

    @Autowired
    private SoldePartenaireService soldes;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("soldes", soldes.soldesClients());
        model.addAttribute("view", "clients/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String formulaire(Model model) {
        Client client = new Client();
        client.setSensSoldeDepart(SensSolde.IL_ME_DOIT);
        model.addAttribute("client", client);
        model.addAttribute("view", "clients/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("client") Client client, BindingResult result, HttpServletRequest request,
                              Model model, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        client.setSoldeDepartSoumis(FormBindingAdvice.soldeDepartSoumis(request));
        try {
            clientService.enregistrerClient(client);
        } catch (IllegalArgumentException | IncoherenceAvanceException e) {
            result.reject("solde.invalide", e.getMessage());
            model.addAttribute("view", "clients/nouveau");
            return "dashboard";
        }
        redirectAttributes.addFlashAttribute("success", "Client créé avec succès.");
        return "redirect:/clients";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModif(@PathVariable Long id, Model model) {
        Client client = clientService.trouverParId(id);
        client.preparerSaisieSoldeDepart();
        model.addAttribute("client", client);
        model.addAttribute("soldeDepartSituation", soldes.soldeClient(id));
        model.addAttribute("view", "clients/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifier(@PathVariable Long id, @ModelAttribute("client") Client client, BindingResult result, HttpServletRequest request,
                           Model model, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        client.setSoldeDepartSoumis(FormBindingAdvice.soldeDepartSoumis(request));
        try {
            clientService.modifierClient(id, client);
        } catch (IllegalArgumentException | IncoherenceAvanceException e) {
            result.reject("solde.invalide", e.getMessage());
            client.setId(id);
            model.addAttribute("soldeDepartSituation", soldes.soldeClient(id));
            model.addAttribute("view", "clients/modifier");
            return "dashboard";
        }
        redirectAttributes.addFlashAttribute("success", "Client modifié avec succès.");
        return "redirect:/clients";
    }

    @PostMapping("/supprimer/{id}")
    public String supprimer(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        clientService.supprimerClient(id);
        redirectAttributes.addFlashAttribute("success", "Client supprimé avec succès.");
        return "redirect:/clients";
    }
}
