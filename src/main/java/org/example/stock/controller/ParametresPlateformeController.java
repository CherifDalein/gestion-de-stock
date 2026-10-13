package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.ParametresPlateformeForm;
import org.example.stock.service.ParametresPlateformeService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/plateforme/parametres")
public class ParametresPlateformeController {
    private final ParametresPlateformeService service;

    public ParametresPlateformeController(ParametresPlateformeService service) { this.service = service; }

    @InitBinder("parametresPlateforme")
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> champs = Set.of("dureeEssaiJours", "_csrf");
        if ("POST".equals(request.getMethod())) for (String champ : request.getParameterMap().keySet()) {
            if (!champs.contains(champ) || request.getParameterValues(champ).length != 1) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
            }
        }
        binder.setAllowedFields(champs.toArray(String[]::new));
    }

    @GetMapping
    public String consulter(Model model) {
        ParametresPlateformeForm formulaire = new ParametresPlateformeForm();
        formulaire.setDureeEssaiJours(service.consulterDureeEssai());
        model.addAttribute("parametresPlateforme", formulaire);
        model.addAttribute("view", "plateforme/parametres");
        return "dashboard";
    }

    @PostMapping
    public String modifier(@Valid @ModelAttribute("parametresPlateforme") ParametresPlateformeForm formulaire,
                           BindingResult erreurs, Model model, RedirectAttributes redirection) {
        service.consulterDureeEssai();
        FormBindingAdvice.verifier(erreurs);
        if (erreurs.hasErrors()) {
            model.addAttribute("view", "plateforme/parametres");
            return "dashboard";
        }
        try {
            service.modifierDureeEssai(formulaire.getDureeEssaiJours());
        } catch (DataAccessException exception) {
            erreurs.reject("parametres.indisponibles", "La durée n'a pas pu être enregistrée. Réessayez.");
            model.addAttribute("view", "plateforme/parametres");
            return "dashboard";
        }
        redirection.addFlashAttribute("success", "La durée d'essai des prochaines boutiques a été mise à jour.");
        return "redirect:/plateforme/parametres";
    }
}
