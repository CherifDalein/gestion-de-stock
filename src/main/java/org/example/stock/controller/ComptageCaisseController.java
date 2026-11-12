package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.enums.StatutComptage;
import org.example.stock.export.ContexteDocumentService;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.export.TelechargementExport;
import org.example.stock.form.ComptageForm;
import org.example.stock.form.DecisionComptageForm;
import org.example.stock.model.ComptageCaisse;
import org.example.stock.service.Billetage;
import org.example.stock.service.ComptageCaissePdfService;
import org.example.stock.service.ComptageCaisseService;
import org.example.stock.service.ComptageExportService;
import org.example.stock.service.IncoherenceAvanceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Comptages de caisse (étape 3b2). Le caissier et le gérant comptent un compte autorisé et consultent leurs comptages ; le gérant seul
 * décide des écarts (valider, refuser), annule un comptage validé et exporte la liste (règles de {@code SecurityConfig}).
 * La boutique vient toujours du compte connecté ; un comptage d'une autre boutique, ou d'un autre caissier, donne 404.
 */
@Controller
@RequestMapping("/controles/comptages")
public class ComptageCaisseController {
    @Autowired private ComptageCaisseService service;
    @Autowired private ComptageExportService export;
    @Autowired private ComptageCaissePdfService pdf;
    @Autowired private ContexteDocumentService contextes;
    @Autowired private LivraisonExport livraison;

    @InitBinder({"comptageForm", "decisionComptage"})
    public void limiter(WebDataBinder binder, HttpServletRequest request) {
        switch (binder.getObjectName()) {
            case "comptageForm" -> LiaisonFinancements.limiter(binder, request, Set.of("compte", "montantCompte", "commentaire", "jeton"), Set.of("quantites"));
            default -> LiaisonFinancements.limiter(binder, request, Set.of("motif"), Set.of());
        }
    }

    // ---- Liste et saisie ------------------------------------------------------------------------------------

    @GetMapping
    public String liste(@RequestParam(name = "statut", required = false) List<String> statut, @RequestParam(name = "du", required = false) List<String> du,
                        @RequestParam(name = "au", required = false) List<String> au, Model model) {
        ComptageCaisseService.Filtre filtre = filtre(statut, du, au);
        model.addAttribute("comptages", service.lister(filtre));
        model.addAttribute("filtre", filtre);
        model.addAttribute("statuts", StatutComptage.values());
        ajouterNombreEnAttente(model);
        model.addAttribute("view", "controles/comptages");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(Model model) {
        ComptageForm formulaire = new ComptageForm();
        formulaire.setJeton(UUID.randomUUID().toString());
        model.addAttribute("comptageForm", formulaire);
        preparerSaisie(model);
        return "dashboard";
    }

    @PostMapping
    public String enregistrer(@Valid @ModelAttribute("comptageForm") ComptageForm formulaire, BindingResult result, Model model,
                              RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                ComptageCaisse comptage = service.saisir(formulaire.getCompte(), formulaire.getMontantCompte(), formulaire.getQuantites(),
                        formulaire.getCommentaire(), formulaire.getJeton());
                redirection.addFlashAttribute("success", comptage.getStatut() == StatutComptage.CONFORME
                        ? "Comptage enregistré : la caisse est conforme."
                        : "Comptage enregistré avec un écart : il attend la validation du gérant.");
                return "redirect:/controles/comptages/" + comptage.getId();
            } catch (IllegalArgumentException e) {
                result.reject("comptage.invalide", e.getMessage());
            } catch (ConcurrencyFailureException e) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Une autre opération modifie ce compte. Rechargez le formulaire et réessayez.");
            }
        }
        preparerSaisie(model);
        return "dashboard";
    }

    private void preparerSaisie(Model model) {
        model.addAttribute("comptesAComter", service.comptesAComter());
        model.addAttribute("coupures", Billetage.COUPURES);
        model.addAttribute("view", "controles/comptage-form");
    }

    // ---- Décisions du gérant --------------------------------------------------------------------------------

    @GetMapping("/en-attente")
    public String enAttente(Model model) {
        model.addAttribute("comptages", service.enAttente());
        model.addAttribute("decisionComptage", new DecisionComptageForm());
        ajouterNombreEnAttente(model);
        model.addAttribute("view", "controles/comptages-attente");
        return "dashboard";
    }

    @PostMapping("/{id}/valider")
    public String valider(@PathVariable Long id, @Valid @ModelAttribute("decisionComptage") DecisionComptageForm formulaire, BindingResult result,
                          RedirectAttributes redirection) {
        return decider(id, formulaire, result, redirection, "Écart validé : le mouvement « Écart de caisse » est passé sur le compte.",
                () -> service.valider(id, formulaire.getMotif()), "/controles/comptages/en-attente");
    }

    @PostMapping("/{id}/refuser")
    public String refuser(@PathVariable Long id, @Valid @ModelAttribute("decisionComptage") DecisionComptageForm formulaire, BindingResult result,
                          RedirectAttributes redirection) {
        return decider(id, formulaire, result, redirection, "Écart refusé : aucun mouvement n'a été passé.",
                () -> service.refuser(id, formulaire.getMotif()), "/controles/comptages/en-attente");
    }

    @PostMapping("/{id}/annuler")
    public String annuler(@PathVariable Long id, @Valid @ModelAttribute("decisionComptage") DecisionComptageForm formulaire, BindingResult result,
                          RedirectAttributes redirection) {
        return decider(id, formulaire, result, redirection, "Comptage annulé : le mouvement « Écart de caisse » a été contre-passé.",
                () -> service.annuler(id, formulaire.getMotif()), "/controles/comptages/" + id);
    }

    private String decider(Long id, DecisionComptageForm formulaire, BindingResult result, RedirectAttributes redirection, String succes,
                           Runnable action, String retour) {
        FormBindingAdvice.verifier(result);
        String detail = "/controles/comptages/" + id;
        if (result.hasErrors()) {
            redirection.addFlashAttribute("error", result.getAllErrors().get(0).getDefaultMessage());
            return "redirect:" + detail;
        }
        try {
            action.run();
        } catch (IllegalArgumentException | IncoherenceAvanceException e) {
            redirection.addFlashAttribute("error", e.getMessage());
            return "redirect:" + detail;
        } catch (ConcurrencyFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Une autre opération modifie ce comptage. Rechargez la page.");
        }
        redirection.addFlashAttribute("success", succes);
        return "redirect:" + retour;
    }

    // ---- Fiche et documents ---------------------------------------------------------------------------------

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        ComptageCaisse comptage = service.trouver(id);
        model.addAttribute("comptage", comptage);
        model.addAttribute("mouvements", service.mouvementsDe(comptage));
        model.addAttribute("decisionComptage", new DecisionComptageForm());
        ajouterNombreEnAttente(model);
        model.addAttribute("view", "controles/comptage-detail");
        return "dashboard";
    }

    @GetMapping("/{id}/pdf")
    public Object pdf(@PathVariable Long id) {
        ComptageCaisse comptage = service.trouver(id);
        return TelechargementExport.pdf(ComptageCaissePdfService.numero(comptage) + ".pdf", pdf.rendre(comptage, contextes.courant()));
    }

    @GetMapping("/export/excel")
    public Object excel(@RequestParam(name = "statut", required = false) List<String> statut, @RequestParam(name = "du", required = false) List<String> du,
                        @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        ComptageCaisseService.Filtre filtre = filtre(statut, du, au);
        return livraison.excel("comptages", filtre.du(), filtre.au(), "/controles/comptages", filtres(filtre), redirection, () -> export.preparer(filtre));
    }

    @GetMapping("/export/pdf")
    public Object exportPdf(@RequestParam(name = "statut", required = false) List<String> statut, @RequestParam(name = "du", required = false) List<String> du,
                            @RequestParam(name = "au", required = false) List<String> au, RedirectAttributes redirection) {
        ComptageCaisseService.Filtre filtre = filtre(statut, du, au);
        return livraison.pdf("comptages", filtre.du(), filtre.au(), "/controles/comptages", filtres(filtre), redirection, () -> export.preparer(filtre));
    }

    // ---- Outils ---------------------------------------------------------------------------------------------

    private void ajouterNombreEnAttente(Model model) {
        model.addAttribute("nombreEnAttente", service.nombreEnAttente());
    }

    private static final Set<String> NOMS_STATUTS = Stream.of(StatutComptage.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet());

    private static ComptageCaisseService.Filtre filtre(List<String> statut, List<String> du, List<String> au) {
        String nom = ParametresListe.choix("statut", statut, NOMS_STATUTS);
        LocalDate debut = ParametresListe.date(du), fin = ParametresListe.date(au);
        ParametresListe.periodeCoherente(debut, fin);
        return new ComptageCaisseService.Filtre(nom == null ? null : StatutComptage.valueOf(nom), debut, fin);
    }

    private static Map<String, Object> filtres(ComptageCaisseService.Filtre f) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("statut", f.statut() == null ? null : f.statut().name());
        m.put("du", f.du()); m.put("au", f.au());
        return m;
    }
}
