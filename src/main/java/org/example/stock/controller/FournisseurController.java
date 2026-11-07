package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.enums.SensSolde;
import org.example.stock.model.Fournisseur;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.SoldePartenaireService;
import org.example.stock.service.FournisseurService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/fournisseurs")
public class FournisseurController {
    @Autowired
    private FournisseurService fournisseurService;

    @Autowired
    private SoldePartenaireService soldes;

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("soldes", soldes.soldesFournisseurs());
        model.addAttribute("view", "fournisseurs/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String formulaire(Model model) {
        Fournisseur fournisseur = new Fournisseur();
        fournisseur.setSensSoldeDepart(SensSolde.IL_ME_DOIT);
        model.addAttribute("fournisseur", fournisseur);
        model.addAttribute("view", "fournisseurs/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@Valid @ModelAttribute("fournisseur") Fournisseur fournisseur, BindingResult result, HttpServletRequest request,
                              Model model, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()){
            model.addAttribute("view", "fournisseurs/nouveau");
            return "dashboard";
        }
        fournisseur.setSoldeDepartSoumis(FormBindingAdvice.soldeDepartSoumis(request));
        try {
            fournisseurService.enregistrer(fournisseur);
        } catch (IllegalArgumentException | IncoherenceAvanceException e) {
            result.reject("solde.invalide", e.getMessage());
            model.addAttribute("view", "fournisseurs/nouveau");
            return "dashboard";
        }
        redirectAttributes.addFlashAttribute("success", "Fournisseur créé avec succès.");
        return "redirect:/fournisseurs";
    }

    @PostMapping("/supprimer/{id}")
    public String supprimerFournisseur(@PathVariable("id") Long id, RedirectAttributes redirectAttributes) {
        fournisseurService.supprimerFournisseur(id);
        redirectAttributes.addFlashAttribute("success", "Fournisseur supprimé avec succès.");
        return "redirect:/fournisseurs";
    }

    @GetMapping("/modifier/{id}")
    public String afficherFormulaireModif(@PathVariable Long id, Model model) {
        Fournisseur fournisseur = fournisseurService.trouverParId(id);
        fournisseur.preparerSaisieSoldeDepart();
        model.addAttribute("fournisseur", fournisseur);
        model.addAttribute("soldeDepartSituation", soldes.soldeFournisseur(id));
        model.addAttribute("view", "fournisseurs/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String modifierFournisseur(@PathVariable Long id, @ModelAttribute("fournisseur") Fournisseur fournisseur, BindingResult result,
                                      HttpServletRequest request, Model model, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        fournisseur.setSoldeDepartSoumis(FormBindingAdvice.soldeDepartSoumis(request));
        try {
            fournisseurService.modifierFournisseur(id, fournisseur);
        } catch (IllegalArgumentException | IncoherenceAvanceException e) {
            result.reject("solde.invalide", e.getMessage());
            fournisseur.setId(id);
            model.addAttribute("soldeDepartSituation", soldes.soldeFournisseur(id));
            model.addAttribute("view", "fournisseurs/modifier");
            return "dashboard";
        }
        redirectAttributes.addFlashAttribute("success", "Fournisseur modifié avec succès.");
        return "redirect:/fournisseurs";
    }
}
