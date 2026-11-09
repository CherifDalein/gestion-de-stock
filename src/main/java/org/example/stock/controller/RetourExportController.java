package org.example.stock.controller;

import org.example.stock.enums.TypeRetour;
import org.example.stock.export.LivraisonExport;
import org.example.stock.export.ParametresListe;
import org.example.stock.service.RetourExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exports Excel et PDF de la liste des retours : réservés au gérant (règle de sécurité « /retours/** »), filtre {@code type=vente|achat} et recherche {@code q=}. */
@Controller
@RequestMapping("/retours/export")
public class RetourExportController {
    private final RetourExportService export;
    private final LivraisonExport livraison;

    public RetourExportController(RetourExportService export, LivraisonExport livraison) {
        this.export = export; this.livraison = livraison;
    }

    @GetMapping("/excel")
    public Object excel(@RequestParam(name = "type", required = false) List<String> type, @RequestParam(name = "q", required = false) List<String> q,
                        RedirectAttributes redirection) {
        String choix = ParametresListe.choix("type", type, Set.of("vente", "achat"));
        String recherche = ParametresListe.recherche(q);
        return livraison.excel("retours", "/retours", retour(choix), redirection, () -> export.preparer(typeRetour(choix), recherche));
    }

    @GetMapping("/pdf")
    public Object pdf(@RequestParam(name = "type", required = false) List<String> type, @RequestParam(name = "q", required = false) List<String> q,
                      RedirectAttributes redirection) {
        String choix = ParametresListe.choix("type", type, Set.of("vente", "achat"));
        String recherche = ParametresListe.recherche(q);
        return livraison.pdf("retours", "/retours", retour(choix), redirection, () -> export.preparer(typeRetour(choix), recherche));
    }

    private static TypeRetour typeRetour(String choix) {
        return choix == null ? null : ("vente".equals(choix) ? TypeRetour.VENTE : TypeRetour.ACHAT);
    }

    private static Map<String, Object> retour(String choix) {
        Map<String, Object> filtres = new LinkedHashMap<>();
        if (choix != null) filtres.put("type", choix);
        return filtres;
    }
}
