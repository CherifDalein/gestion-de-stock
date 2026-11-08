package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.form.RetourVenteForm;
import org.example.stock.model.Retour;
import org.example.stock.service.RetourVenteObsoleteException;
import org.example.stock.service.RetourVenteService;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Retour de vente : réservé au gérant (voir SecurityConfig). Tout est lu dans la boutique du compte connecté ; une vente
 * d'une autre boutique est introuvable (404). La liste et l'impression des retours sont dans {@link RetourController}.
 */
@Controller
public class RetourVenteController {
    @Autowired private RetourVenteService retourVenteService;

    @GetMapping("/ventes/{id}/retour")
    public String formulaire(@PathVariable Long id, Model model) {
        RetourVenteService.Formulaire formulaire = retourVenteService.preparer(id);
        RetourVenteForm saisie = new RetourVenteForm();
        saisie.setRetoursConnus(formulaire.retoursConnus());
        return page(formulaire, saisie, model);
    }

    @PostMapping("/ventes/{id}/retour")
    public String enregistrer(@PathVariable Long id, @ModelAttribute("retourVenteForm") RetourVenteForm saisie, BindingResult result,
                              RedirectAttributes redirectAttributes, Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        try {
            Retour retour = retourVenteService.enregistrer(id, saisie);
            redirectAttributes.addFlashAttribute("success", "Retour " + retour.getNumero() + " enregistré : "
                    + formater(retour.getMontantTotal()) + " GNF"
                    + (retour.getMontantImpute().signum() > 0 ? ", dont " + formater(retour.getMontantImpute()) + " GNF déduits de la dette du client" : "")
                    + (retour.getMontantRembourse().signum() > 0 ? ", dont " + formater(retour.getMontantRembourse()) + " GNF remboursés en argent" : "")
                    + (retour.getMontantAvoir().signum() > 0 ? ", dont " + formater(retour.getMontantAvoir()) + " GNF gardés en avoir" : "") + ".");
            return "redirect:/retours";
        } catch (RetourVenteObsoleteException e) {
            // Formulaire périmé ou second envoi : rien n'est enregistré, le formulaire est rouvert avec les quantités à jour.
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("error", e.getMessage());
            RetourVenteService.Formulaire frais = retourVenteService.preparer(id);
            RetourVenteForm neuf = new RetourVenteForm();
            neuf.setRetoursConnus(frais.retoursConnus());
            return page(frais, neuf, model);
        } catch (IllegalArgumentException | ConcurrencyFailureException e) {
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie cette vente. Réessayez." : e.getMessage());
            RetourVenteService.Formulaire frais = retourVenteService.preparer(id);
            Map<Long, Integer> saisies = new HashMap<>();
            if (saisie.getLignes() != null) for (RetourVenteForm.Ligne ligne : saisie.getLignes()) {
                if (ligne != null && ligne.getDetailVenteId() != null && ligne.getQuantite() != null) saisies.put(ligne.getDetailVenteId(), ligne.getQuantite());
            }
            model.addAttribute("quantitesSaisies", saisies);
            saisie.setRetoursConnus(frais.retoursConnus());
            return page(frais, saisie, model);
        }
    }

    private String page(RetourVenteService.Formulaire formulaire, RetourVenteForm saisie, Model model) {
        model.addAttribute("formulaire", formulaire);
        model.addAttribute("retourVenteForm", saisie);
        model.addAttribute("view", "ventes/retour");
        return "dashboard";
    }

    /** Montant lisible avec espaces de milliers, pour les messages. */
    private static String formater(java.math.BigDecimal montant) {
        return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.FRANCE)).format(montant);
    }
}
