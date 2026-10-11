package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.CreationBoutiqueForm;
import org.example.stock.service.EmailDejaUtiliseException;
import org.example.stock.service.PlateformeService;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
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

import java.util.Arrays;
import java.util.stream.Stream;

@Controller
@RequestMapping("/plateforme/boutiques")
public class PlateformeController {
    private final PlateformeService plateforme;

    public PlateformeController(PlateformeService plateforme) {
        this.plateforme = plateforme;
    }

    @InitBinder({"formulaire", "accesBoutique"})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        String[] champs = binder.getObjectName().equals("formulaire")
                ? new String[]{"nomBoutique", "nom", "email", "password", "accesOffert", "_accesOffert", "_csrf"}
                : new String[]{"actif", "accesOffert", "_actif", "_accesOffert", "_csrf"};
        if (request.getMethod().equals("POST")) {
            for (String parametre : request.getParameterMap().keySet()) {
                if (Arrays.stream(champs).noneMatch(champ -> champ.equals(parametre))) {
                    if (binder.getTarget() instanceof CreationBoutiqueForm formulaire) formulaire.setPassword(null);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
        // Spring propose aussi l'identifiant de route ; le contrôle des paramètres ci-dessus refuse un id envoyé par le client.
        binder.setAllowedFields(Stream.concat(Arrays.stream(champs), Stream.of("id")).toArray(String[]::new));
    }

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("boutiques", plateforme.listerBoutiques());
        model.addAttribute("utilisateursParBoutique", plateforme.compterUtilisateursParBoutique());
        model.addAttribute("view", "plateforme/boutiques/liste");
        return "dashboard";
    }

    @GetMapping("/nouvelle")
    public String nouvelle(Model model) {
        plateforme.verifierAdministrationPlateforme();
        model.addAttribute("formulaire", new CreationBoutiqueForm());
        model.addAttribute("view", "plateforme/boutiques/nouvelle");
        return "dashboard";
    }

    @PostMapping
    public String creer(@Valid @ModelAttribute("formulaire") CreationBoutiqueForm formulaire,
                        BindingResult result, Model model, RedirectAttributes redirectAttributes) {
        try {
            plateforme.verifierAdministrationPlateforme();
            FormBindingAdvice.verifier(result);
            if (!result.hasErrors()) {
                try {
                    var boutique = plateforme.creerBoutique(formulaire);
                    redirectAttributes.addFlashAttribute("success", "Boutique et compte gérant créés avec succès.");
                    return "redirect:/plateforme/boutiques/" + boutique.getId();
                } catch (EmailDejaUtiliseException e) {
                    result.rejectValue("email", "email.duplique", e.getMessage());
                } catch (IllegalArgumentException e) {
                    result.reject("boutique.invalide", e.getMessage());
                } catch (DataAccessException e) {
                    result.reject("boutique.indisponible", "La boutique n'a pas pu être créée. Réessayez plus tard.");
                }
            }
            model.addAttribute("view", "plateforme/boutiques/nouvelle");
            return "dashboard";
        } finally {
            formulaire.setPassword(null);
            model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "formulaire", sansMotDePasseRejete(formulaire, result));
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        var boutique = plateforme.trouverBoutique(id);
        AccesBoutiqueForm acces = new AccesBoutiqueForm();
        acces.setActif(boutique.isActif());
        acces.setAccesOffert(boutique.isAccesOffert());
        model.addAttribute("boutique", boutique);
        model.addAttribute("utilisateurs", plateforme.listerUtilisateurs(id));
        model.addAttribute("accesBoutique", acces);
        model.addAttribute("view", "plateforme/boutiques/detail");
        return "dashboard";
    }

    @PostMapping("/{id}/acces")
    public String modifierAcces(@PathVariable Long id, @Valid @ModelAttribute("accesBoutique") AccesBoutiqueForm formulaire,
                                BindingResult result, RedirectAttributes redirectAttributes) {
        plateforme.verifierAdministrationPlateforme();
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Les choix d'accès sont obligatoires et doivent être valides");
        }
        plateforme.modifierAcces(id, formulaire);
        redirectAttributes.addFlashAttribute("success", "Accès à la boutique mis à jour.");
        return "redirect:/plateforme/boutiques/" + id;
    }

    private static BindingResult sansMotDePasseRejete(CreationBoutiqueForm formulaire, BindingResult original) {
        BeanPropertyBindingResult nettoye = new BeanPropertyBindingResult(formulaire, original.getObjectName());
        for (var erreur : original.getAllErrors()) {
            if (erreur instanceof FieldError champ && champ.getField().equals("password")) {
                nettoye.addError(new FieldError(champ.getObjectName(), champ.getField(), null, champ.isBindingFailure(),
                        champ.getCodes(), champ.getArguments(), champ.getDefaultMessage()));
            } else nettoye.addError(erreur);
        }
        return nettoye;
    }
}
