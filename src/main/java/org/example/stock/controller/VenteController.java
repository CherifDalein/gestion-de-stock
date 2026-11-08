package org.example.stock.controller;

import org.example.stock.model.Utilisateur;
import org.example.stock.model.Vente;
import org.example.stock.model.Montants;
import org.example.stock.form.ReglementVenteForm;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ReglementVenteObsoleteException;
import org.example.stock.enums.TypeOperationCreation;
import org.example.stock.service.OperationCreationService;
import org.example.stock.service.CreationOperationException;
import org.example.stock.service.InitialisationBoutiqueService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.annotation.PathVariable;
import org.example.stock.service.ClientService;
import org.example.stock.service.ProduitService;
import org.example.stock.service.VenteService;
import org.example.stock.service.BoutiqueService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.DevisService;
import org.example.stock.model.Devis;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/ventes")
public class VenteController {

    @Autowired private VenteService venteService;
    @Autowired private ProduitService produitService;
    @Autowired private ClientService clientService;
    @Autowired private CaisseService caisseService;
    @Autowired private OperationCreationService creations;
    @Autowired private InitialisationBoutiqueService initialisation;
    @Autowired private EcheanceService echeances;
    @Autowired private BoutiqueService boutiques;
    @Autowired private DevisService devisService;

    @GetMapping
    public String listeVentes(@RequestParam(required = false) String filtre,
                              @RequestParam(name = "vendeur", required = false) List<String> vendeurDemande,
                              Authentication authentication, Model model) {
        boolean echues = "echues".equals(filtre);
        // Le filtre par vendeur est réservé au gérant ; pour un caissier le paramètre est ignoré et la liste reste la même.
        Long vendeurId = null;
        if (estGerant(authentication)) {
            List<Utilisateur> vendeurs = venteService.listerVendeursPossibles();
            model.addAttribute("vendeursFiltre", vendeurs);
            vendeurId = vendeurChoisi(vendeurDemande, vendeurs, model);
        }
        model.addAttribute("vendeurChoisi", vendeurId);
        model.addAttribute("ventes", vendeurId == null
                ? (echues ? venteService.listerEchuesNonSoldees() : venteService.listerToutes())
                : (echues ? venteService.listerEchuesNonSoldeesDuVendeur(vendeurId) : venteService.listerDuVendeur(vendeurId)));
        model.addAttribute("filtreEchues", echues);
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "ventes/liste");
        return "dashboard";
    }

    private static boolean estGerant(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
                .anyMatch(autorite -> "ROLE_ADMIN".equals(autorite.getAuthority()));
    }

    /**
     * Un seul identifiant, numérique, qui désigne un compte de la boutique. Tout le reste (texte, valeurs multiples,
     * compte d'une autre boutique ou inexistant) est ignoré avec un avertissement identique : rien ne révèle l'existence
     * d'un compte d'une autre boutique.
     */
    private static Long vendeurChoisi(List<String> demande, List<Utilisateur> vendeurs, Model model) {
        if (demande == null || (demande.size() == 1 && demande.getFirst().isBlank())) return null;
        if (demande.size() == 1 && demande.getFirst().strip().matches("\\d{1,18}")) {
            long id = Long.parseLong(demande.getFirst().strip());
            if (vendeurs.stream().anyMatch(vendeur -> vendeur.getId() == id)) return id;
        }
        model.addAttribute("avertissementVendeur", "Ce vendeur n'existe pas dans votre boutique : toutes les ventes sont affichées.");
        return null;
    }

    @GetMapping("/nouveau")
    public String nouveauFormulaire(@RequestParam(required = false) Long devis, RedirectAttributes redirectAttributes, Model model) {
        Vente nouvelle = new Vente();
        if (devis != null) {
            // Vente issue d'un devis : client et lignes sont repris du devis (404 s'il est d'une autre boutique).
            Devis source = devisService.trouver(devis);
            String refus = devisService.raisonRefusTransformation(source);
            if (refus != null) {
                redirectAttributes.addFlashAttribute("error", refus);
                return "redirect:/devis/" + devis;
            }
            nouvelle.setClient(source.getClient());
            model.addAttribute("devisVente", source);
        }
        model.addAttribute("vente", nouvelle);
        model.addAttribute("jetonCreation", creations.ouvrir(TypeOperationCreation.VENTE));
        model.addAttribute("clients", clientService.listerTous());
        model.addAttribute("produits", produitService.listerTous());
        model.addAttribute("initialisationOuverte", initialisation.estOuverte());
        model.addAttribute("echeanceProposee", echeances.proposer(boutiques.boutiqueCourante().getDelaiClientsJours()));
        model.addAttribute("view", "ventes/nouveau");
        return "dashboard";
    }

    @PostMapping("/enregistrer")
    public String enregistrerVente(@ModelAttribute("vente") Vente vente, BindingResult result,
                                  @RequestParam(required = false) String jetonCreation, @RequestParam(required = false) Long devis,
                                  RedirectAttributes redirectAttributes,
                                  Model model, HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        model.addAttribute("jetonCreation", jetonCreation);
        // Après un refus, le formulaire réaffiche la saisie, jamais un numéro ou une échéance calculés puis annulés.
        String numeroSaisi = vente.getNumero();
        var echeanceSaisie = vente.getDateEcheance();
        try {
            var resultat = creations.creerVente(jetonCreation, vente, devis);
            redirectAttributes.addFlashAttribute("success", resultat.dejaEnregistre()
                    ? "Cette vente est déjà enregistrée. Le stock et la caisse n'ont pas été modifiés une seconde fois."
                    : "Vente enregistrée avec succès !");
            return "redirect:/ventes";
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e instanceof CreationOperationException conflit) {
                response.setStatus(conflit.getStatus().value());
                model.addAttribute("creationConflit", true);
            }
            vente.setNumero(numeroSaisi);
            vente.setDateEcheance(echeanceSaisie);
            // Après un refus d'une vente issue d'un devis, le formulaire reprend les lignes et les prix du devis.
            if (devis != null) model.addAttribute("devisVente", devisService.trouver(devis));
            model.addAttribute("clients", clientService.listerTous());
            model.addAttribute("produits", produitService.listerTous());
            model.addAttribute("initialisationOuverte", initialisation.estOuverte());
            model.addAttribute("echeanceProposee", echeances.proposer(boutiques.boutiqueCourante().getDelaiClientsJours()));

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
        formulaire.setDateEcheance(vente.getDateEcheance() != null ? vente.getDateEcheance()
                : echeances.proposer(boutiques.boutiqueCourante().getDelaiClientsJours()));
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
                venteService.reglerVente(id, formulaire.getMontant(), formulaire.getMontantVerseAttendu(), formulaire.getComptePaiement(),
                        formulaire.getDateEcheance());
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

    /** Reporte l'échéance sans enregistrer de versement ; le montant éventuellement saisi est ignoré. */
    @PostMapping("/regler/{id}/echeance")
    public String modifierEcheance(@PathVariable Long id, @ModelAttribute("reglementVente") ReglementVenteForm formulaire,
                                   BindingResult result, Model model, RedirectAttributes redirectAttributes,
                                   HttpServletResponse response) {
        FormBindingAdvice.verifier(result);
        try {
            venteService.modifierEcheance(id, formulaire.getDateEcheance());
            redirectAttributes.addFlashAttribute("success", "Échéance de la vente modifiée.");
            return "redirect:/ventes/regler/" + id;
        } catch (ConcurrencyFailureException e) {
            response.setStatus(HttpStatus.CONFLICT.value());
            result.reject("echeance.conflit", "Une autre opération modifie cette vente. Rechargez le formulaire et réessayez.");
        } catch (IllegalArgumentException e) {
            result.reject("echeance.invalide", e.getMessage());
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
        model.addAttribute("aujourdHui", echeances.aujourdHui());
        model.addAttribute("view", "ventes/regler");
    }
}
