package org.example.stock.controller;

import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.AchatExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Exports Excel et PDF de la liste des achats. Lecture seule (GET), mêmes filtres et mêmes droits que /achats : réservés au gérant
 * par la règle de sécurité « /achats/** », boutique du compte connecté.
 */
@Controller
@RequestMapping("/achats/export")
public class AchatExportController {
    private final AchatExportService export;
    private final LivraisonExport livraison;

    public AchatExportController(AchatExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "filtre", required = false) List<String> filtre, @RequestParam(name = "q", required = false) List<String> q,
                        RedirectAttributes redirection) {
        boolean echus = echus(filtre);
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("achats", "/achats", retour(echus), redirection, () -> export.preparer(echus, recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "filtre", required = false) List<String> filtre, @RequestParam(name = "q", required = false) List<String> q,
                      RedirectAttributes redirection) {
        boolean echus = echus(filtre);
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("achats", "/achats", retour(echus), redirection, () -> export.preparer(echus, recherche));
    }

    private static boolean echus(List<String> filtre) {
        return ParametresListe.choix("filtre", filtre, Set.of("echues")) != null;
    }

    private static Map<String, Object> retour(boolean echus) {
        Map<String, Object> filtres = new LinkedHashMap<>();
        if (echus) filtres.put("filtre", "echues");
        return filtres;
    }
}
