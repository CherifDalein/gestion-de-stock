package org.example.stock.controller;

import org.example.stock.model.Achat;
import org.example.stock.service.AchatService;
import org.example.stock.service.FournisseurService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.CaisseService;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.ModificationAchatRefuseeException;
import org.example.stock.service.ReglementAchatObsoleteException;
import org.example.stock.form.ReglementAchatForm;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.CreationOperationException;
import org.example.stock.service.InitialisationBoutiqueService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.time.LocalDate;

@Controller
@RequestMapping("/achats")
public class AchatController {
    @Autowired private AchatService service;
    @Autowired private ProduitService produitService;
    @Autowired private FournisseurService fournisseurService;
    @Autowired private CaisseService caisseService;
    @Autowired private OperationCreationService creations;
    @Autowired private InitialisationBoutiqueService initialisation;
    @Autowired private EcheanceService echeances;
    @Autowired private BoutiqueService boutiques;
    @Autowired private org.example.stock.recu.RecuService recus;

    @GetMapping
    public String listeVentes(@RequestParam(required = false) String filtre, Model model) {
        boolean echus = "echues".equals(filtre);
        model.addAttribute("achats", echus ? service.listerEchusNonSoldes() : service.listerTous());
        model.addAttribute("filtreEchues", echus);
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "achats/liste");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        Achat achat = new Achat();
        achat.setLignes(new ArrayList<>());

        model.addAttribute("achat", achat);
        model.addAttribute("jetonCreation", creations.ouvrir(TypeOperationCreation.ACHAT));
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("initialisationOuverte", initialisation.estOuverte());
        model.addAttribute("echeanceProposee", echeanceProposee());
        model.addAttribute("view", "achats/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrer(@ModelAttribute("achat") Achat achat, BindingResult result,
                              @RequestParam(required = false) String jetonCreation, RedirectAttributes redirectAttributes,
                              Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        model.addAttribute("jetonCreation", jetonCreation);
        // Après un refus, le formulaire réaffiche la saisie, jamais un numéro ou une échéance calculés puis annulés.
        String numeroSaisi = achat.getNumero();
        var echeanceSaisie = achat.getDateEcheance();
        try {
            var resultat = creations.creerAchat(jetonCreation, achat);
            redirectAttributes.addFlashAttribute("success", resultat.dejaEnregistre()
                    ? "Cet achat est déjà enregistré. Le stock et la caisse n'ont pas été modifiés une seconde fois."
                    : "Achat enregistre avec succes !");
            return "redirect:/achats";
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof CreationOperationException conflit) {
                response.setStatus(conflit.getStatus().value());
                model.addAttribute("creationConflit", true);
            }
            achat.setNumero(numeroSaisi);
            achat.setDateEcheance(echeanceSaisie);
            model.addAttribute("achat", achat);
            model.addAttribute("produits", produitService.listerTous());
            model.addAttribute("fournisseurs", fournisseurService.listerTous());
            model.addAttribute("initialisationOuverte", initialisation.estOuverte());
            model.addAttribute("echeanceProposee", echeanceProposee());
            model.addAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie le stock. Réessayez l'achat." : e.getMessage());
            model.addAttribute("view", "achats/nouveau");
            return "dashboard";
        }
    }

    @GetMapping("/modifier/{id}")
    public String afficherModifier(@PathVariable Long id, Model model, RedirectAttributes redirectAttributes) {
        Achat achat = service.trouverParId(id);
        if (achat == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable");
        // Un achat qui a un retour au fournisseur est figé : on l'explique avant d'ouvrir un formulaire inutile.
        if (service.aDesRetours(id)) {
            redirectAttributes.addFlashAttribute("error", ModificationAchatRefuseeException.MESSAGE);
            return "redirect:/achats";
        }

        model.addAttribute("achat", achat);
        model.addAttribute("fournisseurs", fournisseurService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("echeanceProposee", achat.getDateEcheance() != null ? achat.getDateEcheance() : echeanceProposee());
        model.addAttribute("view", "achats/modifier");
        return "dashboard";
    }

    @PostMapping("/modifier/{id}")
    public String enregistrerModification(@PathVariable Long id, @ModelAttribute("achat") Achat achat, BindingResult result, RedirectAttributes redirectAttributes) {
        FormBindingAdvice.verifier(result);
        try {
            service.modifierAchat(id, achat);
            return "redirect:/achats?success=modifie";
        } catch (ResponseStatusException e) {
            throw e;
        } catch (ModificationAchatRefuseeException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/achats";
        } catch (Exception e) {
            redirectAttributes.addFlashAttribute("error", e instanceof ConcurrencyFailureException
                    ? "Une autre opération modifie cet achat ou son stock. Réessayez la modification." : e.getMessage());
            return "redirect:/achats/modifier/" + id;
        }
    }

    @GetMapping("/regler/{id}")
    public String afficherReglement(@PathVariable Long id, Model model) {
        Achat achat = chargerAchat(id);
        ReglementAchatForm formulaire = new ReglementAchatForm();
        formulaire.setMontantVerseAttendu(achat.getMontantVerse() == null ? BigDecimal.ZERO : achat.getMontantVerse());
        formulaire.setDateEcheance(achat.getDateEcheance() != null ? achat.getDateEcheance() : echeanceProposee());
        model.addAttribute("reglementAchat", formulaire);
        preparerReglement(achat, model);
        return "dashboard";
    }

    @PostMapping("/regler/{id}")
    public String enregistrerReglement(@PathVariable Long id, @Valid @ModelAttribute("reglementAchat") ReglementAchatForm formulaire,
                                      BindingResult result, Model model, RedirectAttributes redirectAttributes,
                                      HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                Long mouvementId = service.reglerAchat(id, formulaire.getMontant(), formulaire.getMontantVerseAttendu(), formulaire.getComptePaiement(),
                        formulaire.getDateEcheance());
                redirectAttributes.addFlashAttribute("success", "Versement fournisseur enregistré avec succès.");
                String recu = recus.proposer("/achats/regler/" + id + "/recu/" + mouvementId);
                if (recu != null) redirectAttributes.addFlashAttribute("recuUrl", recu);
                return "redirect:/achats/regler/" + id;
            } catch (ReglementAchatObsoleteException | ConcurrencyFailureException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                result.reject("reglement.conflit", e instanceof ReglementAchatObsoleteException ? e.getMessage()
                        : "Une autre opération modifie cet achat. Rechargez le formulaire et réessayez.");
            } catch (IllegalArgumentException e) {
                result.reject("reglement.invalide", e.getMessage());
            } catch (EmptyResultDataAccessException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable");
            }
        }
        preparerReglement(chargerAchat(id), model);
        return "dashboard";
    }

    /** Reporte l'échéance sans enregistrer de versement ; le montant éventuellement saisi est ignoré. */
    @PostMapping("/regler/{id}/echeance")
    public String modifierEcheance(@PathVariable Long id, @ModelAttribute("reglementAchat") ReglementAchatForm formulaire,
                                   BindingResult result, Model model, RedirectAttributes redirectAttributes,
                                   HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        try {
            service.modifierEcheance(id, formulaire.getDateEcheance());
            redirectAttributes.addFlashAttribute("success", "Échéance de l’achat modifiée.");
            return "redirect:/achats/regler/" + id;
        } catch (ConcurrencyFailureException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            result.reject("echeance.conflit", "Une autre opération modifie cet achat. Rechargez le formulaire et réessayez.");
        } catch (IllegalArgumentException e) {
            result.reject("echeance.invalide", e.getMessage());
        }
        preparerReglement(chargerAchat(id), model);
        return "dashboard";
    }

    private LocalDate echeanceProposee() {
        return echeances.proposer(boutiques.boutiqueCourante().getDelaiFournisseursJours());
    }

    private Achat chargerAchat(Long id) {
        Achat achat = service.trouverParId(id);
        if (achat == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Achat introuvable");
        return achat;
    }

    private void preparerReglement(Achat achat, Model model) {
        model.addAttribute("achatReglement", achat);
        var reglements = caisseService.listerReglementsAchat(achat.getId());
        model.addAttribute("reglements", reglements);
        model.addAttribute("annulations", caisseService.annulationsDe(reglements));
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "achats/regler");
    }
}
