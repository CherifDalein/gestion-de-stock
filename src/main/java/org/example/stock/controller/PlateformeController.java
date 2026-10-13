package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.AccesBoutiqueForm;
import org.example.stock.form.CreationBoutiqueForm;
import org.example.stock.form.AccesUtilisateurForm;
import org.example.stock.service.AccesUtilisateurService;
import org.example.stock.service.EmailDejaUtiliseException;
import org.example.stock.service.PlateformeService;
import org.example.stock.service.EssaiBoutiqueService;
import org.example.stock.service.GestionAbonnementService;
import org.example.stock.form.ProlongationEssaiForm;
import org.example.stock.form.ActivationAbonnementForm;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Arrays;
import java.util.stream.Stream;
import java.util.UUID;
import java.util.LinkedHashMap;

@Controller
@RequestMapping("/plateforme/boutiques")
public class PlateformeController {
    private final PlateformeService plateforme;
    private final AccesUtilisateurService accesUtilisateurs;
    private final EssaiBoutiqueService essais;
    private final GestionAbonnementService abonnements;

    public PlateformeController(PlateformeService plateforme, AccesUtilisateurService accesUtilisateurs, EssaiBoutiqueService essais,
                               GestionAbonnementService abonnements) {
        this.plateforme = plateforme;
        this.accesUtilisateurs = accesUtilisateurs;
        this.essais = essais;
        this.abonnements = abonnements;
    }

    @InitBinder({"formulaire", "accesBoutique", "accesUtilisateur", "prolongationEssai", "activationAbonnement"})
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servletBinder) servletBinder.addHeaderPredicate(nom -> false);
        String[] champs = switch (binder.getObjectName()) {
            case "formulaire" -> new String[]{"nomBoutique", "nom", "email", "password", "accesOffert", "_accesOffert", "_csrf"};
            case "accesBoutique" -> new String[]{"actif", "accesOffert", "_actif", "_accesOffert", "_csrf"};
            case "accesUtilisateur" -> new String[]{"actif", "_csrf"};
            case "prolongationEssai" -> new String[]{"jours", "motif", "jetonAction", "_csrf"};
            case "activationAbonnement" -> new String[]{"motif", "jetonAction", "_csrf"};
            default -> throw new IllegalArgumentException("Formulaire non pris en charge");
        };
        String chemin = request.getRequestURI().substring(request.getContextPath().length());
        // Sur une erreur, MVC prépare aussi les autres formulaires présents dans le modèle de détail.
        // Contrôler les paramètres seulement pour le formulaire réellement soumis à cette route.
        boolean formulaireSoumis = switch (binder.getObjectName()) {
            case "prolongationEssai" -> chemin.endsWith("/abonnement/essai");
            case "activationAbonnement" -> chemin.endsWith("/abonnement/activer");
            default -> !chemin.endsWith("/abonnement/essai") && !chemin.endsWith("/abonnement/activer");
        };
        if (request.getMethod().equals("POST") && formulaireSoumis) {
            for (String parametre : request.getParameterMap().keySet()) {
                if (Arrays.stream(champs).noneMatch(champ -> champ.equals(parametre))) {
                    if (binder.getTarget() instanceof CreationBoutiqueForm formulaire) formulaire.setPassword(null);
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
                if (("prolongationEssai".equals(binder.getObjectName()) || "activationAbonnement".equals(binder.getObjectName()))
                        && request.getParameterValues(parametre).length != 1) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
                }
            }
        }
        // Spring propose aussi l'identifiant de route ; le contrôle des paramètres ci-dessus refuse un id envoyé par le client.
        var champsRoute = binder.getObjectName().equals("accesUtilisateur") ? Stream.of("id", "utilisateurId") : Stream.of("id");
        binder.setAllowedFields(Stream.concat(Arrays.stream(champs), champsRoute).toArray(String[]::new));
    }

    @GetMapping
    public String liste(Model model) {
        var boutiques = plateforme.listerBoutiques();
        model.addAttribute("boutiques", boutiques);
        model.addAttribute("essaisExpires", boutiques.stream().filter(essais::estExpire)
                .map(org.example.stock.model.Boutique::getId).collect(java.util.stream.Collectors.toSet()));
        var situations = new LinkedHashMap<Long, org.example.stock.service.SituationAbonnement>();
        for (var boutique : boutiques) situations.put(boutique.getId(), abonnements.situation(boutique));
        model.addAttribute("abonnementsParBoutique", situations);
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
    public String detail(@PathVariable Long id, @RequestParam(defaultValue = "0") int page, Model model) {
        return remplirDetail(id, page, model);
    }

    private String remplirDetail(Long id, int page, Model model) {
        var boutique = plateforme.trouverBoutique(id);
        model.addAttribute("essaiExpire", essais.estExpire(boutique));
        AccesBoutiqueForm acces = new AccesBoutiqueForm();
        acces.setActif(boutique.isActif());
        acces.setAccesOffert(boutique.isAccesOffert());
        model.addAttribute("boutique", boutique);
        model.addAttribute("utilisateurs", plateforme.listerUtilisateurs(id));
        model.addAttribute("accesBoutique", acces);
        model.addAttribute("vueAbonnement", abonnements.consulterPourPlateforme(id, page));
        if (!model.containsAttribute("prolongationEssai")) {
            ProlongationEssaiForm prolongation = new ProlongationEssaiForm();
            prolongation.setJours(essais.dureeEssaiJours());
            prolongation.setJetonAction(UUID.randomUUID().toString());
            model.addAttribute("prolongationEssai", prolongation);
        }
        if (!model.containsAttribute("activationAbonnement")) {
            ActivationAbonnementForm activation = new ActivationAbonnementForm();
            activation.setJetonAction(UUID.randomUUID().toString());
            model.addAttribute("activationAbonnement", activation);
        }
        model.addAttribute("view", "plateforme/boutiques/detail");
        return "dashboard";
    }

    @PostMapping("/{id}/abonnement/essai")
    public String prolongerEssai(@PathVariable Long id,
                                @Valid @ModelAttribute("prolongationEssai") ProlongationEssaiForm formulaire,
                                BindingResult erreurs, Model model, RedirectAttributes redirection) {
        plateforme.verifierAdministrationPlateforme();
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                abonnements.prolongerEssai(id, formulaire);
                redirection.addFlashAttribute("success", "La prolongation de l’essai a été enregistrée.");
                return "redirect:/plateforme/boutiques/" + id;
            } catch (IllegalArgumentException exception) {
                erreurs.reject("essai.invalide", exception.getMessage());
            } catch (DataAccessException exception) {
                erreurs.reject("essai.indisponible", "La prolongation n’a pas pu être enregistrée. Réessayez.");
            }
        }
        return remplirDetail(id, 0, model);
    }

    @PostMapping("/{id}/abonnement/activer")
    public String activerMois(@PathVariable Long id,
                             @Valid @ModelAttribute("activationAbonnement") ActivationAbonnementForm formulaire,
                             BindingResult erreurs, Model model, RedirectAttributes redirection) {
        plateforme.verifierAdministrationPlateforme();
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                abonnements.activerMois(id, formulaire);
                redirection.addFlashAttribute("success", "Un mois d’abonnement a été enregistré.");
                return "redirect:/plateforme/boutiques/" + id;
            } catch (IllegalArgumentException exception) {
                erreurs.reject("abonnement.invalide", exception.getMessage());
            } catch (DataAccessException exception) {
                erreurs.reject("abonnement.indisponible", "Le mois d’abonnement n’a pas pu être enregistré. Réessayez.");
            }
        }
        return remplirDetail(id, 0, model);
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

    @PostMapping("/{id}/utilisateurs/{utilisateurId}/acces")
    public String modifierAccesUtilisateur(@PathVariable Long id, @PathVariable Long utilisateurId,
                                          @Valid @ModelAttribute("accesUtilisateur") AccesUtilisateurForm formulaire,
                                          BindingResult result, RedirectAttributes redirectAttributes) {
        plateforme.verifierAdministrationPlateforme();
        FormBindingAdvice.verifier(result);
        if (result.hasErrors()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le statut du compte est obligatoire et doit être valide");
        accesUtilisateurs.modifierPourPlateforme(id, utilisateurId, formulaire);
        redirectAttributes.addFlashAttribute("success", "Accès au compte utilisateur mis à jour.");
        return "redirect:/plateforme/boutiques/" + id;
    }
}
