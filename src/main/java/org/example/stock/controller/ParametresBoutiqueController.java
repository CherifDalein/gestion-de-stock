package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.ParametresBoutiqueForm;
import org.example.stock.service.ParametresBoutiqueService;
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

import java.util.Set;

@Controller
@RequestMapping("/boutique/parametres")
public class ParametresBoutiqueController {
    private final ParametresBoutiqueService parametres;

    public ParametresBoutiqueController(ParametresBoutiqueService parametres) {
        this.parametres = parametres;
    }

    @InitBinder("parametresBoutique")
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> autorises = Set.of("nom", "adresse", "telephone", "email", "_csrf");
        if (request.getMethod().equals("POST")) {
            for (String parametre : request.getParameterMap().keySet()) {
                if (!autorises.contains(parametre)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
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
        parametres.modifierPourBoutiqueCourante(formulaire);
        redirectAttributes.addFlashAttribute("success", "Les paramètres de votre boutique ont été enregistrés.");
        return "redirect:/boutique/parametres";
    }

    private String afficherFormulaire(Model model) {
        model.addAttribute("view", "boutique/parametres");
        return "dashboard";
    }
}
