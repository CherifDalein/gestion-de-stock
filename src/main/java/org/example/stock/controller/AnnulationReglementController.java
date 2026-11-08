package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.AnnulationReglementForm;
import org.example.stock.service.AnnulationDejaFaiteException;
import org.example.stock.service.AnnulationReglementService;
import org.example.stock.service.AnnulationReglementService.Cible;
import org.example.stock.service.IncoherenceAvanceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Annulation d'un règlement : page de confirmation (montant, date, compte, motif obligatoire) puis enregistrement.
 * Réservée au gérant : les adresses des ventes et des clients sont fermées au caissier dans {@code SecurityConfig}
 * (403), et le service le contrôle encore. Le règlement doit appartenir au document de l'adresse, dans la boutique
 * du compte connecté (404 sinon).
 */
@Controller
public class AnnulationReglementController {
    @Autowired private AnnulationReglementService annulations;

    // ---- Ventes ---------------------------------------------------------------------------------------------

    @GetMapping("/ventes/regler/{id}/annuler/{mouvementId}")
    public String afficherVente(@PathVariable Long id, @PathVariable Long mouvementId, Model model) {
        return afficher(Cible.VENTE, id, mouvementId, model, new AnnulationReglementForm());
    }

    @PostMapping("/ventes/regler/{id}/annuler/{mouvementId}")
    public String annulerVente(@PathVariable Long id, @PathVariable Long mouvementId,
                               @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm formulaire, BindingResult result,
                               Model model, RedirectAttributes redirect, HttpServletResponse response) {
        return enregistrer(Cible.VENTE, id, mouvementId, formulaire, result, model, redirect, response);
    }

    // ---- Achats ---------------------------------------------------------------------------------------------

    @GetMapping("/achats/regler/{id}/annuler/{mouvementId}")
    public String afficherAchat(@PathVariable Long id, @PathVariable Long mouvementId, Model model) {
        return afficher(Cible.ACHAT, id, mouvementId, model, new AnnulationReglementForm());
    }

    @PostMapping("/achats/regler/{id}/annuler/{mouvementId}")
    public String annulerAchat(@PathVariable Long id, @PathVariable Long mouvementId,
                               @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm formulaire, BindingResult result,
                               Model model, RedirectAttributes redirect, HttpServletResponse response) {
        return enregistrer(Cible.ACHAT, id, mouvementId, formulaire, result, model, redirect, response);
    }

    // ---- Soldes de départ -----------------------------------------------------------------------------------

    @GetMapping("/clients/solde/regler/{id}/annuler/{mouvementId}")
    public String afficherSoldeClient(@PathVariable Long id, @PathVariable Long mouvementId, Model model) {
        return afficher(Cible.SOLDE_CLIENT, id, mouvementId, model, new AnnulationReglementForm());
    }

    @PostMapping("/clients/solde/regler/{id}/annuler/{mouvementId}")
    public String annulerSoldeClient(@PathVariable Long id, @PathVariable Long mouvementId,
                                     @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm formulaire, BindingResult result,
                                     Model model, RedirectAttributes redirect, HttpServletResponse response) {
        return enregistrer(Cible.SOLDE_CLIENT, id, mouvementId, formulaire, result, model, redirect, response);
    }

    @GetMapping("/fournisseurs/solde/regler/{id}/annuler/{mouvementId}")
    public String afficherSoldeFournisseur(@PathVariable Long id, @PathVariable Long mouvementId, Model model) {
        return afficher(Cible.SOLDE_FOURNISSEUR, id, mouvementId, model, new AnnulationReglementForm());
    }

    @PostMapping("/fournisseurs/solde/regler/{id}/annuler/{mouvementId}")
    public String annulerSoldeFournisseur(@PathVariable Long id, @PathVariable Long mouvementId,
                                          @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm formulaire, BindingResult result,
                                          Model model, RedirectAttributes redirect, HttpServletResponse response) {
        return enregistrer(Cible.SOLDE_FOURNISSEUR, id, mouvementId, formulaire, result, model, redirect, response);
    }

    // ---- Commun ---------------------------------------------------------------------------------------------

    private String afficher(Cible cible, Long id, Long mouvementId, Model model, AnnulationReglementForm formulaire) {
        model.addAttribute("annulationReglement", formulaire);
        preparer(cible, id, mouvementId, model);
        return "dashboard";
    }

    private String enregistrer(Cible cible, Long id, Long mouvementId, AnnulationReglementForm formulaire, BindingResult result,
                               Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                switch (cible) {
                    case VENTE -> annulations.annulerReglementVente(id, mouvementId, formulaire.getMotif());
                    case ACHAT -> annulations.annulerReglementAchat(id, mouvementId, formulaire.getMotif());
                    case SOLDE_CLIENT -> annulations.annulerReglementSoldeClient(id, mouvementId, formulaire.getMotif());
                    case SOLDE_FOURNISSEUR -> annulations.annulerReglementSoldeFournisseur(id, mouvementId, formulaire.getMotif());
                }
                redirect.addFlashAttribute("success", "Règlement annulé. Un mouvement inverse a été enregistré dans le journal de caisse.");
                return "redirect:" + retourUrl(cible, id);
            } catch (AnnulationDejaFaiteException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("annulation.dejaFaite", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                // Deux annulations simultanées : la contrainte d'unicité ou le verrou n'en laisse passer qu'une.
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("annulation.conflit", "Une autre opération a modifié ce règlement ou la trésorerie. Vérifiez l'historique avant de réessayer.");
            } catch (IllegalArgumentException | IncoherenceAvanceException e) {
                result.reject("annulation.refusee", e.getMessage());
            }
        }
        preparer(cible, id, mouvementId, model);
        return "dashboard";
    }

    private void preparer(Cible cible, Long id, Long mouvementId, Model model) {
        model.addAttribute("confirmation", annulations.preparer(cible, id, mouvementId));
        model.addAttribute("nomDocument", switch (cible) {
            case VENTE -> "Règlement d'une vente";
            case ACHAT -> "Règlement d'un achat";
            case SOLDE_CLIENT -> "Règlement du solde de départ d'un client";
            case SOLDE_FOURNISSEUR -> "Règlement du solde de départ d'un fournisseur";
        });
        model.addAttribute("encaissement", cible == Cible.VENTE || cible == Cible.SOLDE_CLIENT);
        model.addAttribute("actionUrl", retourUrl(cible, id) + "/annuler/" + mouvementId);
        model.addAttribute("retourUrl", retourUrl(cible, id));
        model.addAttribute("view", "reglements/annuler");
    }

    private static String retourUrl(Cible cible, Long id) {
        return switch (cible) {
            case VENTE -> "/ventes/regler/" + id;
            case ACHAT -> "/achats/regler/" + id;
            case SOLDE_CLIENT -> "/clients/solde/regler/" + id;
            case SOLDE_FOURNISSEUR -> "/fournisseurs/solde/regler/" + id;
        };
    }
}
