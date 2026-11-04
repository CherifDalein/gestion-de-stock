package org.example.stock.controller;

import org.example.stock.service.CaisseService;
import org.example.stock.service.InitialisationBoutiqueService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.LocalDate;
import org.example.stock.form.OuvertureCaisseForm;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.ExtendedServletRequestDataBinder;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.Set;
import java.util.UUID;
import org.example.stock.form.TransfertTresorerieForm;
import org.example.stock.form.DepenseTresorerieForm;
import org.example.stock.service.OperationTresorerieConflitException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.servlet.http.HttpServletResponse;

@Controller
@RequestMapping("/caisse")
public class CaisseController {

    @Autowired
    private CaisseService caisseService;
    @Autowired private InitialisationBoutiqueService initialisation;

    @InitBinder({"ouvertureCaisse", "transfertTresorerie", "depenseTresorerie"})
    public void limiterOuverture(WebDataBinder binder,HttpServletRequest request) {
        if(binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom->false);
        Set<String> champs = switch (binder.getObjectName()) {
            case "ouvertureCaisse" -> Set.of("montant", "motif", "comptePaiement", "_csrf");
            case "transfertTresorerie" -> Set.of("compteSource", "compteDestination", "montant", "motif", "jetonOperation", "_csrf");
            case "depenseTresorerie" -> Set.of("compteSource", "montant", "motif", "jetonOperation", "_csrf");
            default -> throw new IllegalArgumentException("Formulaire inconnu");
        };
        if(request.getMethod().equals("POST")) for(var param:request.getParameterMap().entrySet())
            if(!champs.contains(param.getKey())||param.getValue().length!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Champ de formulaire non autorisé");
        binder.setAllowedFields(champs.toArray(String[]::new));
    }
    @GetMapping("/ouverture")
    public String ouverture(Model model) {
        var comptesInitialisables = caisseService.getComptesInitialisables();
        if(!model.containsAttribute("ouvertureCaisse")) {
            var formulaire = new OuvertureCaisseForm();
            if (!comptesInitialisables.isEmpty()) formulaire.setComptePaiement(comptesInitialisables.getFirst());
            model.addAttribute("ouvertureCaisse", formulaire);
        }
        model.addAttribute("comptesInitialisables", comptesInitialisables);
        model.addAttribute("soldesParCompte", caisseService.getSoldesParCompte());
        model.addAttribute("initialAutorise",!comptesInitialisables.isEmpty());model.addAttribute("soldeTotal",caisseService.getSoldeActuel());
        model.addAttribute("initialisationOuverte", initialisation.estOuverte());
        model.addAttribute("view","caisse/ouverture");return "dashboard";
    }
    @PostMapping("/ouverture")
    public String ouvrir(@Valid @ModelAttribute("ouvertureCaisse") OuvertureCaisseForm form,BindingResult erreurs,Model model,RedirectAttributes redirect) {
        FormBindingAdvice.verifier(erreurs);
        if(!erreurs.hasErrors()) {
            try {caisseService.initialiser(form);redirect.addFlashAttribute("success","Solde initial enregistré sur le compte " + form.getComptePaiement().getLibelle() + ".");return "redirect:/caisse/journal";}
            catch(IllegalArgumentException e){model.addAttribute("error",e.getMessage());}
        }
        return ouverture(model);
    }

    @GetMapping("/journal")
    public String afficherJournal(Model model) {
        LocalDate aujourdHui = LocalDate.now();

        model.addAttribute("mouvements", caisseService.listerTous());
        model.addAttribute("mouvementsDuJour", caisseService.getMouvementsDuJour(aujourdHui));
        model.addAttribute("soldeTotal", caisseService.getSoldeActuel());
        model.addAttribute("soldeOuverture", caisseService.getSoldeOuverture(aujourdHui));
        model.addAttribute("entreesJour", caisseService.getEntreesDuJour(aujourdHui));
        model.addAttribute("sortiesJour", caisseService.getSortiesDuJour(aujourdHui));
        model.addAttribute("bilanJour", caisseService.getNetDuJour(aujourdHui));
        model.addAttribute("soldeCloture", caisseService.getSoldeCloture(aujourdHui));
        model.addAttribute("soldesParCompte", caisseService.getSoldesParCompte());
        model.addAttribute("initialAutorise", !caisseService.getComptesInitialisables().isEmpty());
        model.addAttribute("view", "caisse/journal");
        return "dashboard";
    }

    @GetMapping("/transfert")
    public String transfert(Model model) {
        if (!model.containsAttribute("transfertTresorerie")) {
            var formulaire = new TransfertTresorerieForm();
            formulaire.setJetonOperation(UUID.randomUUID().toString());
            model.addAttribute("transfertTresorerie", formulaire);
        }
        return preparerOperation("caisse/transfert", model);
    }

    @PostMapping("/transfert")
    public String transferer(@Valid @ModelAttribute("transfertTresorerie") TransfertTresorerieForm formulaire,
                            BindingResult erreurs, Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                boolean repetition = caisseService.transferer(formulaire);
                redirect.addFlashAttribute("success", repetition ? "Ce transfert est déjà enregistré." : "Transfert enregistré. Le total de la boutique est inchangé.");
                return "redirect:/caisse/journal";
            } catch (OperationTresorerieConflitException exception) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("transfert.conflit", exception.getMessage());
            } catch (IllegalArgumentException exception) {
                erreurs.reject("transfert.invalide", exception.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException exception) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("transfert.conflit", "Une autre opération a modifié la trésorerie. Rechargez le formulaire et vérifiez le journal avant de réessayer.");
            }
        }
        return transfert(model);
    }

    @GetMapping("/depense")
    public String depense(Model model) {
        if (!model.containsAttribute("depenseTresorerie")) {
            var formulaire = new DepenseTresorerieForm();
            formulaire.setJetonOperation(UUID.randomUUID().toString());
            model.addAttribute("depenseTresorerie", formulaire);
        }
        return preparerOperation("caisse/depense", model);
    }

    @PostMapping("/depense")
    public String depenser(@Valid @ModelAttribute("depenseTresorerie") DepenseTresorerieForm formulaire,
                           BindingResult erreurs, Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                boolean repetition = caisseService.depenser(formulaire);
                redirect.addFlashAttribute("success", repetition ? "Cette dépense est déjà enregistrée." : "Dépense enregistrée.");
                return "redirect:/caisse/journal";
            } catch (OperationTresorerieConflitException exception) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("depense.conflit", exception.getMessage());
            } catch (IllegalArgumentException exception) {
                erreurs.reject("depense.invalide", exception.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException exception) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("depense.conflit", "Une autre opération a modifié la trésorerie. Rechargez le formulaire et vérifiez le journal avant de réessayer.");
            }
        }
        return depense(model);
    }

    private String preparerOperation(String vue, Model model) {
        caisseService.verifierGestionTresorerie();
        model.addAttribute("soldesParCompte", caisseService.getSoldesParCompte());
        model.addAttribute("soldeTotal", caisseService.getSoldeActuel());
        model.addAttribute("view", vue);
        return "dashboard";
    }
}
