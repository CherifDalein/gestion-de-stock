package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.example.stock.enums.Periodicite;
import org.example.stock.enums.SensEmprunt;
import org.example.stock.enums.TypeOperationTresorerie;
import org.example.stock.enums.TypePartenaireFinancier;
import org.example.stock.form.AnnulationReglementForm;
import org.example.stock.form.EcheancierForm;
import org.example.stock.form.EmpruntForm;
import org.example.stock.form.OperationFinanciereForm;
import org.example.stock.form.RemboursementForm;
import org.example.stock.model.OperationTresorerie;
import org.example.stock.service.AnnulationDejaFaiteException;
import org.example.stock.service.CaisseService;
import org.example.stock.service.ComptesTresorerieService;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.EmpruntObsoleteException;
import org.example.stock.service.EmpruntService;
import org.example.stock.service.OperationTresorerieConflitException;
import org.example.stock.service.OperationsFinancieresService;
import org.example.stock.service.PartenairesFinanciersService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Financements du gérant (étape 3b1) : emprunts et prêts avec leur échéancier, remboursements, apports et retraits des associés, produits et
 * charges hors activité courante. Tout est réservé au gérant : {@code SecurityConfig} refuse le caissier (403) sur {@code /financements/**},
 * et les services le revérifient. La boutique vient du compte connecté ; un document d'une autre boutique est introuvable (404).
 */
@Controller
@RequestMapping("/financements")
public class FinancementsController {
    @Autowired private EmpruntService emprunts;
    @Autowired private OperationsFinancieresService operations;
    @Autowired private PartenairesFinanciersService partenaires;
    @Autowired private CaisseService caisse;
    @Autowired private ComptesTresorerieService comptes;
    @Autowired private EcheanceService echeances;
    @Autowired private org.example.stock.repository.OperationTresorerieRepository operationsRepo;
    @Autowired private org.example.stock.service.BoutiqueService boutiques;

    @InitBinder({"empruntForm", "echeancierForm", "remboursementForm", "annulationReglement", "operationFinanciere"})
    public void limiter(WebDataBinder binder, HttpServletRequest request) {
        switch (binder.getObjectName()) {
            case "empruntForm" -> LiaisonFinancements.limiter(binder, request, Set.of("sens", "partenaireId", "compte", "dateEmprunt", "capital", "tauxAnnuel",
                    "nombreEcheances", "periodicite", "datePremiereEcheance", "fraisDossier", "note", "jetonOperation"), Set.of());
            case "echeancierForm" -> LiaisonFinancements.limiter(binder, request, Set.of(), Set.of("dates", "capitaux"));
            case "remboursementForm" -> LiaisonFinancements.limiter(binder, request, Set.of("montant", "compte", "anticipe", "rangAttendu", "resteAttendu", "note", "jetonOperation"), Set.of());
            case "annulationReglement" -> LiaisonFinancements.limiter(binder, request, Set.of("motif"), Set.of());
            case "operationFinanciere" -> LiaisonFinancements.limiter(binder, request, Set.of("type", "partenaireId", "compte", "montant", "motif", "jetonOperation"), Set.of());
            default -> throw new IllegalArgumentException("Formulaire inconnu");
        }
    }

    // ---- Liste ---------------------------------------------------------------------------------------------------------------

    @GetMapping({"", "/", "/emprunts"})
    public String liste(@RequestParam(name = "statut", required = false) List<String> statut, @RequestParam(name = "sens", required = false) List<String> sens, Model model) {
        String s = unique(statut), d = unique(sens);
        if (s != null && !Set.of("en-cours", "retard", "clos").contains(s)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Statut inconnu");
        if (d != null && !Set.of("EMPRUNT", "PRET").contains(d)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sens inconnu");
        List<EmpruntService.Vue> tous = emprunts.lister();
        List<EmpruntService.Vue> vues = tous.stream()
                .filter(v -> s == null || switch (s) { case "retard" -> v.situation().enRetardMaintenant(); case "clos" -> v.situation().clos(); default -> !v.situation().clos(); })
                .filter(v -> d == null || v.emprunt().getSens().name().equals(d)).toList();
        BigDecimal restantEmprunts = BigDecimal.ZERO, restantPrets = BigDecimal.ZERO;
        for (var v : tous) {
            if (v.emprunt().getSens() == SensEmprunt.EMPRUNT) restantEmprunts = restantEmprunts.add(v.situation().capitalRestantDu());
            else restantPrets = restantPrets.add(v.situation().capitalRestantDu());
        }
        model.addAttribute("vues", vues);
        model.addAttribute("total", tous.size());
        model.addAttribute("restantEmprunts", restantEmprunts);
        model.addAttribute("restantPrets", restantPrets);
        model.addAttribute("retards", emprunts.retards(0));
        model.addAttribute("filtreStatut", s);
        model.addAttribute("filtreSens", d);
        model.addAttribute("view", "financements/emprunts");
        return "dashboard";
    }

    private static String unique(List<String> valeurs) {
        if (valeurs == null) return null;
        if (valeurs.size() != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Une seule valeur est acceptée");
        return valeurs.getFirst().isBlank() ? null : valeurs.getFirst().strip();
    }

    // ---- Création d'un emprunt ou d'un prêt ----------------------------------------------------------------------------------

    @GetMapping("/emprunts/nouveau")
    public String nouveau(@RequestParam(name = "sens", required = false) List<String> sens, Model model) {
        if (!model.containsAttribute("empruntForm")) {
            var f = new EmpruntForm();
            String d = unique(sens);
            if (d != null) {
                try { f.setSens(SensEmprunt.valueOf(d)); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sens inconnu"); }
            }
            f.setJetonOperation(UUID.randomUUID().toString());
            f.setDateEmprunt(echeances.aujourdHui());
            f.setDatePremiereEcheance(echeances.aujourdHui().plusMonths(1));
            f.setCompte(f.getSens() == SensEmprunt.PRET ? "ESPECES" : "BANQUE");
            model.addAttribute("empruntForm", f);
        }
        return preparerFormulaireEmprunt(model);
    }

    @PostMapping("/emprunts/nouveau")
    public String creer(@Valid @ModelAttribute("empruntForm") EmpruntForm f, BindingResult erreurs, Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                var creation = emprunts.creer(f);
                redirect.addFlashAttribute("success", creation.repetition() ? "Ce dossier est déjà enregistré." : (f.getSens() == SensEmprunt.EMPRUNT
                        ? "Emprunt enregistré : le capital est entré sur le compte choisi." : "Prêt enregistré : le capital est sorti du compte choisi."));
                return "redirect:/financements/emprunts/" + creation.id();
            } catch (OperationTresorerieConflitException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("emprunt.conflit", e.getMessage());
            } catch (IllegalArgumentException e) {
                erreurs.reject("emprunt.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("emprunt.conflit", "Une autre opération a modifié la trésorerie. Rechargez le formulaire et vérifiez la liste avant de réessayer.");
            }
        }
        return preparerFormulaireEmprunt(model);
    }

    private String preparerFormulaireEmprunt(Model model) {
        caisse.verifierGestionTresorerie();
        model.addAttribute("partenairesActifs", partenaires.actifs(null));
        model.addAttribute("periodicites", Periodicite.values());
        model.addAttribute("soldesParCompte", caisse.getSoldesParCompte());
        model.addAttribute("view", "financements/emprunt-form");
        return "dashboard";
    }

    // ---- Fiche ---------------------------------------------------------------------------------------------------------------

    @GetMapping("/emprunts/{id}")
    public String fiche(@PathVariable Long id, Model model) {
        model.addAttribute("detail", emprunts.detail(id));
        model.addAttribute("view", "financements/emprunt");
        return "dashboard";
    }

    // ---- Modification de l'échéancier ------------------------------------------------------------------------------------------

    @GetMapping("/emprunts/{id}/echeancier")
    public String echeancier(@PathVariable Long id, Model model) {
        var vue = emprunts.vue(id);
        if (!model.containsAttribute("echeancierForm")) {
            var f = new EcheancierForm();
            for (var l : vue.situation().lignes()) { f.getDates().add(l.date()); f.getCapitaux().add(l.echeance().getCapital()); }
            model.addAttribute("echeancierForm", f);
        }
        return afficherEcheancier(vue, model);
    }

    @PostMapping("/emprunts/{id}/echeancier")
    public String modifierEcheancier(@PathVariable Long id, @Valid @ModelAttribute("echeancierForm") EcheancierForm f, BindingResult erreurs, Model model,
                                     RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                emprunts.modifierEcheancier(id, f);
                redirect.addFlashAttribute("success", "Échéancier modifié : les intérêts ont été recalculés.");
                return "redirect:/financements/emprunts/" + id;
            } catch (IllegalArgumentException e) {
                erreurs.reject("echeancier.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("echeancier.conflit", "Une autre opération a modifié cet emprunt. Rechargez la page avant de réessayer.");
            }
        }
        return afficherEcheancier(emprunts.vue(id), model);
    }

    private String afficherEcheancier(EmpruntService.Vue vue, Model model) {
        model.addAttribute("vue", vue);
        model.addAttribute("modifiable", !vue.situation().aUnRemboursement());
        model.addAttribute("view", "financements/echeancier-form");
        return "dashboard";
    }

    // ---- Remboursement ---------------------------------------------------------------------------------------------------------

    @GetMapping("/emprunts/{id}/rembourser")
    public String rembourser(@PathVariable Long id, Model model) {
        var vue = emprunts.vue(id);
        if (!model.containsAttribute("remboursementForm")) {
            var f = new RemboursementForm();
            f.setJetonOperation(UUID.randomUUID().toString());
            var prochaine = vue.situation().prochaine();
            if (prochaine != null) {
                f.setRangAttendu(prochaine.rang());
                f.setResteAttendu(vue.situation().totalRestantDu());
                f.setMontant(prochaine.resteTotal());
            }
            f.setCompte(vue.emprunt().getCompte().isActif() ? String.valueOf(vue.emprunt().getCompte().getId()) : "ESPECES");
            model.addAttribute("remboursementForm", f);
        }
        return afficherRemboursement(vue, model);
    }

    @PostMapping("/emprunts/{id}/rembourser")
    public String enregistrerRemboursement(@PathVariable Long id, @Valid @ModelAttribute("remboursementForm") RemboursementForm f, BindingResult erreurs, Model model,
                                           RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                boolean repetition = emprunts.rembourser(id, f);
                redirect.addFlashAttribute("success", repetition ? "Ce remboursement est déjà enregistré." : "Remboursement enregistré.");
                return "redirect:/financements/emprunts/" + id;
            } catch (OperationTresorerieConflitException | EmpruntObsoleteException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("remboursement.conflit", e.getMessage());
            } catch (IllegalArgumentException e) {
                erreurs.reject("remboursement.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("remboursement.conflit", "Une autre opération a modifié la trésorerie ou cet emprunt. Rechargez la page et vérifiez l'emprunt avant de réessayer.");
            }
        }
        return afficherRemboursement(emprunts.vue(id), model);
    }

    private String afficherRemboursement(EmpruntService.Vue vue, Model model) {
        model.addAttribute("vue", vue);
        model.addAttribute("soldesParCompte", caisse.getSoldesParCompte());
        model.addAttribute("view", "financements/remboursement");
        return "dashboard";
    }

    // ---- Annulation d'un remboursement -------------------------------------------------------------------------------------------

    @GetMapping("/emprunts/{id}/remboursements/{operationId}/annuler")
    public String annulerRemboursement(@PathVariable Long id, @PathVariable Long operationId, Model model) {
        model.addAttribute("annulationReglement", new AnnulationReglementForm());
        return afficherAnnulationRemboursement(id, operationId, model);
    }

    @PostMapping("/emprunts/{id}/remboursements/{operationId}/annuler")
    public String confirmerAnnulationRemboursement(@PathVariable Long id, @PathVariable Long operationId,
                                                   @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm f, BindingResult erreurs, Model model,
                                                   RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                emprunts.annulerRemboursement(id, operationId, f.getMotif());
                redirect.addFlashAttribute("success", "Remboursement annulé. Des mouvements inverses ont été enregistrés dans le journal de caisse.");
                return "redirect:/financements/emprunts/" + id;
            } catch (AnnulationDejaFaiteException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("annulation.dejaFaite", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("annulation.conflit", "Une autre opération a modifié ce remboursement ou la trésorerie. Vérifiez l'historique avant de réessayer.");
            } catch (IllegalArgumentException e) {
                erreurs.reject("annulation.refusee", e.getMessage());
            }
        }
        return afficherAnnulationRemboursement(id, operationId, model);
    }

    private String afficherAnnulationRemboursement(Long id, Long operationId, Model model) {
        var detail = emprunts.detail(id);
        var remboursement = detail.remboursements().stream().filter(r -> r.operation().getId().equals(operationId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Remboursement introuvable"));
        model.addAttribute("detail", detail);
        model.addAttribute("remboursement", remboursement);
        model.addAttribute("actionUrl", "/financements/emprunts/" + id + "/remboursements/" + operationId + "/annuler");
        model.addAttribute("retourUrl", "/financements/emprunts/" + id);
        model.addAttribute("view", "financements/annuler-remboursement");
        return "dashboard";
    }

    // ---- Apports, retraits, produits et charges ----------------------------------------------------------------------------------

    private static final List<TypeOperationTresorerie> TYPES_LISTE = List.copyOf(OperationsFinancieresService.TYPES_SAISISSABLES.stream()
            .sorted().toList());

    @GetMapping("/operations")
    public String listeOperations(@RequestParam(name = "groupe", required = false) List<String> groupe, Model model) {
        String g = unique(groupe);
        if (g != null && !Set.of("associes", "autres").contains(g)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Groupe inconnu");
        caisse.verifierGestionTresorerie();
        Long boutiqueId = boutiques.idBoutiqueCourante();
        List<TypeOperationTresorerie> types = TYPES_LISTE.stream()
                .filter(t -> g == null || ("associes".equals(g) ? t.estApportOuRetrait() : t.estProduitOuCharge())).toList();
        List<OperationTresorerie> liste = operationsRepo.deTypes(boutiqueId, types);
        Set<Long> annulees = new java.util.HashSet<>(liste.isEmpty() ? List.of() : operationsRepo.annulees(liste.stream().map(OperationTresorerie::getId).toList()));
        model.addAttribute("operations", liste);
        model.addAttribute("annulees", annulees);
        model.addAttribute("filtreGroupe", g);
        model.addAttribute("view", "financements/operations");
        return "dashboard";
    }

    @GetMapping("/operations/nouvelle")
    public String nouvelleOperation(@RequestParam(name = "type", required = false) List<String> type, @RequestParam(name = "partenaire", required = false) List<String> partenaire, Model model) {
        if (!model.containsAttribute("operationFinanciere")) {
            var f = new OperationFinanciereForm();
            String t = unique(type);
            if (t != null) {
                try { f.setType(TypeOperationTresorerie.valueOf(t)); } catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nature inconnue"); }
                if (!OperationsFinancieresService.TYPES_SAISISSABLES.contains(f.getType())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nature inconnue");
            } else f.setType(TypeOperationTresorerie.APPORT_COMPTE_COURANT);
            String p = unique(partenaire);
            if (p != null) {
                if (!p.matches("\\d{1,18}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Partenaire invalide");
                f.setPartenaireId(Long.valueOf(p));
            }
            f.setJetonOperation(UUID.randomUUID().toString());
            model.addAttribute("operationFinanciere", f);
        }
        return preparerFormulaireOperation(model);
    }

    @PostMapping("/operations/nouvelle")
    public String enregistrerOperation(@Valid @ModelAttribute("operationFinanciere") OperationFinanciereForm f, BindingResult erreurs, Model model,
                                       RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                boolean repetition = operations.enregistrer(f);
                redirect.addFlashAttribute("success", repetition ? "Cette opération est déjà enregistrée." : "Opération enregistrée.");
                return "redirect:/financements/operations";
            } catch (OperationTresorerieConflitException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("operation.conflit", e.getMessage());
            } catch (IllegalArgumentException e) {
                erreurs.reject("operation.invalide", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("operation.conflit", "Une autre opération a modifié la trésorerie. Rechargez le formulaire et vérifiez la liste avant de réessayer.");
            }
        }
        return preparerFormulaireOperation(model);
    }

    private String preparerFormulaireOperation(Model model) {
        caisse.verifierGestionTresorerie();
        model.addAttribute("associes", partenaires.actifs(TypePartenaireFinancier.ASSOCIE));
        model.addAttribute("partenairesActifs", partenaires.actifs(null));
        model.addAttribute("types", TYPES_LISTE);
        model.addAttribute("soldesParCompte", caisse.getSoldesParCompte());
        model.addAttribute("view", "financements/operation-form");
        return "dashboard";
    }

    @GetMapping("/operations/{id}/annuler")
    public String annulerOperation(@PathVariable Long id, Model model) {
        model.addAttribute("annulationReglement", new AnnulationReglementForm());
        return afficherAnnulationOperation(id, model);
    }

    @PostMapping("/operations/{id}/annuler")
    public String confirmerAnnulationOperation(@PathVariable Long id, @Valid @ModelAttribute("annulationReglement") AnnulationReglementForm f, BindingResult erreurs,
                                               Model model, RedirectAttributes redirect, HttpServletResponse response) {
        FormBindingAdvice.verifier(erreurs);
        if (!erreurs.hasErrors()) {
            try {
                operations.annuler(id, f.getMotif());
                redirect.addFlashAttribute("success", "Opération annulée. Un mouvement inverse a été enregistré dans le journal de caisse.");
                return "redirect:/financements/operations";
            } catch (AnnulationDejaFaiteException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("annulation.dejaFaite", e.getMessage());
            } catch (ConcurrencyFailureException | DataIntegrityViolationException e) {
                response.setStatus(HttpStatus.CONFLICT.value());
                erreurs.reject("annulation.conflit", "Une autre opération a modifié cette opération ou la trésorerie. Vérifiez la liste avant de réessayer.");
            } catch (IllegalArgumentException e) {
                erreurs.reject("annulation.refusee", e.getMessage());
            }
        }
        return afficherAnnulationOperation(id, model);
    }

    private String afficherAnnulationOperation(Long id, Model model) {
        caisse.verifierGestionTresorerie();
        OperationTresorerie operation = operationsRepo.findByIdAndBoutiqueId(id, boutiques.idBoutiqueCourante())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opération introuvable"));
        model.addAttribute("operation", operation);
        model.addAttribute("dejaAnnulee", !operationsRepo.annulees(List.of(id)).isEmpty());
        model.addAttribute("annulable", OperationsFinancieresService.TYPES_SAISISSABLES.contains(operation.getType()));
        model.addAttribute("actionUrl", "/financements/operations/" + id + "/annuler");
        model.addAttribute("retourUrl", "/financements/operations");
        model.addAttribute("view", "financements/annuler-operation");
        return "dashboard";
    }
}
