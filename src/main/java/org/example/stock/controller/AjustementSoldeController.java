package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.enums.SensAjustement;
import org.example.stock.form.AjustementSoldeForm;
import org.example.stock.service.AjustementSoldeDepartService;
import org.example.stock.service.ClientService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.SoldePartenaireService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

/**
 * Ajustement du solde de départ d'un client ou d'un fournisseur déjà utilisé (gérant seulement, voir {@code SecurityConfig}).
 * La boutique vient toujours du compte connecté : un partenaire d'une autre boutique donne 404.
 */
@Controller
public class AjustementSoldeController {
    @Autowired private AjustementSoldeDepartService ajustements;
    @Autowired private SoldePartenaireService soldes;
    @Autowired private ClientService clientService;
    @Autowired private FournisseurService fournisseurService;

    @GetMapping("/clients/solde/ajuster/{id}")
    public String afficherClient(@PathVariable Long id, Model model) {
        model.addAttribute("ajustementSolde", nouveauFormulaire());
        preparer(true, id, model);
        return "dashboard";
    }

    @PostMapping("/clients/solde/ajuster/{id}")
    public String enregistrerClient(@PathVariable Long id, @Valid @ModelAttribute("ajustementSolde") AjustementSoldeForm formulaire,
                                    BindingResult result, Model model, RedirectAttributes redirectAttributes, HttpServletResponse response) {
        return enregistrer(true, id, formulaire, result, model, redirectAttributes, response);
    }

    @GetMapping("/fournisseurs/solde/ajuster/{id}")
    public String afficherFournisseur(@PathVariable Long id, Model model) {
        model.addAttribute("ajustementSolde", nouveauFormulaire());
        preparer(false, id, model);
        return "dashboard";
    }

    @PostMapping("/fournisseurs/solde/ajuster/{id}")
    public String enregistrerFournisseur(@PathVariable Long id, @Valid @ModelAttribute("ajustementSolde") AjustementSoldeForm formulaire,
                                         BindingResult result, Model model, RedirectAttributes redirectAttributes, HttpServletResponse response) {
        return enregistrer(false, id, formulaire, result, model, redirectAttributes, response);
    }

    private String enregistrer(boolean client, Long id, AjustementSoldeForm formulaire, BindingResult result, Model model,
                               RedirectAttributes redirectAttributes, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        String base = (client ? "/clients" : "/fournisseurs") + "/solde/ajuster/" + id;
        if (!result.hasErrors()) {
            try {
                if (client) ajustements.ajusterClient(id, formulaire.getMontant(), formulaire.getSens(), formulaire.getMotif(), formulaire.getJeton());
                else ajustements.ajusterFournisseur(id, formulaire.getMontant(), formulaire.getSens(), formulaire.getMotif(), formulaire.getJeton());
                redirectAttributes.addFlashAttribute("success", "Ajustement du solde de départ enregistré.");
                return "redirect:" + base;
            } catch (ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("ajustement.conflit", "Une autre opération modifie ce partenaire. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException | IncoherenceAvanceException e) {
                result.reject("ajustement.invalide", e.getMessage());
            }
        }
        preparer(client, id, model);
        return "dashboard";
    }

    private static AjustementSoldeForm nouveauFormulaire() {
        AjustementSoldeForm formulaire = new AjustementSoldeForm();
        formulaire.setJeton(UUID.randomUUID().toString());
        return formulaire;
    }

    private void preparer(boolean client, Long id, Model model) {
        if (client) {
            model.addAttribute("partenaireNom", clientService.trouverParId(id).getNom());
            model.addAttribute("solde", soldes.soldeClient(id));
            model.addAttribute("historique", ajustements.historiqueClient(id));
        } else {
            model.addAttribute("partenaireNom", fournisseurService.trouverParId(id).getNom());
            model.addAttribute("solde", soldes.soldeFournisseur(id));
            model.addAttribute("historique", ajustements.historiqueFournisseur(id));
        }
        model.addAttribute("typePartenaire", client ? "client" : "fournisseur");
        model.addAttribute("actionUrl", (client ? "/clients" : "/fournisseurs") + "/solde/ajuster/" + id);
        model.addAttribute("retourUrl", client ? "/clients/modifier/" + id : "/fournisseurs/modifier/" + id);
        model.addAttribute("sensOptions", SensAjustement.values());
        model.addAttribute("view", "partenaires/ajuster-solde");
    }
}
