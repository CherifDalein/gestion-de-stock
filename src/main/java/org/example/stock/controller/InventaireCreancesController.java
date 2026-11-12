package org.example.stock.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.form.InventaireCreancesForm;
import org.example.stock.model.InventaireCreances;
import org.example.stock.service.EcheanceService;
import org.example.stock.service.InventaireCreancesExportService;
import org.example.stock.service.InventaireCreancesService;
import org.example.stock.service.InventaireCreancesService.Saisie;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inventaire des créances et dettes (étape 3b2), réservé au gérant ({@code SecurityConfig}, « /controles/** »). L'inventaire n'a aucun effet
 * sur les soldes. La boutique vient toujours du compte connecté ; un inventaire ou un partenaire d'une autre boutique donne 404.
 */
@Controller
@RequestMapping("/controles/creances")
public class InventaireCreancesController {
    @Autowired private InventaireCreancesService service;
    @Autowired private InventaireCreancesExportService export;
    @Autowired private LivraisonExport livraison;
    @Autowired private EcheanceService echeances;

    @InitBinder("inventaireForm")
    public void limiter(WebDataBinder binder, HttpServletRequest request) {
        LiaisonFinancements.limiter(binder, request, Set.of("dateInventaire", "note", "jeton"), Set.of("cles", "montants", "sens", "raisons"));
    }

    @GetMapping
    public String liste(Model model) {
        model.addAttribute("inventaires", service.lister());
        model.addAttribute("view", "controles/creances");
        return "dashboard";
    }

    @GetMapping("/nouveau")
    public String nouveau(@RequestParam(name = "q", required = false) List<String> q, Model model) {
        InventaireCreancesForm formulaire = new InventaireCreancesForm();
        formulaire.setJeton(UUID.randomUUID().toString());
        formulaire.setDateInventaire(echeances.aujourdHui());
        model.addAttribute("inventaireForm", formulaire);
        preparer(ParametresListe.recherche(q), model);
        return "dashboard";
    }

    @PostMapping
    public String enregistrer(@Valid @ModelAttribute("inventaireForm") InventaireCreancesForm formulaire, BindingResult result, Model model,
                              RedirectAttributes redirection) {
        FormBindingAdvice.verifier(result);
        if (!result.hasErrors()) {
            try {
                List<Saisie> saisies = new ArrayList<>();
                int n = formulaire.getCles().size();
                if (formulaire.getMontants().size() > n || formulaire.getSens().size() > n || formulaire.getRaisons().size() > n) {
                    throw new IllegalArgumentException("Rechargez le formulaire d'inventaire.");
                }
                for (int i = 0; i < n; i++) {
                    saisies.add(new Saisie(formulaire.getCles().get(i), element(formulaire.getMontants(), i), element(formulaire.getSens(), i), element(formulaire.getRaisons(), i)));
                }
                InventaireCreances inventaire = service.enregistrer(formulaire.getDateInventaire(), formulaire.getNote(), formulaire.getJeton(), saisies);
                redirection.addFlashAttribute("success", "Inventaire enregistré : aucun solde n'a été modifié.");
                return "redirect:/controles/creances/" + inventaire.getId();
            } catch (IllegalArgumentException e) {
                result.reject("inventaire.invalide", e.getMessage());
            }
        }
        preparer("", model);
        return "dashboard";
    }

    private static <T> T element(List<T> liste, int i) { return i < liste.size() ? liste.get(i) : null; }

    private void preparer(String recherche, Model model) {
        model.addAttribute("candidats", service.candidats(recherche));
        model.addAttribute("recherche", recherche);
        model.addAttribute("view", "controles/creances-form");
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable Long id, Model model) {
        model.addAttribute("inventaire", service.trouver(id));
        model.addAttribute("view", "controles/creances-detail");
        return "dashboard";
    }

    @GetMapping("/{id}/export/excel")
    public Object excel(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.excel("inventaire-" + InventaireCreancesExportService.numero(service.trouver(id)).toLowerCase(), "/controles/creances/" + id, Map.of(), redirection, () -> export.preparer(id));
    }

    @GetMapping("/{id}/export/pdf")
    public Object pdf(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.pdf("inventaire-" + InventaireCreancesExportService.numero(service.trouver(id)).toLowerCase(), "/controles/creances/" + id, Map.of(), redirection, () -> export.preparer(id));
    }
}
