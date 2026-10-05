package org.example.stock.controller;

import org.example.stock.model.Achat;
import org.example.stock.service.AchatService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ReglementAchatObsoleteException;
import org.example.stock.form.ReglementAchatForm;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.CreationOperationException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.math.BigDecimal;

@Controller
@RequestMapping("/achats")
public class AchatController {
    @Autowired private AchatService service;
    @Autowired private ProduitService produitService;
    @Autowired private FournisseurService fournisseurService;
    @Autowired private CaisseService caisseService;
    @Autowired private OperationCreationService creations;

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
        model.addAttribute("jetonCreation", creations.ouvrir(TypeOperationCreation.ACHAT));
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("view", "achats/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("achat") Achat achat, BindingResult result,
                              @RequestParam(required = false) String jetonCreation, RedirectAttributes redirectAttributes,
                              Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        model.addAttribute("jetonCreation", jetonCreation);
        try {
            var resultat = creations.creerAchat(jetonCreation, achat);
            redirectAttributes.addFlashAttribute("success", resultat.dejaEnregistre()
                    ? "Cet achat est déjà enregistré. Le stock et la caisse n'ont pas été modifiés une seconde fois."
                    : "Achat enregistre avec succes !");
            return "redirect:/achats";
        } catch (RuntimeException e) {
            if (e instanceof CreationOperationException conflit) {
                response.setStatus(conflit.getStatus().value());
                model.addAttribute("creationConflit", true);
            }
            model.addAttribute("achat", achat);
            model.addAttribute("produits", produitService.listerTous());
            model.addAttribute("fournisseurs", fournisseurService.listerTous());
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie le stock. Réessayez l'achat." : e.getMessage());
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
    public String enregistrerModification(@PathVariable Long id, @ModelAttribute("achat") Achat achat, BindingResult result, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        try {
            service.modifierAchat(id, achat);
            return "redirect:/achats?success=modifie";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie cet achat ou son stock. Réessayez la modification." : e.getMessage());
            return "redirect:/achats/modifier/" + id;
        }
    }

    @GetMapping("/regler/{id}")
    public String afficherReglement(@PathVariable Long id, Model model) {
        Achat achat = chargerAchat(id);
        ReglementAchatForm formulaire = new ReglementAchatForm();
        formulaire.setMontantVerseAttendu(achat.getMontantVerse() == null ? BigDecimal.ZERO : achat.getMontantVerse());
        model.addAttribute("reglementAchat", formulaire);
        preparerReglement(achat, model);
        return "dashboard";
    }

    @PostMapping("/regler/{id}")
    public String enregistrerReglement(@PathVariable Long id, @Valid @ModelAttribute("reglementAchat") ReglementAchatForm formulaire,
                                      BindingResult result, Model model, RedirectAttributes redirectAttributes,
                                      HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                service.reglerAchat(id, formulaire.getMontant(), formulaire.getMontantVerseAttendu());
                redirectAttributes.addFlashAttribute("success", "Versement fournisseur enregistré avec succès.");
                return "redirect:/achats/regler/" + id;
            } catch (ReglementAchatObsoleteException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("reglement.conflit", e instanceof ReglementAchatObsoleteException ? e.getMessage()
                        : "Une autre opération modifie cet achat. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException e) {
                result.reject("reglement.invalide", e.getMessage());
            } catch (EmptyResultDataAccessException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable");
            }
        }
        preparerReglement(chargerAchat(id), model);
        return "dashboard";
    }

    private Achat chargerAchat(Long id) {
        Achat achat = service.trouverParId(id);
        if (achat == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable");
        return achat;
    }

    private void preparerReglement(Achat achat, Model model) {
        model.addAttribute("achatReglement", achat);
        model.addAttribute("reglements", caisseService.listerReglementsAchat(achat.getId()));
        model.addAttribute("view", "achats/regler");
    }
}
