package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.ConfirmationInscriptionForm;
import org.example.stock.form.DemandeInscriptionForm;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.InscriptionBoutiqueException;
import org.example.stock.service.InscriptionBoutiqueService;
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
public class InscriptionBoutiqueController {
    private static final String DEMANDE = "demandeInscription";
    private static final String CONFIRMATION = "confirmationInscription";
    private static final String VUE_DEMANDE = "inscription/demande";
    private static final String VUE_CONFIRMATION = "inscription/confirmer";
    private final InscriptionBoutiqueService service;
    private final EssaiBoutiqueService essais;

    public InscriptionBoutiqueController(InscriptionBoutiqueService service, EssaiBoutiqueService essais) {
        this.service = service;
        this.essais = essais;
    }

    @InitBinder({DEMANDE, CONFIRMATION})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        Set<String> autorises = DEMANDE.equals(binder.getObjectName()) ? Set.of("email", "_csrf")
                : Set.of("jeton", "nom", "nomBoutique", "nouveauMotDePasse", "confirmationMotDePasse", "_csrf");
        if ("POST".equals(request.getMethod())) {
            for (String champ : request.getParameterMap().keySet()) {
                if (!autorises.contains(champ) || !"_csrf".equals(champ) && request.getParameterValues(champ).length != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
        binder.setAllowedFields(autorises.toArray(String[]::new));
    }

    @GetMapping("/inscription")
    public String demander(Model model, HttpServletResponse response,
                           @RequestParam(name = "envoye", required = false) String envoye) {
        protegerReponse(response);
        model.addAttribute(DEMANDE, new DemandeInscriptionForm());
        model.addAttribute("demandeEnvoyee", envoye != null);
        model.addAttribute("dureeEssaiJours", essais.dureeEssaiJours());
        return VUE_DEMANDE;
    }

    @PostMapping("/inscription")
    public String envoyer(@Valid @ModelAttribute(DEMANDE) DemandeInscriptionForm formulaire, BindingResult erreurs,
                         Model model, HttpServletRequest request, HttpServletResponse response) {
        protegerReponse(response);
        FormBindingAdvice.verifier(erreurs);
        if (erreurs.hasErrors()) {
            model.addAttribute("demandeEnvoyee", false);
            model.addAttribute("dureeEssaiJours", essais.dureeEssaiJours());
            return VUE_DEMANDE;
        }
        service.demander(formulaire.getEmail(), request.getRemoteAddr());
        return "redirect:/inscription?envoye";
    }

    @GetMapping("/inscription/confirmer")
    public String afficher(@RequestParam(required = false) String jeton, Model model, HttpServletResponse response) {
        protegerReponse(response);
        ConfirmationInscriptionForm formulaire = new ConfirmationInscriptionForm();
        String email = service.emailPourLien(jeton).orElse(null);
        if (email != null) formulaire.setJeton(jeton);
        model.addAttribute(CONFIRMATION, formulaire);
        ajouterEtatLien(model, email);
        return VUE_CONFIRMATION;
    }

    @PostMapping("/inscription/confirmer")
    public String confirmer(@Valid @ModelAttribute(CONFIRMATION) ConfirmationInscriptionForm formulaire,
                            BindingResult erreurs, Model model, HttpServletRequest request, HttpServletResponse response) {
        protegerReponse(response);
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                service.confirmer(formulaire, request.getRemoteAddr());
                formulaire.effacerMotsDePasse();
                // Le compte créé n'est jamais connecté automatiquement, ni associé à la session d'un autre compte.
                new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
                return "redirect:/login?boutiqueCreated";
            } catch (InscriptionBoutiqueException exception) {
                if (exception.getChamp() == null) erreurs.reject("inscription.invalide", exception.getMessage());
                else erreurs.rejectValue(exception.getChamp(), "inscription.invalide", exception.getMessage());
            }
        }
        String email = service.emailPourLien(formulaire.getJeton()).orElse(null);
        if (email == null) formulaire.setJeton(null);
        ajouterEtatLien(model, email);
        effacerSecrets(formulaire, erreurs, model);
        return VUE_CONFIRMATION;
    }

    private void ajouterEtatLien(Model model, String email) {
        model.addAttribute("emailInscription", email);
        model.addAttribute("lienInvalide", email == null);
        model.addAttribute("dureeEssaiJours", essais.dureeEssaiJours());
    }

    private static void protegerReponse(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Referrer-Policy", "no-referrer");
    }

    private static void effacerSecrets(ConfirmationInscriptionForm formulaire, BindingResult erreurs, Model model) {
        formulaire.effacerMotsDePasse();
        BeanPropertyBindingResult sansSecrets = new BeanPropertyBindingResult(formulaire, CONFIRMATION);
        for (ObjectError erreur : erreurs.getAllErrors()) {
            if (erreur instanceof FieldError champ) {
                sansSecrets.addError(new FieldError(CONFIRMATION, champ.getField(), null, champ.isBindingFailure(),
                        champ.getCodes(), null, champ.getDefaultMessage()));
            } else sansSecrets.addError(new ObjectError(CONFIRMATION, erreur.getCodes(), null, erreur.getDefaultMessage()));
        }
        model.addAttribute(CONFIRMATION, formulaire);
        model.addAttribute(BindingResult.MODEL_KEY_PREFIX + CONFIRMATION, sansSecrets);
    }
}
