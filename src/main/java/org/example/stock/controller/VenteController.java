package org.example.stock.controller;

import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.form.ReglementVenteForm;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ReglementVenteObsoleteException;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.annotation.PathVariable;
import org.example.stock.service.ClientService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.VenteService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/ventes")
public class VenteController {

    @Autowired private VenteService venteService;
    @Autowired private ProduitService produitService;
    @Autowired private ClientService clientService;
    @Autowired private CaisseService caisseService;

    @GetMapping
    public String listeVentes(Model model) {
        model.addAttribute("ventes", venteService.listerToutes());
        model.addAttribute("view", "ventes/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveauFormulaire(Model model) {
        model.addAttribute("vente", new Vente());
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("view", "ventes/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrerVente(@ModelAttribute("vente") Vente vente, BindingResult result, RedirectAttributes redirectAttributes, Model model) {
        FormBindingAdvice.verifier(result);
        try {
            venteService.effectuerVente(vente);
            redirectAttributes.addFlashAttribute("success", "Vente enregistrée avec succès !");
            return "redirect:/ventes";
        } catch (RuntimeException e) {
            model.addAttribute("clients", clientService.listerTous());
            model.addAttribute("produits", produitService.listerTous());

            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie le stock. Réessayez la vente." : e.getMessage());
            model.addAttribute("view", "ventes/nouveau");
            return "dashboard";
        }
    }

    @GetMapping("/regler/{id}")
    public String afficherReglement(@PathVariable Long id, Model model) {
        Vente vente = chargerVente(id);
        ReglementVenteForm formulaire = new ReglementVenteForm();
        formulaire.setMontantVerseAttendu(Montants.ouZero(vente.getMontantVerse()));
        model.addAttribute("reglementVente", formulaire);
        preparerReglement(vente, model);
        return "dashboard";
    }

    @PostMapping("/regler/{id}")
    public String enregistrerReglement(@PathVariable Long id, @Valid @ModelAttribute("reglementVente") ReglementVenteForm formulaire,
                                      BindingResult result, Model model, RedirectAttributes redirectAttributes,
                                      HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                venteService.reglerVente(id, formulaire.getMontant(), formulaire.getMontantVerseAttendu());
                redirectAttributes.addFlashAttribute("success", "Versement client enregistré avec succès.");
                return "redirect:/ventes/regler/" + id;
            } catch (ReglementVenteObsoleteException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("reglement.conflit", e instanceof ReglementVenteObsoleteException ? e.getMessage()
                        : "Une autre opération modifie cette vente. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException e) {
                result.reject("reglement.invalide", e.getMessage());
            } catch (EmptyResultDataAccessException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vente introuvable");
            }
        }
        preparerReglement(chargerVente(id), model);
        return "dashboard";
    }

    private Vente chargerVente(Long id) {
        Vente vente = venteService.trouverParId(id);
        if (vente == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Vente introuvable");
        return vente;
    }

    private void preparerReglement(Vente vente, Model model) {
        model.addAttribute("venteReglement", vente);
        model.addAttribute("reglements", caisseService.listerReglementsVente(vente.getId()));
        model.addAttribute("view", "ventes/regler");
    }
}
