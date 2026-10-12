package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.ChangementMotDePasseForm;
import org.example.stock.service.ChangementMotDePasseException;
import org.example.stock.service.MonCompteService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;

import java.util.Set;

@Controller
@RequestMapping("/mon-compte")
public class MonCompteController {
    private static final String FORMULAIRE = "changementMotDePasse";
    private final MonCompteService comptes;

    public MonCompteController(MonCompteService comptes) {
        this.comptes = comptes;
    }

    @InitBinder(FORMULAIRE)
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> autorises = Set.of("ancienMotDePasse", "nouveauMotDePasse", "confirmationMotDePasse", "_csrf");
        if ("POST".equals(request.getMethod())) {
            for (String champ : request.getParameterMap().keySet()) {
                if (!autorises.contains(champ)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
            }
        }
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }

    @GetMapping
    public String afficher(Model model) {
        model.addAttribute(FORMULAIRE, new ChangementMotDePasseForm());
        return afficherFormulaire(model);
    }

    @PostMapping("/mot-de-passe")
    public String changerMotDePasse(@Valid @ModelAttribute(FORMULAIRE) ChangementMotDePasseForm formulaire,
                                   BindingResult result, Model model, HttpServletRequest request, HttpServletResponse response) {
        comptes.verifierCompteCourant();
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                comptes.changerMotDePasse(formulaire);
                formulaire.effacer();
                // Le service est revenu : son changement a été validé avant de fermer la session courante.
                new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
                return "redirect:/login?passwordChanged";
            } catch (ChangementMotDePasseException erreur) {
                if (erreur.getChamp() == null) result.reject("motDePasse.invalide", erreur.getMessage());
                else result.rejectValue(erreur.getChamp(), "motDePasse.invalide", erreur.getMessage());
            }
        }
        effacerSecrets(formulaire, result, model);
        return afficherFormulaire(model);
    }

    private String afficherFormulaire(Model model) {
        model.addAttribute("compte", comptes.chargerCompteCourant());
        model.addAttribute("view", "compte/mon-compte");
        return "dashboard";
    }

    private static void effacerSecrets(ChangementMotDePasseForm formulaire, BindingResult erreurs, Model model) {
        formulaire.effacer();
        BeanPropertyBindingResult sansSecrets = new BeanPropertyBindingResult(formulaire, FORMULAIRE);
        for (ObjectError erreur : erreurs.getAllErrors()) {
            if (erreur instanceof FieldError champ) {
                sansSecrets.addError(new FieldError(FORMULAIRE, champ.getField(), null, champ.isBindingFailure(),
                        champ.getCodes(), null, champ.getDefaultMessage()));
            } else {
                sansSecrets.addError(new ObjectError(FORMULAIRE, erreur.getCodes(), null, erreur.getDefaultMessage()));
            }
        }
        model.addAttribute(FORMULAIRE, formulaire);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + FORMULAIRE, sansSecrets);
    }
}
