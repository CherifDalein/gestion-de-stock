package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.AccesUtilisateurForm;
import org.example.stock.service.AccesUtilisateurService;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/utilisateurs")
public class UtilisateurController {
    private final AccesUtilisateurService accesUtilisateurs;

    public UtilisateurController(AccesUtilisateurService accesUtilisateurs) {
        this.accesUtilisateurs = accesUtilisateurs;
    }

    @InitBinder("accesUtilisateur")
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        if (request.getMethod().equals("POST")) {
            for (String parametre : request.getParameterMap().keySet()) {
                if (!Set.of("actif", "_csrf").contains(parametre)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
        // Seul l'identifiant injecté par Spring depuis la route est accepté ; le paramètre HTTP id est refusé ci-dessus.
        binder.setAllowedFields("actif", "_csrf", "id");
    }

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("utilisateurs", accesUtilisateurs.listerPourBoutiqueCourante());
        model.addAttribute("view", "utilisateurs/liste");
        return "dashboard";
    }

    @PostMapping("/{id}/acces")
    public String modifierAcces(@PathVariable Long id, @Valid @ModelAttribute("accesUtilisateur") AccesUtilisateurForm formulaire,
                                BindingResult result, RedirectAttributes redirectAttributes) {
        accesUtilisateurs.verifierGestionBoutiqueCourante();
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le statut du compte est obligatoire et doit être valide");
        accesUtilisateurs.modifierPourBoutiqueCourante(id, formulaire);
        redirectAttributes.addFlashAttribute("success", "Accès au compte utilisateur mis à jour.");
        return "redirect:/utilisateurs";
    }
}
