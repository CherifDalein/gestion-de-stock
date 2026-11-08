package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.stock.form.RetourAchatForm;
import org.example.stock.model.Retour;
import org.example.stock.service.RetourAchatObsoleteException;
import org.example.stock.service.RetourAchatService;
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
 * Retour d'achat (retour au fournisseur) : réservé au gérant (voir SecurityConfig). Tout est lu dans la boutique du compte
 * connecté ; un achat d'une autre boutique est introuvable (404). La liste et l'impression des retours sont dans
 * {@link RetourController}.
 */
@Controller
public class RetourAchatController {
    @Autowired private RetourAchatService retourAchatService;

    @GetMapping("/achats/{id}/retour")
    public String formulaire(@PathVariable Long id, Model model) {
        RetourAchatService.Formulaire formulaire = retourAchatService.preparer(id);
        RetourAchatForm saisie = new RetourAchatForm();
        saisie.setRetoursConnus(formulaire.retoursConnus());
        return page(formulaire, saisie, model);
    }

    @PostMapping("/achats/{id}/retour")
    public String enregistrer(@PathVariable Long id, @ModelAttribute("retourAchatForm") RetourAchatForm saisie, BindingResult result,
                              RedirectAttributes redirectAttributes, Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        try {
            Retour retour = retourAchatService.enregistrer(id, saisie);
            redirectAttributes.addFlashAttribute("success", "Retour " + retour.getNumero() + " enregistré : "
                    + formater(retour.getMontantTotal()) + " GNF"
                    + (retour.getMontantImpute().signum() > 0 ? ", dont " + formater(retour.getMontantImpute()) + " GNF déduits de la dette envers le fournisseur" : "")
                    + (retour.getMontantRembourse().signum() > 0 ? ", dont " + formater(retour.getMontantRembourse()) + " GNF remboursés en argent par le fournisseur" : "")
                    + (retour.getMontantAvoir().signum() > 0 ? ", dont " + formater(retour.getMontantAvoir()) + " GNF gardés en avoir chez le fournisseur" : "") + ".");
            return "redirect:/retours?type=achat";
        } catch (RetourAchatObsoleteException e) {
            // Formulaire périmé ou second envoi : rien n'est enregistré, le formulaire est rouvert avec les quantités à jour.
            response.setStatus(HttpStatus.CONFLICT.value());
            model.addAttribute("error", e.getMessage());
            RetourAchatService.Formulaire frais = retourAchatService.preparer(id);
            RetourAchatForm neuf = new RetourAchatForm();
            neuf.setRetoursConnus(frais.retoursConnus());
            return page(frais, neuf, model);
        } catch (IllegalArgumentException | ConcurrencyFailureException e) {
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie cet achat. Réessayez." : e.getMessage());
            RetourAchatService.Formulaire frais = retourAchatService.preparer(id);
            Map<Long, Integer> saisies = new HashMap<>();
            if (saisie.getLignes() != null) for (RetourAchatForm.Ligne ligne : saisie.getLignes()) {
                if (ligne != null && ligne.getDetailAchatId() != null && ligne.getQuantite() != null) saisies.put(ligne.getDetailAchatId(), ligne.getQuantite());
            }
            model.addAttribute("quantitesSaisies", saisies);
            saisie.setRetoursConnus(frais.retoursConnus());
            return page(frais, saisie, model);
        }
    }

    private String page(RetourAchatService.Formulaire formulaire, RetourAchatForm saisie, Model model) {
        model.addAttribute("formulaire", formulaire);
        model.addAttribute("retourAchatForm", saisie);
        model.addAttribute("view", "achats/retour");
        return "dashboard";
    }

    /** Montant lisible avec espaces de milliers, pour les messages. */
    private static String formater(java.math.BigDecimal montant) {
        return new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(java.util.Locale.FRANCE)).format(montant);
    }
}
