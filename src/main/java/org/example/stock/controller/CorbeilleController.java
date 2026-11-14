package org.example.stock.controller;

import org.example.stock.service.CorbeilleService;
import org.example.stock.service.TypeCorbeille;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page « Corbeille » du gérant (lot 6) : les fiches supprimées depuis moins de {@code stock.corbeille.retention-jours} jours, à restaurer ou
 * à effacer. Réservée au gérant par {@code SecurityConfig} (le service le revérifie). Aucun modèle de formulaire n'est lié : seuls des
 * paramètres simples sont lus, donc rien à ajouter à la liste blanche de {@code FormBindingAdvice}.
 */
@Controller
@RequestMapping("/corbeille")
public class CorbeilleController {
    @Autowired private CorbeilleService corbeille;

    @GetMapping
    public String liste(@RequestParam(name = "type", required = false) String type, @RequestParam(name = "q", required = false) String q, Model model) {
        TypeCorbeille filtre = type == null || type.isBlank() ? null : TypeCorbeille.parCode(type)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Type inconnu"));
        String recherche = q == null ? "" : q.strip();
        if (recherche.length() > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Recherche trop longue");
        var compteurs = corbeille.compter();
        model.addAttribute("lignes", corbeille.lister(filtre, recherche));
        model.addAttribute("compteurs", compteurs);
        model.addAttribute("total", compteurs.values().stream().mapToInt(Integer::intValue).sum());
        model.addAttribute("types", TypeCorbeille.values());
        model.addAttribute("typeFiltre", filtre == null ? "" : filtre.code());
        model.addAttribute("recherche", recherche);
        model.addAttribute("retentionJours", corbeille.retentionJours());
        model.addAttribute("view", "corbeille/liste");
        return "dashboard";
    }

    /** {@code depuis=liste} : le lien « Annuler » d'une suppression ramène à la liste d'origine plutôt qu'à la corbeille. */
    @PostMapping("/restaurer/{type}/{id}")
    public String restaurer(@PathVariable("type") String type, @PathVariable("id") Long id,
                            @RequestParam(name = "depuis", required = false) String depuis, RedirectAttributes redirection) {
        TypeCorbeille t = type(type);
        boolean versLaListe = "liste".equals(depuis);
        try {
            var restaure = corbeille.restaurer(t, id);
            redirection.addFlashAttribute("success", restaure.type().libelle() + " " + restaure.nom() + " " + t.restaure() + ".");
        } catch (IllegalArgumentException e) {
            redirection.addFlashAttribute("error", e.getMessage());
            versLaListe = false;
        }
        return "redirect:" + (versLaListe ? "/" + t.route() : "/corbeille");
    }

    @PostMapping("/supprimer/{type}/{id}")
    public String supprimerDefinitivement(@PathVariable("type") String type, @PathVariable("id") Long id, RedirectAttributes redirection) {
        TypeCorbeille t = type(type);
        try {
            var efface = corbeille.supprimerDefinitivement(t, id);
            redirection.addFlashAttribute("success", efface.type().libelle() + " " + efface.nom() + " " + t.efface() + " définitivement.");
        } catch (IllegalArgumentException e) {
            redirection.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/corbeille";
    }

    @PostMapping("/vider")
    public String vider(RedirectAttributes redirection) {
        var bilan = corbeille.vider();
        if (bilan.effaces() == 0 && bilan.conserves() == 0) redirection.addFlashAttribute("success", "La corbeille est déjà vide.");
        else {
            redirection.addFlashAttribute("success", bilan.effaces() + (bilan.effaces() == 1 ? " fiche effacée définitivement." : " fiches effacées définitivement."));
            if (bilan.conserves() > 0) redirection.addFlashAttribute("error", bilan.conserves() + (bilan.conserves() == 1
                    ? " fiche est restée dans la corbeille : une autre fiche s’y réfère encore."
                    : " fiches sont restées dans la corbeille : d’autres fiches s’y réfèrent encore."));
        }
        return "redirect:/corbeille";
    }

    private static TypeCorbeille type(String code) {
        return TypeCorbeille.parCode(code).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Type inconnu"));
    }
}
