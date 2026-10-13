package org.example.stock.controller;

import org.example.stock.repository.MouvementCaisseRepository;
import org.example.stock.service.CaisseService;
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

@Controller
@RequestMapping("/caisse")
public class CaisseController {

    @Autowired
    private CaisseService caisseService;

    @Autowired
    private MouvementCaisseRepository mouvementRepo;

    @InitBinder("ouvertureCaisse")
    public void limiterOuverture(WebDataBinder binder,HttpServletRequest request) {
        if(binder instanceof ExtendedServletRequestDataBinder servlet) servlet.addHeaderPredicate(nom->false);
        Set<String> champs=Set.of("montant","motif","_csrf");
        if(request.getMethod().equals("POST")) for(var param:request.getParameterMap().entrySet())
            if(!champs.contains(param.getKey())||param.getValue().length!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Champ de formulaire non autorisé");
        binder.setAllowedFields(champs.toArray(String[]::new));
    }
    @GetMapping("/ouverture")
    public String ouverture(Model model) {
        if(!model.containsAttribute("ouvertureCaisse")) model.addAttribute("ouvertureCaisse",new OuvertureCaisseForm());
        model.addAttribute("initialAutorise",caisseService.peutInitialiser());model.addAttribute("soldeTotal",caisseService.getSoldeActuel());
        model.addAttribute("view","caisse/ouverture");return "dashboard";
    }
    @PostMapping("/ouverture")
    public String ouvrir(@Valid @ModelAttribute("ouvertureCaisse") OuvertureCaisseForm form,BindingResult erreurs,Model model,RedirectAttributes redirect) {
        FormBindingAdvice.verifier(erreurs);
        if(!erreurs.hasErrors()) {
            try {caisseService.initialiser(form);redirect.addFlashAttribute("success","Solde initial de caisse enregistré.");return "redirect:/caisse/journal";}
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
        model.addAttribute("view", "caisse/journal");
        return "dashboard";
    }
}
