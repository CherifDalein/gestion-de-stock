package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.enums.FormatRecu;
import org.example.stock.form.ParametresBoutiqueForm;
import org.example.stock.service.ParametresBoutiqueService;
import org.example.stock.service.InitialisationBoutiqueService;
import org.example.stock.service.LogoBoutiqueInvalideException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.util.WebUtils;

import java.util.Set;

@Controller
@RequestMapping("/boutique/parametres")
public class ParametresBoutiqueController {
    private final ParametresBoutiqueService parametres;
    private final InitialisationBoutiqueService initialisation;

    public ParametresBoutiqueController(ParametresBoutiqueService parametres, InitialisationBoutiqueService initialisation) {
        this.parametres = parametres;
        this.initialisation = initialisation;
    }

    @InitBinder("parametresBoutique")
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> autorises = Set.of("nom", "adresse", "telephone", "telephoneSecondaire", "email", "logo", "supprimerLogo", "_supprimerLogo",
                "delaiClientsJours", "delaiFournisseursJours", "formatRecu", "proposerImpressionRecu", "_csrf");
        if (request.getMethod().equals("POST")) {
            for (String parametre : request.getParameterMap().keySet()) {
                if (!autorises.contains(parametre) || request.getParameterValues(parametre).length != 1
                        || parametre.equals("logo")) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
            var multipart = WebUtils.getNativeRequest(request, MultipartHttpServletRequest.class);
            if (multipart != null) {
                for (var fichier : multipart.getMultiFileMap().entrySet()) {
                    if (!fichier.getKey().equals("logo") || fichier.getValue().size() != 1) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Un seul fichier de logo doit être envoyé");
                    }
                }
            }
        }
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }

    @GetMapping
    public String afficher(Model model) {
        model.addAttribute("parametresBoutique", parametres.chargerPourBoutiqueCourante());
        return afficherFormulaire(model);
    }

    @PostMapping
    public String enregistrer(@Valid @ModelAttribute("parametresBoutique") ParametresBoutiqueForm formulaire,
                              BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        parametres.verifierGestionBoutiqueCourante();
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) return afficherFormulaire(model);
        try {
            parametres.modifierPourBoutiqueCourante(formulaire);
        } catch (LogoBoutiqueInvalideException exception) {
            result.rejectValue("logo", "logo.invalide", exception.getMessage());
            return afficherFormulaire(model);
        }
        redirectAttributes.addFlashAttribute("success", "Les paramètres de votre boutique ont été enregistrés.");
        return "redirect:/boutique/parametres";
    }

    private String afficherFormulaire(Model model) {
        model.addAttribute("formatsRecu", FormatRecu.values());
        model.addAttribute("initialisationOuverte", initialisation.estOuverte());
        model.addAttribute("view", "boutique/parametres");
        return "dashboard";
    }
}
