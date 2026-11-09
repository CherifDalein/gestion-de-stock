package org.example.stock.controller;

import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.ProduitExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports Excel et PDF de la liste des produits. Même droit que l'écran : gérant et caissier (règle de sécurité propre aux deux routes
 * d'export, voir SecurityConfig) ; les colonnes de coût et de marge ne sont données qu'au gérant, décidé côté serveur.
 * Filtres : {@code categorie=<id>}, {@code stock=<état>} et {@code q=}.
 */
@Controller
@RequestMapping("/produits/export")
public class ProduitExportController {
    private final ProduitExportService export;
    private final LivraisonExport livraison;

    public ProduitExportController(ProduitExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "categorie", required = false) List<String> categorie, @RequestParam(name = "stock", required = false) List<String> stock,
                        @RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        Long categorieId = ParametresListe.identifiant("categorie", categorie);
        String etat = ParametresListe.choix("stock", stock, ProduitExportService.ETATS);
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("produits", "/produits", retour(etat), redirection, () -> export.preparer(categorieId, etat, recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "categorie", required = false) List<String> categorie, @RequestParam(name = "stock", required = false) List<String> stock,
                      @RequestParam(name = "q", required = false) List<String> q, RedirectAttributes redirection) {
        Long categorieId = ParametresListe.identifiant("categorie", categorie);
        String etat = ParametresListe.choix("stock", stock, ProduitExportService.ETATS);
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("produits", "/produits", retour(etat), redirection, () -> export.preparer(categorieId, etat, recherche));
    }

    /** À la volée, la liste revient avec le filtre de stock (le seul que la page lit dans son adresse). */
    private static Map<String, Object> retour(String etat) {
        Map<String, Object> filtres = new LinkedHashMap<>();
        if (etat != null) filtres.put("stock", etat);
        return filtres;
    }
}
