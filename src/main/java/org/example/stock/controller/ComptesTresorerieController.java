package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.form.CompteTresorerieForm;
import org.example.stock.service.ComptesTresorerieService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * Page « Comptes de trésorerie » du gérant (règle de sécurité « /caisse/** ») : ajout, nom, principal, archivage, solde de
 * départ et comptes autorisés à chaque caissier. Tout paramètre inconnu ou répété donne 400.
 */
@Controller
@RequestMapping("/caisse/comptes")
public class ComptesTresorerieController {
    private final ComptesTresorerieService comptes;

    public ComptesTresorerieController(ComptesTresorerieService comptes) { this.comptes = comptes; }

    @InitBinder("compteTresorerie")
    public void limiterChamps(WebDataBinder binder, HttpServletRequest request) {
        if (binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom -> false);
        if (request.getMethod().equals("POST")) exigerParametres(request, Set.of("type", "nom", "numero", "soldeDepart", "_csrf"), Set.of("type", "nom", "numero", "soldeDepart"));
        binder.setAllowedFields("type", "nom", "numero", "soldeDepart", "_csrf");
    }

    @GetMapping
    public String afficher(Model model) {
        if (!model.containsAttribute("compteTresorerie")) model.addAttribute("compteTresorerie", new CompteTresorerieForm());
        model.addAttribute("ecran", comptes.ecran());
        model.addAttribute("types", org.example.stock.enums.TypeCompteTresorerie.values());
        model.addAttribute("view", "caisse/comptes");
        return "dashboard";
    }

    @PostMapping
    public String creer(@Valid @ModelAttribute("compteTresorerie") CompteTresorerieForm formulaire, BindingResult erreurs,
                        Model model, RedirectAttributes redirect) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                var compte = comptes.creer(formulaire);
                redirect.addFlashAttribute("success", "Compte « " + compte.getNom() + " » ajouté.");
                return "redirect:/caisse/comptes";
            } catch (IllegalArgumentException | DataIntegrityViolationException e) {
                model.addAttribute("error", e instanceof IllegalArgumentException ? e.getMessage()
                        : "Un compte de cette boutique porte déjà ce nom. Rechargez la page.");
            }
        }
        return afficher(model);
    }

    @PostMapping("/{id}/modifier")
    public String modifier(@PathVariable Long id, @RequestParam String nom, @RequestParam(required = false) String numero,
                           HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("nom", "numero", "_csrf"), Set.of("nom", "numero"));
        return action(redirect, "Compte modifié.", () -> comptes.modifier(id, nom, numero));
    }

    @PostMapping("/{id}/solde-depart")
    public String soldeDepart(@PathVariable Long id, @RequestParam(name = "montant") BigDecimal montant,
                              HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("montant", "_csrf"), Set.of("montant"));
        return action(redirect, "Solde de départ enregistré.", () -> comptes.definirSoldeDepart(id, montant));
    }

    @PostMapping("/{id}/principal")
    public String principal(@PathVariable Long id, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("_csrf"), Set.of());
        return action(redirect, "Compte principal mis à jour.", () -> comptes.definirPrincipal(id));
    }

    @PostMapping("/{id}/archiver")
    public String archiver(@PathVariable Long id, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("_csrf"), Set.of());
        return action(redirect, "Compte archivé : il n'est plus proposé, son historique est conservé.", () -> comptes.archiver(id));
    }

    @PostMapping("/{id}/desarchiver")
    public String desarchiver(@PathVariable Long id, HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("_csrf"), Set.of());
        return action(redirect, "Compte réactivé.", () -> comptes.desarchiver(id));
    }

    /** Remplace les comptes autorisés d'un caissier ; aucune case cochée : il utilise les comptes principaux. */
    @PostMapping("/autorisations/{utilisateurId}")
    public String autoriser(@PathVariable Long utilisateurId, @RequestParam(name = "comptes", required = false) List<Long> ids,
                            HttpServletRequest requete, RedirectAttributes redirect) {
        exigerParametres(requete, Set.of("comptes", "_csrf"), Set.of());
        return action(redirect, "Comptes autorisés mis à jour.", () -> comptes.autoriser(utilisateurId, ids));
    }

    private String action(RedirectAttributes redirect, String succes, Runnable travail) {
        try {
            travail.run();
            redirect.addFlashAttribute("success", succes);
        } catch (IllegalArgumentException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        } catch (DataIntegrityViolationException e) {
            redirect.addFlashAttribute("error", "Cette modification entre en conflit avec une autre. Rechargez la page.");
        }
        return "redirect:/caisse/comptes";
    }

    /** Liste blanche des paramètres d'une requête POST : un champ inconnu, ou répété s'il ne peut être multiple, donne 400. */
    private static void exigerParametres(HttpServletRequest requete, Set<String> autorises, Set<String> uniques) {
        for (var parametre : requete.getParameterMap().entrySet()) {
            boolean unique = !parametre.getKey().equals("comptes") || uniques.contains("comptes");
            if (!autorises.contains(parametre.getKey()) || (unique && parametre.getValue().length != 1)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
            }
        }
    }
}
