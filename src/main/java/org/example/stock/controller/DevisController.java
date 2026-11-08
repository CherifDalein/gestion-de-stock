package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.stock.enums.EtatDevis;
import org.example.stock.form.DevisForm;
import org.example.stock.form.ProlongationDevisForm;
import org.example.stock.model.Devis;
import org.example.stock.service.ClientService;
import org.example.stock.service.DevisService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.ProduitService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Devis : ouverts au gérant et au caissier (voir SecurityConfig) ; tout est lu dans la boutique du compte connecté. */
@Controller
@RequestMapping("/devis")
public class DevisController {
    @Autowired private DevisService devisService;
    @Autowired private ClientService clientService;
    @Autowired private ProduitService produitService;
    @Autowired private EcheanceService echeances;

    @GetMapping
    public String liste(@RequestParam(required = false) String etat, Model model) {
        EtatDevis filtre = EtatDevis.depuisCode(etat);
        model.addAttribute("devisListe", devisService.lister(filtre));
        model.addAttribute("filtre", filtre);
        model.addAttribute("etats", EtatDevis.values());
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "devis/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        DevisForm form = new DevisForm();
        form.setDateValidite(devisService.validiteProposee());
        return formulaire(form, model);
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("devisForm") DevisForm form, BindingResult result,
                              RedirectAttributes redirectAttributes, Model model) {
        FormBindingAdvice.verifier(result);
        try {
            Devis devis = devisService.creer(form);
            redirectAttributes.addFlashAttribute("success", "Devis " + devis.getNumero() + " enregistré. Il n'a aucun effet sur le stock ni sur la caisse.");
            return "redirect:/devis/" + devis.getId();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IllegalArgumentException | ConcurrencyFailureException e) {
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération est en cours. Réessayez." : e.getMessage());
            return formulaire(form, model);
        }
    }

    @GetMapping("/{id}")
    public String voir(@PathVariable Long id, Model model) {
        return pageDevis(id, model, new ProlongationDevisForm());
    }

    @GetMapping("/{id}/imprimer")
    public String imprimer(@PathVariable Long id, Model model) {
        model.addAttribute("devis", devisService.trouver(id));
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        return "devis/impression";
    }

    @PostMapping("/{id}/prolonger")
    public String prolonger(@PathVariable Long id, @ModelAttribute("prolongationDevis") ProlongationDevisForm form,
                            BindingResult result, RedirectAttributes redirectAttributes, Model model) {
        FormBindingAdvice.verifier(result);
        try {
            devisService.prolonger(id, form.getDateValidite());
            redirectAttributes.addFlashAttribute("success", "Validité du devis prolongée.");
            return "redirect:/devis/" + id;
        } catch (IllegalArgumentException | ConcurrencyFailureException e) {
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie ce devis. Rechargez la page et réessayez." : e.getMessage());
            return pageDevis(id, model, form);
        }
    }

    @PostMapping("/{id}/annuler")
    public String annuler(@PathVariable Long id, HttpServletRequest request, RedirectAttributes redirectAttributes, Model model) {
        // Aucune donnée n'est attendue : seul le jeton CSRF accompagne la demande.
        if (request.getParameterMap().keySet().stream().anyMatch(nom -> !nom.equals("_csrf"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Champ de formulaire non autorisé");
        }
        try {
            devisService.annuler(id);
            redirectAttributes.addFlashAttribute("success", "Devis annulé.");
            return "redirect:/devis/" + id;
        } catch (IllegalArgumentException | ConcurrencyFailureException e) {
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie ce devis. Rechargez la page et réessayez." : e.getMessage());
            return pageDevis(id, model, new ProlongationDevisForm());
        }
    }

    private String formulaire(DevisForm form, Model model) {
        model.addAttribute("devisForm", form);
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "devis/nouveau");
        return "dashboard";
    }

    private String pageDevis(Long id, Model model, ProlongationDevisForm prolongation) {
        Devis devis = devisService.trouver(id);
        if (prolongation.getDateValidite() == null) {
            prolongation.setDateValidite(devis.getDateValidite().isBefore(echeances.aujourdHui()) ? echeances.aujourdHui() : devis.getDateValidite().plusDays(DevisService.VALIDITE_PAR_DEFAUT_JOURS));
        }
        model.addAttribute("devis", devis);
        model.addAttribute("prolongationDevis", prolongation);
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "devis/voir");
        return "dashboard";
    }
}
