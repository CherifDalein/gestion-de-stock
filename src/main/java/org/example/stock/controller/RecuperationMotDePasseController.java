package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.DemandeReinitialisationForm;
import org.example.stock.form.ReinitialisationMotDePasseForm;
import org.example.stock.service.RecuperationMotDePasseException;
import org.example.stock.service.RecuperationMotDePasseService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;

import java.util.Set;

@Controller
public class RecuperationMotDePasseController {
    private static final String DEMANDE = "demandeReinitialisation";
    private static final String RESET = "reinitialisationMotDePasse";
    private static final String VUE_DEMANDE = "compte/mot-de-passe-oublie";
    private static final String VUE_RESET = "compte/reinitialiser-mot-de-passe";
    private final RecuperationMotDePasseService service;

    public RecuperationMotDePasseController(RecuperationMotDePasseService service) { this.service = service; }

    @InitBinder({DEMANDE, RESET})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> autorises = DEMANDE.equals(binder.getObjectName()) ? Set.of("email", "_csrf")
                : Set.of("jeton", "nouveauMotDePasse", "confirmationMotDePasse", "_csrf");
        if ("POST".equals(request.getMethod())) {
            for (String champ : request.getParameterMap().keySet()) {
                if (!autorises.contains(champ)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                if (!"_csrf".equals(champ) && request.getParameterValues(champ).length != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }

    @GetMapping("/mot-de-passe-oublie")
    public String demander(Model model, HttpServletResponse response,
                           @RequestParam(name = "envoye", required = false) String envoye) {
        protegerReponse(response);
        model.addAttribute(DEMANDE, new DemandeReinitialisationForm());
        model.addAttribute("demandeEnvoyee", envoye != null);
        return VUE_DEMANDE;
    }

    @PostMapping("/mot-de-passe-oublie")
    public String envoyer(@Valid @ModelAttribute(DEMANDE) DemandeReinitialisationForm formulaire,
                         BindingResult erreurs, HttpServletRequest request, HttpServletResponse response) {
        protegerReponse(response);
        FormBindingAdvice.verifier(erreurs);
        if (erreurs.hasErrors()) return VUE_DEMANDE;
        service.demander(formulaire.getEmail(), request.getRemoteAddr());
        return "redirect:/mot-de-passe-oublie?envoye";
    }

    @GetMapping("/reinitialiser-mot-de-passe")
    public String afficher(@RequestParam(required = false) String jeton, Model model, HttpServletResponse response) {
        protegerReponse(response);
        ReinitialisationMotDePasseForm formulaire = new ReinitialisationMotDePasseForm();
        boolean valide = service.lienValide(jeton);
        if (valide) formulaire.setJeton(jeton);
        model.addAttribute(RESET, formulaire);
        model.addAttribute("lienInvalide", !valide);
        return VUE_RESET;
    }

    @PostMapping("/reinitialiser-mot-de-passe")
    public String reinitialiser(@Valid @ModelAttribute(RESET) ReinitialisationMotDePasseForm formulaire,
                               BindingResult erreurs, Model model, HttpServletRequest request, HttpServletResponse response) {
        protegerReponse(response);
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                service.reinitialiser(formulaire, request.getRemoteAddr());
                formulaire.effacerMotsDePasse();
                // Ne connecter aucun compte : fermer aussi une ancienne session présente dans ce navigateur.
                new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
                return "redirect:/login?passwordReset";
            } catch (RecuperationMotDePasseException exception) {
                if (exception.getChamp() == null) erreurs.reject("lien.invalide", exception.getMessage());
                else erreurs.rejectValue(exception.getChamp(), "motDePasse.invalide", exception.getMessage());
            }
        }
        boolean valide = service.lienValide(formulaire.getJeton());
        model.addAttribute("lienInvalide", !valide);
        if (!valide) formulaire.setJeton(null);
        effacerSecrets(formulaire, erreurs, model);
        return VUE_RESET;
    }

    private static void protegerReponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private static void effacerSecrets(ReinitialisationMotDePasseForm formulaire, BindingResult erreurs, Model model) {
        formulaire.effacerMotsDePasse();
        BeanPropertyBindingResult sansSecrets = new BeanPropertyBindingResult(formulaire, RESET);
        for (ObjectError erreur : erreurs.getAllErrors()) {
            if (erreur instanceof FieldError champ) {
                sansSecrets.addError(new FieldError(RESET, champ.getField(), null, champ.isBindingFailure(),
                        champ.getCodes(), null, champ.getDefaultMessage()));
            } else sansSecrets.addError(new ObjectError(RESET, erreur.getCodes(), null, erreur.getDefaultMessage()));
        }
        model.addAttribute(RESET, formulaire);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + RESET, sansSecrets);
    }
}
