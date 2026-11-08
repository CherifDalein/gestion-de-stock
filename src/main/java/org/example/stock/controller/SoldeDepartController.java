package org.example.stock.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.form.ReglementSoldeForm;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ClientService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.IncoherenceAvanceException;
import org.example.stock.service.ReglementSoldeObsoleteException;
import org.example.stock.service.SoldeDepartService;
import org.example.stock.service.SoldePartenaire;
import org.example.stock.service.SoldePartenaireService;
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
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;

/**
 * Règlement du solde de départ des clients (gérant et caissier, comme les règlements de ventes) et des fournisseurs
 * (gérant seulement, comme les règlements d'achats), et lecture de l'avance d'un partenaire pour les formulaires
 * de vente et d'achat. La boutique vient toujours du compte connecté : un partenaire d'une autre boutique donne 404.
 */
@Controller
public class SoldeDepartController {
    @Autowired private SoldeDepartService soldeDepart;
    @Autowired private SoldePartenaireService soldes;
    @Autowired private ClientService clientService;
    @Autowired private FournisseurService fournisseurService;
    @Autowired private CaisseService caisseService;

    /** Avance disponible d'un client, à titre d'information : le calcul qui fait foi reste celui du serveur, sous verrou. */
    @GetMapping("/clients/{id}/avance")
    @ResponseBody
    public Map<String, String> avanceClient(@PathVariable Long id) {
        return Map.of("avance", soldes.avanceClient(id).toPlainString());
    }

    @GetMapping("/fournisseurs/{id}/avance")
    @ResponseBody
    public Map<String, String> avanceFournisseur(@PathVariable Long id) {
        return Map.of("avance", soldes.avanceFournisseur(id).toPlainString());
    }

    // ---- Clients --------------------------------------------------------------------------------------------

    @GetMapping("/clients/solde/regler/{id}")
    public String afficherClient(@PathVariable Long id, Model model) {
        SoldePartenaire solde = soldes.soldeClient(id);
        ReglementSoldeForm formulaire = new ReglementSoldeForm();
        formulaire.setReglementAttendu(solde.soldeDepartRegle());
        formulaire.setDateEcheance(solde.echeanceSoldeDepart());
        model.addAttribute("reglementSolde", formulaire);
        preparerClient(id, model);
        return "dashboard";
    }

    @PostMapping("/clients/solde/regler/{id}")
    public String enregistrerClient(@PathVariable Long id, @Valid @ModelAttribute("reglementSolde") ReglementSoldeForm formulaire,
                                    BindingResult result, Model model, RedirectAttributes redirectAttributes, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                soldeDepart.reglerClient(id, formulaire.getMontant(), formulaire.getReglementAttendu(), formulaire.getComptePaiement(), formulaire.getDateEcheance());
                redirectAttributes.addFlashAttribute("success", "Règlement du solde de départ enregistré avec succès.");
                return "redirect:/clients/solde/regler/" + id;
            } catch (ReglementSoldeObsoleteException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("reglement.conflit", e instanceof ReglementSoldeObsoleteException ? e.getMessage()
                        : "Une autre opération modifie ce client. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException | IncoherenceAvanceException e) {
                result.reject("reglement.invalide", e.getMessage());
            }
        }
        preparerClient(id, model);
        return "dashboard";
    }

    private void preparerClient(Long id, Model model) {
        var client = clientService.trouverParId(id);
        model.addAttribute("partenaireNom", client.getNom());
        model.addAttribute("typePartenaire", "client");
        model.addAttribute("solde", soldes.soldeClient(id));
        var reglements = caisseService.listerReglementsSoldeClient(id);
        model.addAttribute("reglements", reglements);
        model.addAttribute("annulations", caisseService.annulationsDe(reglements));
        model.addAttribute("actionUrl", "/clients/solde/regler/" + id);
        model.addAttribute("retourUrl", "/clients");
        model.addAttribute("view", "partenaires/regler-solde");
    }

    // ---- Fournisseurs ---------------------------------------------------------------------------------------

    @GetMapping("/fournisseurs/solde/regler/{id}")
    public String afficherFournisseur(@PathVariable Long id, Model model) {
        SoldePartenaire solde = soldes.soldeFournisseur(id);
        ReglementSoldeForm formulaire = new ReglementSoldeForm();
        formulaire.setReglementAttendu(solde.soldeDepartRegle());
        formulaire.setDateEcheance(solde.echeanceSoldeDepart());
        model.addAttribute("reglementSolde", formulaire);
        preparerFournisseur(id, model);
        return "dashboard";
    }

    @PostMapping("/fournisseurs/solde/regler/{id}")
    public String enregistrerFournisseur(@PathVariable Long id, @Valid @ModelAttribute("reglementSolde") ReglementSoldeForm formulaire,
                                         BindingResult result, Model model, RedirectAttributes redirectAttributes, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                soldeDepart.reglerFournisseur(id, formulaire.getMontant(), formulaire.getReglementAttendu(), formulaire.getComptePaiement(), formulaire.getDateEcheance());
                redirectAttributes.addFlashAttribute("success", "Règlement du solde de départ enregistré avec succès.");
                return "redirect:/fournisseurs/solde/regler/" + id;
            } catch (ReglementSoldeObsoleteException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("reglement.conflit", e instanceof ReglementSoldeObsoleteException ? e.getMessage()
                        : "Une autre opération modifie ce fournisseur. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException | IncoherenceAvanceException e) {
                result.reject("reglement.invalide", e.getMessage());
            }
        }
        preparerFournisseur(id, model);
        return "dashboard";
    }

    private void preparerFournisseur(Long id, Model model) {
        var fournisseur = fournisseurService.trouverParId(id);
        model.addAttribute("partenaireNom", fournisseur.getNom());
        model.addAttribute("typePartenaire", "fournisseur");
        model.addAttribute("solde", soldes.soldeFournisseur(id));
        var reglements = caisseService.listerReglementsSoldeFournisseur(id);
        model.addAttribute("reglements", reglements);
        model.addAttribute("annulations", caisseService.annulationsDe(reglements));
        model.addAttribute("actionUrl", "/fournisseurs/solde/regler/" + id);
        model.addAttribute("retourUrl", "/fournisseurs");
        model.addAttribute("view", "partenaires/regler-solde");
    }
}
