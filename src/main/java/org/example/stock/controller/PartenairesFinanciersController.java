package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.PartenaireFinancierForm;
import org.example.stock.model.PartenaireFinancier;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.service.PartenairesFinanciersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Set;

/**
 * Autres partenaires (banques, associés, personnel, divers) : liste avec soldes, fiche avec historique, création, modification, archivage.
 * Réservé au gérant ({@code /financements/**} refuse le caissier en 403) ; la boutique vient du compte connecté, un partenaire d'une autre
 * boutique est introuvable (404).
 */
@Controller
@RequestMapping("/financements/partenaires")
public class PartenairesFinanciersController {
    @Autowired private PartenairesFinanciersService partenaires;

    @InitBinder({"partenaireFinancierForm"})
    public void limiter(WebDataBinder binder, HttpServletRequest request) {
        LiaisonFinancements.limiter(binder, request, Set.of("nom", "type", "telephone", "note"), Set.of());
    }

    @GetMapping({"", "/"})
    public String liste(@RequestParam(name = "archives", required = false) List<String> archives, Model model) {
        boolean avecArchives = false;
        if (archives != null) {
            if (archives.size() != 1 || !Set.of("1", "0").contains(archives.getFirst())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paramètre invalide");
            avecArchives = "1".equals(archives.getFirst());
        }
        model.addAttribute("vues", partenaires.lister(avecArchives));
        model.addAttribute("avecArchives", avecArchives);
        model.addAttribute("view", "financements/partenaires");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        if (!model.containsAttribute("partenaireFinancierForm")) model.addAttribute("partenaireFinancierForm", new PartenaireFinancierForm());
        return formulaire(model, null);
    }

    @PostMapping("/nouveau")
    public String creer(@Valid @ModelAttribute("partenaireFinancierForm") PartenaireFinancierForm f, BindingResult erreurs, Model model,
                        RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                PartenaireFinancier p = partenaires.creer(f);
                redirect.addFlashAttribute("success", "Partenaire enregistré.");
                return "redirect:/financements/partenaires/" + p.getId();
            } catch (IllegalArgumentException e) {
                erreurs.reject("partenaire.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("partenaire.conflit", "Une autre opération a modifié la liste. Rechargez la page avant de réessayer.");
            }
        }
        return formulaire(model, null);
    }

    @GetMapping("/{id}")
    public String fiche(@PathVariable Long id, Model model) {
        model.addAttribute("detail", partenaires.detail(id));
        model.addAttribute("view", "financements/partenaire");
        return "dashboard";
    }

    @GetMapping("/{id}/modifier")
    public String modifier(@PathVariable Long id, Model model) {
        var vue = partenaires.vue(id);
        if (!model.containsAttribute("partenaireFinancierForm")) {
            var f = new PartenaireFinancierForm();
            f.setNom(vue.partenaire().getNom()); f.setType(vue.partenaire().getType());
            f.setTelephone(vue.partenaire().getTelephone()); f.setNote(vue.partenaire().getNote());
            model.addAttribute("partenaireFinancierForm", f);
        }
        return formulaire(model, vue);
    }

    @PostMapping("/{id}/modifier")
    public String enregistrer(@PathVariable Long id, @Valid @ModelAttribute("partenaireFinancierForm") PartenaireFinancierForm f, BindingResult erreurs,
                              Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                partenaires.modifier(id, f);
                redirect.addFlashAttribute("success", "Partenaire modifié.");
                return "redirect:/financements/partenaires/" + id;
            } catch (IllegalArgumentException e) {
                erreurs.reject("partenaire.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("partenaire.conflit", "Une autre opération a modifié ce partenaire. Rechargez la page avant de réessayer.");
            }
        }
        return formulaire(model, partenaires.vue(id));
    }

    @PostMapping("/{id}/archiver")
    public String archiver(@PathVariable Long id, RedirectAttributes redirect) {
        return changer(id, false, redirect, "Partenaire archivé : il garde son historique.");
    }

    @PostMapping("/{id}/reactiver")
    public String reactiver(@PathVariable Long id, RedirectAttributes redirect) {
        return changer(id, true, redirect, "Partenaire réactivé.");
    }

    private String changer(Long id, boolean actif, RedirectAttributes redirect, String succes) {
        try {
            partenaires.definirActif(id, actif);
            redirect.addFlashAttribute("success", succes);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "Une autre opération a modifié ce partenaire. Rechargez la page avant de réessayer.");
        }
        return "redirect:/financements/partenaires/" + id;
    }

    private String formulaire(Model model, PartenairesFinanciersService.Vue vue) {
        model.addAttribute("vue", vue);
        model.addAttribute("types", TypePartenaireFinancier.values());
        model.addAttribute("view", "financements/partenaire-form");
        return "dashboard";
    }
}
