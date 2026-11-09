package org.example.stock.controller;

import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.CategorieExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

/** Exports Excel et PDF de la liste des catégories : réservés au gérant (règle de sécurité « /categories/** »), recherche {@code q=}. */
@Controller
@RequestMapping("/categories/export")
public class CategorieExportController {
    private final CategorieExportService export;
    private final LivraisonExport livraison;

    public CategorieExportController(CategorieExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("categories", "/categories", Map.of(), redirection, () -> export.preparer(recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("categories", "/categories", Map.of(), redirection, () -> export.preparer(recherche));
    }
}
