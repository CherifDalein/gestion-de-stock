package org.example.stock.controller;

import org.example.stock.export.LivraisonExport;
import org.example.stock.service.FinancementsExportService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports Excel et PDF des financements : liste des partenaires, relevé d'un partenaire, échéancier d'un emprunt ou d'un prêt.
 * Gérant seulement (règle {@code /financements/**}).
 */
@Controller
public class FinancementsExportController {
    private final FinancementsExportService export;
    private final LivraisonExport livraison;

    public FinancementsExportController(FinancementsExportService export, LivraisonExport livraison) { this.export = export; this.livraison = livraison; }

    @GetMapping("/financements/partenaires/export/excel")
    public Object partenairesExcel(@RequestParam(name = "archives", required = false) List<String> archives, RedirectAttributes redirection) {
        return partenaires(true, archives, redirection);
    }

    @GetMapping("/financements/partenaires/export/pdf")
    public Object partenairesPdf(@RequestParam(name = "archives", required = false) List<String> archives, RedirectAttributes redirection) {
        return partenaires(false, archives, redirection);
    }

    private Object partenaires(boolean excel, List<String> archives, RedirectAttributes redirection) {
        boolean avec = archives != null && archives.size() == 1 && "1".equals(archives.getFirst());
        Map<String, Object> retour = new LinkedHashMap<>();
        if (avec) retour.put("archives", "1");
        return excel ? livraison.excel("partenaires-financiers", "/financements/partenaires", retour, redirection, () -> export.partenaires(avec))
                : livraison.pdf("partenaires-financiers", "/financements/partenaires", retour, redirection, () -> export.partenaires(avec));
    }

    @GetMapping("/financements/partenaires/{id}/releve/excel")
    public Object releveExcel(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.excel("releve-partenaire-" + id, "/financements/partenaires/" + id, Map.of(), redirection, () -> export.releve(id));
    }

    @GetMapping("/financements/partenaires/{id}/releve/pdf")
    public Object relevePdf(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.pdf("releve-partenaire-" + id, "/financements/partenaires/" + id, Map.of(), redirection, () -> export.releve(id));
    }

    @GetMapping("/financements/emprunts/{id}/echeancier/excel")
    public Object echeancierExcel(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.excel("echeancier-" + id, "/financements/emprunts/" + id, Map.of(), redirection, () -> export.echeancier(id));
    }

    @GetMapping("/financements/emprunts/{id}/echeancier/pdf")
    public Object echeancierPdf(@PathVariable Long id, RedirectAttributes redirection) {
        return livraison.pdf("echeancier-" + id, "/financements/emprunts/" + id, Map.of(), redirection, () -> export.echeancier(id));
    }
}
